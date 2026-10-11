#!/usr/bin/env python3
"""Build OTA notes from changelog entries added since the preceding release tag."""

import argparse
import re
import subprocess
from pathlib import Path


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()


def previous_tag(current_tag: str, revision: str = "HEAD") -> str | None:
    candidates = []
    for tag in git("tag", "--merged", revision).splitlines():
        if tag == current_tag or not re.fullmatch(r"r\d+|v\d+\.\d+\.\d+\.\d+", tag):
            continue
        distance = int(git("rev-list", "--count", f"{tag}..{revision}"))
        if distance > 0:
            candidates.append((distance, not tag.startswith("v"), tag))
    return min(candidates)[2] if candidates else None


FORMAT_MARKER = "<!-- nyanime-changelog:1 -->"
HISTORICAL_HEADING_SUFFIX = " [storico sperimentale]"
CATEGORIES = ("Novità", "Correzioni", "Prestazioni", "Interfaccia", "Compatibilità",
              "Sicurezza", "Distribuzione", "Verifiche", "Limitazioni")


def validate_notes_format(markdown: str) -> None:
    """Require the versioned structure understood by the native OTA timeline."""
    markdown = markdown.replace("\r\n", "\n")
    if not markdown.lstrip().startswith(FORMAT_MARKER + "\n"):
        raise ValueError("Release notes must use the structured changelog format; run scripts/release_notes.py")
    version = False
    category = False
    entries = 0
    fence = None
    for line in markdown.splitlines():
        trimmed = line.strip()
        marker = trimmed[:3] if trimmed[:3] in {"```", "~~~"} else None
        if marker:
            fence = None if fence == marker else fence or marker
        elif fence:
            continue
        elif not trimmed or trimmed == FORMAT_MARKER:
            continue
        elif re.fullmatch(r"## Novità di Nyanime \d+\.\d+\.\d+\.\d+", line):
            continue
        elif line.startswith("### "):
            if not re.search(r"(?<![\d.])\d+\.\d+\.\d+\.\d+(?![\d.])", line[4:]):
                raise ValueError("Release notes need a numeric version heading at level three")
            version, category = True, False
        elif line.startswith("#### "):
            if not version or line[5:].strip() not in (*CATEGORIES, "Note originali"):
                raise ValueError("Release note categories must follow a version heading and use a declared category")
            category = True
        elif trimmed.startswith("[Changelog completo]("):
            continue
        elif line.startswith("#"):
            raise ValueError("Release notes have incorrect heading levels; run scripts/release_notes.py")
        elif line.startswith("- "):
            if not category:
                raise ValueError("Every release note must belong to a category")
            entries += 1
        elif not category:
            raise ValueError("Uncategorized text in release notes")
    if not entries:
        raise ValueError("Release notes must contain categorized changes")


def categorized_sections(markdown: str) -> list[tuple[str, list[tuple[str | None, list[str]]]]]:
    result = []
    heading = ""
    category = None
    entries: list[str] = []
    current: list[str] = []
    groups = []

    def flush_entry() -> None:
        if current:
            entries.append(" ".join(current))
            current.clear()

    def flush_group() -> None:
        flush_entry()
        if entries:
            groups.append((category, entries.copy()))
        entries.clear()

    def flush_section() -> None:
        flush_group()
        if heading:
            result.append((heading, groups.copy()))
        groups.clear()

    for line in markdown.splitlines():
        if line.startswith("## "):
            flush_section()
            heading = line[3:]
            category = None
        elif heading and line.startswith("### "):
            flush_group()
            category = line[4:].strip() if line[4:].strip() in CATEGORIES else None
        elif heading and line.startswith("- "):
            flush_entry()
            current.append(line[2:].strip())
        elif current and line.startswith("  "):
            current.append(line.strip())
        else:
            flush_entry()
    flush_section()
    return result


def sections(markdown: str) -> list[tuple[str, list[str]]]:
    return [(heading, [entry for _, entries in groups for entry in entries])
            for heading, groups in categorized_sections(markdown)]


def release_notes(current: str, previous: str | None, tag: str) -> str:
    current_sections = categorized_sections(current)
    old_entries = {entry for _, entries in sections(previous or "") for entry in entries}
    notes = [FORMAT_MARKER, "", f"## Novità di Nyanime {tag.removeprefix('v')}", ""]
    for heading, groups in current_sections:
        # Imported experimental history stays in the complete changelog, but is
        # not a newly delivered feature of the ordinary release.
        if heading.endswith(HISTORICAL_HEADING_SUFFIX):
            continue
        added_groups = [(category, [entry for entry in entries if entry not in old_entries])
                        for category, entries in groups]
        if any(entries for _, entries in added_groups):
            notes.extend((f"### {heading}", ""))
            for category, entries in added_groups:
                if entries:
                    notes.extend((f"#### {category or 'Note originali'}", ""))
                    notes.extend(f"- {entry}" for entry in entries)
                    notes.append("")
    return "\n".join(notes).rstrip() + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()

    current = Path("CHANGELOG.md").read_text(encoding="utf-8")
    previous = previous_tag(args.tag, args.revision)
    old = git("show", f"{previous}:CHANGELOG.md") if previous else None
    notes = release_notes(current, old, args.tag)
    if not re.search(r"(?m)^- ", notes):
        raise SystemExit("No new changelog entries since the previous release; add release notes before publishing.")
    notes += f"\n[Changelog completo](https://github.com/{args.repository}/blob/{args.tag}/CHANGELOG.md).\n"
    validate_notes_format(notes)
    args.output.write_text(notes, encoding="utf-8")


if __name__ == "__main__":
    main()
