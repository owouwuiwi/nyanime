#!/usr/bin/env python3
"""Single source of truth for immutable four-part Nyanime releases."""

import argparse
import re
import subprocess
from dataclasses import dataclass, replace
from pathlib import Path

VERSION_PATTERN = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)")
CHANNELS = {"recommended", "preview"}


@dataclass(frozen=True)
class ReleaseVersion:
    parts: tuple[int, int, int, int]
    code: int
    channel: str

    @property
    def name(self) -> str:
        return ".".join(map(str, self.parts))

    @property
    def tag(self) -> str:
        return f"v{self.name}"

    def bumped(self, kind: str, channel: str = "preview") -> "ReleaseVersion":
        index = {"major": 0, "feature": 1, "improvement": 2, "fix": 3}[kind]
        parts = list(self.parts)
        parts[index] += 1
        parts[index + 1:] = [0] * (3 - index)
        candidate = replace(self, parts=tuple(parts), code=self.code + 1, channel=channel)
        return parse(candidate.properties())

    def properties(self) -> str:
        return (
            "# One immutable public version per published APK. See docs/versioning.md.\n"
            f"versionName={self.name}\nversionCode={self.code}\nchannel={self.channel}\n"
        )


def parse(text: str) -> ReleaseVersion:
    values = dict(line.split("=", 1) for raw in text.splitlines()
                  if (line := raw.strip()) and not line.startswith("#"))
    match = VERSION_PATTERN.fullmatch(values.get("versionName", ""))
    if not match:
        raise ValueError("versionName must have four numeric components without leading zeros")
    parts = tuple(int(value) for value in match.groups())
    if any(value > 2_147_483_647 for value in parts):
        raise ValueError("Version component out of range")
    code = int(values.get("versionCode", "0"))
    if not 134 <= code <= 2_100_000_000:
        raise ValueError("Android versionCode must be between 134 and 2100000000")
    channel = values.get("channel", "")
    if channel not in CHANNELS:
        raise ValueError("channel must be recommended or preview")
    return ReleaseVersion(parts, code, channel)


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()


def check_published(version: ReleaseVersion) -> None:
    head = git("rev-parse", "HEAD")
    tags = git("tag", "--list", "v*").splitlines()
    if version.tag in tags and git("rev-parse", f"{version.tag}^{{commit}}") != head:
        raise ValueError(f"{version.tag} is already published from another commit; bump release.properties")
    for tag in git("tag", "--merged", "HEAD", "--list", "v*").splitlines():
        match = VERSION_PATTERN.fullmatch(tag.removeprefix("v"))
        if not match or git("rev-parse", f"{tag}^{{commit}}") == head:
            continue
        if version.parts <= tuple(int(value) for value in match.groups()):
            raise ValueError(f"The public version must increase after {tag}")
        previous = subprocess.run(["git", "show", f"{tag}:release.properties"],
                                  text=True, encoding="utf-8", capture_output=True)
        if previous.returncode == 0 and version.code <= parse(previous.stdout).code:
            raise ValueError(f"Android versionCode must increase after {tag}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", type=Path, default=Path("release.properties"))
    parser.add_argument("--bump", choices=["major", "feature", "improvement", "fix"])
    parser.add_argument("--channel", choices=sorted(CHANNELS))
    parser.add_argument("--check-published", action="store_true")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    version = parse(args.file.read_text(encoding="utf-8"))
    if args.bump:
        version = version.bumped(args.bump, args.channel or "preview")
        args.file.write_text(version.properties(), encoding="utf-8")
    elif args.channel:
        version = replace(version, channel=args.channel)
    if args.check_published:
        check_published(version)
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"version={version.name}\ncode={version.code}\ntag={version.tag}\n"
                         f"channel={version.channel}\n")
    print(f"Nyanime {version.name} · {version.channel} · Android {version.code}")


if __name__ == "__main__":
    main()
