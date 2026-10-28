package com.vichrov.localai;

import android.media.*;
import com.github.csukuangfj.piper.phonemize.PiperPhonemize;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional Piper VITS backend. AudioTrack PCM is also the exact AEC3 render reference. */
final class PiperTtsEngine implements AutoCloseable {
    interface Listener { void started(String id,String text); void audio(String id,short[] pcm,int rate); void done(String id); void error(String reason); }
    private final PiperVoiceStore store;
    private final Listener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"piper-tts");t.setDaemon(true);return t;});
    private final AtomicInteger epoch=new AtomicInteger();
    private volatile AudioTrack activeTrack;
    private OfflineTts tts;
    private OfflineTtsConfig config;
    private static boolean phonemizerReady;

    PiperTtsEngine(PiperVoiceStore store,Listener listener) { this.store=store; this.listener=listener; }
    boolean available() { return store.installed(); }
    void speak(String id,String text) {
        final int e=epoch.get();
        worker.execute(()-> {
            try {
                if(e!=epoch.get()) return;
                ensureLoaded();
                int[][] phonemes=phonemize(text);
                GenerationConfig gen=new GenerationConfig(); gen.setSid(0); gen.setSpeed(1.02f); gen.setSilenceScale(config.getSilenceScale()); gen.setPhonemeCodepoints(phonemes);
                int rate=tts.getSampleRate();
                int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack track=new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min,rate/5*2)).setTransferMode(AudioTrack.MODE_STREAM).build();
                activeTrack=track; track.play(); listener.started(id,text);
                OfflineTtsCallback cb=samples->{
                    if(e!=epoch.get()) return 0;
                    short[] pcm=toPcm16(samples); listener.audio(id,pcm,rate);
                    int off=0; while(off<pcm.length && e==epoch.get()) { int n=track.write(pcm,off,pcm.length-off,AudioTrack.WRITE_BLOCKING); if(n<=0) return 0; off+=n; }
                    return e==epoch.get()?1:0;
                };
                tts.generateWithConfigAndCallback("",gen,cb);
                if(e==epoch.get()) { track.stop(); listener.done(id); }
                track.release(); if(activeTrack==track) activeTrack=null;
            } catch(Throwable x) {
                if(e==epoch.get()) listener.error("Piper: "+(x.getMessage()==null?x.getClass().getSimpleName():x.getMessage()));
                AudioTrack t=activeTrack; if(t!=null){try{t.pause();t.flush();t.release();}catch(Exception ignored){} if(activeTrack==t)activeTrack=null;}
            }
        });
    }
    void stop() {
        epoch.incrementAndGet(); AudioTrack t=activeTrack; activeTrack=null;
        if(t!=null) try { t.pause(); t.flush(); t.stop(); t.release(); } catch(Exception ignored) {}
    }
    private synchronized void ensureLoaded() {
        if(tts!=null) return;
        File model=store.modelFile(),tokens=store.tokensFile(); if(model==null||tokens==null) throw new IllegalStateException("Голос Piper не установлен");
        OfflineTtsVitsModelConfig vits=OfflineTtsVitsModelConfig.builder().setModel(model.getAbsolutePath()).setTokens(tokens.getAbsolutePath()).build();
        OfflineTtsModelConfig mc=OfflineTtsModelConfig.builder().setVits(vits).setNumThreads(2).setDebug(false).build();
        config=OfflineTtsConfig.builder().setModel(mc).build(); tts=new OfflineTts(config);
        synchronized(PiperTtsEngine.class) { if(!phonemizerReady) { PiperPhonemize.initialize(); phonemizerReady=true; } }
    }
    private int[][] phonemize(String text) {
        String lang=tts.getLang(); if(lang==null||lang.isEmpty()) lang="ru";
        try(PiperPhonemize.Result r=PiperPhonemize.text(text,lang)) {
            if(r==null||r.getNumSentences()==0) throw new IllegalStateException("Не удалось фонемизировать русский текст");
            int[][] out=new int[r.getNumSentences()][]; for(int i=0;i<out.length;i++) out[i]=r.getPhonemes(i); return out;
        }
    }
    private static short[] toPcm16(float[] f) { short[] s=new short[f.length]; for(int i=0;i<f.length;i++){float v=Math.max(-1f,Math.min(1f,f[i]));s[i]=(short)Math.round(v*32767f);} return s; }
    @Override public synchronized void close() { stop(); worker.shutdownNow(); if(tts!=null){tts.release();tts=null;} }
}
