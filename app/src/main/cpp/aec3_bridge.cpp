#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>
#include <webrtc/api/audio/builtin_audio_processing_builder.h>
#include <webrtc/api/environment/environment_factory.h>
#include <webrtc/api/scoped_refptr.h>
#include <webrtc/modules/audio_processing/include/audio_processing.h>

namespace {
constexpr int kRate=16000;
constexpr int kFrame=160; // WebRTC APM works on 10 ms frames.
constexpr size_t kMaxRender=kRate*4;

struct Aec3 {
    std::mutex lock;
    webrtc::scoped_refptr<webrtc::AudioProcessing> apm;
    std::deque<int16_t> render;
    Aec3() { rebuild(); }
    void rebuild() {
        webrtc::AudioProcessing::Config cfg;
        cfg.echo_canceller.enabled=true;
        cfg.high_pass_filter.enabled=true;
        apm=webrtc::BuiltinAudioProcessingBuilder(cfg).Build(webrtc::CreateEnvironment());
        render.clear();
    }
};

static Aec3 *ptr(jlong h) { return reinterpret_cast<Aec3*>(h); }
static std::vector<int16_t> resample16k(const int16_t *src,int n,int rate) {
    if(n<=0 || rate<=0) return {};
    if(rate==kRate) return std::vector<int16_t>(src,src+n);
    int outN=std::max(1,(int)((int64_t)n*kRate/rate));
    std::vector<int16_t> out(outN);
    for(int i=0;i<outN;i++) {
        double pos=(double)i*rate/kRate;
        int a=std::min(n-1,(int)pos), b=std::min(n-1,a+1);
        double f=pos-a;
        out[i]=(int16_t)std::clamp((int)std::lround(src[a]*(1.0-f)+src[b]*f),-32768,32767);
    }
    return out;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_vichrov_localai_NativeAec3_create(JNIEnv*,jclass) {
    try { auto *a=new Aec3(); if(!a->apm) { delete a; return 0; } return reinterpret_cast<jlong>(a); }
    catch(...) { return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeAec3_destroy(JNIEnv*,jclass,jlong h) { delete ptr(h); }
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeAec3_resetNative(JNIEnv*,jclass,jlong h) {
    auto *a=ptr(h); if(!a) return; std::lock_guard<std::mutex> g(a->lock); a->rebuild();
}
extern "C" JNIEXPORT void JNICALL
Java_com_vichrov_localai_NativeAec3_renderNative(JNIEnv *env,jclass,jlong h,jshortArray pcm,jint count,jint rate) {
    auto *a=ptr(h); if(!a || !pcm || count<=0) return;
    jsize len=env->GetArrayLength(pcm); count=std::min<jint>(count,len);
    std::vector<int16_t> in(count); env->GetShortArrayRegion(pcm,0,count,reinterpret_cast<jshort*>(in.data()));
    auto data=resample16k(in.data(),count,rate);
    std::lock_guard<std::mutex> g(a->lock);
    for(auto v:data) a->render.push_back(v);
    while(a->render.size()>kMaxRender) a->render.pop_front();
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_vichrov_localai_NativeAec3_processNative(JNIEnv *env,jclass,jlong h,jshortArray pcm,jint count) {
    auto *a=ptr(h); if(!a || !a->apm || !pcm || count<kFrame) return JNI_FALSE;
    jsize len=env->GetArrayLength(pcm); count=std::min<jint>(count,len);
    std::vector<int16_t> data(count); env->GetShortArrayRegion(pcm,0,count,reinterpret_cast<jshort*>(data.data()));
    webrtc::StreamConfig cfg(kRate,1);
    std::lock_guard<std::mutex> g(a->lock);
    for(int off=0;off+kFrame<=count;off+=kFrame) {
        int16_t far[kFrame]{};
        for(int i=0;i<kFrame && !a->render.empty();i++) { far[i]=a->render.front(); a->render.pop_front(); }
        if(a->apm->ProcessReverseStream(far,cfg,cfg,far)!=webrtc::AudioProcessing::kNoError) return JNI_FALSE;
        a->apm->set_stream_delay_ms(50);
        if(a->apm->ProcessStream(data.data()+off,cfg,cfg,data.data()+off)!=webrtc::AudioProcessing::kNoError) return JNI_FALSE;
    }
    env->SetShortArrayRegion(pcm,0,count,reinterpret_cast<const jshort*>(data.data()));
    return JNI_TRUE;
}
