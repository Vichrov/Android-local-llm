# Local Eyes v0.6-Fix4

Финальный голосовой фикс ветки v0.6: true barge-in переведён с эвристического подавления на native WebRTC AEC3. Также добавлен второй локальный TTS — Piper.

## Native WebRTC AEC3

- Встроен standalone WebRTC AudioProcessing M155 с настоящим `ProcessReverseStream -> ProcessStream`.
- Микрофон остаётся активным во время ответа; 16-кГц mono capture обрабатывается AEC3 10-мс кадрами **до** Vosk/GigaAM.
- Для Piper в AEC3 передаётся тот же PCM, который непосредственно затем записывается в `AudioTrack`.
- Для Android TTS render-reference берётся из `UtteranceProgressListener.onAudioAvailable()` и синхронизируется с `onStart()`.
- Системный Android AcousticEchoCanceler используется только как fallback, если native AEC3 недоступен; NoiseSuppressor сохраняется.
- Текстовый `PlaybackEchoGuard` оставлен вторым защитным слоем для очевидных копий собственного ответа.
- Голосовое перебивание работает и во время генерации, и во время самой озвучки.

## Второй TTS: Piper

- Android TTS остаётся вариантом №1 и выбран по умолчанию.
- Добавлен опциональный полностью локальный Piper VITS `ru_RU-dmitri-medium-int8` через уже используемый sherpa-onnx 2.0.1.
- Модель Piper не входит в APK: архив около 21 МБ скачивается из настроек отдельно и проверяется по SHA-256.
- Используется актуальный `lexicon.txt` sherpa/Piper; отдельный piper-phonemize не нужен.
- Piper синтезирует потоково и воспроизводится через собственный `AudioTrack`, поэтому reference для AEC3 получается максимально точным.
- Для Snapdragon 855+ выбран INT8 medium как компромисс между качеством русского голоса, нагрузкой CPU/RAM и размером модели.

## Сохранено

Исправление загрузки GGUF из v0.6-Fix, KV-prefix reuse, TTFT/tokens/s, Vosk/GigaAM, Living Eyes, Flyme UI и полностью локальная работа после загрузки моделей.

Android 12+ / ARM64 / Snapdragon 855+.

> CI проверяет сборку и runtime на Android 12/15, однако реальную акустическую петлю «динамик -> помещение -> микрофон» окончательно можно оценить только на физическом устройстве.
