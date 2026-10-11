# SM Nuvola — NyanimeB

## Current design: one video, lightweight UI cloud

The owner approved the same video on both sides of the cloud after the private
0.48.2.6 experiment proved too subtle and caused stutter during moving playback.
The organic outline is retained, with a more visible cyan/violet soft edge.
The cloud is now a decorative Compose Canvas below the player controls. It does
not represent an original/enhanced comparison or sample the video surface.

The ordinary SM action runs once. One decoder and the unchanged video pipeline
retain their preset, custom shaders, scaling, HDR and common color management.
The cloud never writes MPV options, adds video shaders, creates frame copies,
pauses, seeks, or asks the video renderer to redraw. This also removes the need
for custom JNI or different handling of gpu/gpu-next and user scalers.

## Drawing and lifecycle

The 1,150 ms contour starts at the actual button's window position. The harmonic
shape is sampled at 192 points, with outward normals computed once. A feathered
triangle mesh replaces the former seven overlapping strokes. Vertex positions
reuse a single buffer; colors and indices stay fixed for the entire effect.
The continuous transparent-to-bright band preserves the cyan/violet glow without
per-frame path reconstruction, stroke tessellation or a fullscreen blur.
An independent graphics display list isolates the cloud's draw invalidation from
the player controls. Its alpha is applied while drawing, avoiding a full-window
offscreen layer. Animation state is read in the draw phase, so it does not
recompose the complete player for every frame.

