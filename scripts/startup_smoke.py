#!/usr/bin/env python3
"""Real Android process + UI smoke test. Only run on a disposable CI emulator."""
import pathlib
import shlex
import subprocess
import sys
import time

PACKAGE = 'com.vichrov.localai'
OUT = pathlib.Path('app/build/reports/startup')
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, check=True):
    result = subprocess.run(['adb', *args], text=True, capture_output=True, timeout=45)
    if check and result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout

def shell(*args, check=True):
    return adb('shell', shlex.join(args), check=check)

def app_shell(command):
    return shell('run-as', PACKAGE, 'sh', '-c', command)

def logs(name):
    data = adb('logcat', '-d', '-v', 'threadtime')
    (OUT / (name + '.log')).write_text(data)
    return data

def visible(name, expected):
    for _ in range(12):
        if not shell('pidof', PACKAGE, check=False).strip():
            raise AssertionError('App process died: ' + name)
        result = shell('uiautomator', 'dump', '/sdcard/startup.xml', check=False)
        if 'dumped' in result:
            xml = shell('cat', '/sdcard/startup.xml')
            if expected in xml:
                (OUT / (name + '.xml')).write_text(xml)
                with (OUT / (name + '.png')).open('wb') as f:
                    subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=f, check=True, timeout=30)
                data = logs(name)
                assert 'FATAL EXCEPTION' not in data, data[-8000:]
                print('PASS', name, flush=True)
                return
        time.sleep(.5)
    raise AssertionError('Expected visible screen not found: ' + expected)

def launch():
    shell('am', 'start', '-W', '-n', PACKAGE + '/.MainActivity', check=False)
    time.sleep(1)

def stop():
    shell('am', 'force-stop', PACKAGE)

try:
    adb('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
    shell('pm', 'clear', PACKAGE)
    shell('input', 'keyevent', 'KEYCODE_WAKEUP')
    shell('wm', 'dismiss-keyguard')
    adb('logcat', '-c')
    if '--expect-v03-crash' in sys.argv:
        launch()
        data = logs('v03-reproduced-crash')
        assert 'NullPointerException' in data and 'PhoneWindow.getInsetsController' in data and 'MainActivity.loadPalette' in data, data[-10000:]
        assert not shell('pidof', PACKAGE, check=False).strip(), 'Original process unexpectedly alive'
        print('PASS: original v0.3 startup crash reproduced in PhoneWindow.getInsetsController', flush=True)
    else:
        for night in ('no', 'yes'):
            stop()
            shell('cmd', 'uimode', 'night', night)
            adb('logcat', '-c')
            launch()
            visible('settings-' + night, 'Добро пожаловать')
        # Simulate an installed model without downloading or loading its weights.
        stop()
        app_shell('mkdir -p shared_prefs')
        prefs = '<map><string name="selected">qwen08q4</string><boolean name="voice" value="false" /></map>'
        app_shell('printf %s ' + shlex.quote(prefs) + ' > shared_prefs/settings.xml')
        model = '/sdcard/Android/data/' + PACKAGE + '/files/Download/models/qwen08q4.gguf'
        app_shell('mkdir -p ' + shlex.quote(str(pathlib.PurePosixPath(model).parent)))
        app_shell('truncate -s 532517120 ' + shlex.quote(model))
        for night in ('no', 'yes'):
            stop()
            shell('cmd', 'uimode', 'night', night)
            adb('logcat', '-c')
            launch()
            visible('chat-' + night, 'Авто-голос')
        before = shell('pidof', PACKAGE).strip()
        shell('cmd', 'uimode', 'night', 'no')
        time.sleep(1)
        visible('chat-live-theme', 'Авто-голос')
        assert shell('pidof', PACKAGE).strip() == before, 'Theme switch restarted app process'
        print('PASS: both cold-start routes, both themes, live theme switch', flush=True)
finally:
    logs('final-' + ('baseline' if '--expect-v03-crash' in sys.argv else 'fixed'))
    stop()
