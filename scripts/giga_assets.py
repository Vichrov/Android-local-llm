#!/usr/bin/env python3
"""SHA-pinned test inputs from the same providers used by the Android downloader."""
import hashlib
import pathlib
import tarfile
import urllib.request

ROOT = 'sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16'
BASE = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/'
CACHE = pathlib.Path('build/giga-test')

def fetch(name, url, sha):
    CACHE.mkdir(parents=True, exist_ok=True)
    p = CACHE / name
    if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest() != sha:
        temp = p.with_suffix('.download')
        urllib.request.urlretrieve(url, temp)
        if hashlib.sha256(temp.read_bytes()).hexdigest() != sha:
            temp.unlink()
            raise RuntimeError('Checksum mismatch: ' + name)
        temp.replace(p)
    return p

def prepare():
    archive = fetch('giga.tar.bz2', BASE + ROOT + '.tar.bz2', 'e1291d704460cab4a01716081170c86c12f6b15338a1534f71cc5956922adb52')
    vad = fetch('silero.onnx', BASE + 'silero_vad.onnx', '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6')
    folder = CACHE / 'gigaam'
    folder.mkdir(exist_ok=True)
    # Extract only explicit regular files from the verified archive.
    with tarfile.open(archive) as t:
        for name in ('model.int8.onnx', 'tokens.txt', 'LICENSE', 'test_wavs/example.wav'):
            member = t.getmember(ROOT + '/' + name)
            if not member.isfile(): raise RuntimeError('Expected a regular model file')
            (folder / pathlib.PurePosixPath(name).name).write_bytes(t.extractfile(member).read())
    (folder / '.verified').touch()
    return folder, vad

if __name__ == '__main__':
    print(prepare())
