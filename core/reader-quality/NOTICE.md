# Reader image quality

Nyanime's tile planner, preferences, private worker, IPC, reader overlay and EGL
executor are original integration code. The optional feature is off by default.

`Anime4kCompiler.kt` and `Anime4kSizeEvaluator.kt` are adapted from Kototoro,
revision `21aee6a94f9c9367434d4922c6247b66d881c3b1`, Apache-2.0:
https://github.com/Kototoro-app/Kototoro/tree/21aee6a94f9c9367434d4922c6247b66d881c3b1
Original paths: `app/src/main/kotlin/org/skepsun/kototoro/reader/translate/data/`.
Modifications: Nyanime package, fixed image pipeline, resource bounds and formatting.
The upstream license is preserved in `licenses/Kototoro-Apache-2.0.txt`.

The GPU worker uses a separate, non-exported Android process. It has the app UID,
not an isolated UID: Android denies access to /dev/kgsl-3d0 for the isolated domain
on the tested device. App.onCreate exits before initializing UI, sources or cloud
in this worker. This is crash/resource separation, not a security sandbox.

The image worker uses the existing Anime4K shader assets and their existing
MIT notices, not MPV or a copied video engine. Fixed pipeline:

| Asset in app/src/main/assets/anime4k | SHA-256 |
| --- | --- |
| Anime4K_Restore_CNN_S.glsl | 97c24dc370ab300c108bfaa09db7f175aeff343674842c299cf3940a3d330427 |
| Anime4K_Upscale_CNN_x2_S.glsl | 4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4 |

## Optional neural image backends

Original `reader_quality.cpp` provides a bounded CPU inference adapter. It links
NCNN `56775de50990ab7f16627efdcf5529b49541206f` (tag 20240410), BSD-3-Clause
and embedded third-party notices in `licenses/NCNN.txt`. Its bundled ruapu CPU
feature detector retains the nihui/kernelbin MIT notice in `licenses/Ruapu.txt`.
Vulkan, OpenMP, tools,
examples and benchmarks are excluded. NDK r28c, static C++ runtime, 16 KiB ELF
load alignment. The library is loaded only inside the private worker when used.
Reproduction: `tools/native/reader-quality/build.sh` and the pinned CMake input.
Ordinary builds restore the reviewed four-ABI mirror using
`python3 -m scripts.restore_reader_quality_native`; no native compilation or
model download is required to build the app. The mirror and each entry are
verified against the recipe, provenance, ABI and 16 KiB ELF alignment.

Models are downloaded only on explicit user request and are not bundled in the
APK. Download and extracted-file SHA-256 values are fixed in `QualityModels.kt`.
Only the two declared model entries are extracted from the official ESRGAN ZIP;
desktop executables are never extracted or executed. Models are removable and
excluded from backups; image inputs never leave the device.

- RealCUGAN: `models-nose/up2x-no-denoise` from
  `nihui/realcugan-ncnn-vulkan` revision
  `395302c5c70f1bff604c974e92e0a87e45c9f9ee`; MIT converter and original
  bilibili/ailab Real-CUGAN MIT license retained separately. This no-SE model
  avoids global pooling across independently scheduled image tiles. A 64-pixel
  halo and 18-pixel reflect padding preserve its convolution/crop geometry.
- Real-ESRGAN: `realesr-animevideov3-x2` from the official
  `xinntao/Real-ESRGAN` v0.2.5.0 asset
  `realesrgan-ncnn-vulkan-20220424-windows.zip`, SHA-256
  `abc02804e17982a3be33675e4d471e91ea374e65b70167abc09e31acb412802d`.
  Original weights BSD-3-Clause; NCNN conversion MIT. Both notices are retained.

No Kototoro native binary, model downloader or video engine is copied. Native
and device checks are recorded separately; a successful synthetic test does not
constitute complete reader performance or image quality validation.
