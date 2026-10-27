package com.vichrov.localai;

import android.annotation.SuppressLint;
import android.media.*;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Owns Vosk and AudioRecord on one thread; no audio is written to disk. */
final class HandsFreeVoice implements AutoCloseable {
    interface Listener {
        void ready();
        void activity(boolean speech,float level);
        void result(String text);
        void error(String message);
    }
    private volatile boolean alive=true, armed;
    private final Thread thread;
    HandsFreeVoice(String modelPath,Listener listener) {
        thread=new Thread(()->run(modelPath,listener),"hands-free-asr"); thread.start();
    }
    void arm(boolean value) { armed=value; }
    @SuppressLint("MissingPermission") // Activity checks RECORD_AUDIO before constructing this owner.
    private void run(String path,Listener callback) {
        AudioRecord mic=null;
        try(Model model=new Model(path); Recognizer recognizer=new Recognizer(model,16000f)) {
            int size=Math.max(6400,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
            mic=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size);
            if(mic.getState()!=AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Микрофон недоступен");
            SpeechGate gate=new SpeechGate();
            ArrayDeque<short[]> preRoll=new ArrayDeque<>();
            short[] frame=new short[320];
            boolean recording=false, speech=false;
            int tick=0;
            StringBuilder utterance=new StringBuilder();
            callback.ready();
            while(alive) {
                if(!armed) {
                    if(recording) { mic.stop(); recording=false; recognizer.reset(); gate.reset(); preRoll.clear(); speech=false; }
                    Thread.sleep(40); continue;
                }
                if(!recording) { gate.reset(); mic.startRecording(); recording=true; }
                int n=mic.read(frame,0,frame.length,AudioRecord.READ_BLOCKING);
                if(!alive) break;
                if(!armed) continue;
                if(n<0) throw new IllegalStateException("Ошибка записи микрофона: "+n);
                if(n==0) continue;
                float rms=SpeechGate.rms(frame,n);
                SpeechGate.Event event=gate.accept(rms);
                if(!speech) {
                    preRoll.addLast(Arrays.copyOf(frame,n)); while(preRoll.size()>32) preRoll.removeFirst();
                }
                if(event==SpeechGate.Event.START) {
                    speech=true; recognizer.reset(); utterance.setLength(0);
                    for(short[] prior:preRoll) feed(recognizer,prior,prior.length,utterance);
                    preRoll.clear();
                } else if(speech) feed(recognizer,frame,n,utterance);
                if(++tick%5==0 || event==SpeechGate.Event.START) callback.activity(speech,Math.min(1,rms/4000f));
                if(event==SpeechGate.Event.END) {
                    append(utterance,recognizer.getFinalResult());
                    String text=utterance.toString().trim();
                    speech=false; recognizer.reset(); preRoll.clear();
                    if(!text.isEmpty()) { armed=false; mic.stop(); recording=false; callback.result(text); }
                    else { gate.reset(); callback.activity(false,0); }
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
    private static void append(StringBuilder text,String json) throws Exception {
        String part=new JSONObject(json).optString("text","").trim();
        if(!part.isEmpty()) { if(text.length()>0) text.append(' '); text.append(part); }
    }
    private static void feed(Recognizer recognizer,short[] pcm,int n,StringBuilder text) throws Exception {
        if(recognizer.acceptWaveForm(pcm,n)) append(text,recognizer.getResult());
    }
    public void close() { armed=false; alive=false; thread.interrupt(); }
}
