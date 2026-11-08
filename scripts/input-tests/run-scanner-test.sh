#!/bin/sh
# Explicit-device, recording-only native scanner regression; no Root or real evdev I/O.
set -eu
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <adb-serial>" >&2
    exit 2
fi
MAPPER_TEST_SERIAL=$1
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ANDROID_SDK_DIR=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}
ANDROID_NDK_DIR=${ANDROID_NDK_HOME:-$ANDROID_SDK_DIR/ndk/27.2.12479018}
ADB="$ANDROID_SDK_DIR/platform-tools/adb"
ABI=$("$ADB" -s "$MAPPER_TEST_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
case "$ABI" in
    arm64-v8a) TARGET=aarch64-linux-android26 ;;
    armeabi-v7a) TARGET=armv7a-linux-androideabi26 ;;
    x86_64) TARGET=x86_64-linux-android26 ;;
    *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
esac
mkdir -p "$PROJECT_ROOT/build/input-tests"
EXE="$PROJECT_ROOT/build/input-tests/scanner-self-test-$ABI"
"$ANDROID_NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/$TARGET-clang++" \
    -std=c++17 -Wall -Wextra -Werror -O2 -static-libstdc++ \
    "$PROJECT_ROOT/scripts/input-tests/ScannerSelfTest.cpp" -o "$EXE"
"$ADB" -s "$MAPPER_TEST_SERIAL" push "$EXE" /data/local/tmp/mapper-scanner-self-test
"$ADB" -s "$MAPPER_TEST_SERIAL" shell chmod 755 /data/local/tmp/mapper-scanner-self-test
"$ADB" -s "$MAPPER_TEST_SERIAL" shell /data/local/tmp/mapper-scanner-self-test
"$ADB" -s "$MAPPER_TEST_SERIAL" shell rm -f /data/local/tmp/mapper-scanner-self-test
