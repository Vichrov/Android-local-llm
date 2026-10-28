# Local Eyes v0.6-Fix3

Возвращено настоящее голосовое перебивание во время озвучивания без самопрослушивания ассистента.

## Что изменено

- Микрофон снова остаётся активным во время TTS: пользователь может перебить Local Eyes прямо в середине озвучиваемого ответа.
- Android `VOICE_COMMUNICATION` + `AcousticEchoCanceler`/`NoiseSuppressor` остаются первым слоем защиты, но приложение больше не полагается только на флаг `AEC enabled`.
- Добавлен `PlaybackEchoGuard`: Local Eyes получает reference текущего TTS через `UtteranceProgressListener.onAudioAvailable()` и точный текст каждой озвучиваемой фразы.
- ASR-гипотезы, которые сильно совпадают с текущим/недавно озвученным ответом ассистента, считаются playback echo и не отправляются обратно в LLM.
- Отличающаяся речь пользователя не подавляется: после двух подтверждённых partial-событий barge-in останавливает TTS/генерацию и продолжает распознавать новую реплику.
- Команды вроде «стоп», «хватит», «подожди» имеют приоритет и никогда не считаются эхом только из-за совпадения с текстом ответа.
- Добавлены unit regression tests для full-duplex policy и playback-reference echo rejection.

## Сохранено

Исправленная загрузка GGUF из v0.6-Fix, KV-prefix reuse, TTFT/tokens/s, Vosk/GigaAM, streaming TTS, Living Eyes и полностью локальная работа.

Android 12+ / ARM64 / Snapdragon 855+.
