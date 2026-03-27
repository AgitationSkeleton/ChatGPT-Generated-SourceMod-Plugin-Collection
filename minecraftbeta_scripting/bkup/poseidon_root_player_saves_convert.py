#!/usr/bin/env python3
"""
Poseidon/Bukkit legacy player save converter.

Runs from a server root, discovers world save folders, and copies UUID-named
player save files to username-named copies in the appropriate folder without
deleting the originals.

Ignored directory names anywhere in the tree:
- backups
- logs
- old
- plugins

Supported legacy layouts:
- <world>/playerdata/*.dat
- <world>/players/*.dat
- <world>/*.dat          (only when the folder looks like a world folder)

Name mapping sources, in order:
1) root uuidcache.json
2) root usernames.txt
3) root ops.txt
4) common plugins/xAuth files
5) existing username-named .dat files in each target save folder

Usage:
    py poseidon_root_player_saves_convert.py
    py poseidon_root_player_saves_convert.py --dry-run
    py poseidon_root_player_saves_convert.py --overwrite
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path
from typing import Dict, Optional, Iterable
from uuid import UUID

IGNORED_DIR_NAMES = {"backups", "logs", "old", "plugins"}
PLAYER_SAVE_DIR_NAMES = {"playerdata", "players"}


def offline_uuid_for_username(username: str) -> str:
    digest = hashlib.md5(f"OfflinePlayer:{username}".encode("utf-8")).digest()
    data = bytearray(digest)
    data[6] = (data[6] & 0x0F) | 0x30
    data[8] = (data[8] & 0x3F) | 0x80
    return str(UUID(bytes=bytes(data)))


def normalize_uuid(uuid_text: str) -> Optional[str]:
    candidate = uuid_text.strip()
    if not candidate:
        return None

    try:
        return str(UUID(candidate))
    except ValueError:
        pass

    candidate_no_dashes = candidate.replace("-", "")
    if len(candidate_no_dashes) == 32:
        try:
            return str(UUID(candidate_no_dashes))
        except ValueError:
            return None

    return None


def is_uuid_stem(stem_text: str) -> bool:
    return normalize_uuid(stem_text) is not None


def is_probable_username(stem_text: str) -> bool:
    if not (3 <= len(stem_text) <= 16):
        return False
    return all(character.isalnum() or character == "_" for character in stem_text)


def load_json_file(json_path: Path) -> Optional[object]:
    try:
        with json_path.open("r", encoding="utf-8") as file_handle:
            return json.load(file_handle)
    except FileNotFoundError:
        return None
    except Exception as exc:
        print(f"[WARN] Failed to parse JSON file: {json_path} ({exc})")
        return None


def add_mapping(uuid_to_name: Dict[str, str], uuid_text: str, username: str) -> None:
    normalized_uuid = normalize_uuid(uuid_text)
    cleaned_name = username.strip()

    if not normalized_uuid or not cleaned_name:
        return

    existing_name = uuid_to_name.get(normalized_uuid)
    if existing_name and existing_name != cleaned_name:
        print(
            f"[WARN] UUID {normalized_uuid} already mapped to {existing_name}, "
            f"ignoring conflicting name {cleaned_name}"
        )
        return

    uuid_to_name[normalized_uuid] = cleaned_name


def extract_uuidcache_entries(data: object, uuid_to_name: Dict[str, str]) -> None:
    if isinstance(data, dict):
        players_value = data.get("players")
        if isinstance(players_value, list):
            extract_uuidcache_entries(players_value, uuid_to_name)

        for key_text, value in data.items():
            if key_text == "players":
                continue

            normalized_key = normalize_uuid(str(key_text))
            normalized_value = normalize_uuid(str(value)) if isinstance(value, str) else None

            if normalized_key and isinstance(value, str) and not normalized_value:
                add_mapping(uuid_to_name, key_text, value)
            elif normalized_value and isinstance(key_text, str):
                add_mapping(uuid_to_name, value, key_text)
            elif isinstance(value, (dict, list)):
                extract_uuidcache_entries(value, uuid_to_name)

    elif isinstance(data, list):
        for entry in data:
            if isinstance(entry, dict):
                uuid_value = (
                    entry.get("uuid")
                    or entry.get("id")
                    or entry.get("uniqueId")
                    or entry.get("unique_id")
                )
                name_value = (
                    entry.get("name")
                    or entry.get("username")
                    or entry.get("player")
                    or entry.get("lastKnownName")
                )
                if isinstance(uuid_value, str) and isinstance(name_value, str):
                    add_mapping(uuid_to_name, uuid_value, name_value)
            extract_uuidcache_entries(entry, uuid_to_name)


def load_uuidcache(uuidcache_path: Path, uuid_to_name: Dict[str, str]) -> None:
    data = load_json_file(uuidcache_path)
    if data is None:
        return
    before_count = len(uuid_to_name)
    extract_uuidcache_entries(data, uuid_to_name)
    added_count = len(uuid_to_name) - before_count
    print(f"[INFO] Loaded {added_count} UUID mappings from {uuidcache_path}")


def load_usernames_text(usernames_path: Path, uuid_to_name: Dict[str, str]) -> None:
    if not usernames_path.is_file():
        return

    loaded_count = 0
    with usernames_path.open("r", encoding="utf-8") as file_handle:
        for line_text in file_handle:
            username = line_text.strip()
            if not is_probable_username(username):
                continue
            add_mapping(uuid_to_name, offline_uuid_for_username(username), username)
            loaded_count += 1

    print(f"[INFO] Loaded {loaded_count} usernames from {usernames_path}")


def load_ops_text(ops_path: Path, uuid_to_name: Dict[str, str]) -> None:
    if not ops_path.is_file():
        return

    loaded_count = 0
    with ops_path.open("r", encoding="utf-8") as file_handle:
        for line_text in file_handle:
            username = line_text.strip()
            if not is_probable_username(username):
                continue
            add_mapping(uuid_to_name, offline_uuid_for_username(username), username)
            loaded_count += 1

    print(f"[INFO] Loaded {loaded_count} operator names from {ops_path}")


def load_xauth_data(xauth_dir: Path, uuid_to_name: Dict[str, str]) -> None:
    if not xauth_dir.is_dir():
        return

    loaded_names = set()

    for candidate_path in (
        xauth_dir / "accounts.yml",
        xauth_dir / "accounts.txt",
        xauth_dir / "auths.db.csv",
        xauth_dir / "players.yml",
        xauth_dir / "data.yml",
    ):
        if not candidate_path.exists():
            continue

        try:
            with candidate_path.open("r", encoding="utf-8", errors="ignore") as file_handle:
                if candidate_path.suffix.lower() == ".csv":
                    reader = csv.reader(file_handle)
                    for row_values in reader:
                        if not row_values:
                            continue
                        username = row_values[0].strip()
                        if is_probable_username(username):
                            loaded_names.add(username)
                else:
                    for line_text in file_handle:
                        stripped_line = line_text.strip()
                        if not stripped_line or stripped_line.startswith("#"):
                            continue

                        candidate_names = []

                        if ":" in stripped_line:
                            left_part = stripped_line.split(":", 1)[0].strip().strip('"').strip("'")
                            candidate_names.append(left_part)

                        if "=" in stripped_line:
                            left_part = stripped_line.split("=", 1)[0].strip().strip('"').strip("'")
                            candidate_names.append(left_part)

                        candidate_names.append(stripped_line.strip('"').strip("'"))

                        for candidate_name in candidate_names:
                            if is_probable_username(candidate_name):
                                loaded_names.add(candidate_name)
                                break
        except Exception as exc:
            print(f"[WARN] Failed to inspect xAuth file {candidate_path}: {exc}")

    for username in sorted(loaded_names):
        add_mapping(uuid_to_name, offline_uuid_for_username(username), username)

    if loaded_names:
        print(f"[INFO] Loaded {len(loaded_names)} usernames from xAuth data in {xauth_dir}")


def load_local_name_based_files(target_dir: Path, uuid_to_name: Dict[str, str]) -> None:
    added_count = 0
    for dat_path in target_dir.glob("*.dat"):
        stem_text = dat_path.stem
        if is_uuid_stem(stem_text):
            continue
        if not is_probable_username(stem_text):
            continue

        add_mapping(uuid_to_name, offline_uuid_for_username(stem_text), stem_text)
        added_count += 1

    if added_count:
        print(f"[INFO] Derived {added_count} local mappings from existing name-based files in {target_dir}")


def is_ignored_path(path_obj: Path, root_dir: Path) -> bool:
    try:
        relative_parts = path_obj.resolve().relative_to(root_dir.resolve()).parts
    except Exception:
        relative_parts = path_obj.parts
    return any(part.lower() in IGNORED_DIR_NAMES for part in relative_parts)


def directory_has_uuid_dat_files(dir_path: Path) -> bool:
    for dat_path in dir_path.glob("*.dat"):
        if is_uuid_stem(dat_path.stem):
            return True
    return False


def looks_like_world_folder(dir_path: Path) -> bool:
    if (dir_path / "level.dat").is_file():
        return True
    if any((dir_path / subdir_name).is_dir() for subdir_name in ("region", "DIM-1", "DIM1", "poi", "data")):
        return True
    if directory_has_uuid_dat_files(dir_path):
        return True
    return False


def discover_target_save_dirs(root_dir: Path) -> list[Path]:
    discovered: list[Path] = []
    seen: set[Path] = set()

    for current_root, dir_names, _ in os.walk(root_dir):
        dir_names[:] = [name for name in dir_names if name.lower() not in IGNORED_DIR_NAMES]
        current_path = Path(current_root)

        if current_path.name.lower() in PLAYER_SAVE_DIR_NAMES:
            resolved = current_path.resolve()
            if resolved not in seen and directory_has_uuid_dat_files(current_path):
                discovered.append(current_path)
                seen.add(resolved)
            dir_names[:] = []
            continue

        if looks_like_world_folder(current_path):
            for subdir_name in PLAYER_SAVE_DIR_NAMES:
                candidate_dir = current_path / subdir_name
                if candidate_dir.is_dir() and not is_ignored_path(candidate_dir, root_dir):
                    resolved = candidate_dir.resolve()
                    if resolved not in seen and directory_has_uuid_dat_files(candidate_dir):
                        discovered.append(candidate_dir)
                        seen.add(resolved)

            if directory_has_uuid_dat_files(current_path):
                resolved = current_path.resolve()
                if resolved not in seen:
                    discovered.append(current_path)
                    seen.add(resolved)

    return discovered


def convert_save_dir(
    target_dir: Path,
    uuid_to_name: Dict[str, str],
    overwrite: bool,
    dry_run: bool,
) -> tuple[int, int, int]:
    copied_count = 0
    skipped_count = 0
    unresolved_count = 0

    for source_path in sorted(target_dir.glob("*.dat")):
        normalized_uuid = normalize_uuid(source_path.stem)
        if normalized_uuid is None:
            continue

        username = uuid_to_name.get(normalized_uuid)
        if not username:
            unresolved_count += 1
            print(f"[WARN] No username mapping for UUID file: {source_path}")
            continue

        target_path = target_dir / f"{username}.dat"

        if target_path.exists() and not overwrite:
            skipped_count += 1
            print(f"[INFO] Skipping existing file: {target_path}")
            continue

        print(f"[INFO] Copying {source_path.name} -> {target_path.name}")
        if not dry_run:
            shutil.copy2(source_path, target_path)
        copied_count += 1

    return copied_count, skipped_count, unresolved_count


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Convert Poseidon UUID-based player save files to name-based copies under discovered world folders."
    )
    parser.add_argument(
        "--root",
        default=".",
        help="Server root directory to scan. Defaults to the current directory.",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Overwrite existing username-based .dat files.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show what would be copied without writing any files.",
    )
    args = parser.parse_args()

    root_dir = Path(args.root).resolve()
    if not root_dir.is_dir():
        print(f"[FATAL] Root directory does not exist: {root_dir}")
        return 1

    uuid_to_name: Dict[str, str] = {}
    load_uuidcache(root_dir / "uuidcache.json", uuid_to_name)
    load_usernames_text(root_dir / "usernames.txt", uuid_to_name)
    load_ops_text(root_dir / "ops.txt", uuid_to_name)
    load_xauth_data(root_dir / "plugins" / "xAuth", uuid_to_name)

    target_dirs = discover_target_save_dirs(root_dir)
    if not target_dirs:
        print("[WARN] No suitable player save directories were found.")
        print("[INFO] This usually means the world saves are not in a playerdata/players folder,")
        print("[INFO] or there are no UUID-named .dat files in the discovered world folders.")
        return 0

    print(f"[INFO] Found {len(target_dirs)} target save directorie(s).")

    total_copied = 0
    total_skipped = 0
    total_unresolved = 0

    for target_dir in target_dirs:
        print(f"\n[INFO] Processing {target_dir}")
        local_mapping = dict(uuid_to_name)
        load_local_name_based_files(target_dir, local_mapping)

        copied_count, skipped_count, unresolved_count = convert_save_dir(
            target_dir=target_dir,
            uuid_to_name=local_mapping,
            overwrite=args.overwrite,
            dry_run=args.dry_run,
        )

        total_copied += copied_count
        total_skipped += skipped_count
        total_unresolved += unresolved_count

    print("\n[SUMMARY]")
    print(f"Copied:     {total_copied}")
    print(f"Skipped:    {total_skipped}")
    print(f"Unresolved: {total_unresolved}")
    if args.dry_run:
        print("Mode:       dry-run")

    return 0


if __name__ == "__main__":
    sys.exit(main())
