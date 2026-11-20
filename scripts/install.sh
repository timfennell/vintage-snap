#!/bin/bash
# Builds and installs Relens on the connected phone and gives it the depth model.
#   scripts/install.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PKG=app.relens
MODELS="${RELENS_MODELS:-/Volumes/Mike 1/relens_models}"
DEV=/sdcard/Android/data/$PKG/files
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

./gradlew -q assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk >/dev/null
# The app creates files/models itself on launch (an adb-made folder isn't listable by the app).
if ! adb shell ls -ld $DEV/models 2>/dev/null | grep -q " u0_"; then
  adb shell rm -rf $DEV/models
  adb shell am start -W -n $PKG/.ui.MainActivity >/dev/null
  sleep 2
fi
# The 1036 px depth model (static shape: scripts/fix_shapes.py da2s_fp32.onnx 770 1036).
if ! adb shell ls $DEV/models/da2s_fp32_770x1036.onnx >/dev/null 2>&1; then
  echo "pushing the depth model (99 MB)"
  adb push "$MODELS/da2s_fp32_770x1036.onnx" $DEV/models/ >/dev/null
fi
adb shell am start -n $PKG/.ui.MainActivity >/dev/null
echo "Relens installed and started"
