package com.vichrov.localai;

/** Pure policy for continuous foreground voice sessions. */
final class ConversationPolicy {
    private ConversationPolicy() {}

    static boolean interruptWindow(boolean bargeIn, boolean busy, boolean ttsWork, boolean speaking, boolean echoControlled) {
        // Do not trust Android's AEC enabled flag as proof that playback is actually cancelled.
        // Hardware/ROM implementations may report enabled while still leaking TTS into ASR.
        if(!bargeIn || !busy) return false;
        return !ttsWork && !speaking;
    }

    static boolean canListen(boolean ready, boolean foreground, boolean settings, boolean manualListening,
                             boolean loadingVoice, boolean busy, boolean ttsWork, boolean speaking,
                             boolean bargeIn, boolean echoControlled, long now, long rearmAt) {
        if(!ready || !foreground || settings || manualListening || loadingVoice || now<rearmAt) return false;
        if(ttsWork || speaking) return false;
        boolean interrupt=interruptWindow(bargeIn,busy,ttsWork,speaking,echoControlled);
        return !busy || interrupt;
    }

    static boolean shouldInterrupt(String partial, float rms, boolean bargeIn, boolean busy,
                                   boolean ttsWork, boolean speaking, boolean echoControlled) {
        if(!interruptWindow(bargeIn,busy,ttsWork,speaking,echoControlled)) return false;
        String text=partial==null?"":partial.trim();
        return rms>=180f && !text.isEmpty() && !text.equals("…");
    }
}
