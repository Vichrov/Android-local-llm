package com.vichrov.localai;

final class NativeLlm {
    static { System.loadLibrary("localeyes"); }
    private volatile long handle = create();
    private native long create();
    native void dispose();
    interface Tokens { void onToken(byte[] utf8); }
    // All methods except cancel run on the same serial executor.
    native void load(String path, int threads);
    native void generate(byte[] prompt, Tokens sink);
    native void cancel();
    native void prepare();
    native void close();
}
