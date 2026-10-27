# Local Eyes v0.2 — eyes-only conversation

- Fullscreen eyes with no app text/buttons. Enter with **Только глаза**; exit by long press or system Back. System microphone/privacy indicators remain controlled by Android.
- Automatic speech onset detection: local adaptive energy gate, 640 ms pre-roll, 900 ms trailing silence, 20 s maximum phrase. Vosk RU stays loaded for the session. No wake word is required.
- Microphone pauses during generation and until the entire TTS queue is finished, then rearms after a 700 ms echo tail. This is half-duplex conversation, not echo-cancelled barge-in. Backgrounding/locking exits hands-free mode.
- Smoother eye transitions, randomized blinking and gaze, reactive listening amplitude, breathing/speaking animation, softer glow. Respect Android's disabled-animation setting.
- Refreshed rounded controls, typography and full-black immersive background.
- New catalog entries: Qwen3.5 0.8B FP16 (~1.52 GB) and 0.8B Q4 (~533 MB), pinned sources and SHA-256 checks. FP16 is not necessarily faster than Q4 on CPU; compare on your device.
- Unload the previous LLM when selecting another to avoid unnecessary RAM pressure.

Android 12+, ARM64, CPU inference. Fullscreen voice mode requires downloaded Vosk RU, a selected LLM, microphone permission and enabled offline Russian Android TTS. Microphone capture begins only after entering this mode. Audio is never saved or sent to a server. Nearby speech/noise can activate the energy gate; this is not a neural VAD or speaker verification system.

Experimental debug-signed preview. The CI signing key can differ from v0.1, so installing may require uninstalling v0.1 (and downloading models again). Hardware speech/noise, echo-tail, thermal and Snapdragon 855 performance testing remain pending. Build, unit tests, lint, APK signature and 16 KB ZIP alignment are verified in CI; these do not substitute for real-device tests.
