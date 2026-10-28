from pathlib import Path

p=Path('app/src/main/java/com/vichrov/localai/MainActivity.java')
s=p.read_text(encoding='utf-8')
old='''            public void speech(boolean value) {\n                speaking=value;\n                if(!value) rearmAt=SystemClock.uptimeMillis()+500;\n                else if(autoEnabled && !fullMode && stateText!=null) {\n                    boolean canInterrupt=bargeInEnabled && handsFree!=null && handsFree.echoControlled();\n                    stateText.setText(canInterrupt?"Отвечаю · можно перебить":"Отвечаю…");\n                }\n                updateEyes();\n            }'''
new='''            public void speech(boolean value) {\n                speaking=value;\n                if(value) {\n                    // Hard self-echo gate: never capture while our own TTS is queued/playing.\n                    if(handsFree!=null) handsFree.arm(false);\n                    hearing=false; micRecording=false; bargeFrames=0;\n                    if(autoEnabled && !fullMode && stateText!=null) stateText.setText("Отвечаю…");\n                } else {\n                    // Let speaker/room echo decay before reopening the microphone.\n                    rearmAt=SystemClock.uptimeMillis()+700;\n                }\n                updateAutoStatus(); updateEyes();\n            }'''
if old not in s: raise SystemExit('speech callback fragment not found')
s=s.replace(old,new,1)
repls={
'ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.hasWork(),speaking,handsFree.echoControlled())':'ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.blocksMic(),speaking,handsFree.echoControlled())',
'ConversationPolicy.shouldInterrupt(partial,rms,bargeInEnabled,busy,voice.hasWork(),speaking,handsFree.echoControlled())':'ConversationPolicy.shouldInterrupt(partial,rms,bargeInEnabled,busy,voice.blocksMic(),speaking,handsFree.echoControlled())',
'ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.hasWork(),speaking,source.echoControlled())':'ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.blocksMic(),speaking,source.echoControlled())',
'ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.hasWork(),speaking,echo)':'ConversationPolicy.interruptWindow(bargeInEnabled,busy,voice.blocksMic(),speaking,echo)',
'ConversationPolicy.canListen(autoReady,foreground,settings,listening,loadingVoice,busy,voice.hasWork(),speaking,bargeInEnabled,echo,SystemClock.uptimeMillis(),rearmAt)':'ConversationPolicy.canListen(autoReady,foreground,settings,listening,loadingVoice,busy,voice.blocksMic(),speaking,bargeInEnabled,echo,SystemClock.uptimeMillis(),rearmAt)',
'Во время озвучивания перебивание работает только когда Android даёт приложению системное эхоподавление. Без AEC Local Eyes автоматически не слушает собственный голос.':'Для защиты от самопрослушивания микрофон всегда отключается на время озвучивания и ещё на короткую паузу после неё. Перебивание голосом работает во время генерации до начала речи ассистента.',
'section(list,"Local Eyes · v0.6-Fix");':'section(list,"Local Eyes · v0.6-Fix2");'
}
for a,b in repls.items():
    if a not in s: raise SystemExit('missing fragment: '+a[:100])
    s=s.replace(a,b)
p.write_text(s,encoding='utf-8')
