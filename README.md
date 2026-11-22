# Vintage Snap

An Android camera that shoots real photos through the simulated lenses and
film stocks of [Vintage Click](https://github.com/timfennell/vintage-click).
You shoot with the phone; Vintage Snap measures the scene's depth and
re-renders the picture through a vintage lens: aperture-shaped bokeh, cat's
eyes, tilt and shift, flare and light leaks. It then develops it on a film
stock.

The camera body is the interface:
- focus, aperture and shutter rings, a light meter with a swinging needle;
- a seven-segment readout, a frame counter, a camera bag of lenses and filters, rolls of film;
- a light table to look at your contact strips through a loupe.

GPL-3.0, like Vintage Click.

## Features

- **54 lenses in 7 collections**, each with its own bokeh shape, rendering,
  vignetting and flare character. Every lens frames its true field of view on
  a 3:2 frame: the app picks the phone camera that suits it and crops to it.
- **Tilt, shift and freelensing ("whacked") lenses**: rock the plane of focus
  live in the finder.
- **17 film stocks** with push and pull development, halation and grain;
  **26 filters** (colour, ND, black-and-white contrast, star, diffraction,
  soft and fog).
- **RAW capture and development** on the GPU (Camera2 RAW to a linear picture).
- **Depth**: Depth Anything V2 Small for relative depth, scaled by Metric3D and
  the camera's own focus, with a rangefinder sweep while you hold steady.
- **Parallax capture** (ARCore phones): after the shot, a ghost camera in the
  finder guides you a few centimetres left and right, and the frame fires by
  itself when you line up. ARCore's depth and a GPU stereo match make the
  depth measured rather than guessed. The side views also show what was
  behind blurred foreground edges.
- **Lens flare from real lights**: lights are found on the full sensor and on
  an ultra-wide frame taken just after the shot, so lights outside the
  picture still flare in.
- **A DNG of every print**: the print itself in high dynamic range (the
  film's colour, contrast, grain and halation; 16-bit float, highlights kept
  above white), beside the JPEG, for your own raw editor. Optionally the
  camera's untouched RAW too.
- **Nothing kept in the app**: once a print is in `Pictures/Vintage Snap`,
  the frame's negative and working files are deleted.
- **Light table**: the `Pictures/Vintage Snap` folder as contact strips, laid
  out by day. Delete a print from the folder and it leaves the table. Pinch
  in to look through a loupe, mark keepers in china marker, and share a print
  or its DNG to other apps.

## Install

Download `vintage-snap-<version>.apk` from the
[releases](https://github.com/timfennell/vintage-snap/releases) and install it.
You may need to allow installs from your browser or file manager.

- **Phones:** Android 10 or later, with a 64-bit ARM processor (arm64-v8a).
  Parallax capture needs Google Play Services for AR.
- **Depth models:** the app downloads them on first use, about 250 MB, once.
  Tap *Download* on the camera screen. They come from this repository's
  [models-v1 release](https://github.com/timfennell/vintage-snap/releases/tag/models-v1)
  and are checked against their SHA-256.
- **Developed on:** a Nothing Phone (3a) Pro. Camera behaviour varies a lot
  between phones, so reports from others are welcome.

## Build

macOS (Linux works the same):

- JDK 17: `brew install openjdk@17`, with
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- The Android SDK command-line tools in `~/Library/Android/sdk` (platform 37.2,
  build-tools 36), and `adb` (`brew install --cask android-platform-tools`).

```bash
./gradlew assembleRelease            # app/build/outputs/apk/release/app-release.apk
scripts/install.sh                   # build, install on a USB-connected phone, push local models
```

Release builds are signed with a keystore kept outside the repository. Pass
`-PvsSigning=/path/to/keystore.properties`; it holds `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`. Without one, release builds
use the debug key.

The code lives in the `app.relens` package (the project's working name). The
installed app id is `app.vintagesnap`.

`app/src/main/java/app/relens/spike/` is the original performance bench
(`scripts/run_bench.sh`), kept for timing depth models and the GPU lens on new
phones. `docs/PLAN.md` holds the design notes.

## Credits and licences

- Lenses, film stocks, filters, sounds, the lens and film shaders and the
  icon come from [Vintage Click](https://github.com/timfennell/vintage-click)
  (GPL-3.0). Lens and film makers' names are fictional.
- [Depth Anything V2 Small](https://github.com/DepthAnything/Depth-Anything-V2)
  (Apache-2.0). The larger Depth Anything V2 models are CC-BY-NC and are not used.
- [Metric3D v2](https://github.com/YvanYin/Metric3D) ViT-S (BSD-2-Clause).
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT).
- [ARCore](https://developers.google.com/ar) (Google APIs Terms of Service).
- [Jost](https://github.com/indestructible-type/Jost) by indestructible type*
  (SIL Open Font License, `licenses/Jost-OFL.txt`), in place of Futura.
