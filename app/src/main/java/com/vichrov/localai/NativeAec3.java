package com.vichrov.localai;

import android.media.AudioFormat;
import java.nio.*;

/** WebRTC AudioProcessing AEC3. Capture is fixed at 16 kHz mono; render may use any TTS rate. */
final class NativeAec3 implements AutoCloseable {
    static { System.loadLibrary("localeyes"); }
    private long handle;
    NativeAec3() { try { handle=create(); } catch(Throwable ignored) { handle=0; } }
    boolean available() { return handle!=0; }
    synchronized void reset() { if(handle!=0) resetNative(handle); }
    synchronized boolean process(short[] pcm,int count) { return handle!=0 && processNative(handle,pcm,count); }
    synchronized void render(short[] pcm,int count,int sampleRate) {
        if(handle!=0 && pcm!=null && count>0 && sampleRate>0) renderNative(handle,pcm,count,sampleRate);
    }
    synchronized void render(byte[] pcm,int sampleRate,int encoding,int channels) {
        if(handle==0 || pcm==null || pcm.length==0 || sampleRate<=0) return;
        channels=Math.max(1,channels);
        short[] mono;
        if(encoding==AudioFormat.ENCODING_PCM_FLOAT) {
            FloatBuffer f=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            int frames=f.remaining()/channels; mono=new short[frames];
            for(int i=0;i<frames;i++) {
                float sum=0; for(int c=0;c<channels;c++) sum+=f.get();
                float v=Math.max(-1f,Math.min(1f,sum/channels)); mono[i]=(short)Math.round(v*32767f);
            }
        } else if(encoding==AudioFormat.ENCODING_PCM_8BIT) {
            int frames=pcm.length/channels; mono=new short[frames];
            for(int i=0;i<frames;i++) { int sum=0; for(int c=0;c<channels;c++) sum+=(pcm[i*channels+c]&255)-128; mono[i]=(short)((sum/channels)<<8); }
        } else {
            ShortBuffer s=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            int frames=s.remaining()/channels; mono=new short[frames];
            for(int i=0;i<frames;i++) { int sum=0; for(int c=0;c<channels;c++) sum+=s.get(); mono[i]=(short)(sum/channels); }
        }
        render(mono,mono.length,sampleRate);
    }
    @Override public synchronized void close() { if(handle!=0) { destroy(handle); handle=0; } }
    private static native long create();
    private static native void destroy(long handle);
    private static native void resetNative(long handle);
    private static native void renderNative(long handle,short[] pcm,int count,int sampleRate);
    private static native boolean processNative(long handle,short[] pcm,int count);
}
