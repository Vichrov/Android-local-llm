package com.vichrov.localai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.Locale;

final class VoiceOutput {
    interface State { void speech(boolean speaking); void unavailable(String reason); }
    private static final long STREAM_TICK_MS=350;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SpeechChunks chunks = new SpeechChunks();
    private final TextToSpeech tts;
    private final State state;
    private boolean ready, streaming, enabled = true;
    private int pending, epoch, sequence;
    VoiceOutput(Context context, State listener) {
        state = listener;
        tts = new TextToSpeech(context, status -> handler.post(() -> init(status)));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String id) { handler.post(() -> { if (current(id)) state.speech(true); }); }
            public void onDone(String id) { handler.post(() -> complete(id)); }
            public void onError(String id) { handler.post(() -> { if (current(id)) { ready=false; stop(); state.unavailable("Ошибка TTS. Проверьте русский голос в настройках Android."); } }); }
        });
    }
    private void init(int status) {
        ready=false;
        if (status == TextToSpeech.SUCCESS && tts.getVoices() != null) {
            for (Voice voice : tts.getVoices()) {
                if (voice.getLocale().getLanguage().equals("ru") && !voice.isNetworkConnectionRequired() && !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                    ready = tts.setVoice(voice) == TextToSpeech.SUCCESS;
                    if (ready) { tts.setSpeechRate(1.05f); return; }
                }
            }
        }
        state.unavailable("Нет офлайн-голоса RU. Установите русский голос в настройках синтеза речи Android.");
    }
    private boolean current(String id) { return id != null && id.startsWith(epoch + ":"); }
    private void complete(String id) {
        if (current(id)) { pending = Math.max(0, pending - 1); if (pending == 0) state.speech(false); }
    }
    boolean available() { return ready && enabled; }
    boolean hasWork() { return streaming || pending>0; }
    void refresh() { init(TextToSpeech.SUCCESS); }
    void enabled(boolean value) { enabled = value; if (!value) stop(); }
    void begin() { stop(); streaming = true; handler.postDelayed(tick, STREAM_TICK_MS); }
    void add(String token) { if (enabled && ready) { chunks.add(token); drain(false, false); } }
    void finish() { streaming = false; handler.removeCallbacks(tick); drain(true, false); }
    private void drain(boolean finish, boolean timer) {
        String s;
        while (!(s = chunks.take(finish, timer)).isEmpty()) {
            if (!enabled || !ready) continue;
            String id = epoch + ":" + sequence++;
            pending++;
            if (tts.speak(s, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) { ready=false; stop(); state.unavailable("Не удалось озвучить ответ. Проверьте офлайн-голос Android."); }
        }
    }
    private final Runnable tick = new Runnable() {
        public void run() { if (streaming) { drain(false, true); handler.postDelayed(this, STREAM_TICK_MS); } }
    };
    void stop() { epoch++; pending = 0; streaming = false; chunks.clear(); handler.removeCallbacks(tick); tts.stop(); state.speech(false); }
    void close() { stop(); tts.shutdown(); }
}
