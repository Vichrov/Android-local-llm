#!/usr/bin/env python3
"""Pinned official Android runtime. Weights are downloaded by the app, not bundled."""
import hashlib
import pathlib
import urllib.request
p = pathlib.Path(__file__).resolve().parents[1] / 'vendor/sherpa-onnx.aar'
sha = 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest() != sha:
    p.parent.mkdir(exist_ok=True)
    temp = p.with_suffix('.part')
    urllib.request.urlretrieve('https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar', temp)
    if hashlib.sha256(temp.read_bytes()).hexdigest() != sha:
        temp.unlink()
        raise RuntimeError('sherpa-onnx AAR checksum mismatch')
    temp.replace(p)
print('sherpa-onnx 2.0.1 AAR verified')
