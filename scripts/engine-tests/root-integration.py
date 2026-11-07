#!/usr/bin/env python3
"""Root-emulator integration test. Requires prepared probes and foreground TouchLab.

Does not change SELinux, su, display settings or activity. Uses a root peer over adb
forwarding, so passing is not a substitute for a physical device su-manager check.
"""
import datetime
import json
import os
import pathlib
import re
import socket
import shlex
import sys
import subprocess
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = os.environ.get("ANDROID_SDK_ROOT", "/opt/homebrew/share/android-commandlinetools") + "/platform-tools/adb"
REPORT = ROOT / "outputs/root-engine-test.json"
LOG = ROOT / "outputs/root-engine-daemon.log"
messages = []
checks = []
snapshots = {}


def adb(*args):
    return subprocess.check_output([ADB, *args], text=True).strip()


def report():
    text = adb("shell", "cat /data/user/0/cn.offline.mapper/files/touch-lab.json 2>/dev/null || true")
    return json.loads(text) if text else {"events": 0, "active": 0, "maxPointers": 0, "history": []}


def check(value, message):
    if not value:
        raise AssertionError(message)
    checks.append(message)
    print("PASS", message, flush=True)


def new_probe():
    process = subprocess.Popen([ADB, "shell", "/data/local/tmp/mapper-uinput-probe"],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if process.stdout.readline().strip() != "READY":
        raise RuntimeError("uinput failed: " + process.stderr.read())
    return process


def emit(probe, *lines):
    probe.stdin.write("\n".join(lines) + "\n")
    probe.stdin.flush()


def wait_report(predicate, seconds=3):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = report()
        if predicate(value):
            return value
        time.sleep(.04)
    raise AssertionError("TouchLab condition timed out: " + json.dumps(report()))


def main():
    check(adb("shell", "id -u") == "0", "test peer is root")
    focus = adb("shell", "dumpsys window displays")
    check(bool(re.search(r"mCurrentFocus=.*TouchLabActivity", focus)), "TouchLab is foreground")
    display = adb("shell", "dumpsys window displays")
    match = re.search(r"cur=(\d+)x(\d+)", display)
    if not match:
        raise RuntimeError("Could not determine active display dimensions")
    width, height = map(int, match.groups())
    classpath="/data/local/tmp/mapper-engine-test.jar"
    library="/data/local/tmp/libmapper_input_test.so"
    installed="--installed-apk" in sys.argv
    if installed:
        classpath=adb("shell", "pm path cn.offline.mapper").split("package:",1)[1]
        abi=adb("shell", "getprop ro.product.cpu.abi")
        arch={"arm64-v8a":"arm64","armeabi-v7a":"arm","x86_64":"x86_64"}[abi]
        library=classpath.rsplit("/",1)[0]+"/lib/"+arch+"/libmapper_input.so"
        adb("shell", "test -f "+shlex.quote(library))
        expected=json.loads((ROOT/"outputs/交付校验.json").read_text())["sha256"]
        check(adb("shell", "sha256sum "+shlex.quote(classpath)).split()[0]==expected,"installed APK bytes match the delivered signed artifact")
    before = report()
    probe = new_probe()
    daemon = None
    sock = None
    stream = None
    port = None
    result = {"date": datetime.datetime.now(datetime.timezone.utc).isoformat(), "api": adb("shell", "getprop ro.build.version.sdk"),
              "abi": adb("shell", "getprop ro.product.cpu.abi"), "display": [width, height], "peer": "root adb-forward",
              "physicalDeviceVerified": False, "installedAPK": installed, "engineClasspath": classpath, "before": before, "checks": checks, "messages": messages, "snapshots": snapshots}
    try:
        name, token = "mapper_test_" + uuid.uuid4().hex, uuid.uuid4().hex
        log = LOG.open("w")
        daemon = subprocess.Popen([ADB, "shell", "CLASSPATH="+shlex.quote(classpath)+" app_process / "
                                   f"cn.offline.mapper.engine.RootDaemon {name} 10000 {shlex.quote(library)} {token}"],
                                  stdout=log, stderr=log, text=True)
        port = int(adb("forward", "tcp:0", "localabstract:" + name))
        deadline = time.monotonic() + 20
        while name not in adb("shell", "cat /proc/net/unix"):
            if daemon.poll() is not None:
                raise RuntimeError("Daemon failed before listening: " + LOG.read_text())
            if time.monotonic() > deadline:
                raise RuntimeError("Daemon did not create its local socket: " + LOG.read_text())
            time.sleep(.15)
        # Prove a wrong token cannot claim or activate the service.
        denied = socket.create_connection(("127.0.0.1", port), 3)
        denied.settimeout(3)
        denied.sendall(b'{"cmd":"auth","token":"incorrect"}\n')
        check(denied.recv(1) == b"", "wrong token is rejected")
        denied.close()
        sock = socket.create_connection(("127.0.0.1", port), 3)
        sock.settimeout(5)
        stream = sock.makefile("rw")

        def send(value):
            stream.write(json.dumps(value) + "\n")
            stream.flush()

        def receive(predicate):
            while True:
                line = stream.readline()
                if not line:
                    raise RuntimeError("Daemon EOF: " + LOG.read_text())
                value = json.loads(line)
                messages.append(value)
                if predicate(value):
                    return value

        def command(value, predicate=lambda v: v.get("type") == "status"):
            send(value)
            return receive(predicate)

        send({"cmd": "auth", "token": token})
        ready = receive(lambda v: v.get("type") == "ready")
        check(ready["uid"] == 0 and ready["injection"], "root daemon resolves injection API")
        check(len(ready["devices"]) == 1 and "Development USB Probe" in ready["devices"][0]["name"],
              "touchscreens and power node are excluded")
        profile = {"bindings": [
            {"id": 1, "type": "hold", "code": 57, "x": .85, "y": .78},
            {"id": 2, "type": "hold", "code": 272, "x": .87, "y": .48},
            {"id": 3, "type": "repeat", "code": 19, "x": .45, "y": .4, "duration": 30, "interval": 100},
            {"id": 4, "type": "swipe", "code": 20, "x": .3, "y": .5, "endX": .6, "endY": .5, "duration": 160}],
                   "joystick": {"enabled": True, "x": .18, "y": .75, "radius": .075},
                   "aim": {"enabled": True, "x": .65, "y": .5, "radius": .2, "toggleCode": 59, "idleMs": 80}}
        config = {"cmd": "config", "width": width, "height": height, "profile": profile}
        command(config)
        started = command({"cmd": "start"}, lambda v: v.get("running"))
        path = started["devices"][0]["path"]
        check(adb("shell", f"/data/local/tmp/mapper-uinput-probe --check-grab {path}") == "BUSY", "EVIOCGRAB excludes another root reader")
        # Pointer mode overrides the template's left-button shooting binding.
        baseline = report()["events"]
        emit(probe, "rel 0 40", "rel 1 20", "key 272 1")
        time.sleep(.06)
        emit(probe, "key 272 0")
        pointer = wait_report(lambda v: v["events"] >= baseline + 2 and v["active"] == 0)
        snapshots["pointerClick"] = pointer
        click = [e for e in pointer["history"] if e["action"] == 0][-1]
        check(abs(click["pointers"][0]["x"] - (width / 2 + 40 - 4)) < 2 and abs(click["pointers"][0]["y"] - (height / 2 + 20 - 4)) < 2,
              "unlocked FPS left button clicks ordinary cursor position")
        emit(probe, "key 17 1", "key 57 1", "key 59 1", "key 59 0", "key 272 1", "rel 0 18", "rel 1 -12")
        simultaneous = wait_report(lambda v: v["maxPointers"] >= 4)
        snapshots["simultaneous"] = simultaneous
        check(any(set(p["id"] for p in e["pointers"]) == {0, 1, 2, 3} for e in simultaneous["history"]),
              "W, space, locked left-click and mouse view produce four coherent pointers")
        emit(probe, "key 272 0", "key 57 0", "key 17 0")
        wait_report(lambda v: v["active"] == 0)
        emit(probe, "key 19 1")
        time.sleep(.35)
        emit(probe, "key 19 0")
        ended = wait_report(lambda v: v["active"] == 0)
        time.sleep(.25)
        check(report()["events"] == ended["events"], "repeat stops after physical key release")
        emit(probe, "key 20 1", "key 20 0")
        time.sleep(.3)
        swipe = report()
        snapshots["swipe"] = swipe
        check(swipe["active"] == 0 and any(e["action"] == 1 and any(p["id"] < 10 and abs(p["x"] - .6 * (width - 1)) < 2
                                                             for p in e["pointers"]) for e in swipe["history"]),
              "swipe reaches endpoint and releases real touch")
        emit(probe, "key 17 1", "key 57 1")
        wait_report(lambda v: v["active"] >= 2)
        emit(probe, "key 66 1", "key 66 0")
        paused = receive(lambda v: v.get("type") == "status" and not v["running"])
        check(not any(d["grabbed"] for d in paused["devices"]), "F8 releases all daemon grabs")
        check(adb("shell", f"/data/local/tmp/mapper-uinput-probe --check-grab {path}") == "FREE", "F8 restores root reader access")
        cancelled = wait_report(lambda v: v["active"] == 0 and v["history"][-1]["action"] == 3)
        snapshots["f8"] = cancelled
        # Release physical state before starting again; evdev does not synthesize
        # a second DOWN for a key that remained physically pressed across pause.
        emit(probe, "key 17 0", "key 57 0")
        time.sleep(.04)
        command({"cmd": "start"}, lambda v: v.get("running"))
        emit(probe, "key 57 1")
        wait_report(lambda v: v["active"] > 0)
        emit(probe, "quit")
        probe.wait(timeout=3)
        disconnected = receive(lambda v: v.get("type") == "error")
        check(bool(disconnected["message"]), "physical disconnect produces an explicit error")
        command({"cmd": "status"}, lambda v: v.get("type") == "status" and not v["running"])
        snapshots["unplug"] = wait_report(lambda v: v["active"] == 0)
        check(snapshots["unplug"]["history"][-1]["action"] in (1,3), "unplug releases real active touch with kernel UP or explicit CANCEL")
        # A bad config must not reactivate the old layout.
        command({"cmd": "config", "profile": {}, "width": 0, "height": 0}, lambda v: v.get("type") == "error")
        failure = command({"cmd": "start"}, lambda v: v.get("type") == "error")
        check("配置" in failure["message"], "start is rejected after failed config")
        # Reconnect a new device and prove closing the client cancels a held touch.
        probe = new_probe()
        time.sleep(1.1)
        command(config)
        command({"cmd": "start"}, lambda v: v.get("running"))
        emit(probe, "key 57 1")
        wait_report(lambda v: v["active"] > 0)
        stream.close()
        stream = None
        sock.close()
        sock = None
        daemon.wait(timeout=4)
        snapshots["socketClose"] = wait_report(lambda v: v["active"] == 0)
        check(daemon.returncode == 0 and snapshots["socketClose"]["history"][-1]["action"] == 3,
              "socket close cancels touch and exits daemon")
        result["passed"] = True
        result["after"] = report()
    except Exception as exc:
        result["passed"] = False
        result["failure"] = str(exc)
        raise
    finally:
        if stream:
            stream.close()
        if sock:
            sock.close()
        if probe.poll() is None:
            emit(probe, "quit")
            probe.wait(timeout=3)
        if daemon is not None and daemon.poll() is None:
            daemon.terminate()
            daemon.wait(timeout=3)
        if port:
            subprocess.run([ADB, "forward", "--remove", "tcp:" + str(port)], check=False)
        REPORT.write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print("Report:", REPORT, flush=True)


if __name__ == "__main__":
    main()
