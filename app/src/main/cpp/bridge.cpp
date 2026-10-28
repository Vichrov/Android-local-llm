#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include <algorithm>
#include <memory>
#include <mutex>
#include "llama.h"

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::atomic<bool> stopped{false};
    std::vector<llama_token> cached_tokens;
    int last_reuse = 0;
};
static Engine *engine_of(JNIEnv *env, jobject self) {
    auto cls = env->GetObjectClass(self);
    auto field = env->GetFieldID(cls, "handle", "J");
    env->DeleteLocalRef(cls);
    return reinterpret_cast<Engine *>(env->GetLongField(self, field));
}
static bool abort_decode(void *data) { return static_cast<Engine *>(data)->stopped.load(); }
extern "C" JNIEXPORT jlong JNICALL
Java_com_vichrov_localai_NativeLlm_create(JNIEnv *, jobject) { return reinterpret_cast<jlong>(new Engine()); }
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
static void clear_cache(Engine *e) {
    if(e->ctx) llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached_tokens.clear();
    e->last_reuse = 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeLlm_cancel(JNIEnv *env, jobject self) { auto *e=engine_of(env,self); if(e) e->stopped = true; }
extern "C" JNIEXPORT jint JNICALL
Java_com_vichrov_localai_NativeLlm_cacheReuse(JNIEnv *env, jobject self) { auto *e=engine_of(env,self); return e?e->last_reuse:0; }
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeLlm_close(JNIEnv *env, jobject self) {
    auto *e=engine_of(env,self); if(!e) return;
    auto &ctx=e->ctx; auto &model=e->model;
    if (ctx) llama_free(ctx);
    if (model) llama_model_free(model);
    ctx = nullptr; model = nullptr; e->cached_tokens.clear(); e->last_reuse=0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeLlm_load(JNIEnv *env, jobject self, jstring path, jint threads) {
    Java_com_vichrov_localai_NativeLlm_close(env, self);
    auto *e=engine_of(env,self); if(!e) return;
    auto &ctx=e->ctx; auto &model=e->model;
    static std::once_flag init;
    std::call_once(init, llama_backend_init);
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0; mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.progress_callback = [](float, void *data) { return !static_cast<Engine *>(data)->stopped.load(); };
    mp.progress_callback_user_data = e;
    const char *p = env->GetStringUTFChars(path, nullptr);
    model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!model) { fail(env, "Не удалось загрузить GGUF. Проверьте модель и свободную память."); return; }
    auto cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 256;
    cp.n_ubatch = 256;
    cp.n_threads = std::max(1, static_cast<int>(threads));
    cp.n_threads_batch = cp.n_threads;
    cp.abort_callback = abort_decode; cp.abort_callback_data = e;
    ctx = llama_init_from_model(model, cp);
    if (!ctx) { Java_com_vichrov_localai_NativeLlm_close(env, self); fail(env, "Недостаточно памяти для контекста модели."); }
}
// Emit only complete UTF-8 sequences, including Cyrillic split across tokens.
static size_t complete_utf8(const std::string &s) {
    size_t i = 0;
    while (i < s.size()) {
        auto c = static_cast<unsigned char>(s[i]);
        size_t n = c < 0x80 ? 1 : (c & 0xe0) == 0xc0 ? 2 : (c & 0xf0) == 0xe0 ? 3 : 4;
        if (i + n > s.size()) break;
        i += n;
    }
    return i;
}
static bool emit(JNIEnv *env, jobject sink, jmethodID callback, std::string &buffer) {
    if (buffer.empty()) return true;
    auto bytes = env->NewByteArray(static_cast<jsize>(buffer.size()));
    if (!bytes) return false;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(buffer.size()), reinterpret_cast<const jbyte *>(buffer.data()));
    env->CallVoidMethod(sink, callback, bytes);
    env->DeleteLocalRef(bytes);
    buffer.clear();
    return !env->ExceptionCheck();
}
extern "C" JNIEXPORT jint JNICALL
Java_com_vichrov_localai_NativeLlm_generate(JNIEnv *env, jobject self, jbyteArray prompt, jobject sink) {
    auto *e=engine_of(env,self); if(!e) return 0;
    auto *ctx=e->ctx; auto *model=e->model; auto &stopped=e->stopped;
    if (!ctx || !model) { fail(env, "Сначала выберите скачанную модель."); return 0; }
    if (stopped) return 0;
    const auto *vocab = llama_model_get_vocab(model);
    auto len = env->GetArrayLength(prompt);
    std::string input(len, '\0');
    env->GetByteArrayRegion(prompt, 0, len, reinterpret_cast<jbyte *>(input.data()));
    int count = llama_tokenize(vocab, input.data(), len, nullptr, 0, true, true);
    if (count >= 0) { fail(env, "Пустой запрос."); return 0; }
    std::vector<llama_token> tokens(-count);
    count = llama_tokenize(vocab, input.data(), len, tokens.data(), tokens.size(), true, true);
    if (count < 1 || count > 1536) { fail(env, "Контекст слишком длинный. Начните новый диалог или сократите сообщение."); return 0; }

    // Keep the longest exact prompt prefix already present in the KV cache. Decode at
    // least the final prompt token again so the logits always correspond to this turn.
    int reuse=0;
    int max_common=std::min<int>(count,e->cached_tokens.size());
    while(reuse<max_common && tokens[reuse]==e->cached_tokens[reuse]) ++reuse;
    reuse=std::min(reuse,std::max(0,count-1));
    if(reuse<16) reuse=0;
    auto mem=llama_get_memory(ctx);
    if(reuse>0) {
        if(!llama_memory_seq_rm(mem,0,reuse,-1)) {
            llama_memory_clear(mem,true); reuse=0;
        }
    } else llama_memory_clear(mem,true);
    e->cached_tokens.resize(reuse);
    e->last_reuse=reuse;

    for (int i = reuse; i < count && !stopped; i += 256) {
        int n=std::min(256,count-i);
        auto batch = llama_batch_get_one(tokens.data() + i, n);
        if (llama_decode(ctx, batch) != 0) {
            clear_cache(e);
            if (!stopped) fail(env, "Ошибка обработки запроса.");
            return 0;
        }
        e->cached_tokens.insert(e->cached_tokens.end(),tokens.begin()+i,tokens.begin()+i+n);
    }
    if(stopped) return 0;

    auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    auto type = env->GetObjectClass(sink);
    auto callback = env->GetMethodID(type, "onToken", "([B)V");
    env->DeleteLocalRef(type);
    if (!callback) return 0;
    std::string pending;
    std::string outbox;
    outbox.reserve(192);
    int generated = 0;
    for (int n = 0; n < 512 && !stopped; ++n) {
        auto token = llama_sampler_sample(sampler.get(), ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> piece(256);
        int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
        if (size > 0) pending.append(piece.data(), size);
        size_t ready = complete_utf8(pending);
        if (ready) {
            outbox.append(pending.data(), ready);
            pending.erase(0, ready);
        }
        if (outbox.size() >= 96 || (n & 3) == 3) {
            if (!emit(env, sink, callback, outbox)) return generated;
        }
        if (llama_decode(ctx, llama_batch_get_one(&token, 1)) != 0) {
            if (!stopped) fail(env, "Генерация прервана: ошибка вычисления.");
            return generated;
        }
        e->cached_tokens.push_back(token);
        ++generated;
    }
    emit(env, sink, callback, outbox);
    return generated;
}
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeLlm_prepare(JNIEnv *env, jobject self) { auto *e=engine_of(env,self); if(e) e->stopped = false; }
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeLlm_dispose(JNIEnv *env, jobject self) {
    Java_com_vichrov_localai_NativeLlm_close(env,self);
    delete engine_of(env,self);
    auto cls=env->GetObjectClass(self);
    env->SetLongField(self,env->GetFieldID(cls,"handle","J"),0);
    env->DeleteLocalRef(cls);
}
