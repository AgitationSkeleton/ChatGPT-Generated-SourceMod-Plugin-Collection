#!/usr/bin/env python3
"""
Batch-copy/rename files by replacing a filename prefix.

Example:
  from: v_pistol_merc
  to:   v_pistol_engineer

Copies (in current working directory):
  v_pistol_merc.dx80.vtx  -> v_pistol_engineer.dx80.vtx
  v_pistol_merc.mdl       -> v_pistol_engineer.mdl
...leaving originals intact.
"""

from __future__ import annotations

import os
import shutil
import sys


def prompt_nonempty(prompt_text: str) -> str:
    while True:
        value = input(prompt_text).strip()
        if value:
            return value
        print("Please enter a non-empty value.")


def main() -> int:
    sourcePrefix = prompt_nonempty("Current name/prefix to replace (e.g., v_pistol_merc): ")
    destPrefix = prompt_nonempty("Replace with (e.g., v_pistol_engineer): ")

    if sourcePrefix == destPrefix:
        print("Source and destination are the same; nothing to do.")
        return 0

    cwdPath = os.getcwd()
    try:
        entries = os.listdir(cwdPath)
    except OSError as exc:
        print(f"Failed to list directory '{cwdPath}': {exc}")
        return 1

    matchedFiles: list[str] = []
    for name in entries:
        fullPath = os.path.join(cwdPath, name)
        if not os.path.isfile(fullPath):
            continue
        if name.startswith(sourcePrefix):
            matchedFiles.append(name)

    if not matchedFiles:
        print(f"No files found starting with '{sourcePrefix}' in '{cwdPath}'.")
        return 0

    copiedCount = 0
    skippedCount = 0
    errorCount = 0

    for srcName in sorted(matchedFiles):
        suffix = srcName[len(sourcePrefix):]  # keeps everything after the prefix, including dots
        dstName = f"{destPrefix}{suffix}"

        srcPath = os.path.join(cwdPath, srcName)
        dstPath = os.path.join(cwdPath, dstName)

        if os.path.exists(dstPath):
            print(f"SKIP (already exists): {dstName}")
            skippedCount += 1
            continue

        try:
            shutil.copy2(srcPath, dstPath)  # preserves timestamps/metadata when possible
            print(f"COPIED: {srcName} -> {dstName}")
            copiedCount += 1
        except OSError as exc:
            print(f"ERROR copying '{srcName}' -> '{dstName}': {exc}")
            errorCount += 1

    print(
        f"\nDone. Matched: {len(matchedFiles)}, Copied: {copiedCount}, "
        f"Skipped: {skippedCount}, Errors: {errorCount}"
    )

    return 1 if errorCount else 0


if __name__ == "__main__":
    raise SystemExit(main())