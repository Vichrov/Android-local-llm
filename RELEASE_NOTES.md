# Local Eyes v0.1 — experimental preview

Android 12+ (API 31), ARM64. Install `LocalEyes-v0.1-arm64.apk`, then download a language model in the first-run screen. Download Vosk RU for microphone input. Select a downloaded LLM and open the assistant.

- Local Qwen3.5 inference via pinned llama.cpp, CPU, 4 threads maximum.
- Catalog: 2B Q4_K_M, 2B Q8_0, 4B Q6_K. SHA-256 verification before activation.
- Offline Russian Vosk ASR; Android offline Russian TTS starts before generation ends.
- Animated rounded rectangle eyes, listening/thinking/speaking states and text-driven expression.
- Download progress, Wi-Fi default, system download persistence, cancel/delete/select models.

This is a **debug-signed preview APK**, not a production-signed release. A later build signed with a different key requires uninstalling this version (which removes downloaded models). Do not use for sensitive production workflows.

Snapdragon 855 compatibility/performance has NOT been measured on physical hardware. CPU baseline is ARMv8-A; GPU acceleration is not enabled. 2B Q4 is the lightest option. 4B Q6 requires substantially more memory and may be slow. Recommended total RAM estimates in the app are conservative guidelines, not guarantees. ASR accuracy and TTS availability vary. TTS requires an installed offline Russian voice. No background listening. No device actions, vision or cloud fallback. Responses are capped at 512 tokens, context at 2048 tokens.

Build verification: unit tests, Android lint and debug APK compilation run in the attached GitHub Actions workflow. Device smoke tests remain pending; see README.
