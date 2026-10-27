package com.vichrov.localai;

import android.annotation.SuppressLint;
import android.media.*;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Continuous offline Vosk endpointing. No energy threshold discards quiet speech. */
final class HandsFreeVoice implements AutoCloseable {
    interface Listener {
        void ready();
        void recording(boolean active);
        void activity(String partial,float rms,long epoch);
        void result(String text,long epoch);
        void notice(String message);
        void error(String message);
    }
    private static final Object AUDIO_OWNER=new Object();
    private volatile boolean alive=true;
    private final CaptureState capture=new CaptureState();
    private final Thread thread;
    HandsFreeVoice(String modelPath,Listener listener) {
        thread=new Thread(()-> { synchronized(AUDIO_OWNER) { if(alive) run(modelPath,listener); } },"hands-free-asr"); thread.start();
    }
    void arm(boolean value) { capture.enable(value); }
    boolean current(long epoch) { return capture.current(epoch); }
    void acknowledge(long epoch) { capture.acknowledge(epoch); }
    private static String text(String json,String field) {
        try { return new JSONObject(json).optString(field,""); }
        catch(Exception e) { throw new IllegalStateException("Некорректный ответ распознавания",e); }
    }
    @SuppressLint("MissingPermission") // Activity checks permission, revocation is caught below.
    private AudioRecord microphone(int source) {
        int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0) throw new IllegalStateException("Устройство не поддерживает запись 16 кГц");
        AudioRecord mic=new AudioRecord(source,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(12800,min));
        if(mic.getState()!=AudioRecord.STATE_INITIALIZED) { mic.release(); throw new IllegalStateException("Микрофон не инициализирован"); }
        return mic;
    }
    private void run(String path,Listener callback) {
        AudioRecord mic=null;
        try(Model model=new Model(path); Recognizer recognizer=new Recognizer(model,16000f)) {
            StreamingSpeech processor=new StreamingSpeech(new StreamingSpeech.Decoder() {
                public boolean accept(short[] pcm,int n) { return recognizer.acceptWaveForm(pcm,n); }
                public String partial() { return text(recognizer.getPartialResult(),"partial"); }
                public String result() { return text(recognizer.getResult(),"text"); }
                public String finish() { return text(recognizer.getFinalResult(),"text"); }
                public void reset() { recognizer.reset(); }
            });
            boolean fallback=false, recording=false, warned=false;
            int zeroSamples=0, tick=0;
            long recordingEpoch=-1;
            try { mic=microphone(MediaRecorder.AudioSource.VOICE_RECOGNITION); }
            catch(Exception first) { mic=microphone(MediaRecorder.AudioSource.MIC); fallback=true; }
            short[] frame=new short[1280]; // 80 ms; partial preview every 160 ms.
            callback.ready();
            while(alive) {
                long epoch=capture.epoch();
                if(!capture.capturing() || (recording && epoch!=recordingEpoch)) {
                    if(recording) { mic.stop(); recording=false; processor.reset(); callback.recording(false); }
                    zeroSamples=0;
                    Thread.sleep(40); continue;
                }
                if(!recording) {
                    processor.reset(); recordingEpoch=epoch;
                    try {
                        mic.startRecording();
                        if(mic.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Микрофон не начал запись");
                    } catch(Exception first) {
                        if(fallback) throw first;
                        mic.release(); mic=microphone(MediaRecorder.AudioSource.MIC); fallback=true; continue;
                    }
                    recording=true; callback.recording(true);
                }
                int n=mic.read(frame,0,frame.length,AudioRecord.READ_BLOCKING);
                if(!alive) break;
                if(!capture.current(epoch) || !capture.capturing()) continue;
                if(n<0) throw new IllegalStateException("Ошибка чтения микрофона: "+n);
                if(n==0) continue;
                float rms=StreamingSpeech.rms(frame,n);
                zeroSamples=rms==0?zeroSamples+n:0;
                if(zeroSamples>=64000 && !fallback) {
                    mic.stop(); mic.release(); recording=false; callback.recording(false);
                    mic=microphone(MediaRecorder.AudioSource.MIC); fallback=true; zeroSamples=0; processor.reset();
                    callback.notice("Переключён источник микрофона: MIC"); continue;
                }
                if(zeroSamples>=160000 && !warned) {
                    warned=true; callback.notice("Микрофон выдаёт тишину. Проверьте системный доступ к микрофону и подключённую гарнитуру.");
                }
                StreamingSpeech.Update update=processor.accept(frame,n);
                if(++tick%2==0) callback.activity(update.partial,rms,epoch);
                if(!update.text.isEmpty() && capture.publish(epoch)) {
                    mic.stop(); recording=false; callback.recording(false);
                    callback.result(update.text,epoch);
                }
            }
        } catch(InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch(Exception e) {
            if(alive) callback.error(e.getMessage()==null?"Ошибка распознавания речи":e.getMessage());
        } finally {
            if(mic!=null) { try { if(mic.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING) mic.stop(); } catch(Exception ignored) {} mic.release(); }
        }
    }
    public void close() { capture.enable(false); alive=false; thread.interrupt(); }
}
