package com.vichrov.localai;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Owned by the single capture thread, including model creation and disposal. */
abstract class AsrDecoder implements StreamingSpeech.Decoder, AutoCloseable {
    static AsrDecoder open(String engine,String path,String vadPath) throws Exception {
        return engine.equals("gigaam") ? new GigaDecoder(path,vadPath) : new VoskDecoder(path);
    }
    public abstract void close();
    private static final class VoskDecoder extends AsrDecoder {
        private final Model model;
        private final Recognizer recognizer;
        VoskDecoder(String path) throws Exception {
            model=new Model(path);
            try { recognizer=new Recognizer(model,16000f); }
            catch(Exception e) { model.close(); throw e; }
        }
        private String text(String json,String field) {
            try { return new JSONObject(json).optString(field,""); }
            catch(Exception e) { throw new IllegalStateException("Некорректный ответ распознавания",e); }
        }
        public boolean accept(short[] pcm,int n) { return recognizer.acceptWaveForm(pcm,n); }
        public String partial() { return text(recognizer.getPartialResult(),"partial"); }
        public String result() { return text(recognizer.getResult(),"text"); }
        public String finish() { return text(recognizer.getFinalResult(),"text"); }
        public void reset() { recognizer.reset(); }
        public void close() { recognizer.close(); model.close(); }
    }
}
