#!/usr/bin/env python3
from pathlib import Path
import hashlib, shutil, tarfile, urllib.request

ROOT=Path(__file__).resolve().parent.parent
URL='https://github.com/Sqhh99/webrtc-audio-processing/releases/download/m155.8059.4.0/webrtc-audio-processing.android.tar.gz'
SHA='0d9d3d9d801e2cfcd3b73a7296352037ac6c0b41a2632bee675c0162913e253b'
VENDOR=ROOT/'vendor'/'aec3'
ARCHIVE=ROOT/'vendor'/'aec3.android.tar.gz'
JNILIBS=ROOT/'app'/'src'/'main'/'jniLibs'
ABIS=('arm64-v8a','x86_64')

def sha256(path):
    h=hashlib.sha256()
    with open(path,'rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
    return h.hexdigest()

def safe_extract(t, dest):
    base=dest.resolve()
    for m in t.getmembers():
        p=(dest/m.name).resolve()
        if p!=base and base not in p.parents: raise RuntimeError('unsafe AEC3 archive path')
        if m.issym() or m.islnk(): raise RuntimeError('links are not allowed in AEC3 archive')
    t.extractall(dest)

def main():
    marker=VENDOR/'.source-revision'
    expected='m155.8059.4.0:'+SHA
    ready=marker.is_file() and marker.read_text().strip()==expected
    ready=ready and (VENDOR/'include').is_dir()
    ready=ready and all((JNILIBS/a/'libwebrtc-audio-processing-3.so').is_file() for a in ABIS)
    if ready: return
    (ROOT/'vendor').mkdir(exist_ok=True)
    if not ARCHIVE.is_file() or sha256(ARCHIVE)!=SHA:
        urllib.request.urlretrieve(URL,ARCHIVE)
    got=sha256(ARCHIVE)
    if got!=SHA: raise RuntimeError('AEC3 SHA-256 mismatch: '+got)
    shutil.rmtree(VENDOR,ignore_errors=True); VENDOR.mkdir(parents=True)
    with tarfile.open(ARCHIVE,'r:gz') as t: safe_extract(t,VENDOR)
    for abi in ABIS:
        src=VENDOR/'lib'/abi/'libwebrtc-audio-processing-3.so'
        if not src.is_file(): raise RuntimeError('AEC3 library missing for '+abi)
        dst=JNILIBS/abi; dst.mkdir(parents=True,exist_ok=True)
        shutil.copy2(src,dst/'libwebrtc-audio-processing-3.so')
    if not (VENDOR/'include'/'webrtc'/'modules'/'audio_processing'/'include'/'audio_processing.h').is_file():
        raise RuntimeError('AEC3 headers missing')
    marker.write_text(expected)
    ARCHIVE.unlink(missing_ok=True)

if __name__=='__main__': main()
