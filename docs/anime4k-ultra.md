# Anime4K Ultra offline

Ultra is an optional second step after a successful internal episode download.
Enable **Settings → Downloads → Anime4K Ultra → Ultra dopo il download**.
It is disabled by default. Once enabled, processing can run on battery and with the
screen on. Charging-only and screen-off-only are optional restrictions, off by default;
explicitly saved choices are retained. These switches are checked live for waiting work too.

Downloaded episode rows expose their local file and Ultra state directly in the
title screen, in both ModernUI and legacy UI. Tap the row for playback, preparation,
pause/resume, retry, cancellation and **Export a copy** through Android's document
picker. The original stays in the episode's download folder; a completed Ultra
copy stays beside it. Normal episode playback selects that verified copy.
**Downloads → Ultra** and the settings queue show the same durable state, including
the reason for an automatic pause. Foreground notifications open the title.

The regular **SM** player button remains. The separate **4K** quick button has
been removed; existing saved Maximum and Custom choices remain compatible.

## Output

- Uses the bundled Maximum **A+ HQ** shader sequence, including its CNN weights,
  signed half-float intermediate textures and highlight clamping. Shader assets
  are unchanged. Media3 Transformer performs GPU rendering and hardware encoding.
- SDR output is H.264, up to four times the source dimensions and bounded by
  3840×2160 (2160×3840 for portrait). Aspect ratio and frame timestamps are preserved.
  Hardware must support the requested output; there is no silent preset downgrade.
- FFmpeg remuxes the encoded video with the original audio tracks, subtitles,
  chapters and attachments into Matroska. Unsupported input/track combinations
  fail the conversion and retain the original.
- `Nyanime-Ultra.mkv` is selected only after dimensions, duration, copied length
  and the completion marker have been checked. An unfinished export is never Ultra.
- Completed downloads show a neutral **ULTRA** label in place of SM. Runtime
  Anime4K controls, diagnostics and automatic startup are suppressed for that file.
  Opening a regular video restores its own preferences. Room restrictions remain.

## Resource and lifecycle behavior

The original download is retained alongside the processed copy. Conversion needs
additional temporary storage and can take longer than the episode's duration.
It may substantially increase file size; the output is an enhancement, not a way
to recover information missing from the original recording.

Only one GPU conversion runs at a time. Transformer control and cancellation run
on a background HandlerThread, with background-priority GL work and an encoder
operating-rate hint of 12 / priority 1. This hint does not alter output frame rate.
Only hardware video encoders are selected.

Each shader pass uses scissored strips of at most 262,144 pixels and 128 rows.
The viewport, sampling coordinates, shaders and half-float intermediate images
remain unchanged. Each strip drains with glFinish before resting for four times
its measured work duration (nine times while interactive/warm). These are software
duty budgets, **not measured CPU/GPU utilization or a guaranteed device temperature**.

Admission is rechecked during encoding. Ordinary warmth and Android MODERATE status
allow paced processing. SEVERE thermal status, battery sensor temperature >= 45 C,
or current thermal headroom >= 1 pauses work. Resume requires status <= MODERATE,
battery <= 42 C and headroom <= 0.9 when available. Headroom is sampled at most once
every 10 seconds, using the current value rather than a 30-second forecast.
These are app admission thresholds, not a guarantee of a particular device temperature.
The thermal scale follows [Android PowerManager](https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int)).
Battery temperature is a fallback sensor, not a measurement of the GPU temperature.
Playback, Android-reported low memory and battery below 15% while unplugged defer work.
Power saving slows the GPU duty budget without blocking admission. Optional charging
and screen restrictions are sampled during work; storage remains a WorkManager constraint.

Resource waits schedule a new check after 30 seconds instead of accumulating an
ever-longer failure backoff. Android may delay this check; it is not an exact timer.
After upgrading, waiting jobs with old constraints are refreshed on app startup,
preserving progress and completed clips. Running jobs and explicit user pauses are
not restarted by this refresh. Only a scheduling failure uses WorkManager's retry backoff.

Exports checkpoint short video-only clips, normally about two seconds of source
video, split at actual presentation timestamps. Finished clips and atomic receipts
live in app-private no-backup storage. A pause or process death discards only the
unfinished clip. Fixed source-timeline durations in the FFmpeg concat manifest
avoid accumulating rounding errors at joins. Audio is copied once from the original.
Sessions yield at a completed clip boundary after 20 minutes.

The durable task journal is separate from prunable WorkManager history. Worker
updates are scoped to their attempt ID, so an obsolete worker cannot overwrite a
new attempt or a manual pause. Cancellation discards checkpoints, never the original.
Deleting downloads cancels their work; successful publication removes scratch data.
Interrupted copies never receive a completion marker.

HDR exports and devices that cannot render/encode the target output fail safely.
This does not change the native MPV/FFmpeg playback libraries, streaming resolver,
download quality selection, manga pipeline or external downloader behavior.

## Validation boundaries

Download rows and details offer a direct delete action. The title's download sheet
loads all its displayed downloaded episodes and supports multiple selection.
Deletion offers either the complete episode or only Ultra plus its temporary files,
with an estimated storage total and an explicit confirmation. Exported copies,
library membership and playback history are not deleted.

The writer is cancelled and awaited before deleting files. Its previous attempt ID
is invalidated to prevent late publication from reviving the job. File deletion
and temporary cleanup must both succeed before clearing the journal; failures are
shown and can be retried. Closing the screen does not cancel confirmed cleanup.
The download cache is refreshed even after a partial failure. Download surfaces
use the app's neutral colors and theme accent instead of a hardcoded purple.

JVM tests cover thermal hysteresis, playback/resource admission, pacing arithmetic,
frame-aligned segment boundaries, interrupted checkpoints, queue persistence and
stale-worker exclusion, alongside the real bundled shader graph and existing
Smart/room preference tests. LayoutLib previews cover narrow screens, large text
and legacy styling without an emulator.

The new pacing and segmented export still require a physical-device endurance
check: temperature over time, responsiveness, pause/resume, audio synchronization
across joins, complete-episode output and vendor codec compatibility. Desktop
tests and successful APK assembly do not establish those measurements.
