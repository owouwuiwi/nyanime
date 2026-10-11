# Anime4K Smart quality governor — NyanimeB

This private experiment belongs only to the NyanimeB MPV path. It does not change
the embedded Media3 engine, Android TV, source contracts or background ULTRA.
The bundled Anime4K 4.0.1 shaders and their weights remain unchanged. Perceived
detail takes precedence over avoiding the already accepted sharpening artifacts.

## Quality and workload

Smart starts at the highest compatible preset, or a compatible non-Off level
measured successfully within the last six hours. The latter is a starting hint,
not a permanent ceiling. Cache keys include renderer/API/driver/decoder, source
and output geometry, FPS, refresh rate, treatment family and calibration revision.
Calibration preferences are private and excluded by BackupPreferencePolicy:
measurements on one phone do not become limits on another phone.

Two consecutive GPU overruns accompanied by actual output losses trigger the
fast guard after warmup. Intentional frame discard above the display cadence is
subtracted. An isolated spike cannot activate this guard. Existing five-second
windows provide the slower health and calibration assessment. Missing timings
remain unknown; decoder drops and mistiming alone do not prove GPU overload.
Pauses, buffering, seeks, counter resets and timing discontinuities discard the
measurement window. Recent render-pass cost complements the moving average;
neither video cadence nor a missing cost is treated as measured GPU capacity.

GPU timestamps do not include the entire scheduling, queueing and presentation
path. A second guard therefore recognizes repeated output backpressure when the
source cadence is known, measured rendering consumes at least half its frame
interval and the measured decoder loses no frames. Two consecutive samples with
at least two losses and a 3% presentation loss rate trigger the fast response;
smaller losses need two overloaded five-second windows. Cheap rendering, missing
decoder counters, intentional above-display discards and isolated bursts cannot
activate this additional guard. The thresholds describe a workload's budget,
not a fixed ceiling derived from the validation phone.

After at least 30 seconds of lossless, measured stability with capacity >=1.35
times the target cadence, Smart may try the next higher level. There are at most
two attempts per episode, at least two minutes apart. The episode-owned budget
survives quality changes and Smart governor recreation. A trial must produce a
lossless measured window with capacity >=1.05 times target; it cannot remain
unconfirmed beyond 15 seconds of sampled playback. Failure restores the previous
level and excludes that candidate for the same workload. Shader compilation
failures have separate session exclusions. Manual Off, Maximum and Custom keep
precedence. No periodically disabled filters or continuous image scanning occur.

## Optional startup image analysis

Image analysis is **experimental and off by default**. Synthetic feature checks
are not proof of better perceived image quality. Enabling it by default requires
paired perceptual evaluation on held-out clips and physical-device measurements;
PSNR/SSIM alone are not acceptance criteria.

When enabled, one session-owned coroutine requests at most three original,
bounded surface samples (longer side <=512 pixels). Playback starts immediately;
the batch has a one-second deadline before normal resolution-based selection
resumes. Feature extraction runs off the main thread. Samples are transient,
never written to storage or sent over the network. Secure windows are excluded;
PixelCopy failures and lack of informative content fall back safely. Duplicate
frames do not manufacture agreement. Black/white/flat frames and outer/subtitle
regions are excluded. Two agreeing informative samples are required, otherwise
the current resolution heuristic remains in charge. HQ and compatible secondary
passes remain available with any accepted A/B/C family.

Changing episode or selection cancels the batch. Generation and session checks
reject late results. Temporary bitmaps are recycled after success, cancellation
or failure. The experimental preference applies on the next Smart startup;
changing it does not turn filters off during playback.

## UI and diagnostics

The existing player settings panel shows preparation, current preset, quality
recovery and image-analysis fallback. New controls and messages have Italian and
English resources. The existing opt-in Smart diagnostics overlay also enables
one numeric log snapshot every five seconds. It includes timing and counters,
never titles, sources, paths, accounts or sampled images. Diagnostics are off by
default. Runtime data is evidence for this device and workload only.

