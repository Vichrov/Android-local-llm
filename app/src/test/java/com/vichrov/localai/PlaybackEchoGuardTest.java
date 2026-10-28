package com.vichrov.localai;

import static org.junit.Assert.*;
import org.junit.Test;

public class PlaybackEchoGuardTest {
    @Test public void rejectsAssistantPhraseWhileReferenceIsActive() {
        PlaybackEchoGuard g=new PlaybackEchoGuard();
        g.queued("1","Сейчас расскажу вам о погоде и температуре воздуха");
        g.started("1");
        assertTrue(g.looksLikeEcho("расскажу вам о погоде"));
    }

    @Test public void unrelatedUserPhraseIsNotEcho() {
        PlaybackEchoGuard g=new PlaybackEchoGuard();
        g.queued("1","Сейчас расскажу вам о погоде и температуре воздуха");
        g.started("1");
        assertFalse(g.looksLikeEcho("лучше расскажи про космос"));
    }

    @Test public void stopCommandAlwaysWinsOverEchoText() {
        PlaybackEchoGuard g=new PlaybackEchoGuard();
        g.queued("1","Если хотите сказать стоп, просто произнесите это слово");
        g.started("1");
        assertFalse(g.looksLikeEcho("стоп"));
    }

    @Test public void normalizesRussianTextAndPunctuation() {
        assertEquals("еще один тест",PlaybackEchoGuard.normalize("  Ещё, один ТЕСТ!  "));
    }
}
