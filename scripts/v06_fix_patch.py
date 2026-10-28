from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    s = p.read_text(encoding='utf-8')
    if old not in s:
        raise SystemExit(f'missing fragment in {path}: {old[:120]}')
    s = s.replace(old, new, 1)
    p.write_text(s, encoding='utf-8')

# The v0.6 regression was caused by prepare() being moved after load().
# A previous cancel()/onStop leaves NativeLlm.stopped=true, while the GGUF
# loader uses that same flag as its progress-callback abort condition.
# Prepare exactly once at the beginning of a still-current serial operation,
# then keep cancellation sticky through both load and generate.
replace_once(
    'app/src/main/java/com/vichrov/localai/MainActivity.java',
    '''        worker.execute(()-> {\n            try {\n                if(!loadedId.equals(model.id)) { loadedId=""; llm.load(store.file(model).getAbsolutePath(),PerformanceTuning.threads(MainActivity.this)); loadedId=model.id; }\n                main.post(()-> { if(!destroyed) { dismissModelLoading(ticket); if(ticket==turn && stateText!=null) stateText.setText("Думаю…"); } });\n                if(ticket!=turn || destroyed) return;\n                llm.prepare();\n                int generated=llm.generate(prompt.getBytes(StandardCharsets.UTF_8), bytes-> {''',
    '''        worker.execute(()-> {\n            try {\n                if(ticket!=turn || destroyed) return;\n                if(!LlmOperationGate.prepare(ticket,()->turn,llm::prepare)) return;\n                if(!loadedId.equals(model.id)) {\n                    loadedId="";\n                    llm.load(store.file(model).getAbsolutePath(),PerformanceTuning.threads(MainActivity.this));\n                    if(ticket!=turn || destroyed) return;\n                    loadedId=model.id;\n                }\n                main.post(()-> { if(!destroyed) { dismissModelLoading(ticket); if(ticket==turn && stateText!=null) stateText.setText("Думаю…"); } });\n                if(ticket!=turn || destroyed) return;\n                int generated=llm.generate(prompt.getBytes(StandardCharsets.UTF_8), bytes-> {'''
)
replace_once(
    'app/src/main/java/com/vichrov/localai/MainActivity.java',
    '        section(list,"Local Eyes · v0.6");',
    '        section(list,"Local Eyes · v0.6-Fix");'
)

Path('app/src/main/java/com/vichrov/localai/LlmOperationGate.java').write_text('''package com.vichrov.localai;\n\nimport java.util.function.IntSupplier;\n\n/** Keeps NativeLlm cancellation sticky for one serial load -> generate operation. */\nfinal class LlmOperationGate {\n    private LlmOperationGate() {}\n\n    static boolean prepare(int ticket, IntSupplier currentTicket, Runnable nativePrepare) {\n        if(ticket != currentTicket.getAsInt()) return false;\n        nativePrepare.run();\n        return ticket == currentTicket.getAsInt();\n    }\n}\n''', encoding='utf-8')

Path('app/src/test/java/com/vichrov/localai/LlmOperationGateTest.java').write_text('''package com.vichrov.localai;\n\nimport static org.junit.Assert.*;\nimport java.util.concurrent.atomic.AtomicBoolean;\nimport java.util.concurrent.atomic.AtomicInteger;\nimport org.junit.Test;\n\npublic class LlmOperationGateTest {\n    @Test public void currentOperationIsPrepared() {\n        AtomicInteger current=new AtomicInteger(7);\n        AtomicBoolean prepared=new AtomicBoolean(false);\n        assertTrue(LlmOperationGate.prepare(7,current::get,()->prepared.set(true)));\n        assertTrue(prepared.get());\n    }\n\n    @Test public void staleOperationNeverClearsCancellation() {\n        AtomicInteger current=new AtomicInteger(8);\n        AtomicBoolean prepared=new AtomicBoolean(false);\n        assertFalse(LlmOperationGate.prepare(7,current::get,()->prepared.set(true)));\n        assertFalse(prepared.get());\n    }\n\n    @Test public void cancellationDuringPrepareStopsBeforeLoad() {\n        AtomicInteger current=new AtomicInteger(7);\n        assertFalse(LlmOperationGate.prepare(7,current::get,current::incrementAndGet));\n        assertEquals(8,current.get());\n    }\n}\n''', encoding='utf-8')

replace_once('app/build.gradle', "        versionCode 10\n        versionName '0.6'", "        versionCode 11\n        versionName '0.6-Fix'")

Path('RELEASE_NOTES_V06_FIX.md').write_text('''# Local Eyes v0.6-Fix\n\nИсправление критической регрессии загрузки GGUF после v0.6.\n\n## Исправлено\n\n- Исправлена ситуация, когда после `Stop`, ухода приложения в фон или другой отмены следующая загрузка LLM завершалась сообщением «Не удалось загрузить GGUF. Проверьте модель и свободную память».\n- Причина: v0.6 переносил `NativeLlm.prepare()` после `llm.load()`, а загрузчик llama.cpp использует тот же флаг отмены в progress callback. Поэтому сохранённый `stopped=true` мог отменять загрузку корректного GGUF ещё до генерации.\n- Теперь одна операция `load -> generate` получает `prepare()` только в начале serial worker и только если её ticket всё ещё актуален. После этого `cancel()` остаётся активным до завершения всей операции и не может быть случайно сброшен между загрузкой и генерацией.\n- Добавлен unit regression test для stale/cancelled operation ticket.\n\n## Сохранено из v0.6\n\nVoice 2.0, безопасный barge-in с AEC fallback, KV-prefix reuse, TTFT/tokens/s метрики, Vosk/GigaAM и полностью локальная работа остаются без отката.\n\nAndroid 12+ / ARM64. Snapdragon 855+ остаётся минимальной целевой платформой.\n''',encoding='utf-8')