The Lua adapter publishes its protocol as a native map in MPV `user-data`.
String observers receive that map as a JSON object. Publishing already serialized
JSON instead stores a string node: MPV JSON-escapes it again on observation and
the object decoder correctly rejects the quoted payload. That transport defect
was found on the physical phone and corrected before acceptance. The fallback
JNI counters remain useful when the adapter is unavailable, but cannot invent GPU
capacity or enable quality recovery without measured timings.

## Validation record

The common 0.48.2.7 code passed 120 focused JVM tests in eleven suites, including
all 21 quality-recovery checks and two startup-default checks. A later 23-test
cloud/pipeline run covered the final independent UI cleanup; these overlap with
the common run and are not 143 distinct tests. All 16 Lua checks (Lua 5.2 and
LuaJIT), 16 release-note/version/encoding checks and scoped Kotlin formatting also
passed. Android Kotlin/Java compilation and the physical acceptance run below
passed. Tests cover existing
startup/shader/geometry/telemetry behavior,
fast overloads, real versus intentional drops, recovery limits, probe timeouts,
pause/discontinuity handling, calibration expiry, treatment compatibility,
bounded captures and external cancellation. Synthetic images verify feature
response to degradation, not a claim of improved perceptual preset selection.

The optimized signed **0.48.2.7 (263)** APK built successfully. Its SHA-256 is
`e1d1ad79d48884ddd93bea1ea498c01fac9659db66ec91078a5bf89f3c6dbd33`.
An independent artifact check confirmed the production package/certificate,
all 37 native libraries against the pinned baseline/runtime, retained JNI classes,
Telegram/DeepPanel/ViewModel ABI checks, offline attribution and byte-identical
Lua/changelog assets. The native player is the verified stock 1.0.0 runtime;
the concurrent UI effect uses a single video and a separate Canvas overlay.
The installed APK hash was read back from Android and matched this artifact.

The failed reference arm64 APK was **0.48.2.5 (261)**, package
`it.owouwuiwi.nyanime`, SHA-256
`d8eeacbadfb904a196137cf2d9209ba638df4b92261110c52bd76529257dd119`.
Its certificate SHA-256 is
`7DD368792C4D94D1186CC4B0E2AD6C2F34FCE5DD37F19C6A1BAB45106FFC136B`.
All 37 native libraries match the previous APK and pinned private runtime;
required JNI classes, offline attribution and the packaged changelog were checked.
This change does not replace the native player binaries or shader weights.
The exact packaged Lua bytes were checked against the corrected source. Concurrent
optional reveal hooks are present in this APK, but its unchanged native runtime
does not expose that capability, so the reveal is skipped before composition or
measurement suppression. The later reveal build is a separate validation target.

The APK was installed in place on the authorized USB phone after retaining a
verified complete backup and the exact previous APK. The installed base APK hash
matches the checked artifact. No application data was cleared.
**This APK failed the prolonged rendering-loss target below and is retained for
reproduction, not accepted as the final Smart build.**

Physical acceptance target: three comparable >=20-minute runs (Off, previous
Smart, new Smart), on the same USB phone and video workload, separating startup
settling, network buffering, decoder losses and output losses. Extra output
losses attributable to rendering should be below 0.1% outside settling, with no
recurring interruptions. One successful phone is not a universal performance
guarantee; presets are selected from each live workload's evidence.

### Physical comparison, 16 October 2026

The workload is one 23:06 video: 640x480, 23.976 fps, hardware H.264 decoding,
1440x1080 letterboxed output on a 2640x1080 landscape surface, playback speed 1x.
The three runs use the same USB Samsung SM-F741B. Native MPV statistics page 1
stays enabled in every run; optional Smart diagnostics and image analysis stay off.
Screenshots, monotonic elapsed times, process IDs and current thermal HAL readings
are retained locally. Thermal status and cached readings are distinguished from
current sensor values. This is a sequential comparison, not a randomized device
benchmark or evidence for another phone's limits.

