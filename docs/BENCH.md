# Spike results: Nothing Phone (3a) Pro, 2026-11-19

Phone: Nothing A059P, Qualcomm SM7635 (Snapdragon 7s Gen 3), **Adreno 810**
(OpenGL ES 3.2), 12 GB RAM, Android 16. Release build, phone on USB.
Raw logs and pictures: `/Volumes/Mike 1/relens_bench/<timestamp>/`.

## Cameras (Camera2, as a third-party app sees them)

| id | facing | focal | 35 mm equiv | aperture | sensor | RAW | depth out |
|---|---|---|---|---|---|---|---|
| 0 | back (main) | 5.56 mm | ~23 mm | f/1.88 | 8.19 x 6.14 mm | **4096 x 3072** (12.5 MP binned; no max-res RAW) | none |
| 1 | front | 3.61 mm | ~24 mm | f/2.20 | 5.22 x 3.93 mm | 4080 x 3072 | none |

The 3x periscope and the ultra-wide are not exposed to apps. No `DEPTH_JPEG`
or `DEPTH16`. The stock camera does save DNGs (25 MB, beside the JPEG).
So **full resolution = 12.5 MP**, and depth comes from the network.

## Depth: Depth Anything V2 Small

Median of 5 runs after a warm-up, 518 px long side.

| runtime / backend | model | infer | notes |
|---|---|---|---|
| **ONNX Runtime, CPU EP (4 threads)** | fp32, 392x518 | **0.80–1.1 s** | fastest, simplest; load 0.5 s |
| ONNX Runtime, NNAPI | fp16 | 1.17 s | |
| ONNX Runtime, NNAPI | fp32 | 10.1 s | NNAPI partitions badly |
| ONNX Runtime, XNNPACK EP | fp16 / fp32 | 1.8 / 2.1 s | slower than the default CPU EP |
| ONNX Runtime, QNN EP (HTP) | fp32 | 0.81 s | QNN took none of the graph: CPU in effect |
| ONNX Runtime, QNN EP (HTP) | Qualcomm w8a16 | ~16 s | Q/DQ fell back to the CPU; NPU not engaged |
| LiteRT, GPU delegate | Qualcomm fp32, 518x518 | 3.3 s | plus a **15–22 s** first load (shader compile) |
| LiteRT, CPU (XNNPACK x4) | Qualcomm fp32, 518x518 | 3.6 s | |

Depth quality is good on a real photo (living room: chair, wire chairs, lamp).

## GPU lens + film (OpenGL ES 3.1 compute)

85 mm f/1.4 (the biggest blur). Totals are all passes, `glFinish` between.

| size | gather samples | lens + film | of which gather |
|---|---|---|---|
| preview 1080 x 1440 | 48 | **70 ms** | 47 ms |
| preview | 96 | 107 ms | 85 ms |
| full 3072 x 4096 (12.5 MP) | 48 | 475 ms | 339 ms |
| full | **96** | **806 ms** | 670 ms |
| full | 160 | 1.25 s | 1.11 s |
| 2x (50 MP) | 96 | 3.7 s | 2.7 s |

Other passes at 12.5 MP: depth upsample (JBU) 61 ms, film develop 28 ms,
linearize 11 ms, CoC 12 ms, mips 8 ms, tiles 17 ms.
Readback 100 ms, JPEG q95 encode 170 ms, JPEG decode 90 ms.

## Full-resolution export (12.5 MP photo)

decode 0.09 + depth 0.8–1.1 + lens & film 0.8 + readback & encode 0.27 ≈ **2 s**.
The target was 1–2 minutes: there is ~50x headroom for better depth edges,
inpainting, flare, more samples.

## Things the spike found

- **Compute mip generation broke the texture on Adreno.** Sampling a texture
  while writing another of its levels as an image is undefined in GL ES;
  on the Adreno 810 every later read returned zeros (no GL error). Use
  `glGenerateMipmap` (0.9 ms at preview).
- **Long dispatches lose the GL context.** A single 1.3 s gather dispatch
  (50 MP) tripped the GPU watchdog; everything after it silently did nothing.
  The gather now runs in ~1.5 MP strips with a flush between; 50 MP then works.
  Keep every dispatch well under ~0.5 s.
- **App-owned folders.** A folder that `adb` creates inside the app's external
  files dir can't be listed by the app; the app must create it.
- The NPU needs more work to engage (QNN context binaries / Qualcomm's
  pre-compiled QNN model, or LiteRT's Qualcomm NPU accelerator). Not needed
  for the export budget; worth it later for a live viewfinder depth.
