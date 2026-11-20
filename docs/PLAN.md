# Relens: architecture and milestones (draft for Tim's approval)

Target phone: Nothing Phone (3a) Pro, Snapdragon 7s Gen 3 (SM7635), Adreno 810 GPU,
Hexagon NPU, 12 GB RAM. Apps see the main camera (RAW 4096 x 3072) and the front camera only.
Measured numbers are in `docs/BENCH.md`.

## Shape of the app

```
Camera2 RAW (DNG) / JPEG / shared photo (with Dynamic Depth if present)
      │
      ├─► develop RAW to scene-linear (GPU demosaic, white balance, colour matrix)
      │
      ├─► depth: Depth Anything V2 Small at 518 px long side (NPU/GPU)
      │      └─ or the phone's own depth map (DEPTH_JPEG / portrait XMP) when present
      │
      ├─► edge refinement: joint bilateral upsample to full res (+ guided filter / matting at hair)
      │
      ├─► 2.5D layers: near / far split at occlusion edges, background inpaint
      │
      ├─► lens engine (GLES 3.1 compute; all passes share one linear RGBA16F frame)
      │      circle of confusion from disparity + focus + aperture + tilt
      │      tile reach, mip chain, aperture-shaped gather (blades, cat's eye, soap bubble,
      │      mirror ring), field curvature swirl, vignette, lateral + axial colour
      │
      ├─► lights: detect clipped point sources (+ the sun) → flare rows, ghosts, glare, leaks
      │
      ├─► film: crosstalk, curve, push/pull, halation, grain (film_look), IR foliage guess
      │
      └─► preview (screen size, re-rendered per slider move) / export (full res, tiled)
```

Two resolutions throughout: the preview works on a screen-sized copy (depth
and layers are computed once per photo, so a slider move re-runs only the
lens + film passes); the export reruns the same shaders at full resolution,
in tiles if the frame does not fit in GPU memory.

## Design principle: the camera body is the UI

The app should feel like a vintage camera, not a photo editor (proposal; Tim decides):

- **Dials, rings and levers, not sliders.** An aperture ring with click stops
  (`Lens.apertureStops`); a focus ring with a distance scale and depth-of-field
  marks that open and close with the aperture; a tilt/shift knob on lenses that
  move (`Lens.movement`); a shutter button with a proper press. Every detent
  gives a haptic tick (Android `VibrationEffect` primitives: `PRIMITIVE_TICK` /
  `CLICK`, the analogue of `Haptics.swift`) and a mechanical sound.
- **The lens bag.** Lenses picked from Vintage Click's shelves (Field Assignment,
  Screw-Mount Classics, Bokeh Monsters, Macro, Exotic, Toys & Mods,
  Rangefinder) and "mounted", as in `MenuOverlay.swift`.
- **Film is loaded as a roll**: stock and exposure count, a frame counter that
  advances per shot; push/pull chosen at "development", as in the game.
- **The viewfinder** borrows from `ViewfinderHUD.swift`: the finder readout,
  focusing aids, the meter. Look and feel from `splat_camera/docs/ui-reference`
  and `docs/screenshots`.
- Fictional maker names throughout, for lenses, film and the body itself.

Real-time limits shape this: the rings drive the screen-res preview (lens +
film passes only, measured below), so they must stay inside a frame budget or
degrade gracefully (fewer gather samples while a ring turns, full quality when it stops).

## Decisions (measured, docs/BENCH.md)

- **Depth runtime: ONNX Runtime Mobile, default CPU execution provider**,
  Depth Anything V2 Small fp32 at 518 px: 0.8–1.1 s, once per photo. LiteRT's
  GPU delegate was 3x slower with a 15–22 s first load; NNAPI and XNNPACK
  slower too. The NPU (QNN) didn't engage yet: a later optimisation for live
  viewfinder depth, not needed for editing or export.
- **GPU API: OpenGL ES 3.1 compute from Kotlin.** 12.5 MP lens + film in 0.8 s
  (96 samples), preview in 70 ms (48 samples). No NDK, no Vulkan needed.
  Rules learnt: mips via `glGenerateMipmap`; long passes in strips.
- **Budget:** a full-resolution export is ~2 s against a 1–2 minute target,
  so quality work (edge matting, inpainting, flare, more samples) has room.
- **Preview while a ring turns:** 48 samples at screen size (~70 ms, ~14 fps);
  96–160 when it stops. Halving the preview again makes turning fully live.
- **Film/lens maths**: ported literally from Vintage Click (`FilmStock.swift`,
  `Lens.swift`, `CameraBody.swift`, `Shaders.swift`) so looks match the game.

## Findings so far

- **Depth quality is good.** Depth Anything V2 Small on a macro render
  separates legs, antennae and the proboscis cleanly; the joint bilateral
  upsample carries the edges to full resolution (`scripts/reference_relens.py`,
  the NumPy twin of the shaders).
- **JPEG input is tone-mapped twice.** The film curve on top of the phone's own
  tone curve flattens contrast and saturation. RAW (milestone 1) is the real
  fix; for JPEGs and shared photos add an approximate inverse of the phone's
  tone curve before `linearize`, and keep the highlight expansion small
  (big clipped areas glow; only small hot points should become bokeh balls,
  which light detection in milestone 4 can pick out properly).
- **Occlusion edges show.** On the living-room photo the out-of-focus chair
  leaves faint streaks and doubled edges over the background: the
  near/far layer split + inpainting in milestone 3 is needed.
- **Scene scale must be the user's.** The network's depth is relative; a
  fixed near/far guess blurs a macro subject far too much. The near/far
  calibration (milestone 2) is required, not optional; good defaults can come
  from the camera's focus distance (`LENS_FOCUS_DISTANCE` in the capture
  result) and the subject's size.

## Milestones

1. **Capture**: Camera2 RAW_SENSOR to DNG (and JPEG fallback), the 3 rear
   cameras, focal length + sensor size from characteristics (for the
   CoC maths and "as if shot on a 35 mm body"); import from the photo picker;
   read Dynamic Depth / portrait depth when present. GPU RAW develop.
2. **Depth + edges**: the network, joint bilateral upsample, guided-filter
   refinement at hair, a user "near/far" calibration (two taps or a slider,
   since the network's depth is only relative), focus by tap.
3. **Lens engine**: port the whole lens catalogue (incl. the whacked
   freelensing lenses and the pinhole), tilt/shift (`applyMovement`), cat's
   eye, swirl, vignette, colour fringing; occlusion handling (near/far layers
   + inpainting behind the foreground).
4. **Lights, flare and leaks**: point-source detection on the linear frame,
   port `lens_flare` / `light_leak` and the flare rows from `Flare.swift`.
5. **Film stocks**: all 17 + push/pull, halation, grain; infrared foliage
   guess (the colour rule from `film_look`, later a segmentation model).
6. **Camera-body UI** (Jetpack Compose, custom-drawn dials): aperture and focus
   rings with detents, DoF scale, tilt/shift knob, shutter button, lens bag
   shelves, roll loading and frame counter, development (push/pull) step;
   haptics and mechanical sounds; live preview. Starts as a thin shell in
   milestone 1 so every later feature lands on a ring, not a slider.
7. **Export**: full-res tiled render, JPEG/HEIF + 16-bit TIFF/DNG-style out,
   EXIF with the fictional lens and stock, share sheet.
