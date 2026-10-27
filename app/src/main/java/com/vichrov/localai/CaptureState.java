package com.vichrov.localai;

/** Prevents rearming before a result reaches the UI, and rejects callbacks from a paused capture. */
final class CaptureState {
    private boolean enabled, pending;
    private long epoch;
    synchronized void enable(boolean value) {
        if(enabled!=value) { enabled=value; pending=false; epoch++; }
    }
    synchronized long epoch() { return epoch; }
    synchronized boolean capturing() { return enabled && !pending; }
    synchronized boolean current(long token) { return enabled && token==epoch; }
    synchronized boolean publish(long token) {
        if(!current(token) || pending) return false;
        pending=true; return true;
    }
    synchronized void acknowledge(long token) { if(token==epoch) pending=false; }
}
