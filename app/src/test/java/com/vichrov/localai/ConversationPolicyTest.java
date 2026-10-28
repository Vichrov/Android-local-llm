package com.vichrov.localai;

import static org.junit.Assert.*;
import org.junit.Test;

public class ConversationPolicyTest {
    @Test public void listensNormallyWhenIdle() {
        assertTrue(ConversationPolicy.canListen(true,true,false,false,false,false,false,false,true,false,1000,0));
    }
    @Test public void canInterruptGenerationBeforeTtsStarts() {
        assertTrue(ConversationPolicy.canListen(true,true,false,false,false,true,false,false,true,false,1000,0));
    }
    @Test public void ttsNeedsSoftwareEchoProtectionForBargeIn() {
        assertFalse(ConversationPolicy.canListen(true,true,false,false,false,true,true,true,true,false,1000,0));
        assertTrue(ConversationPolicy.canListen(true,true,false,false,false,true,true,true,true,true,1000,0));
    }
    @Test public void canInterruptTtsEvenAfterGenerationFinished() {
        assertTrue(ConversationPolicy.canListen(true,true,false,false,false,false,true,true,true,true,1000,0));
    }
    @Test public void partialNeedsRealSpeechAndLevel() {
        assertFalse(ConversationPolicy.shouldInterrupt("…",1000,true,true,true,true,true));
        assertFalse(ConversationPolicy.shouldInterrupt("привет",100,true,true,true,true,true));
        assertTrue(ConversationPolicy.shouldInterrupt("привет",600,true,true,true,true,true));
    }
}
