#!/bin/sh
# Runs the joystick trajectory tests on a plain JVM (no Android SDK or device needed).
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac --release 11 -d "$OUT" "$PROJECT_ROOT/app/src/main/java/cn/offline/mapper/engine/JoystickTrajectory.java" \
    "$PROJECT_ROOT/scripts/trajectory-tests/TrajectoryTest.java"
java -cp "$OUT" cn.offline.mapper.engine.TrajectoryTest
