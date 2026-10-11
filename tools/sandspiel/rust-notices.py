#!/usr/bin/env python3
"""Collect notices from the exact Cargo target graph, without guessing licenses."""
import json
import os
from pathlib import Path
import subprocess
import sys

destination = Path(sys.argv[1])
metadata = json.loads(subprocess.check_output([
    "cargo", "+1.85.1", "metadata", "--locked", "--offline",
    "--filter-platform", "wasm32-unknown-unknown", "--format-version", "1",
]))
selected = {node["id"] for node in metadata["resolve"]["nodes"]}
notices = ["Sandspiel WebAssembly dependency notices\n"
           "Includes the target dependency graph and build-time macro notices conservatively.\n"]
for package in sorted(metadata["packages"], key=lambda value: (value["name"], value["version"])):
    if package["id"] not in selected or package["source"] is None:
        continue
    folder = Path(package["manifest_path"]).parent
    files = sorted(file for file in folder.iterdir()
                   if file.is_file() and file.name.upper().startswith(("LICENSE", "COPYING", "NOTICE")))
    if not files:
        raise RuntimeError(f"No license file for {package['name']} {package['version']}")
    notices.append(f"\n{package['name']} {package['version']}\n"
                   f"Declared license: {package['license']}\n{package.get('repository') or ''}\n")
    for file in files:
        notices.append(f"\n{file.name}\n{file.read_text()}\n")
rust = Path(os.environ["RUSTUP_HOME"]) / "toolchains/1.85.1-x86_64-unknown-linux-gnu/share/doc/rust"
notices.append("\nRust 1.85.1 standard library and runtime notices\n")
for name in ["LICENSE-MIT", "LICENSE-APACHE", "COPYRIGHT"]:
    notices.append(f"\n{name}\n{(rust / name).read_text()}\n")
destination.write_text("\n".join(notices))
