#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ANDROID_SDK_DIR=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}
ANDROID_NDK_DIR=${ANDROID_NDK_HOME:-$ANDROID_SDK_DIR/ndk/27.2.12479018}
TOOLCHAIN="$ANDROID_NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin"
for ABI in arm64-v8a armeabi-v7a x86_64; do
    case "$ABI" in
        arm64-v8a) TARGET=aarch64-linux-android26 ;;
        armeabi-v7a) TARGET=armv7a-linux-androideabi26 ;;
        x86_64) TARGET=x86_64-linux-android26 ;;
    esac
    DEST="$PROJECT_ROOT/app/src/main/jniLibs/$ABI"
    mkdir -p "$DEST"
    "$TOOLCHAIN/$TARGET-clang++" -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
        -static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
        "$PROJECT_ROOT/app/src/main/cpp/input_reader.cpp" -o "$DEST/libmapper_input.so"
    "$TOOLCHAIN/llvm-strip" "$DEST/libmapper_input.so"
done
