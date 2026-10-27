package com.vichrov.localai;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpeechGateTest {
    private SpeechGate calibrated() { SpeechGate g=new SpeechGate(); for(int i=0;i<30;i++) g.accept(70); return g; }
    @Test public void silenceDoesNotStartConversation() { SpeechGate g=calibrated(); for(int i=0;i<200;i++) assertEquals(SpeechGate.Event.WAIT,g.accept(80)); }
    @Test public void isolatedClickIsRejected() { SpeechGate g=calibrated(); assertEquals(SpeechGate.Event.WAIT,g.accept(4000)); for(int i=0;i<10;i++) assertEquals(SpeechGate.Event.WAIT,g.accept(60)); }
    @Test public void startsAfterSustainedVoiceAndEndsAfterPause() {
        SpeechGate g=calibrated(); for(int i=0;i<4;i++) assertEquals(SpeechGate.Event.WAIT,g.accept(1200));
        assertEquals(SpeechGate.Event.START,g.accept(1200));
        for(int i=0;i<44;i++) assertEquals(SpeechGate.Event.SPEECH,g.accept(70));
        assertEquals(SpeechGate.Event.END,g.accept(70));
    }
    @Test public void shortPauseDoesNotSplitPhrase() {
        SpeechGate g=calibrated(); for(int i=0;i<8;i++) g.accept(1000);
        for(int i=0;i<20;i++) assertEquals(SpeechGate.Event.SPEECH,g.accept(50));
        assertEquals(SpeechGate.Event.SPEECH,g.accept(1000));
    }
    @Test public void utteranceHasHardDurationLimit() {
        SpeechGate g=calibrated(); int ends=0; for(int i=0;i<1000;i++) if(g.accept(1000)==SpeechGate.Event.END) ends++;
        assertEquals(1,ends);
    }
    @Test public void resetCannotLeakPreviousOnset() { SpeechGate g=calibrated(); for(int i=0;i<4;i++) g.accept(1000); g.reset(); assertEquals(SpeechGate.Event.WAIT,g.accept(1000)); }
    @Test public void pcmEnergyIsStable() { assertEquals(1000,SpeechGate.rms(new short[]{1000,-1000},2),.01); }
}
