package com.vichrov.localai;

import android.annotation.SuppressLint;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;

/** Shared foreground capture for Vosk and GigaAM; no RMS gating. */
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
    private volatile boolean echoControlled;
    private volatile AudioRecord activeMic;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private final CaptureState capture=new CaptureState();
    private final Thread thread;
    private final boolean duplex;

    HandsFreeVoice(String engine,String modelPath,String vadPath,Listener listener) {
        this(engine,modelPath,vadPath,false,listener);
    }
    HandsFreeVoice(String engine,String modelPath,String vadPath,boolean duplex,Listener listener) {
        this.duplex=duplex;
        thread=new Thread(()-> { synchronized(AUDIO_OWNER) { if(alive) run(engine,modelPath,vadPath,listener); } },"hands-free-asr");
        thread.start();
    }
    void arm(boolean value) { capture.enable(value); if(!value) stopMicrophone(); }
    boolean echoControlled() { return echoControlled; }
    private void stopMicrophone() {
        AudioRecord mic=activeMic;
        if(mic!=null) try { if(mic.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING) mic.stop(); } catch(IllegalStateException ignored) { }
    }
    boolean current(long epoch) { return capture.current(epoch); }
    void acknowledge(long epoch) { capture.acknowledge(epoch); }

    private void releaseEffects() {
        if(echoCanceler!=null) { try { echoCanceler.release(); } catch(Exception ignored) {} echoCanceler=null; }
        if(noiseSuppressor!=null) { try { noiseSuppressor.release(); } catch(Exception ignored) {} noiseSuppressor=null; }
        echoControlled=false;
    }
    private void attachDuplexEffects(AudioRecord mic) {
        releaseEffects();
        if(!duplex) return;
        try {
            if(AcousticEchoCanceler.isAvailable()) {
                echoCanceler=AcousticEchoCanceler.create(mic.getAudioSessionId());
                if(echoCanceler!=null) echoCanceler.setEnabled(true);
                echoControlled=echoCanceler!=null && echoCanceler.getEnabled();
            }
        } catch(Exception ignored) { echoControlled=false; }
        try {
            if(NoiseSuppressor.isAvailable()) {
                noiseSuppressor=NoiseSuppressor.create(mic.getAudioSessionId());
                if(noiseSuppressor!=null) noiseSuppressor.setEnabled(true);
            }
        } catch(Exception ignored) { }
    }
    @SuppressLint("MissingPermission") // Activity checks permission, revocation is caught below.
    private AudioRecord microphone(int source) {
        int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0) throw new IllegalStateException("Устройство не поддерживает запись 16 кГц");
        AudioRecord mic=new AudioRecord(source,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(12800,min));
        if(mic.getState()!=AudioRecord.STATE_INITIALIZED) { mic.release(); throw new IllegalStateException("Микрофон не инициализирован"); }
        activeMic=mic; attachDuplexEffects(mic); return mic;
    }
    private AudioRecord preferredMicrophone(int[] selectedSource) {
        int[] sources=duplex
            ? new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION,MediaRecorder.AudioSource.VOICE_RECOGNITION,MediaRecorder.AudioSource.MIC}
            : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION,MediaRecorder.AudioSource.MIC};
        Exception last=null;
        for(int source:sources) {
            try { selectedSource[0]=source; return microphone(source); }
            catch(Exception e) { last=e; }
        }
        throw new IllegalStateException(last==null?"Микрофон недоступен":last.getMessage());
    }
    private void run(String engine,String path,String vadPath,Listener callback) {
        AudioRecord mic=null;
        try(AsrDecoder decoder=AsrDecoder.open(engine,path,vadPath)) {
            StreamingSpeech processor=new StreamingSpeech(decoder);
            boolean recording=false, warned=false;
            int zeroSamples=0, tick=0;
            long recordingEpoch=-1;
            int[] source=new int[1];
            mic=preferredMicrophone(source);
            short[] frame=new short[1280]; // 80 ms; partial preview every 160 ms.
            callback.ready();
            while(alive) {
                long epoch=capture.epoch();
                if(!capture.capturing() || (recording && epoch!=recordingEpoch)) {
                    if(recording) { stopMicrophone(); recording=false; processor.reset(); callback.recording(false); }
                    zeroSamples=0;
                    Thread.sleep(40); continue;
                }
                if(!recording) {
                    processor.reset(); recordingEpoch=epoch;
                    try {
                        mic.startRecording();
                        if(mic.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Микрофон не начал запись");
                    } catch(Exception first) {
                        if(source[0]==MediaRecorder.AudioSource.MIC) throw first;
                        releaseEffects(); mic.release(); mic=microphone(MediaRecorder.AudioSource.MIC); source[0]=MediaRecorder.AudioSource.MIC; continue;
                    }
                    if(!alive || !capture.current(epoch) || !capture.capturing()) { stopMicrophone(); continue; }
                    recording=true; callback.recording(true);
                }
                int n=mic.read(frame,0,frame.length,AudioRecord.READ_BLOCKING);
                if(!alive) break;
                if(!capture.current(epoch) || !capture.capturing()) continue;
                if(n<0) throw new IllegalStateException("Ошибка чтения микрофона: "+n);
                if(n==0) continue;
                float rms=StreamingSpeech.rms(frame,n);
                zeroSamples=rms==0?zeroSamples+n:0;
                if(zeroSamples>=64000 && source[0]!=MediaRecorder.AudioSource.MIC) {
                    stopMicrophone(); releaseEffects(); mic.release(); recording=false; callback.recording(false);
                    mic=microphone(MediaRecorder.AudioSource.MIC); source[0]=MediaRecorder.AudioSource.MIC; zeroSamples=0; processor.reset();
                    callback.notice("Переключён источник микрофона: MIC"); continue;
                }
                if(zeroSamples>=160000 && !warned) {
                    warned=true; callback.notice("Микрофон выдаёт тишину. Проверьте системный доступ к микрофону и подключённую гарнитуру.");
                }
                StreamingSpeech.Update update=processor.accept(frame,n);
                if(++tick%2==0) callback.activity(update.partial,rms,epoch);
                if(!update.text.isEmpty() && capture.publish(epoch)) {
                    stopMicrophone(); recording=false; callback.recording(false);
                    callback.result(update.text,epoch);
                }
            }
        } catch(InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch(Exception | LinkageError e) {
            if(alive) callback.error(e.getMessage()==null?"Ошибка распознавания речи":e.getMessage());
        } finally {
            activeMic=null; releaseEffects();
            if(mic!=null) { try { if(mic.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING) mic.stop(); } catch(Exception ignored) {} mic.release(); }
        }
    }
    public void close() { capture.enable(false); alive=false; stopMicrophone(); thread.interrupt(); }
}
