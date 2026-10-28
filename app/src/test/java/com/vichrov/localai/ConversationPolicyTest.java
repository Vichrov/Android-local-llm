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
    @Test public void queuedTtsAlwaysMutesMicEvenWhenAecClaimsEnabled() {
        assertFalse(ConversationPolicy.canListen(true,true,false,false,false,true,true,false,true,false,1000,0));
        assertFalse(ConversationPolicy.canListen(true,true,false,false,false,true,true,false,true,true,1000,0));
    }
    @Test public void speakingAlwaysMutesMicEvenWhenAecClaimsEnabled() {
        assertFalse(ConversationPolicy.canListen(true,true,false,false,false,true,true,true,true,false,1000,0));
        assertFalse(ConversationPolicy.canListen(true,true,false,false,false,true,true,true,true,true,1000,0));
    }
    @Test public void partialNeedsRealSpeechAndLevelDuringInterruptibleGeneration() {
        assertFalse(ConversationPolicy.shouldInterrupt("…",1000,true,true,false,false,true));
        assertFalse(ConversationPolicy.shouldInterrupt("привет",100,true,true,false,false,true));
        assertTrue(ConversationPolicy.shouldInterrupt("привет",600,true,true,false,false,true));
        assertFalse(ConversationPolicy.shouldInterrupt("привет",600,true,true,true,true,true));
    }
}
