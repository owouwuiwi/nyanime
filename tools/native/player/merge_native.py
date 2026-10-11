#!/usr/bin/env python3
"""Combine separately installed NDK modules without silently choosing a library."""
import argparse
import hashlib
from pathlib import Path
import shutil


def merge(inputs, output):
    if output.exists():
        raise ValueError("Use a fresh native output directory")
    libraries = {}
    for directory in inputs:
        if not directory.is_dir():
            raise ValueError(f"Missing native staging directory: {directory}")
        candidates = sorted(directory.glob("*.so"))
        if not candidates:
            raise ValueError(f"Empty native staging directory: {directory}")
        for candidate in candidates:
            digest = hashlib.sha256(candidate.read_bytes()).hexdigest()
            previous = libraries.get(candidate.name)
            if previous and previous[1] != digest:
                raise ValueError(f"Conflicting native libraries: {candidate.name}")
            libraries[candidate.name] = (candidate, digest)
    # Validate the entire input set before creating output or copying any bytes.
    output.mkdir(parents=True)
    for name, (source, digest) in sorted(libraries.items()):
        destination = output / name
        shutil.copyfile(source, destination)
        if hashlib.sha256(destination.read_bytes()).hexdigest() != digest:
            raise ValueError(f"Native copy changed: {name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", action="append", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    merge(args.input, args.output)
