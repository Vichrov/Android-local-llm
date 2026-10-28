package com.vichrov.localai;

import com.k2fsa.sherpa.onnx.*;
import java.io.File;

/** GigaAM v3 INT8 CTC, 64-bin features. Silero endpoints speech before batch ASR. */
final class GigaDecoder extends AsrDecoder {
    private final OfflineRecognizer recognizer;
    private final Vad vad;
    GigaDecoder(String directory,String vadPath) {
        OfflineRecognizerConfig config=new OfflineRecognizerConfig();
        config.getFeatConfig().setSampleRate(16000);
        config.getFeatConfig().setFeatureDim(64);
        OfflineModelConfig model=config.getModelConfig();
        model.getNemo().setModel(new File(directory,"model.int8.onnx").getAbsolutePath());
        model.setTokens(new File(directory,"tokens.txt").getAbsolutePath());
        model.setNumThreads(Math.min(2,Runtime.getRuntime().availableProcessors()));
        model.setProvider("cpu");
        recognizer=new OfflineRecognizer(null,config);
        try {
            VadModelConfig vc=new VadModelConfig();
            vc.setSampleRate(16000); vc.setNumThreads(1); vc.setProvider("cpu");
            SileroVadModelConfig silero=vc.getSileroVadModelConfig();
            silero.setModel(vadPath); silero.setThreshold(.5f);
            silero.setMinSilenceDuration(.65f); silero.setMinSpeechDuration(.2f);
            silero.setWindowSize(512); silero.setMaxSpeechDuration(15f);
            vad=new Vad(null,vc);
        } catch(RuntimeException | LinkageError e) { recognizer.release(); throw e; }
    }
    public boolean accept(short[] pcm,int count) {
        float[] samples=new float[count];
        for(int i=0;i<count;i++) samples[i]=pcm[i]/32768f;
        // Feed all audio; do not gate quiet speech by an RMS threshold.
        vad.acceptWaveform(samples);
        return !vad.empty();
    }
    public String partial() { return vad.isSpeechDetected()?"…":""; }
    public String result() {
        if(vad.empty()) return "";
        float[] samples=vad.front().getSamples(); vad.pop();
        OfflineStream stream=recognizer.createStream();
        try {
            stream.acceptWaveform(samples,16000);
            recognizer.decode(stream);
            return recognizer.getResult(stream).getText().trim();
        } finally { stream.release(); }
    }
    public String finish() { vad.flush(); return result(); }
    public void reset() { vad.reset(); vad.clear(); }
    public void close() { vad.release(); recognizer.release(); }
}
