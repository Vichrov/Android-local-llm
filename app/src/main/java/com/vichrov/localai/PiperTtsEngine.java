package com.vichrov.localai;

import android.media.*;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.jvm.functions.Function1;

/** Optional Piper VITS backend. AudioTrack PCM is also the exact AEC3 render reference. */
final class PiperTtsEngine implements AutoCloseable {
    interface Listener { void started(String id,String text); void audio(String id,short[] pcm,int rate); void done(String id); void error(String reason); }
    private final PiperVoiceStore store;
    private final Listener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"piper-tts");t.setDaemon(true);return t;});
    private final AtomicInteger epoch=new AtomicInteger();
    private volatile AudioTrack activeTrack;
    private OfflineTts tts;

    PiperTtsEngine(PiperVoiceStore store,Listener listener){this.store=store;this.listener=listener;}
    boolean available(){return store.installed();}
    void speak(String id,String text){
        final int e=epoch.get();
        worker.execute(()->{
            AudioTrack track=null;
            try{
                if(e!=epoch.get())return;ensureLoaded();int rate=tts.sampleRate();
                int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(min,rate/5*2)).setTransferMode(AudioTrack.MODE_STREAM).build();
                if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("AudioTrack не инициализирован");
                activeTrack=track;track.play();listener.started(id,text);final AudioTrack out=track;
                Function1<float[],Integer> cb=samples->{
                    if(e!=epoch.get())return 0;short[] pcm=toPcm16(samples);listener.audio(id,pcm,rate);
                    int off=0;while(off<pcm.length&&e==epoch.get()){int n=out.write(pcm,off,pcm.length-off,AudioTrack.WRITE_BLOCKING);if(n<=0)return 0;off+=n;}return e==epoch.get()?1:0;
                };
                tts.generateWithCallback(text,0,1.02f,cb);
                if(e==epoch.get()){try{track.stop();}catch(Exception ignored){}listener.done(id);}
            }catch(Throwable x){if(e==epoch.get())listener.error("Piper: "+(x.getMessage()==null?x.getClass().getSimpleName():x.getMessage()));}
            finally{if(track!=null){try{track.release();}catch(Exception ignored){}}if(activeTrack==track)activeTrack=null;}
        });
    }
    void stop(){epoch.incrementAndGet();AudioTrack t=activeTrack;activeTrack=null;if(t!=null)try{t.pause();t.flush();t.stop();}catch(Exception ignored){} }
    private synchronized void ensureLoaded(){
        if(tts!=null)return;File model=store.modelFile(),tokens=store.tokensFile(),lexicon=store.lexiconFile();
        if(model==null||tokens==null||lexicon==null)throw new IllegalStateException("Голос Piper не установлен полностью");
        // The official Android 2.0.1 AAR exposes the Kotlin data-class API.
        OfflineTtsVitsModelConfig vits=new OfflineTtsVitsModelConfig(model.getAbsolutePath(),lexicon.getAbsolutePath(),tokens.getAbsolutePath(),"","",0.667f,0.8f,1.0f);
        OfflineTtsMatchaModelConfig matcha=new OfflineTtsMatchaModelConfig("","","","","","",1.0f,1.0f);
        OfflineTtsKokoroModelConfig kokoro=new OfflineTtsKokoroModelConfig("","","","","","","",1.0f);
        OfflineTtsZipVoiceModelConfig zip=new OfflineTtsZipVoiceModelConfig("","","","","","",0.1f,0.5f,0.1f,1.0f);
        OfflineTtsKittenModelConfig kitten=new OfflineTtsKittenModelConfig("","","","",1.0f,"");
        OfflineTtsPocketModelConfig pocket=new OfflineTtsPocketModelConfig("","","","","","","",50);
        OfflineTtsSupertonicModelConfig supertonic=new OfflineTtsSupertonicModelConfig("","","","","","","");
        OfflineTtsModelConfig mc=new OfflineTtsModelConfig(vits,matcha,kokoro,zip,kitten,pocket,supertonic,2,false,"cpu");
        OfflineTtsConfig config=new OfflineTtsConfig(mc,"","",1,0.2f);
        tts=new OfflineTts(null,config);
    }
    private static short[] toPcm16(float[] f){short[] s=new short[f.length];for(int i=0;i<f.length;i++){float v=Math.max(-1f,Math.min(1f,f[i]));s[i]=(short)Math.round(v*32767f);}return s;}
    @Override public synchronized void close(){stop();worker.shutdownNow();if(tts!=null){tts.release();tts=null;}}
}
