#!/usr/bin/env python3
"""Download SHA-pinned public test inputs and run the host-only ASR regression."""
import hashlib
from pathlib import Path
import subprocess
import sys
import urllib.request
import zipfile

root=Path(__file__).resolve().parents[1]
cache=root/'build'/'asr-smoke'; cache.mkdir(parents=True,exist_ok=True)
inputs=[
 ('vosk.whl','https://files.pythonhosted.org/packages/fc/ca/83398cfcd557360a3d7b2d732aee1c5f6999f68618d1645f38d53e14c9ff/vosk-0.3.45-py3-none-manylinux_2_12_x86_64.manylinux2010_x86_64.whl','25e025093c4399d7278f543568ed8cc5460ac3a4bf48c23673ace1e25d26619f'),
 ('model.zip','https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip','961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4'),
 ('test.wav','https://huggingface.co/alphacep/vosk-model-small-ru/resolve/4d68c4017bcfa44e2a79581f7933339e916a35da/test.wav','d6e768803b3bc3afcb08326677f3bb872c9beeed29af40d3579bcc14e74484f8')
]
for name,url,digest in inputs:
    target=cache/name
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest()!=digest:
        with urllib.request.urlopen(url,timeout=60) as response: target.write_bytes(response.read())
    assert hashlib.sha256(target.read_bytes()).hexdigest()==digest, name+' SHA-256 mismatch'
    if name.endswith(('.zip','.whl')):
        with zipfile.ZipFile(target) as archive: archive.extractall(cache)
run=subprocess.run([sys.executable,str(root/'scripts/test_vosk_audio.py'),str(cache/'vosk/libvosk.so'),str(cache/'vosk-model-small-ru-0.22'),str(cache/'test.wav')],text=True,capture_output=True)
report=root/'app/build/reports/asr-host-smoke.txt'; report.parent.mkdir(parents=True,exist_ok=True)
report.write_text(run.stdout+run.stderr,encoding='utf-8'); print(run.stdout+run.stderr)
raise SystemExit(run.returncode)
