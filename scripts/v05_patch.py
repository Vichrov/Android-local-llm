from pathlib import Path

p=Path('app/src/main/java/com/vichrov/localai/MainActivity.java')
s=p.read_text(encoding='utf-8')
replacements={
    'section(list,"Local Eyes · v0.4");':'section(list,"Local Eyes · v0.5");',
    'CPU · 4 потока · контекст 2048 · до 512 токенов. Ответы могут быть ошибочными.':'CPU · KleidiAI auto · до 6 потоков с thermal fallback · контекст 2048 · batch 256 · до 512 токенов. Ответы могут быть ошибочными.',
    'llm.load(store.file(model).getAbsolutePath(),Math.min(4,Runtime.getRuntime().availableProcessors()))':'llm.load(store.file(model).getAbsolutePath(),PerformanceTuning.threads(MainActivity.this))',
    'modelLoadingTicket=ticket;\n        LinearLayout content=':'modelLoadingTicket=ticket;\n        if(eyes!=null) eyes.mode(EyesView.Mode.LOADING_MODEL);\n        LinearLayout content=',
}
for old,new in replacements.items():
    if old not in s:
        raise SystemExit(f'expected source fragment not found: {old[:80]}')
    s=s.replace(old,new,1)
p.write_text(s,encoding='utf-8')
