package com.vichrov.localai;
import org.junit.Test;
import static org.junit.Assert.*;
public class CaptureStateTest {
    @Test public void pollingCannotRearmBeforeUiHandlesResult() {
        CaptureState s=new CaptureState(); s.enable(true); long token=s.epoch();
        assertTrue(s.publish(token)); s.enable(true); s.enable(true);
        assertFalse(s.capturing()); assertFalse(s.publish(token));
        s.acknowledge(token); assertTrue(s.capturing());
    }
    @Test public void pauseAndResumeRejectStaleResult() {
        CaptureState s=new CaptureState(); s.enable(true); long token=s.epoch();
        s.enable(false); s.enable(true); assertFalse(s.current(token)); assertFalse(s.publish(token));
    }
    @Test public void staleAcknowledgementCannotReleaseNewResult() {
        CaptureState s=new CaptureState(); s.enable(true); long old=s.epoch(); s.publish(old);
        s.enable(false); s.enable(true); long next=s.epoch(); s.publish(next);
        s.acknowledge(old); assertFalse(s.capturing()); s.acknowledge(next); assertTrue(s.capturing());
    }
    @Test public void disabledCaptureCannotPublishOrRecord() {
        CaptureState s=new CaptureState(); assertFalse(s.capturing()); assertFalse(s.publish(s.epoch()));
    }
}
