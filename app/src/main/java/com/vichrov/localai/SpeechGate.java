package com.vichrov.localai;

/** Adaptive energy gate, 20 ms PCM frames. Not a neural speech classifier. */
final class SpeechGate {
    enum Event { WAIT, START, SPEECH, END }
    private float noise = 100;
    private int calibration, onset, quiet, duration;
    private boolean active;
    void reset() { calibration=0; onset=quiet=duration=0; active=false; noise=100; }
    Event accept(float rms) {
        if (calibration++ < 20) { noise += .12f*(Math.min(rms,600)-noise); return Event.WAIT; }
        float threshold=Math.max(230,noise*3.2f);
        boolean loud=rms>threshold;
        if (!active) {
            if (!loud) noise += .025f*(rms-noise);
            onset=loud?onset+1:0;
            if (onset>=5) { active=true; duration=onset; quiet=0; return Event.START; }
            return Event.WAIT;
        }
        duration++;
        quiet=loud?0:quiet+1;
        if (quiet>=45 || duration>=1000) { active=false; onset=quiet=duration=0; return Event.END; }
        return Event.SPEECH;
    }
    static float rms(short[] pcm,int n) {
        double sum=0; for(int i=0;i<n;i++) sum+=(double)pcm[i]*pcm[i];
        return n==0?0:(float)Math.sqrt(sum/n);
    }
}
