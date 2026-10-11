# Anime4K Smart

Automatic Smart startup is enabled by default and can be disabled in the internal
player settings without removing manual Smart selection. Saved manual choices
remain independent; see [startup and sleep controls](player-startup-and-sleep.md).
Anime4K is unavailable throughout an active watch room, including local holds
and reconnects. Leaving restores the episode's solo choice. Cast playback runs
on the receiver and does not apply the phone's shaders.

Smart starts at the highest compatible bundled preset for each episode. It keeps
session-local performance and shader failure exclusions, then reduces quality
only when rendering evidence indicates overload. Manual Maximum, Custom and Off
remain independent selections. Official Anime4K 4.0.1 shaders and weights are
unchanged.

The quick **SM** control remains; the separate 4K quick button is removed.
[Ultra downloads](anime4k-ultra.md) contain the processed image and temporarily
suppress all live Anime4K modes without changing stored episode preferences.

## Boundaries

- `Anime4K`: pure preset, scale, FPS, rendering-budget and calibration-key rules.
- `Anime4KGeometry`: rotated source pixels and actual displayed video area from
  MPV OSD dimensions/margins, or aspect-fitted player surface as fallback.
- `Anime4KSmartController`: windows, warmup, capability settling, failure
  exclusions, suspension and confirmed calibration. No Android or MPV calls.
- `aniyomi_anime4k.lua`: native MPV property collection on the script thread.
- `Anime4KTelemetry` / `Anime4KTelemetrySession`: bounded, versioned parsing,
  session/sequence validation and JNI fallback throttling.
- `Anime4KMediaRefresh`: coalesces high-frequency metadata/resize notifications
  into one native refresh per second, invalidating callbacks from old episodes.
- `Anime4KShaderPipeline`: verified asset installation and ownership-aware
  shader-list replacement.
- `PlayerActivity`: episode lifecycle, property observers, applying decisions,
  preferences and diagnostic publication.

MPV replaces nonalphanumeric characters in script client names. Keep the asset
filename aligned with `Anime4KTelemetry.SCRIPT_NAME`, using underscores. The host
sends `script-message-to aniyomi_anime4k set-active yes TOKEN`. A new token is
issued whenever a preset starts; `no` stops the timer. Snapshots arrive at
`user-data/aniyomi-anime4k/telemetry` once per second. Stale tokens and replayed
sequence numbers are ignored.

## Measurement and control

`estimated-vf-fps` is video cadence, not GPU throughput. Native `vo-passes`
provides fresh/redraw pass averages in nanoseconds; the Lua adapter sums them
and converts to milliseconds. These are approximate render costs, not end-to-end
latency. Missing, invalid or incomplete timing sets stay unknown.

For target frame rate `F = min(source FPS * playback speed, display Hz)`, the
estimated cost per new displayed frame is:

```text
fresh_ms + redraw_ms * max(display_Hz / F - 1, 0)
```

Unknown redraw cost contributes zero; unavailable fresh cost gives unknown
capacity. Rendering capacity is `1000 / cost`. The controller takes a slow-side
percentile across a five-second window with sufficient timing coverage. After
load, mode changes, seek, buffering, pause, counter resets or long sampling gaps,
it discards incomplete windows and allows two seconds to settle. A severe
overload needs one complete window; a moderate overload needs two. Positive
calibration requires a measured healthy window, not merely selecting a preset.

Output drops are adjusted for source cadence exceeding display Hz. Decoder
drops and A/V mistiming remain diagnostics; they do not alone justify reducing
shaders. When measured GPU headroom is healthy, output drops do not by themselves
blame the shaders either. Without GPU timing support, output drops can still
trigger a downgrade. If the Lua adapter is unavailable, JNI fallback reads are
limited before entering JNI to once per second.

A changed source family is selected immediately. A secondary pass is removed
immediately below 2x scale or with incompatible FPS. Higher quality enabled by
new media capabilities requires 1.5 seconds of settling, avoiding repeated
recompilation around a resize boundary. Small metadata jitter neither resets
measurement windows nor clears previous failures. Healthy windows confirm
quality but do not initiate speculative periodic upgrades.

A shader failure excludes every mode containing that shader. Delayed errors
from shaders absent from the active preset do not punish the new preset.
Synchronous application failures traverse a bounded fallback ladder. Smart can
suspend at Off; explicit reactivation clears performance exclusions while
retaining shader compilation failures for the current session.

## Asset and user configuration safety

Installation compares the curated shader files with APK bytes, repairs changed
files through temporary-file replacement, and permits presets only after full
verification. Off remains available if verification fails. Mode changes replace
only bundled filenames owned by the private `mpv/anime4k-shaders` directory.
Other paths, ordering and repetitions survive. An unreadable MPV list fails
without sending a destructive replacement. An identical target list sends no
command. After sending a change, the list is read back before confirming the
active preset: the Android JNI wrapper discards `mpv_command` return codes,
so the absence of an exception does not acknowledge the change.

## Verification

```sh
./gradlew :app:testDebugUnitTest spotlessCheck
python -m venv .venv-anime4k-tests
# Activate the environment for your shell, then:
python -m pip install lupa==2.8
python scripts/tests/test_anime4k_telemetry.py
```

The release workflow runs the Lua program under Lua 5.2 and LuaJIT as well as
the normal Android tests and preview build. JVM tests cover budget, geometry,
transitions, invalid telemetry, pause/seek/counter discontinuities, asset repair
and preservation of user shaders. These checks do not establish GPU performance,
image quality, thermal behavior or playback stability on Android devices.

A/B/C family selection remains resolution-based. No visual-content classifier
or universally optimal preset is implied. Real-device validation is still needed
for renderer-specific timer availability and sustained playback performance.

Sources: [MPV manual](https://mpv.io/manual/stable/),
[MPV Lua API](https://github.com/mpv-player/mpv/blob/master/DOCS/man/lua.rst),
[Anime4K preset guidance](https://github.com/bloc97/Anime4K/blob/master/md/GLSL_Instructions_Advanced.md).
