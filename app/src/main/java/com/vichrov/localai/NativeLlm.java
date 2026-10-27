package com.vichrov.localai;

final class NativeLlm {
    static { System.loadLibrary("localeyes"); }
    interface Tokens { void onToken(byte[] utf8); }
    // All methods except cancel run on the same serial executor.
    native void load(String path, int threads);
    native void generate(byte[] prompt, Tokens sink);
    native void cancel();
    native void prepare();
    native void close();
}
