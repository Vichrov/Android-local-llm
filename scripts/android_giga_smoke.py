#!/usr/bin/env python3
"""Install verified ASR fixtures on a disposable userdebug emulator, then run JNI tests."""
import pathlib
import shlex
import subprocess
from giga_assets import prepare

def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT, timeout=180)

def shell(*args): return adb('shell', shlex.join(args))

folder, vad = prepare()
package = 'com.vichrov.localai'
base = '/data/media/0/Android/data/' + package + '/files/Download/models'
adb('root'); adb('wait-for-device')
shell('mkdir', '-p', base)
adb('push', str(folder), base + '/')
adb('push', str(vad), base + '/silerovad.onnx')
shell('chown', '-R', 'media_rw:media_rw', base)
shell('chmod', '-R', '770', base)
adb('unroot'); adb('wait-for-device')
adb('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
adb('logcat', '-c')
result = adb('shell', 'am', 'instrument', '-w', '-r', package + '.test/androidx.test.runner.AndroidJUnitRunner')
out = pathlib.Path('app/build/reports/startup'); out.mkdir(parents=True, exist_ok=True)
(out / 'giga-jni-tests.txt').write_text(result)
(out / 'giga-logcat.txt').write_text(adb('logcat', '-d'))
print(result)
if 'OK (' not in result or 'FAILURES' in result or 'INSTRUMENTATION_FAILED' in result:
    raise RuntimeError('GigaAM Android JNI tests failed')
