package com.vichrov.localai;

/** Pure policy for continuous foreground voice sessions. */
final class ConversationPolicy {
    private ConversationPolicy() {}

    static boolean interruptWindow(boolean bargeIn, boolean busy, boolean ttsWork, boolean speaking, boolean echoProtected) {
        if(!bargeIn || (!busy && !ttsWork)) return false;
        return !speaking || echoProtected;
    }

    static boolean canListen(boolean ready, boolean foreground, boolean settings, boolean manualListening,
                             boolean loadingVoice, boolean busy, boolean ttsWork, boolean speaking,
                             boolean bargeIn, boolean echoProtected, long now, long rearmAt) {
        if(!ready || !foreground || settings || manualListening || loadingVoice || now<rearmAt) return false;
        boolean interrupt=interruptWindow(bargeIn,busy,ttsWork,speaking,echoProtected);
        return (!busy && !ttsWork) || interrupt;
    }

    static boolean shouldInterrupt(String partial, float rms, boolean bargeIn, boolean busy,
                                   boolean ttsWork, boolean speaking, boolean echoProtected) {
        if(!interruptWindow(bargeIn,busy,ttsWork,speaking,echoProtected)) return false;
        String text=partial==null?"":partial.trim();
        return rms>=180f && !text.isEmpty() && !text.equals("…");
    }
}
