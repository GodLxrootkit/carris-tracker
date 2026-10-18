#!/usr/bin/env bash
# Build the debug APK. See BUILD.md for prerequisites.
set -e
cd "$(dirname "$0")"

if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ] && [ ! -f local.properties ]; then
  echo "Android SDK not found."
  echo "Set ANDROID_HOME=/path/to/Android/Sdk or create local.properties with sdk.dir=..."
  echo "Full instructions: BUILD.md"
  exit 1
fi

./gradlew assembleDebug testDebugUnitTest

echo
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
