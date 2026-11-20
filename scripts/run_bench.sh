#!/bin/bash
# Installs the spike, pushes models (and a test photo), runs the bench on the
# phone and pulls the log and pictures back.
#   scripts/run_bench.sh [photo.jpg] [flags]
# Models come from $RELENS_MODELS (default /Volumes/Mike 1/relens_models).
set -euo pipefail
cd "$(dirname "$0")/.."
PKG=app.relens
MODELS="${RELENS_MODELS:-/Volumes/Mike 1/relens_models}"
OUT="${RELENS_BENCH_OUT:-/Volumes/Mike 1/relens_bench}/$(date +%Y%m%d_%H%M%S)"
DEV=/sdcard/Android/data/$PKG/files
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

./gradlew -q assembleRelease ${QNN:+-Pqnn=true}
adb install -r app/build/outputs/apk/release/app-release.apk >/dev/null
adb shell am force-stop $PKG
# Let the app create its own files/models first: a folder made by adb isn't listable by the app.
if ! adb shell ls -ld $DEV/models 2>/dev/null | grep -q " u0_"; then
  adb shell rm -rf $DEV/models
  adb shell am start -W -n $PKG/.spike.BenchActivity --es flags setup >/dev/null
  sleep 2
  adb shell am force-stop $PKG
fi
adb shell rm -rf $DEV/out $DEV/bench.txt
# Static-shape ONNX (NNAPI/QNN need them) and the LiteRT model; push only what's missing.
have=$(adb shell ls $DEV/models 2>/dev/null || true)
for f in "$MODELS"/da2s_fp32_392x518.onnx "$MODELS"/da2s_fp16_392x518.onnx \
         "$MODELS"/qai_tflite/depth_anything_v2-tflite-float/depth_anything_v2.tflite \
         ${QNN:+"$MODELS"/da2s_qai_w8a16_518x518.onnx} ${EXTRA_MODELS:-}; do
  n=$(basename "$f")
  if ! grep -qx "$n" <<<"$have"; then echo "push $n"; adb push "$f" $DEV/models/ >/dev/null; fi
done
if [ -n "${1:-}" ]; then adb push "$1" $DEV/input.jpg >/dev/null; else adb shell rm -f $DEV/input.jpg; fi
adb shell am start -n $PKG/.spike.BenchActivity --es flags "${2:-none}" >/dev/null
echo "running (log on the phone's screen)..."
until adb shell ls $DEV/bench.txt >/dev/null 2>&1; do sleep 3; done
mkdir -p "$OUT"
adb pull $DEV/bench.txt "$OUT/" >/dev/null
cat "$OUT/bench.txt"
adb pull $DEV/out "$OUT/" >/dev/null
echo "results in $OUT"
