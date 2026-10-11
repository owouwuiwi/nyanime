# Measured local Android builds

`python -m scripts.local_build --mode dev :app:testPreviewUnitTest` enables the
Gradle daemon, build cache and Kotlin incremental compilation. On this 16 GiB
Windows machine Kotlin remains in the Gradle process to avoid competing large
heaps; incremental compilation does not require a separate compiler daemon.
The default is two workers. Change memory/worker choices only after profiling.

On Windows the local runner keeps task output-cache exports for resources, JARs,
tests and library modules, but omits exports of the app's large Kotlin and ASM
class directories. Up-to-date tracking and Kotlin incremental state remain active;
the tasks still run whenever their inputs require it. `--archive-class-outputs`
restores the complete export policy for comparisons or faster filesystems. Linux
and the CI wrapper retain normal exports. The explicit cold fallback disables
task caching independently of this policy.

`--mode verify` additionally requires format, the ordinary runtime boundary and
app/domain unit tests. It retains valid Gradle and Kotlin caches: disabling Kotlin
incremental compilation invalidates those caches and makes the next development
build expensive again. `--mode verify --cold` is the explicit no-daemon,
no-build-cache, nonincremental, rerun-all fallback. Both modes run the
requested tests. Neither disables tests, changes antivirus settings, deletes
global caches or performs publishing/device installation. A failed or corrupt
incremental/cache run is evidence to diagnose, then repeat verification from
known inputs; it is not permission to ship its output.

Use the existing JDK/SDK and production signing environment. `--sign` loads the
local DPAPI identity; credentials never enter command arguments or build reports.
The runner accepts task paths, not arbitrary Gradle/probe flags. Probe builds
remain separate workflows and are not release candidates. The ordinary runtime
boundary check still runs before app builds.

Reports and complete Gradle logs are retained under `.local/builds/<timestamp>`.
Each report records the mode, actual tasks/flags, revision, tracked diff digest,
SOURCE_DATE_EPOCH, worker limit, elapsed time and exit code. Gradle's `--profile`
adds task/configuration timings; the runner copies new profile HTML and Kotlin
JSON metrics into the same evidence directory. It compares revision and tracked
and untracked source hashes again at the end. Even a successful Gradle process
is rejected as verification evidence if sources changed during the run.
Task caches still use Gradle/Kotlin's declared toolchain, variant, dependency,
compiler/probe/source inputs; the report is not an alternate cache key.

`BUILD_TIME` defaults to SOURCE_DATE_EPOCH or the Git commit timestamp, in UTC.
Wall-clock time must not invalidate BuildConfig during an unchanged local build.
Commit SHA, commit count, version, signing, dependencies and actual source changes
remain real inputs and must invalidate their consumers. This does not promise
that a release-version change or R8 will be cheap.

Compare the same tasks on the same machine: first/cold run, unchanged repeat,
small Kotlin edit, resource edit, shared-module edit. Separate configuration,
compiler/KSP, tests, R8, packaging/signing, patch generation/replay, upload and ADB
installation. The initial conservative unchanged baseline for #34 was 1m53s,
628 tasks (16 executed, 612 up-to-date); it is not a cold-build measurement.
Publish only measured improvements, with variants and cache state stated.

## Controlled Windows measurements, 21 October 2026

JDK 17, Gradle 9.6.1, two workers, ordinary Preview variant, warmed daemon.
The probes were restored byte-for-byte afterward; none produced an APK.

| Case | Requested work | Gradle time | Tasks |
| --- | --- | --- | --- |
| One-digit Kotlin body edit, complete output-cache export | App Kotlin and ASM | 19s | 415: 15 executed, 400 up-to-date |
| Equivalent one-digit edit, local two-task export policy | App Kotlin and ASM | 9s | 415: 15 executed, 400 up-to-date |
| XML resource comment | Resource processing | 58s | 297: 12 executed, 285 up-to-date |
| Private body edit in domain module | App compile dependency graph | 21s | 252: 15 executed, 237 up-to-date |
| Unchanged app/domain test repeat | App/domain tests | 6s | 500: 13 executed, 487 up-to-date |
| Cold verification fallback | Format, runtime boundary and nine test modules; no task cache, nonincremental, rerun all | 19m40s | 705 executed |

The domain edit rebuilt its producer while app Kotlin stayed up-to-date; the
unchanged test repeat reused valid test results and did not compile the app.
These are individual measurements, not a statistical guarantee or full APK/R8
times. The earlier 1m53s baseline also requested assembly and is not a like-for-like
comparison with the six-second test repeat.
The cold run passed 2,122 tests with zero failures/errors and ten existing skips;
it did not assemble an APK. Its remaining output hashing and class/JAR processing
cost is intentionally visible here. Cold verification remains expensive and is
an explicit fallback, not the default for every iteration.

Earlier broader changes took 12m10s and 8m15s with different inputs. Their profiles
showed app Kotlin at 293.59s (12.72s actual compilation) and ASM at 300.78s.
A thread sample subsequently confirmed `TarBuildCacheEntryPacker.storeFileEntry`
opening thousands of class files during export. Disabling exports for both JAR
tasks was also evaluated and rejected as the default: those tasks took 229.70s
and 211.21s in that experiment. They retain their normal cache policy.

A separate disposable Gradle fixture verified a real cache hit, input invalidation,
a deliberately corrupt owned cache entry (build rejected), and a successful
explicit no-cache rebuild with its output checked. App/shared caches were not
corrupted. This fixture is distinct from final Android artifact verification.

Release APKs must be copied to immutable hash-verified storage after final checks.
Generate deltas from those bytes, reuse unchanged corresponding native source
archives, and retry matching uploads without rebuilding/signing the application.
Patch download is smaller; Android still installs the complete reconstructed APK.
