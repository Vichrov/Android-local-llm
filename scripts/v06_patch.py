from pathlib import Path
p=Path('app/src/main/java/com/vichrov/localai/MainActivity.java')
s=p.read_text(encoding='utf-8')

def repl(old,new):
    global s
    if old not in s:
        raise SystemExit('missing fragment: '+old)
    s=s.replace(old,new,1)

repl('if(firstTokenAt==0) firstTokenAt=SystemClock.elapsedRealtime();',
     'if(ticket==turn && firstTokenAt==0) firstTokenAt=SystemClock.elapsedRealtime();')
repl('turn++; dismissModelLoading(); llm.cancel(); voice.stop(); busy=false; bargeFrames=0;',
     'turn++; dismissModelLoading(); llm.cancel(); voice.stop(); rearmAt=SystemClock.uptimeMillis(); busy=false; bargeFrames=0;')
repl('turn++; dismissModelLoading(); llm.cancel(); voice.stop(); stopRecording();\n        if(stateText!=null) stateText.setText(busy?"Останавливаю…":"Готов к разговору");',
     'turn++; dismissModelLoading(); llm.cancel(); voice.stop(); stopRecording(); busy=false;\n        if(stateText!=null) stateText.setText("Готов к разговору");')
p.write_text(s,encoding='utf-8')
