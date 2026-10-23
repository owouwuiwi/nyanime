# Manga reader renewal

The manga reader renewal is prepared for ordinary Nyanime 0.55.0.0.
The existing SSIV/PhotoView renderer, manga/page identities, progress handling,
guided-reading editor, spread viewer, page actions and reading rooms are retained.

## Reader controls

A discreet title/chapter/bookmark header and floating **Chapters / Mode / Quality /
More** dock replace the previous toolbars. Chapter selection uses the reader's
existing filtered and download-aware chapter sequence. The vertical navigator,
orientation, guided reading, crop, advanced settings, source web actions, sharing
and reading rooms remain accessible.

Only the chrome animates, using ModernMotion. The renderer does not move or get
recreated when a panel opens. The controls report their measured size so room
tools and video overlays reserve enough room, including with large fonts.
Reader sheets appear at the bottom on phones and at the side from 840 dp.

## Optional image enhancement

`:core:reader-quality` owns geometry, preferences, GPU/CPU execution and IPC.
The original remains the base layer, and processed regions are drawn in the
existing viewer's source coordinates. Toggling the treatment or comparison does
not call setImage or alter page/zoom state.

Comparison dismisses the panel so the whole page can be inspected. While showing
the original, the dock reads **Original**; tapping it restores the processed
regions. Comparison survives configuration changes, resets at reader exit, and
does not change the selected treatment or reading position.

- Off by default; per-manga choice overrides the global default.
- 192-pixel tile interiors with a 24-pixel overlapping halo for Anime4K and
  Real-ESRGAN; RealCUGAN uses 128-pixel interiors and a 64-pixel halo. Only the
  processed interiors enter the bitmap cache; halos are removed before display.
- Visible regions first, bounded prefetch and bitmap cache; no complete enhanced
  long-strip bitmap. Cache identity includes image SHA, region, sampling and pipeline.
- One worker executor owns EGL; viewport cancellation does not recompile the
  shaders. Superseded queued work is skipped and stale results are discarded.
- No image uploads or saved processed pages. The worker loads at use and unbinds
  on Off, page detachment or reader stopping. Neural models download only after
  an explicit action in the Quality panel; the renderer never downloads them.
- Geometry mismatches, decoding/GPU failures or allocation errors preserve the
  original. Transformed/animated inputs without a verified coordinate mapping
  are not enhanced.

The GPU needs a private process with the app UID on the tested device; Android
denies direct GPU access to isolated UIDs. This is **not** a script sandbox. The
worker is not exported, receives tiles only and skips ordinary app initialization.

Anime4K uses the existing MIT still-image shader assets. RealCUGAN and
Real-ESRGAN use a bounded original JNI adapter over pinned NCNN 20240410, CPU
only, NDK r28c and static C++ runtime. Model descriptions show download sizes
and licenses; downloads are streamed, hash-verified and published atomically.
Cancellation or corruption cannot expose a partial model. Model files reside in
the app's no-backup directory and can be removed from the same panel.

NCNN's Net-local tensor pools are disabled. A book visits many differently shaped
edge tiles, and retaining all their intermediate buffers consumed about 818 MiB
of native heap in the first host test. The model stays loaded, while inference
intermediates are released after each tile. This changes allocation lifetime,
not the model or pixel calculation.

The RealCUGAN no-SE model avoids global pooling between independent tiles.
The official Real-ESRGAN archive contains desktop programs, but the importer
extracts only the two declared, individually verified model entries. It never
extracts or executes the desktop programs. See `core/reader-quality/NOTICE.md`.

The source decoder closes the input it receives. `QualityImageInput` owns the
underlying source and lends a non-owning stream to the decoder, allowing the
remaining bytes to complete a single-pass fingerprint before the owner closes
the source. Decoder errors and cancellation still release the source and image.

Reviewed native bytes are restored with
`python3 -m scripts.restore_reader_quality_native` and verified with
`python3 scripts/verify_reader_quality_native.py`. All four ABIs must match their
source hashes, toolchain, ELF architecture and 16 KiB alignment. A Linux rebuild
is a separate maintenance operation, not a requirement for each app build.

## Evidence so far

- Twenty JVM tests pass: tile planning, sampled edges, bounded state, model
  integrity/cancellation, stable title identity and decoder stream ownership.
- Nine native mirror tests cover altered/duplicate/unsafe/oversized inputs,
  mismatched recipes and failure before replacing existing verified engines.
- Self-targeted USB worker probes verified channel order, orientation and halo
  stitching on generated 240×240 artwork. The final CPU models had zero seam
  mean error on that sample, with first calls of about 1.1 s for RealCUGAN and
  0.7 s for Real-ESRGAN. These are synthetic tests on one device, not a quality
  judgement or a performance guarantee for actual books or other devices.
- A separate test-only Activity over generated artwork exercises the real image
  renderer and reader chrome without library/progress writes. The first USB UI
  pass checked dark/light themes, chapter list, hide/show and font scaling to
  200%. It found the decoder input ownership problem described above. Its fix
  passes JVM regression tests. A subsequent ordinary app build also completed
  the real LocalSource → CBZ → ReaderActivity → worker path: all three engines
  reached READY on generated 600×960 / 600×2400 pages, paging/panning stayed on
  the same page, and Off removed the worker service binding. Incognito prevented
  personal progress writes; generated files and temporary choices were removed.
- A separate native probe with the final allocation change checked 16 differently
  sized tiles per engine. Retained native heap was about 34 MB for RealCUGAN and
  28 MB for Real-ESRGAN, growing by about 112 / 110 KB during that sequence.
  Colour/orientation checks still passed and mean seam error was zero on the
  generated sample. These measure retention after inference, not transient peak
  memory or image quality on every device.
- Full reader UI/gesture/memory/video/room regression, large-font/theme checks
  beyond the synthetic fixture, real-page visual comparisons, model download UI
  and the final optimized signed app artifact remain separate validation gates.

## Second delivery

EPUB/TXT, structural positions, annotations, dedicated storage, NovelSource,
Studio novel flows, Legado and versioned backup/cloud support are a separate
approved delivery, now in [foundation integration](novel-reader.md), and are **not released yet**. Existing image-only EPUB
reading remains unchanged. PDF, TTS, OCR and translation are excluded.