| Run | Local time (Europe/Rome) | Decoder losses | Output losses | Preset |
| --- | --- | ---: | ---: | --- |
| Off | 17:51:29–18:11:29 | 0 | 0 | Off |
| Previous Smart, 0.48.2.4 | 18:13:18–18:33:18 | 0 | 17 | C+A HQ initially, C+A at completion |
| Native-map Smart, 0.48.2.5, failed acceptance | 19:58:57–20:18:57 | 0 | 572 | C+A HQ at start and completion |
| Backpressure guard, 0.48.2.7 | 21:27:15–21:47:15 | 0 | 20 total, 19 during window | C+A HQ initially, C+A at completion |

The previous controller remained on the faster C+A preset at the end of its
20-minute run. An initial new-Smart run before the transport correction recorded
13 output losses but still had no native GPU telemetry; it exercised fallback
only and is excluded from the final governor acceptance. With the corrected
transport, the real phone reported C+A HQ, Healthy, 25.64 ms/frame, headroom 1.55
and 90% telemetry confidence. In the subsequent 20-minute run, output losses rose
from 16 at minute 10 to 197 at minute 13, 343 at minute 15 and 572 at completion.
The decoder counter stayed zero. Native GPU fresh-pass averages remained around
25–31 ms and the phone reached thermal status 2 (moderate). Nominal GPU headroom
did not represent the full scheduling/queueing/presentation budget: the controller
kept HQ despite sustained real output losses. This run failed the <0.1% target.
A rendering-backpressure guard was required; the later 0.48.2.7 run above is its
physical recheck rather than a reinterpretation of the failed result.

For 0.48.2.7, the monotonic capture interval was 1200.218 seconds. Output losses
were already 1 at its initial capture, 9 at minute 3 and 20 at minute 4; the counter
remained 20 at minutes 6, 9, 10, 12, 15 and 20. Decoder losses stayed zero. Even
counting all 20 losses against only 20 minutes gives **0.0695%**, below the 0.1%
target; the observed-window delta is 19 (**0.0660%**). C+A HQ's initial GPU cost
was approximately 25–26 ms, while the lower C+A preset measured approximately
10.7–11.1 ms thereafter. The final controller label confirmed C+A. Current HAL
skin readings ranged from 34.4 to 37.7 C and framework thermal status stayed 0;
the correction avoided the sustained HQ load and heat reached by the failed run.
This is adaptive preset selection, not a claim that the unchanged HQ shaders
became cheaper or that every phone has the same limit.

### Recovery and lifecycle on the final APK

After the 20-minute run was automatically paused and its evidence closed, a
separate test reduced playback to 0.75x (17.98 target fps). After approximately
37 seconds of measured playback, Smart promoted C+A to C+A HQ. Native metrics
then showed approximately 29–32 ms/frame and the panel confirmed **higher quality
confirmed**, headroom 1.77 and 90% confidence, with zero output/decoder losses in
that separate segment. This verifies a real upward transition on a different
workload; it does not claim sustained HQ acceptance at the original 1x speed.

The exact same APK also passed physical PiP entry/return, fullscreen/embedded
orientation changes and rapid toggles in the coordinated UI validation. Those
short recordings establish lifecycle behavior, not thermal performance. The
current process's crash buffer was empty after player closure. Speed 1x and the
original Off selection were restored; statistics, diagnostics and experimental
image analysis were disabled, the existing speed presets were preserved, and the
phone was left on Home. No app data was cleared.

Physical validation is limited to this GPU renderer, SDR clip and USB phone.
GPU-next, HDR, reduced-motion system settings and all other device/engine/room/
Cast combinations were not physically established by this run. Existing gates
and relevant automated regressions remain in place. The optional classifier has
no held-out perceptual acceptance result and remains disabled by default.

Paused captures of the same frame with Off, C+A and C+A HQ show the existing
presets' different line reconstruction. They are a visual reference for the
quality retained by the governor, not proof that the experimental classifier
selects the best family. Image analysis therefore remains off by default.
