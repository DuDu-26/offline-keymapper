#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_ROOT"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
ANDROID_SDK_DIR=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}
TOOLS="$ANDROID_SDK_DIR/build-tools/35.0.0"
./scripts/build-native.sh
./gradlew :app:assembleRelease :app:lintRelease --offline
mkdir -p outputs
"$TOOLS/zipalign" -f -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk outputs/offline-mapper-aligned.apk
"$TOOLS/apksigner" sign --ks .signing/offline-mapper.jks --ks-key-alias offline-mapper \
    --ks-pass file:.signing/password \
    --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
    --out 'outputs/离线键鼠映射-v1.1.0-root.apk' outputs/offline-mapper-aligned.apk
"$TOOLS/zipalign" -c -P 16 -v 4 'outputs/离线键鼠映射-v1.1.0-root.apk'
"$TOOLS/apksigner" verify --min-sdk-version 24 --verbose --print-certs 'outputs/离线键鼠映射-v1.1.0-root.apk'

rm -f outputs/offline-mapper-aligned.apk 'outputs/离线键鼠映射-v1.1.0-root.apk.idsig'
python3 scripts/verify-apk.py
