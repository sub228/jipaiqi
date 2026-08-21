#!/usr/bin/env bash
# Build the debug APK with the system Gradle.
# Assumes ANDROID_HOME points at a configured Android SDK.
set -euo pipefail

if [ -z "${ANDROID_HOME:-}" ]; then
    echo "ERROR: ANDROID_HOME is not set." >&2
    echo "Install the Android SDK and export ANDROID_HOME=/path/to/sdk" >&2
    exit 1
fi

cd "$(dirname "$0")"
echo "Using ANDROID_HOME=$ANDROID_HOME"
gradle --no-daemon assembleDebug

APK=app/build/outputs/apk/debug/app-debug.apk
if [ -f "$APK" ]; then
    echo
    echo "Build OK: $APK ($(du -h "$APK" | cut -f1))"
else
    echo "Build did not produce $APK" >&2
    exit 1
fi
