# Nyanime benchmarks

The isolated package is `xyz.jmir.tachiyomi.mi.anime4k.benchmark`.
Build with `:app:assembleBenchmark :macrobenchmark:assembleBenchmark`.
Run `:macrobenchmark:connectedBenchmarkAndroidTest` only on a dedicated emulator
or benchmark device. The interface smoke test temporarily changes global font size
and display size, restoring them in `finally`.

Fixtures contain 500 anime, 501 manga and eight synthetic local pages. The setup
activity exists only in the benchmark variant. No user database or extensions are
required. Home feeds are seeded in the real catalogue cache before each run.

The `App performance and interface checks` workflow runs only when manually requested and preserves startup and
frame timing JSON, traces, screenshots and generated baseline profiles. Emulator
results verify repeatability and regressions; they do not establish phone speed,
GPU capacity, power consumption or thermal behavior.

BaselineProfileGenerator prepares fixtures outside the profiled block, then covers
Home, library scrolling, search and opening the manga reader. Inspect the generated
profile (generated with `-PprofileGeneration=true`, without minification) before copying it to `app/src/main/baseline-prof.txt`; keep production rules
only. Timing runs leave minification enabled. Never synthesize timings or replace a measured profile with guessed rules.

Use a physical benchmark device for publishable before/after performance comparisons:
https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
