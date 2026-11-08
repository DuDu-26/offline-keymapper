#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ANDROID_SDK_DIR=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}
JAVA_RUNTIME_DIR=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
TEST_DIR="$PROJECT_ROOT/build/engine-test-classes"
mkdir -p "$TEST_DIR"
# Rebuild the complete test dex from the production engine and development-only tests.
find "$TEST_DIR" -name '*.class' -delete
"$JAVA_RUNTIME_DIR/bin/javac" --release 11 -cp "$ANDROID_SDK_DIR/platforms/android-35/android.jar" \
    -d "$TEST_DIR" "$PROJECT_ROOT"/app/src/main/java/cn/offline/mapper/engine/*.java \
    "$PROJECT_ROOT"/scripts/engine-tests/EngineSelfTest.java
JAVA_HOME="$JAVA_RUNTIME_DIR" "$ANDROID_SDK_DIR/build-tools/35.0.0/d8" \
    --lib "$ANDROID_SDK_DIR/platforms/android-35/android.jar" --output "$PROJECT_ROOT/build/engine-test.jar" \
    "$TEST_DIR"/cn/offline/mapper/engine/*.class
ADB="$ANDROID_SDK_DIR/platform-tools/adb"
"$ADB" push "$PROJECT_ROOT/build/engine-test.jar" /data/local/tmp/mapper-engine-test.jar
"$ADB" shell 'CLASSPATH=/data/local/tmp/mapper-engine-test.jar app_process / cn.offline.mapper.engine.EngineSelfTest'
if [ "${1:-}" = "--prepare-root-probe" ]; then
    "$PROJECT_ROOT/scripts/build-native.sh"
    DEVICE_ABI=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')
    case "$DEVICE_ABI" in
        arm64-v8a) TARGET=aarch64-linux-android26 ;;
        armeabi-v7a) TARGET=armv7a-linux-androideabi26 ;;
        x86_64) TARGET=x86_64-linux-android26 ;;
        *) echo "Unsupported probe ABI: $DEVICE_ABI" >&2; exit 1 ;;
    esac
    NDK_DIR=${ANDROID_NDK_HOME:-$ANDROID_SDK_DIR/ndk/27.2.12479018}
    "$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/$TARGET-clang++" -std=c++17 -O2 -static-libstdc++ \
        "$PROJECT_ROOT/app/src/main/cpp/uinput_probe.cpp" -o "$PROJECT_ROOT/build/uinput-probe-$DEVICE_ABI"
    "$ADB" push "$PROJECT_ROOT/build/uinput-probe-$DEVICE_ABI" /data/local/tmp/mapper-uinput-probe
    "$ADB" push "$PROJECT_ROOT/app/src/main/jniLibs/$DEVICE_ABI/libmapper_input.so" /data/local/tmp/libmapper_input_test.so
    "$ADB" shell chmod 755 /data/local/tmp/mapper-uinput-probe
fi
