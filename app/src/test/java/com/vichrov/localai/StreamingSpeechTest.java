package com.vichrov.localai;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class StreamingSpeechTest {
    private static class Fake implements StreamingSpeech.Decoder {
        int samples, resets, finishes;
        boolean endpoint;
        String text="", draft="";
        public boolean accept(short[] pcm,int n) { samples+=n; return endpoint; }
        public String partial() { return draft; }
        public String result() { return text; }
        public String finish() { finishes++; return text; }
        public void reset() { resets++; }
    }
    @Test public void quietSpeechIsNeverDiscarded() {
        Fake d=new Fake(); StreamingSpeech s=new StreamingSpeech(d);
        short[] low=new short[1280]; Arrays.fill(low,(short)25);
        for(int i=0;i<30;i++) s.accept(low,low.length);
        d.endpoint=true; d.text="привет";
        assertEquals("привет",s.accept(low,low.length).text);
        assertEquals(31*1280,d.samples);
    }
    @Test public void speakingImmediatelyNeedsNoNoiseCalibration() {
        Fake d=new Fake(); d.draft="как дела"; StreamingSpeech s=new StreamingSpeech(d);
        assertEquals("как дела",s.accept(new short[2560],2560).partial);
    }
    @Test public void voskEndpointDispatchesEvenWhenRmsRemainsHigh() {
        Fake d=new Fake(); d.endpoint=true; d.text="какая погода";
        short[] loud=new short[1280]; Arrays.fill(loud,(short)4000);
        StreamingSpeech.Update update=new StreamingSpeech(d).accept(loud,loud.length);
        assertTrue(update.endpoint); assertEquals(d.text,update.text); assertEquals(0,d.finishes);
    }
    @Test public void emptyEndpointDoesNotBlockNextPhrase() {
        Fake d=new Fake(); d.endpoint=true; StreamingSpeech s=new StreamingSpeech(d);
        assertEquals("",s.accept(new short[1280],1280).text);
        d.text="новый вопрос"; assertEquals(d.text,s.accept(new short[1280],1280).text);
    }
    @Test public void durationLimitUsesSamplesNotFrameCount() {
        Fake d=new Fake(); d.text="длинная фраза"; StreamingSpeech s=new StreamingSpeech(d);
        for(int i=0;i<749;i++) assertFalse(s.accept(new short[640],640).endpoint);
        assertEquals(d.text,s.accept(new short[640],640).text); assertEquals(1,d.finishes);
    }
    @Test public void pauseResetDropsPartialFromPreviousCapture() {
        Fake d=new Fake(); d.draft="старые слова"; StreamingSpeech s=new StreamingSpeech(d);
        s.accept(new short[2560],2560); s.reset(); d.draft="";
        assertEquals("",s.accept(new short[1280],1280).partial);
    }
    @Test public void levelMeterDoesNotChangeAudio() {
        short[] frame={100,-100,100,-100}; short[] copy=frame.clone();
        assertEquals(100,StreamingSpeech.rms(frame,4),.01); assertArrayEquals(copy,frame);
    }
}
