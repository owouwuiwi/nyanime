# Cinema in headphones

An optional local player effect, off by default. Choose **Audio → Cinema in
headphones → Original / Cinema** for an immediate comparison. The preference is
also available in Player settings → Audio and persists between episodes.

The supported decoded layouts are 5.1 (rear or side surround) and 7.1.
Mono, stereo, unknown layouts, manual channel overrides and custom audio filters
retain their original audio. Connecting headphones makes a compatible track
eligible; disconnecting them removes the effect. Android cannot distinguish
headphones from speakers behind Bluetooth or a USB DAC, so users must enable the option
only when wearing headphones.

## Processing and integration

The bundled FFmpeg 7.1 contains the HRTF-based headphone filter but not
sofalizer. This implementation uses measured MIT KEMAR responses extracted
from SOFA at development time. It does not substitute stereo widening for
multichannel virtualization, add libmysofa to Android, or replace native binaries.
The existing HLS seek backport remains unchanged.

Only the labelled nyanime_headphone_cinema audio filter is added/removed.
No volume, pause, seek, watch-room synchronization, streaming URL, source contract
or Cast receiver setting is changed. Cast audio remains the receiver's output.
The controller and callbacks stop before native player destruction.

Audio is resampled to 48 kHz and convolved in the frequency domain using
1,024-sample blocks and 558-tap measured responses. This block size is approximately
21.3 ms; it is **not** a claim about total headphone/Bluetooth latency.
Nine dB of summing headroom and a non-amplifying limiter constrain peaks.
Limiter latency compensation and original timestamps preserve the A/V timeline.
The effect may sound quieter than the device's ordinary downmix.

Android 13+ supplies anticipated media output devices. Earlier releases use
connected wired/USB headsets and active A2DP; actual route selection may be less
precise. No microphone, recording, location or Bluetooth scan permission is added.

## Data and reproducibility

HRTF measurements: Bill Gardner and Keith Martin, MIT Media Laboratory, ©1994.
[Original data and permission](https://sound.media.mit.edu/resources/KEMAR.html).
The complete notice and provenance are shipped in
app/src/main/assets/audio/cinema/LICENSE.txt and README.txt.

Run tools/audio/prepare_headphone_hrir.py INPUT.sofa OUTPUT_DIRECTORY with
Python, NumPy 2.5.3, SciPy 1.18.1 and h5py 3.16.0. Input is pinned by SHA-256;
the generated files are committed, so Android builds do not need Python or
network access to the dataset.

Generic HRTFs cannot promise the same spatial perception for every listener.
Device measurements and listening tests are separate from compilation and
unit-test results.

## Device evidence — 2026-10-04

On the connected Galaxy Z Flip6, using the application's existing native libraries:

- An eight-second 48 kHz 7.1 impulse fixture produced exactly 384,000 stereo frames.
  The complete offline operation took 0.209 seconds, including startup and export.
  This demonstrates processing margin on this device, not battery-life or thermal endurance.
- Center impulses were identical in both ears. Left/right impulses were mirrored;
  their level and arrival-time differences survived convolution. Output was finite
  and below the limiter threshold.
- The actual mpv audio graph accepted three Original/Cinema cycles and intervening
  exact seeks. Playback position kept advancing and the processed output was stereo.
  This used a null audio output, so it does not verify Bluetooth latency or listening quality.
- The probe exposed that this wrapper does not provide af/count or af/N/label.
  Production therefore reads the native, length-escaped af option string, preserving
  commas inside graphs and UTF-8 paths rather than splitting it naively.
- The final graph, including its explicit input-layout guard, was retested for
  5.1, 5.1(side) and 7.1. All returned success and 384,000 finite stereo frames;
  measured runtimes were 0.202, 0.136 and 0.229 seconds respectively. The 5.1
  checks deliberately downmixed the 7.1 fixture to exercise the layout guard.
  Three additional mpv on/off/seek cycles also passed with the final graph.
- Seven cinema unit tests and the three existing audio-channel tests passed
  under the Preview Gradle variant; two asset integrity tests passed separately.

An external signed probe was used without modifying production routes or native
libraries. Its APK, public fixtures and temporary private files were removed.
End-to-end headphone routing, subjective listening and sustained battery/thermal
measurements still require a physical headset and a longer listening session.
