# Relens (working name)

An Android app that relenses real phone photos with the simulated lenses and
film stocks of [Vintage Click](https://github.com/timfennell/vintage-click):
capture (RAW where possible), estimate depth on-device, lift the picture to
2.5D, then re-render it through a vintage lens (aperture-shaped bokeh,
tilt/shift, cat's eyes, flare, leaks) and develop it on a film stock.

GPL-3.0, like Vintage Click.

## Status: performance spike

`app/` is a benchmark, not the app. It times, on the phone:

1. Depth Anything V2 Small (Apache-2.0) through ONNX Runtime (CPU, XNNPACK,
   NNAPI, optionally QNN) and LiteRT (GPU delegate, CPU).
2. The lens: a depth-dependent gather blur as OpenGL ES 3.1 compute shaders
   (`app/src/main/assets/shaders/`), at preview, 12 MP and 50 MP.
3. One film develop pass, ported from Vintage Click's `FilmStock.swift` /
   `Shaders.swift` (`film_look`).

```bash
scripts/run_bench.sh                 # synthetic scene
scripts/run_bench.sh photo.jpg       # a real photo
QNN=1 scripts/run_bench.sh photo.jpg # ONNX Runtime's QNN (Hexagon NPU) build
```

Models live outside the repo (`/Volumes/Mike 1/relens_models`); see
`scripts/fix_shapes.py` for the static-shape ONNX copies NNAPI/QNN need.
Do not ship Depth Anything V2 Base/Large: they are CC-BY-NC.

## Build setup (macOS)

- JDK 17: `brew install openjdk@17`; `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- Android SDK command-line tools in `~/Library/Android/sdk` (platform 36, build-tools 36)
- adb: `brew install --cask android-platform-tools`
