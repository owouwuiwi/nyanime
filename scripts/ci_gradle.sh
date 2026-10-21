#!/usr/bin/env bash
set -euo pipefail

# Each CI phase uses a single-use JVM. Compile preview sources before assembling
# in a fresh invocation, so R8 does not retain the compiler/test heap. The public
# 16 GiB runner leaves room for the OS and APK tools even with R8's 8 GiB limit.
phase="${CI_GRADLE_PHASE:-build}"
heap="${CI_GRADLE_HEAP:-6g}"
diagnostics="${RUNNER_TEMP:-/tmp}/nyanime-gradle"
mkdir -p "$diagnostics"

./gradlew "$@" --no-daemon --no-parallel --max-workers=2 --console=plain \
  --build-cache -Pkotlin.incremental=true -PcloudSyncProbe=false \
  -Pkotlin.build.report.output=file,json \
  "-Pkotlin.build.report.json.directory=${diagnostics}/kotlin" \
  "-Pkotlin.build.report.file.output_dir=${diagnostics}/kotlin" \
  -Pkotlin.build.report.include_compiler_arguments=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  "-Dorg.gradle.jvmargs=-Xmx${heap} -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 -Xlog:gc:file=${diagnostics}/${phase}-gc.log:time,uptime,level,tags:filecount=2,filesize=10M" &
gradle_pid=$!

# Silent R8 work must remain diagnosable without dumping environment variables,
# credentials or the process command lines into a public workflow log.
(
  timer_pid=''
  trap 'if [[ -n "$timer_pid" ]]; then kill "$timer_pid" 2>/dev/null || true; fi' EXIT
  trap 'exit 0' INT TERM
  while kill -0 "$gradle_pid" 2>/dev/null; do
    {
      printf '\nCI build %s: %s\n' "$phase" "$(date -u +%FT%TZ)"
      free -m
      ps -C java -o pid,etimes,pcpu,rss,comm || true
    } | tee -a "$diagnostics/${phase}-resources.log"
    sleep 60 &
    timer_pid=$!
    wait "$timer_pid" || true
  done
) &
monitor_pid=$!
trap 'kill "$monitor_pid" 2>/dev/null || true; wait "$monitor_pid" 2>/dev/null || true' EXIT
trap 'kill "$gradle_pid" 2>/dev/null || true; exit 130' INT
trap 'kill "$gradle_pid" 2>/dev/null || true; exit 143' TERM

wait "$gradle_pid"
