package com.vichrov.localai;

import android.content.Context;
import android.os.*;
import android.speech.tts.*;
import java.util.*;

/** TTS facade: Android is default; optional Piper shares the same streaming/AEC path. */
final class VoiceOutput {
    interface State { void speech(boolean speaking); void unavailable(String reason); }
    private static final long STREAM_TICK_MS=350;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SpeechChunks chunks=new SpeechChunks();
    private final PlaybackEchoGuard echo=new PlaybackEchoGuard();
    private final TextToSpeech tts;
    private final State state;
    private final NativeAec3 aec3;
    private final PiperTtsEngine piper;
    private final Map<String,AndroidRef> androidRefs=Collections.synchronizedMap(new HashMap<>());
    private boolean ready,streaming,enabled=true;
    private String engine="android";
    private int pending,epoch,sequence;

    private static final class AndroidRef {
        int rate=16000,encoding=android.media.AudioFormat.ENCODING_PCM_16BIT,channels=1;
        boolean started;
        final ArrayList<byte[]> queued=new ArrayList<>();
    }

    VoiceOutput(Context context,State listener,NativeAec3 aec,PiperVoiceStore store) {
        state=listener; aec3=aec;
        piper=new PiperTtsEngine(store,new PiperTtsEngine.Listener() {
            public void started(String id,String text) { echo.started(id); handler.post(()->{if(current(id))state.speech(true);}); }
            public void audio(String id,short[] pcm,int rate) { echo.audioPcm(id,pcm); aec3.render(pcm,pcm.length,rate); }
            public void done(String id) { echo.done(id); handler.post(()->complete(id)); }
            public void error(String reason) { handler.post(()->{stop();state.unavailable(reason);}); }
        });
        tts=new TextToSpeech(context,status->handler.post(()->init(status)));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onBeginSynthesis(String id,int rate,int format,int channels) {
                echo.format(id,format,channels); AndroidRef r=androidRefs.computeIfAbsent(id,k->new AndroidRef()); r.rate=rate;r.encoding=format;r.channels=Math.max(1,channels);
            }
            @Override public void onAudioAvailable(String id,byte[] audio) {
                echo.audio(id,audio); AndroidRef r=androidRefs.computeIfAbsent(id,k->new AndroidRef());
                if(r.started) aec3.render(audio,r.rate,r.encoding,r.channels); else synchronized(r){r.queued.add(audio.clone());}
            }
            @Override public void onStart(String id) {
                echo.started(id); AndroidRef r=androidRefs.computeIfAbsent(id,k->new AndroidRef()); r.started=true;
                synchronized(r){for(byte[] b:r.queued)aec3.render(b,r.rate,r.encoding,r.channels);r.queued.clear();}
                handler.post(()->{if(current(id))state.speech(true);});
            }
            @Override public void onDone(String id){androidRefs.remove(id);echo.done(id);handler.post(()->complete(id));}
            @Override public void onStop(String id,boolean interrupted){androidRefs.remove(id);echo.done(id);handler.post(()->complete(id));}
            @Override public void onError(String id){androidRefs.remove(id);echo.done(id);handler.post(()->{if(current(id)){ready=false;stop();state.unavailable("Ошибка Android TTS. Проверьте русский офлайн-голос.");}});}
        });
    }

    private void init(int status) {
        ready=false;
        if(status==TextToSpeech.SUCCESS&&tts.getVoices()!=null) for(Voice voice:tts.getVoices()) {
            if(voice.getLocale().getLanguage().equals("ru")&&!voice.isNetworkConnectionRequired()&&!voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                ready=tts.setVoice(voice)==TextToSpeech.SUCCESS; if(ready){tts.setSpeechRate(1.05f);return;}
            }
        }
        if(engine.equals("android")) state.unavailable("Нет офлайн-голоса RU. Установите русский голос в настройках синтеза речи Android.");
    }
    void engine(String value){String v="piper".equals(value)?"piper":"android";if(!engine.equals(v)){stop();engine=v;}}
    String engine(){return engine;}
    private boolean backendReady(){return engine.equals("piper")?piper.available():ready;}
    private boolean current(String id){return id!=null&&id.startsWith(epoch+":");}
    private void complete(String id){if(current(id)){pending=Math.max(0,pending-1);if(pending==0)state.speech(false);}}
    boolean available(){return enabled&&backendReady();}
    boolean hasWork(){return streaming||pending>0;}
    boolean echoProtected(){return enabled&&(aec3.available()||backendReady());}
    boolean nativeAec(){return aec3.available();}
    boolean echoLikely(String text){return echo.looksLikeEcho(text);}
    void refresh(){init(TextToSpeech.SUCCESS);}
    void enabled(boolean value){enabled=value;if(!value)stop();}
    void begin(){stop();streaming=true;handler.postDelayed(tick,STREAM_TICK_MS);}
    void add(String token){if(enabled&&backendReady()){chunks.add(token);drain(false,false);}}
    void finish(){streaming=false;handler.removeCallbacks(tick);drain(true,false);}

    private void drain(boolean finish,boolean timer) {
        String s;
        while(!(s=chunks.take(finish,timer)).isEmpty()) {
            if(!enabled||!backendReady())continue;
            String id=epoch+":"+sequence++;boolean first=pending==0;pending++;echo.queued(id,s);if(first)state.speech(true);
            if(engine.equals("piper")) piper.speak(id,s);
            else if(tts.speak(s,TextToSpeech.QUEUE_ADD,null,id)==TextToSpeech.ERROR){ready=false;stop();state.unavailable("Не удалось озвучить ответ через Android TTS.");}
        }
    }
    private final Runnable tick=new Runnable(){public void run(){if(streaming){drain(false,true);handler.postDelayed(this,STREAM_TICK_MS);}}};
    void stop(){epoch++;pending=0;streaming=false;chunks.clear();handler.removeCallbacks(tick);androidRefs.clear();echo.stopped();piper.stop();tts.stop();aec3.reset();state.speech(false);}
    void close(){stop();piper.close();tts.shutdown();}
}
