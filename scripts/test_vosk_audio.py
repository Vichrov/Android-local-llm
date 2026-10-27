#!/usr/bin/env python3
"""Host smoke test of Vosk endpointing with normal and sub-threshold Russian PCM.
Uses libvosk from the official Linux wheel, not Android AudioRecord.
Usage: test_vosk_audio.py LIBVOSK MODEL_DIRECTORY WAV
Sample: https://huggingface.co/alphacep/vosk-model-small-ru/resolve/4d68c4017bcfa44e2a79581f7933339e916a35da/test.wav
"""
import array
import ctypes as C
import json
import sys
import wave

lib = C.CDLL(sys.argv[1])
def bind(name, result, *args):
    f = getattr(lib, name); f.restype = result; f.argtypes = args; return f
bind('vosk_set_log_level', None, C.c_int)(-1)
model_new = bind('vosk_model_new', C.c_void_p, C.c_char_p)
model_free = bind('vosk_model_free', None, C.c_void_p)
rec_new = bind('vosk_recognizer_new', C.c_void_p, C.c_void_p, C.c_float)
rec_free = bind('vosk_recognizer_free', None, C.c_void_p)
accept = bind('vosk_recognizer_accept_waveform', C.c_int, C.c_void_p, C.c_char_p, C.c_int)
result = bind('vosk_recognizer_result', C.c_char_p, C.c_void_p)
partial = bind('vosk_recognizer_partial_result', C.c_char_p, C.c_void_p)
finish = bind('vosk_recognizer_final_result', C.c_char_p, C.c_void_p)
reset = bind('vosk_recognizer_reset', None, C.c_void_p)
with wave.open(sys.argv[3], 'rb') as wav:
    assert (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) == (1, 2, 16000)
    pcm = array.array('h', wav.readframes(wav.getnframes()))
model = model_new(sys.argv[2].encode()); assert model
try:
    for name, gain in [('normal', 1), ('quiet_peak_180', 180 / max(abs(x) for x in pcm))]:
        audio = array.array('h', (int(x * gain) for x in pcm))
        if name.startswith('quiet'): assert max(abs(x) for x in audio) <= 180  # v0.2 RMS gate can never open.
        audio.extend([0] * 16000 * 3)
        rec = rec_new(model, 16000); assert rec
        texts, partial_seen, endpoint_texts = [], False, 0
        try:
            for offset in range(0, len(audio), 1280):
                chunk = audio[offset:offset + 1280].tobytes()
                if accept(rec, chunk, len(chunk)):
                    text = json.loads(result(rec)).get('text', '')
                    if text: texts.append(text); endpoint_texts += 1
                    reset(rec)
                else:
                    partial_seen |= bool(json.loads(partial(rec)).get('partial', ''))
            tail = json.loads(finish(rec)).get('text', '')
            if tail: texts.append(tail)
        finally: rec_free(rec)
        assert endpoint_texts > 0 and partial_seen, (name, texts)
        print(json.dumps({'case': name, 'partial_seen': partial_seen, 'nonempty_endpoints': endpoint_texts, 'text': ' '.join(texts)}, ensure_ascii=False))
    rec = rec_new(model, 16000)
    try:
        for _ in range(125):
            chunk = bytes(2560)
            if accept(rec, chunk, len(chunk)):
                assert not json.loads(result(rec)).get('text', ''); reset(rec)
        assert not json.loads(finish(rec)).get('text', '')
        print('silence: no transcript (PASS)')
    finally: rec_free(rec)
finally: model_free(model)
