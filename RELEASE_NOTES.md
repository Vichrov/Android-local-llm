# Local Eyes v0.2-Fix

Fix for the reported silent hands-free mode, plus **Авто-голос** in chat.

## Root cause and changes

v0.2 discarded audio below a custom RMS gate (minimum 230). Quiet speech could never reach Vosk; speaking during noise calibration could raise the threshold further. Even completed Vosk results waited for the second energy-based end detector. The gate is now removed: all microphone samples go to Vosk while listening, and Vosk's own non-empty endpoint result immediately becomes a question.

- One shared automatic voice pipeline for chat and fullscreen eyes.
- Chat: **Авто-голос** switch, actual microphone-start status, signal-level meter and partial transcript. No offline TTS is required for chat (text replies still work).
- Microphone starts before the LLM is loaded, so ASR readiness is no longer hidden behind a cold LLM load.
- Validate actual recording state, fall back from VOICE_RECOGNITION to MIC if needed, report a persistently zero audio signal.
- Latch completed results until the UI processes them; reject callbacks from paused capture sessions.
- Resume based on Activity lifecycle, not window-focus changes caused by immersive UI.
- Pause for generation and the entire TTS queue; TTS errors clear that queue. Stop, manual microphone, settings and backgrounding disable auto mode.

Fullscreen still has only eyes. Exit with long press or Back. Automatic mode does not require a wake word and may recognize nearby conversation/TV. Continuous Vosk decoding uses more CPU than the broken pre-filter. Audio is not saved or uploaded. Maximum uninterrupted decoder interval: 30 s.

## Verification

Unit regression tests cover quiet PCM, immediate speech, endpoints without an external silence gate, empty results, duration limits and capture/callback races. CI also runs real public Russian audio through host Vosk 0.3.45 with the same RU 0.22 model, at normal volume and with every sample below the old threshold (peak 180), plus pure silence. Android uses Vosk 0.3.75; the host smoke test does not exercise Android AudioRecord, phone audio routing or a physical microphone.

Android 12+, ARM64. Experimental debug-signed APK; versionCode 3. If Android reports a signing conflict with v0.2, uninstalling the old app removes its models. Re-download them after installation. Physical-device confirmation remains necessary; the reported device was not available for testing.
