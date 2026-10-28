from pathlib import Path

p=Path('app/src/main/java/com/vichrov/localai/MainActivity.java')
s=p.read_text(encoding='utf-8')

old='''            public void speech(boolean value) {\n                speaking=value;\n                if(value) {\n                    // Hard self-echo gate: never capture while our own TTS is queued/playing.\n                    if(handsFree!=null) handsFree.arm(false);\n                    hearing=false; micRecording=false; bargeFrames=0;\n                    if(autoEnabled && !fullMode && stateText!=null) stateText.setText("Отвечаю…");\n                } else {\n                    // Let speaker/room echo decay before reopening the microphone.\n                    rearmAt=SystemClock.uptimeMillis()+700;\n                }\n                updateAutoStatus(); updateEyes();\n            }'''
new='''            public void speech(boolean value) {\n                speaking=value;\n                // Fix3 keeps the microphone alive during TTS. PlaybackEchoGuard uses\n                // TTS PCM/text reference to reject our own speech while preserving barge-in.\n                rearmAt=SystemClock.uptimeMillis();\n                if(value && autoEnabled && !fullMode && stateText!=null) stateText.setText("Отвечаю · можно перебить");\n                updateAutoStatus(); updateEyes();\n            }'''
if old not in s: raise SystemExit('speech callback fragment not found')
s=s.replace(old,new,1)

s=s.replace('voice.blocksMic()','voice.hasWork()')
s=s.replace('handsFree.echoControlled()','voice.echoProtected()')
s=s.replace('source.echoControlled()','voice.echoProtected()')
s=s.replace('boolean echo=handsFree!=null && handsFree.echoControlled();','boolean echo=voice.echoProtected();')

old_activity='''                    boolean interrupt=ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.hasWork(),speaking,voice.echoProtected());\n                    if((!busy && !voice.hasWork()) || interrupt) {\n                        hearing=!partial.isEmpty();'''
new_activity='''                    boolean interrupt=ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.hasWork(),speaking,voice.echoProtected());\n                    if((!busy && !voice.hasWork()) || interrupt) {\n                        if(voice.echoLikely(partial)) {\n                            hearing=false; bargeFrames=0;\n                            if(!fullMode && voiceCaption!=null) voiceCaption.setText("Эхо ответа подавлено");\n                            if(eyes!=null) eyes.level(0);\n                            updateEyes(); return;\n                        }\n                        hearing=!partial.isEmpty();'''
if old_activity not in s: raise SystemExit('activity fragment not found')
s=s.replace(old_activity,new_activity,1)

old_result='''                HandsFreeVoice source=handsFree;\n                boolean interrupt=ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.hasWork(),speaking,voice.echoProtected());\n                if(source.current(epoch) && foreground && ((!busy && !voice.hasWork()) || interrupt)) {'''
new_result='''                HandsFreeVoice source=handsFree;\n                boolean interrupt=ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.hasWork(),speaking,voice.echoProtected());\n                if(source.current(epoch) && foreground && voice.echoLikely(text)) {\n                    source.acknowledge(epoch); hearing=false; bargeFrames=0;\n                    if(!fullMode && voiceCaption!=null) voiceCaption.setText("Эхо ответа подавлено");\n                    return;\n                }\n                if(source.current(epoch) && foreground && ((!busy && !voice.hasWork()) || interrupt)) {'''
if old_result not in s: raise SystemExit('result fragment not found')
s=s.replace(old_result,new_result,1)

s=s.replace('Для защиты от самопрослушивания микрофон всегда отключается на время озвучивания и ещё на короткую паузу после неё. Перебивание голосом работает во время генерации до начала речи ассистента.',
'''Микрофон остаётся активным во время ответа. Local Eyes использует системный AEC как первый слой и собственный playback-reference фильтр: TTS PCM/текст сравниваются с распознанной речью, поэтому собственный ответ отбрасывается, а отличающаяся фраза пользователя прерывает озвучивание.''')
s=s.replace('section(list,"Local Eyes · v0.6-Fix2");','section(list,"Local Eyes · v0.6-Fix3");')
s=s.replace('Voice 2.0 · непрерывная локальная сессия, безопасное перебивание ответа, AEC/NoiseSuppressor при поддержке Android, ускоренный streaming TTS и повторное использование KV-префикса между репликами.',
'''Voice 2.0 · true barge-in во время озвучивания, hybrid echo protection (Android AEC + playback-reference guard), Vosk/GigaAM, streaming TTS и повторное использование KV-префикса между репликами.''')

p.write_text(s,encoding='utf-8')