Android 10+ uses one hardware `drawVertices` operation. Android 8–9 uses the same
mesh via `drawBitmapMesh` and a single immutable white texel: this is not a video
bitmap, texture readback or a bitmap allocation per frame. The older path follows
the [documented hardware support levels](https://developer.android.com/develop/ui/views/graphics/hardware-accel).
The band feathers its edges rather than depending on unsupported triangle
antialiasing. A decorative drawing failure ends the cloud, without changing SM.

The normal pipeline's successful Smart selection starts the effect. Optional
content analysis keeps its own original-image sampling and bounded preparation;
an unavailable preset times out without preventing later normal enhancement.
Deactivation fades an existing decorative cloud over 150 ms. The video itself
uses the ordinary SM toggle, with no second video retained for a crossfade.

Latest interaction wins. Completion tokens ignore stale callbacks; rotation,
surface removal, background, PiP, sheets and reduced motion clear the effect.
There is no animation for automatically applied episode profiles. Both the
player preference and disabled system animations keep ordinary SM without the
cloud. Compose respects system animation duration; a bounded owner job covers
an absent chrome.

Transient samples are separated from Smart calibration and its measurement
window resets at both boundaries. Real output losses can end decoration without
reducing the chosen preset; decoder failures remain distinct. The ordinary
decoder/Smart recovery protections continue to operate.

## Native sources and retired experiment

The selected player is again the verified baseline `nyanime-mpv:1.0.0` and
`nyanime-ffmpeg:1.0.0`. No custom compositor or JNI bridge is included. Its
unchanged native bytes correspond to the existing source materials in
`tools/native/distribution.json`; the separate NyanimeB runtime overlay remains
applicable to the other selected AndroidX/Conscrypt libraries.

The private 0.48.2.6 APK and its matching app/native archives remain historical
test evidence. Its MPV1.0.1 experiment is removed from the selected dependency,
source recipe and current repository artifacts. Retired generated helper files
and shader-list entries are cleaned only from the app's owned shader directory;
same-name external user shaders are preserved.

## Verification

Unit coverage checks one ordinary toggle, actual-preset readiness, timeout,
reduced motion, cancellation, stale completion, finite geometry, complete contour
coverage, cache cleanup and preservation of the selected quality. The physical
acceptance gate remains a moving-video comparison, visibility, pause, rapid taps,
rotation/PiP and no persistent performance change, tied to the exact signed APK.
The earlier GLSL endpoint checks are evidence for the retired experiment only.

The 16 October private candidate passed the common 120-test Anime4K/automatic
startup suite and formatting checks. A subsequent 23-test focused run passed
after adding the boundary-counter regression: losses accumulated before the
cloud starts cannot immediately cancel it. The original native packaging and
license checks also passed 27 Python tests; merged offline attribution contains
52 bundled entries, 357 dependencies and 15 license texts. These checks establish
code and packaging behavior, not moving-video performance on Android.

### Private device checkpoint — 0.48.2.7

APK SHA-256: `e1d1ad79d48884ddd93bea1ea498c01fac9659db66ec91078a5bf89f3c6dbd33`.
The signed arm64 APK was installed over 0.48.2.6 on a physical SM-F741B, retaining
data. Its 37 native libraries match the declared baseline/runtime overlay; the
experimental bridge and helper shaders are absent. The actual APK passed the
offline inventory check for all 52 bundled attribution entries.

With the `gpu` renderer, the cyan/violet contour was visible during both paused
and moving playback, and disappeared after completion. Rapid inputs, entry into
PiP, return, and landscape/fullscreen to portrait/embedded layout during the
effect left no residual contour. The applied C+A HQ selection survived the
layout transition. The process remained alive and its filtered crash log was
empty. Two screen recordings establish visual behavior only; their encoder
overhead is excluded from performance claims. Subsequent moving snapshots held
the output-loss counter at 3 with zero decoder losses; this is a bounded smoke
check, not proof of zero drops or equivalent performance for every scene.

Long thermal validation of the shared Smart controller is coordinated separately
on the same APK. Physical `gpu-next`, HDR, system animation-scale and reduced-motion
coverage remain outstanding; unit coverage does not substitute for those checks.

This is private NyanimeB work. No public release, push or OTA is authorized.

### Animation-specific regression reported after 0.48.2.7

The owner's moving-video report remains valid despite the separate continuous
playback benchmark passing. The latter did not measure the cloud's UI cadence.
The second controlled HWUI capture, without screen recording, measured 32 late
frames out of 123 (26.02%) during moving activation, with a 54.60 ms p95 completion
latency. Its paused counterpart measured 21 out of 125 (16.80%). Canvas recording
itself took a median 0.16 ms; the longer delay was after command recording, in
render submission/queue/completion. The seven-pass contour was therefore replaced
by the mesh described above. These are pre-change measurements, not acceptance
results for the new candidate. The first pair was excluded after the owner
reported accidental screen touches.

SM shaders, profile rules, controller, decoder, quality settings, native artifacts
and calibration behavior are unchanged by this drawing optimization. The updated
candidate must pass the same no-recording HWUI comparison and separate visual
checks before improved animation fluidity is claimed.

The mesh candidate passed all 124 focused Anime4K/automatic-startup tests and
scoped formatting checks, including buffer reuse, closed color/geometry seams,
finite coordinates, unchanged centerline, transparent feather and bounded indices.
The controller/pipeline/native/Lua source diff remains empty. The final APK's
native and shader bytes are checked separately against 0.48.2.7 before installation.

### Private device checkpoint — 0.48.2.8

APK SHA-256: `bad902a3abe318bd25ae9750ba30e66f5604b60b578437532f52c05cb56f0323`.
The signed arm64 APK was built from `7edbf327e09d9e84b3350f4eadf2459030c131ad`.
Comparison of the actual 0.48.2.7 and 0.48.2.8 APKs confirms byte-identical
contents for all 37 native libraries and all 17 SM shader/telemetry assets.
The offline attribution, package identity and native inventory checks passed.
The corresponding app source archive retains that exact code revision.

The physical SM-F741B comparison used the same SDR video, `gpu` renderer,
C+A HQ selection and 120 Hz display. No screen recording or screenshots ran
during timing captures. Paused activation measured 2 late frames out of 142
(1.41%), compared with 21 out of 125 (16.80%) before the change. Moving activation
measured 10 out of 144 (6.94%); two subsequent steady-playback captures measured
10 out of 132 (7.58%) and 10 out of 134 (7.46%). The corresponding per-window
summaries reach 8.27%. Moving-frame p95 completion latency fell from 54.60 ms
to 34.29–36.42 ms. These are bounded samples, not a zero-jank guarantee: some
late UI frames remain during moving playback. Long idle intervals after cloud
completion are excluded from interpretations of animation cadence.

A separate visual capture confirms the feathered cyan/violet contour. Four
rapid toggles retained the final C+A HQ selection. Leaving fullscreen during
activation retained that selection in the embedded portrait player, with no
residual cloud. A settled PiP capture showed the full video, without half-black
output or leftover decoration. The same process remained alive and its filtered
crash buffer was empty. The USB device was left paused with its original SM Off
and 1x preferences restored.

The owner identified the SM-S948B as the affected phone but temporarily excluded
it from testing. Version 0.48.2.8 was installed there over 0.48.2.7 and its package
version read back, without launching or operating the app. Animation performance
on that device remains unverified. Physical `gpu-next`, HDR, older Android mesh
fallback and reduced-motion coverage also remain outstanding. No public release,
push or OTA was performed.
