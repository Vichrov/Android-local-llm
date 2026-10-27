#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include <algorithm>
#include <memory>
#include "llama.h"

static llama_model *model = nullptr;
static llama_context *ctx = nullptr;
static std::atomic<bool> stopped{false};
static bool abort_decode(void *) { return stopped.load(); }
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeLlm_cancel(JNIEnv *, jobject) { stopped = true; }
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeLlm_close(JNIEnv *, jobject) {
    if (ctx) llama_free(ctx);
    if (model) llama_model_free(model);
    ctx = nullptr; model = nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeLlm_load(JNIEnv *env, jobject self, jstring path, jint threads) {
    Java_com_vichrov_localai_NativeLlm_close(env, self);
    stopped = false;
    llama_backend_init();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0; mp.use_mmap = true;
    const char *p = env->GetStringUTFChars(path, nullptr);
    model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!model) { fail(env, "Не удалось загрузить GGUF. Проверьте модель и свободную память."); return; }
    auto cp = llama_context_default_params();
    cp.n_ctx = 2048; cp.n_batch = 128; cp.n_ubatch = 128;
    cp.n_threads = threads; cp.n_threads_batch = threads;
    cp.abort_callback = abort_decode;
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
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeLlm_generate(JNIEnv *env, jobject, jbyteArray prompt, jobject sink) {
    if (!ctx || !model) { fail(env, "Сначала выберите скачанную модель."); return; }
    // Cancellation is reset by load; Java calls reset via prepare before queuing a turn.
    if (stopped) return;
    const auto *vocab = llama_model_get_vocab(model);
    auto len = env->GetArrayLength(prompt);
    std::string input(len, '\0');
    env->GetByteArrayRegion(prompt, 0, len, reinterpret_cast<jbyte *>(input.data()));
    int count = llama_tokenize(vocab, input.data(), len, nullptr, 0, true, true);
    if (count >= 0) { fail(env, "Пустой запрос."); return; }
    std::vector<llama_token> tokens(-count);
    count = llama_tokenize(vocab, input.data(), len, tokens.data(), tokens.size(), true, true);
    if (count < 1 || count > 1536) { fail(env, "Контекст слишком длинный. Начните новый диалог или сократите сообщение."); return; }
    llama_memory_clear(llama_get_memory(ctx), true);
    for (int i = 0; i < count && !stopped; i += 128) {
        auto batch = llama_batch_get_one(tokens.data() + i, std::min(128, count-i));
        if (llama_decode(ctx, batch) != 0) { if (!stopped) fail(env, "Ошибка обработки запроса."); return; }
    }
    auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    auto type = env->GetObjectClass(sink);
    auto callback = env->GetMethodID(type, "onToken", "([B)V");
    if (!callback) return;
    std::string pending;
    for (int n = 0; n < 512 && !stopped; ++n) {
        auto token = llama_sampler_sample(sampler.get(), ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> piece(256);
        int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
        if (size > 0) pending.append(piece.data(), size);
        size_t ready = complete_utf8(pending);
        if (ready) {
            auto bytes = env->NewByteArray(ready);
            env->SetByteArrayRegion(bytes, 0, ready, reinterpret_cast<const jbyte *>(pending.data()));
            env->CallVoidMethod(sink, callback, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) return;
            pending.erase(0, ready);
        }
        if (llama_decode(ctx, llama_batch_get_one(&token, 1)) != 0) {
            if (!stopped) fail(env, "Генерация прервана: ошибка вычисления.");
            return;
        }
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeLlm_prepare(JNIEnv *, jobject) { stopped = false; }
