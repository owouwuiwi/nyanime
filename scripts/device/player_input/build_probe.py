#!/usr/bin/env python3
"""Build the separate, self-targeted player input probe without Gradle or app binaries."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--build-tools", default="36.0.0")
    parser.add_argument("--platform", default="36")
    parser.add_argument("--debug-keystore", type=Path, default=Path.home() / ".android/debug.keystore")
    root = Path(__file__).resolve().parents[3]
    parser.add_argument("--output", type=Path, default=root / ".local/player-input-probe")
    args = parser.parse_args()
    source = Path(__file__).with_name("PlayerInputDeviceProbe.java")
    tools = args.sdk.resolve() / "build-tools" / args.build_tools
    android = args.sdk.resolve() / "platforms" / ("android-" + args.platform) / "android.jar"
    suffix = ".exe" if os.name == "nt" else ""
    java = args.java_home.resolve() / "bin" / ("java" + suffix)
    javac = args.java_home.resolve() / "bin" / ("javac" + suffix)
    for required in [java, javac, android, tools / ("aapt" + suffix), tools / ("zipalign" + suffix),
                     tools / "lib/d8.jar", tools / "lib/apksigner.jar", args.debug_keystore]:
        if not required.is_file():
            parser.error("Missing local prerequisite: " + str(required))
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    classes = output / "classes"
    dex = output / "dex"
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    package = "nyanime.qa.playerinputprobe"
    manifest = output / "AndroidManifest.xml"
    manifest.write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="' + package + '">'
        '<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/>'
        '<application android:label="Player input verification" android:testOnly="true" android:debuggable="true">'
        '<meta-data android:name="probe.source.sha256" android:value="' + source_hash + '"/>'
        '</application><instrumentation android:name="nyanime.qa.PlayerInputDeviceProbe" android:targetPackage="'
        + package + '" android:label="Player input verification"/></manifest>', encoding="utf-8",
    )
    log = output / "build.log"
    log.write_text("", encoding="utf-8")

    def run(command):
        result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                                encoding="utf-8", errors="replace")
        with log.open("a", encoding="utf-8") as stream:
            stream.write(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError("Probe tool failed; see " + str(log))

    run([javac, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-cp", android, "-d", classes, source])
    # Invoke the Java tools directly so paths are never interpreted by a batch shell.
    run([java, "-Xmx256m", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--release", "--min-api", "29",
         "--lib", android, "--output", dex, *sorted(classes.rglob("*.class"))])
    unsigned = output / "unsigned.apk"
    run([tools / ("aapt" + suffix), "package", "-f", "-M", manifest, "-I", android, "-F", unsigned])
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex / "classes.dex", "classes.dex")
    aligned = output / "aligned.apk"
    run([tools / ("zipalign" + suffix), "-f", "4", unsigned, aligned])
    apk = output / "player-input-probe.apk"
    signer = [java, "-jar", tools / "lib/apksigner.jar"]
    # Android's standard debug key signs only this self-targeted, test-only package.
    run([*signer, "sign", "--ks", args.debug_keystore, "--ks-key-alias", "androiddebugkey",
         "--ks-pass", "pass:android", "--key-pass", "pass:android", "--v4-signing-enabled", "false",
         "--out", apk, aligned])
    run([*signer, "verify", apk])
    report = {"apk": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
              "sourceSha256": source_hash, "selfTargeted": True, "mainAppDependencies": False}
    (output / "verified.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
