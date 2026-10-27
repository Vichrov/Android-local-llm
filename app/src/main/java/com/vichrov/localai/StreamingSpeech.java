package com.vichrov.localai;

/** The decoder receives every PCM sample, regardless of microphone gain or room noise. */
final class StreamingSpeech {
    interface Decoder {
        boolean accept(short[] pcm,int count);
        String partial();
        String result();
        String finish();
        void reset();
    }
    static final class Update {
        final String partial, text;
        final boolean endpoint;
        Update(String partial,String text,boolean endpoint) { this.partial=partial; this.text=text; this.endpoint=endpoint; }
    }
    private final Decoder decoder;
    private int samples, sincePartial;
    private String partial="";
    StreamingSpeech(Decoder decoder) { this.decoder=decoder; }
    Update accept(short[] pcm,int count) {
        boolean endpoint=decoder.accept(pcm,count);
        samples+=count; sincePartial+=count;
        if(endpoint || samples>=16000*30) {
            String text=endpoint?decoder.result():decoder.finish();
            reset();
            return new Update("",text.trim(),true);
        }
        if(sincePartial>=2560) { partial=decoder.partial().trim(); sincePartial=0; }
        return new Update(partial,"",false);
    }
    void reset() { decoder.reset(); samples=sincePartial=0; partial=""; }
    static float rms(short[] pcm,int n) {
        double sum=0; for(int i=0;i<n;i++) sum+=(double)pcm[i]*pcm[i];
        return n==0?0:(float)Math.sqrt(sum/n);
    }
}
