import io
import json
import struct
import zlib
import gzip
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import nbtlib


# ----------------------------
# Text component handling
# ----------------------------

def safe_json_loads(maybe_json: str) -> Optional[Any]:
    try:
        return json.loads(maybe_json)
    except Exception:
        return None


def strip_control_whitespace(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    cleaned = []
    for ch in text:
        o = ord(ch)
        if ch in ("\n", "\t") or o >= 32:
            cleaned.append(ch)
    return "".join(cleaned)


def component_to_plain_text(component: Any) -> str:
    """
    Convert Minecraft JSON text components to readable text.
    Includes support for modern wrapped book page shapes like {"raw": "...", "filtered": "..."}.
    """
    if component is None:
        return ""

    # JSON-string component
    if isinstance(component, str):
        parsed = safe_json_loads(component)
        if parsed is not None:
            return component_to_plain_text(parsed)
        return component

    # Component array
    if isinstance(component, list):
        return "".join(component_to_plain_text(part) for part in component)

    # Component object
    if isinstance(component, dict):
        # Modern wrapper pattern (common in book pages on newer versions)
        if "raw" in component:
            raw_val = component.get("raw")
            if raw_val is not None:
                return component_to_plain_text(raw_val)
        if "filtered" in component:
            filtered_val = component.get("filtered")
            if filtered_val is not None:
                return component_to_plain_text(filtered_val)

        base = ""
        if isinstance(component.get("text"), str):
            base = component["text"]
        elif isinstance(component.get("translate"), str):
            base = component["translate"]

        extra = component.get("extra")
        if isinstance(extra, list):
            base += "".join(component_to_plain_text(part) for part in extra)

        with_args = component.get("with")
        if isinstance(with_args, list):
            base += "".join(component_to_plain_text(part) for part in with_args)

        return base

    return str(component)


def page_to_plain_text(page_value: Any) -> str:
    """
    Pages can be:
      - JSON strings
      - dict/list components
      - dict wrappers {"raw": "...", "filtered": "..."} (very common in modern MC)
    """
    if page_value is None:
        return ""

    # if it's a JSON string or raw text
    if isinstance(page_value, str):
        parsed = safe_json_loads(page_value)
        text = component_to_plain_text(parsed) if parsed is not None else page_value
        return strip_control_whitespace(text)

    # wrapper dict or component dict
    if isinstance(page_value, dict) or isinstance(page_value, list):
        return strip_control_whitespace(component_to_plain_text(page_value))

    return strip_control_whitespace(str(page_value))


# ----------------------------
# Item / book extraction
# ----------------------------

def normalize_item_id(item_id: str) -> str:
    if ":" in item_id:
        return item_id
    return f"minecraft:{item_id}"


def find_first_key_recursive(node: Any, wanted_key: str) -> Optional[Any]:
    if isinstance(node, dict):
        if wanted_key in node:
            return node[wanted_key]
        for v in node.values():
            hit = find_first_key_recursive(v, wanted_key)
            if hit is not None:
                return hit
    elif isinstance(node, list):
        for v in node:
            hit = find_first_key_recursive(v, wanted_key)
            if hit is not None:
                return hit
    return None


def extract_book_fields_from_any(item_payload: Dict[str, Any]) -> Tuple[str, str, List[str]]:
    """
    Pull title/author/pages out of either classic "tag" layout or modern "components" layout.
    Pages are normalized through page_to_plain_text() which handles modern wrappers.
    """
    tag_data = item_payload.get("tag") if isinstance(item_payload.get("tag"), dict) else {}
    components_data = item_payload.get("components") if isinstance(item_payload.get("components"), dict) else {}

    title_raw = tag_data.get("title")
    author_raw = tag_data.get("author")
    pages_raw = tag_data.get("pages")

    # If not found in tag, try to locate in components recursively (varies by version)
    if title_raw is None:
        title_raw = find_first_key_recursive(components_data, "title")
    if author_raw is None:
        author_raw = find_first_key_recursive(components_data, "author")
    if pages_raw is None:
        pages_raw = find_first_key_recursive(components_data, "pages")

    def to_plain(value: Any) -> str:
        if value is None:
            return ""
        return strip_control_whitespace(component_to_plain_text(value))

    title_plain = to_plain(title_raw)
    author_plain = to_plain(author_raw)

    pages_plain: List[str] = []
    if isinstance(pages_raw, list):
        for page in pages_raw:
            pages_plain.append(page_to_plain_text(page))

    return title_plain, author_plain, pages_plain


def extract_book_from_item(item_stack: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    raw_id = item_stack.get("id")
    if not raw_id:
        return None

    item_id = normalize_item_id(str(raw_id))

    # You probably only care about written books, but including writable_book can help too.
    if item_id not in ("minecraft:written_book", "minecraft:writable_book"):
        return None

    title, author, pages = extract_book_fields_from_any(item_stack)
    if not title.strip():
        title = "(untitled)"

    return {
        "itemId": item_id,
        "title": title,
        "author": author,
        "pages": pages,
    }


def iter_books_in_items(items: List[Dict[str, Any]], recursion_depth: int = 0, max_depth: int = 6) -> List[Dict[str, Any]]:
    found: List[Dict[str, Any]] = []

    for item_stack in items:
        if not isinstance(item_stack, dict):
            continue

        book = extract_book_from_item(item_stack)
        if book is not None:
            found.append(book)

        if recursion_depth >= max_depth:
            continue

        tag_data = item_stack.get("tag")
        if not isinstance(tag_data, dict):
            continue

        # Shulker boxes and other "container items" keep nested Items here.
        block_entity_tag = tag_data.get("BlockEntityTag")
        if isinstance(block_entity_tag, dict):
            nested_items = block_entity_tag.get("Items")
            if isinstance(nested_items, list) and nested_items:
                nested_items_py = [ni for ni in nested_items if isinstance(ni, dict)]
                found.extend(iter_books_in_items(nested_items_py, recursion_depth + 1, max_depth))

    return found


# ----------------------------
# Sign + Lectern extraction
# ----------------------------

def extract_sign_text_from_block_entity(be: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    be_id = str(be.get("id", "")).lower()
    if "sign" not in be_id:
        return None

    def extract_side(side_obj: Any) -> List[str]:
        lines: List[str] = []
        if not isinstance(side_obj, dict):
            return lines
        messages = side_obj.get("messages")
        if isinstance(messages, list):
            for msg in messages:
                # msg can be string json or dict
                lines.append(strip_control_whitespace(component_to_plain_text(msg)))
        return lines

    front_lines = extract_side(be.get("front_text"))
    back_lines = extract_side(be.get("back_text"))

    # Legacy fallback
    if not front_lines:
        legacy: List[str] = []
        for key in ("Text1", "Text2", "Text3", "Text4"):
            if key in be:
                value = be.get(key)
                legacy.append(strip_control_whitespace(component_to_plain_text(value)))
        front_lines = legacy

    if not any(line.strip() for line in front_lines + back_lines):
        return None

    return {"front": front_lines, "back": back_lines}


def extract_lectern_book(be: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    be_id = str(be.get("id", "")).lower()
    if be_id not in ("minecraft:lectern", "lectern"):
        return None

    book_stack = be.get("Book") or be.get("book")
    if not isinstance(book_stack, dict):
        return None

    return extract_book_from_item(book_stack)


# ----------------------------
# Region (.mca) reader (binary)
# ----------------------------

@dataclass
class DimensionScanTarget:
    dimension: str
    world_folder: Path


def read_mca_locations(region_fp) -> List[Tuple[int, int]]:
    region_fp.seek(0)
    header = region_fp.read(4096)
    if len(header) != 4096:
        return [(0, 0)] * 1024

    locations: List[Tuple[int, int]] = []
    for i in range(1024):
        entry = header[i * 4:(i + 1) * 4]
        offset = (entry[0] << 16) | (entry[1] << 8) | entry[2]
        count = entry[3]
        locations.append((offset, count))
    return locations


def decompress_chunk_payload(compression_type: int, compressed: bytes) -> Optional[bytes]:
    try:
        if compression_type == 1:
            return gzip.decompress(compressed)
        if compression_type == 2:
            return zlib.decompress(compressed)
        if compression_type == 3:
            return compressed
        return None
    except Exception:
        return None


def parse_chunk_nbt(payload: bytes) -> Optional[Dict[str, Any]]:
    """
    Correct binary NBT parsing:
      nbtlib.File.parse(BytesIO(...)) reads binary NBT (what chunks contain).
    """
    try:
        fileobj = io.BytesIO(payload)
        nbt_file = nbtlib.File.parse(fileobj)
        unpacked = nbt_file.unpack() if hasattr(nbt_file, "unpack") else None
        if isinstance(unpacked, dict):
            return unpacked
        if hasattr(nbt_file, "root") and hasattr(nbt_file.root, "unpack"):
            root_unpacked = nbt_file.root.unpack()
            if isinstance(root_unpacked, dict):
                return root_unpacked
        return None
    except Exception:
        return None


def read_chunk_root_from_region(region_path: Path, offset_sectors: int, sector_count: int) -> Optional[Dict[str, Any]]:
    if offset_sectors <= 0 or sector_count <= 0:
        return None

    try:
        with region_path.open("rb") as f:
            f.seek(offset_sectors * 4096)

            length_bytes = f.read(4)
            if len(length_bytes) != 4:
                return None
            (length,) = struct.unpack(">I", length_bytes)

            if length <= 1:
                return None

            compression_type_b = f.read(1)
            if len(compression_type_b) != 1:
                return None
            compression_type = compression_type_b[0]

            compressed = f.read(length - 1)
            if len(compressed) != (length - 1):
                return None

        payload = decompress_chunk_payload(compression_type, compressed)
        if payload is None:
            return None

        return parse_chunk_nbt(payload)

    except Exception:
        return None


def iter_region_dirs(world_folder: Path) -> List[Path]:
    region_dirs: List[Path] = []
    for region_dir in world_folder.rglob("region"):
        if not region_dir.is_dir():
            continue
        if any(region_dir.glob("r.*.*.mca")):
            region_dirs.append(region_dir)

    seen = set()
    unique: List[Path] = []
    for d in sorted(region_dirs, key=lambda p: str(p)):
        key = str(d.resolve())
        if key in seen:
            continue
        seen.add(key)
        unique.append(d)
    return unique


def get_block_entities_from_chunk_root(chunk_root: Dict[str, Any]) -> List[Dict[str, Any]]:
    possible_keys = {"block_entities", "BlockEntities", "tile_entities", "TileEntities"}
    found: List[Dict[str, Any]] = []

    def looks_like_be(obj: Any) -> bool:
        if not isinstance(obj, dict):
            return False
        if "id" not in obj:
            return False
        if all(k in obj for k in ("x", "y", "z")):
            return True
        if "pos" in obj and isinstance(obj["pos"], (list, tuple)) and len(obj["pos"]) == 3:
            return True
        return False

    def visit(node: Any) -> None:
        if isinstance(node, dict):
            for k, v in node.items():
                if k in possible_keys and isinstance(v, list):
                    for e in v:
                        if isinstance(e, dict) and looks_like_be(e):
                            found.append(e)
                visit(v)
        elif isinstance(node, list):
            for e in node:
                visit(e)

    visit(chunk_root)

    unique: List[Dict[str, Any]] = []
    seen = set()
    for be in found:
        be_id = str(be.get("id", ""))
        x = be.get("x")
        y = be.get("y")
        z = be.get("z")
        key = (be_id, x, y, z)
        if key in seen:
            continue
        seen.add(key)
        unique.append(be)

    return unique


# ----------------------------
# Offline playerdata scan
# ----------------------------

def load_nbt_file_to_dict(path: Path) -> Optional[Dict[str, Any]]:
    try:
        tag = nbtlib.load(str(path))
        unpacked = tag.unpack() if hasattr(tag, "unpack") else None
        return unpacked if isinstance(unpacked, dict) else None
    except Exception:
        return None


def scan_playerdata(overworld_folder: Path) -> List[Dict[str, Any]]:
    results: List[Dict[str, Any]] = []
    playerdata_dir = overworld_folder / "playerdata"
    if not playerdata_dir.is_dir():
        return results

    for dat_file in playerdata_dir.glob("*.dat"):
        uuid_str = dat_file.stem
        nbt_data = load_nbt_file_to_dict(dat_file)
        if not isinstance(nbt_data, dict):
            continue

        for key_name, source_label in (("Inventory", "inventory"), ("EnderItems", "enderChest")):
            items = nbt_data.get(key_name)
            if not isinstance(items, list):
                continue

            items_py = [it for it in items if isinstance(it, dict)]
            books = iter_books_in_items(items_py)
            if not books:
                continue

            results.append({
                "type": "playerBooks",
                "uuid": uuid_str,
                "source": source_label,
                "bookCount": len(books),
                "books": books,
            })

    return results


# ----------------------------
# Region scan logic
# ----------------------------

def scan_world_folder(dimension: str, world_folder: Path) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    sign_records: List[Dict[str, Any]] = []
    book_entries: List[Dict[str, Any]] = []

    region_dirs = iter_region_dirs(world_folder)
    print(f"Scanning dimension '{dimension}' in {world_folder}")
    print(f"  Region dirs found: {len(region_dirs)}")
    for rd in region_dirs:
        count = len(list(rd.glob("r.*.*.mca")))
        print(f"    - {rd} ({count} region files)")

    region_paths: List[Path] = []
    for rd in region_dirs:
        region_paths.extend(sorted(rd.glob("r.*.*.mca")))

    total_regions = len(region_paths)
    print(f"  Total region files: {total_regions}")

    parsed_chunks_ok = 0
    parsed_chunks_fail = 0

    for region_index, region_path in enumerate(region_paths, start=1):
        print(f"  [{region_index}/{total_regions}] {region_path.name} ...", flush=True)

        try:
            with region_path.open("rb") as f:
                locations = read_mca_locations(f)
        except Exception as ex:
            print(f"    Skipped (cannot read header): {ex}", flush=True)
            continue

        region_signs_before = len(sign_records)
        region_books_before = len(book_entries)

        for (offset_sectors, sector_count) in locations:
            if offset_sectors == 0 or sector_count == 0:
                continue

            chunk_root = read_chunk_root_from_region(region_path, offset_sectors, sector_count)
            if not isinstance(chunk_root, dict):
                parsed_chunks_fail += 1
                continue

            parsed_chunks_ok += 1

            block_entities = get_block_entities_from_chunk_root(chunk_root)
            for be in block_entities:
                x = be.get("x")
                y = be.get("y")
                z = be.get("z")
                be_id = str(be.get("id", ""))

                sign_text = extract_sign_text_from_block_entity(be)
                if sign_text is not None:
                    sign_records.append({
                        "type": "sign",
                        "dimension": dimension,
                        "pos": {"x": x, "y": y, "z": z},
                        "front": sign_text.get("front", []),
                        "back": sign_text.get("back", []),
                        "blockEntityId": be_id,
                    })

                lectern_book = extract_lectern_book(be)
                if lectern_book is not None:
                    book_entries.append({
                        "type": "lecternBook",
                        "dimension": dimension,
                        "pos": {"x": x, "y": y, "z": z},
                        "blockEntityId": be_id,
                        "bookCount": 1,
                        "books": [lectern_book],
                    })

                items = be.get("Items")
                if isinstance(items, list) and items:
                    items_py = [it for it in items if isinstance(it, dict)]
                    books = iter_books_in_items(items_py)
                    if books:
                        book_entries.append({
                            "type": "containerBooks",
                            "dimension": dimension,
                            "pos": {"x": x, "y": y, "z": z},
                            "blockEntityId": be_id,
                            "bookCount": len(books),
                            "books": books,
                        })

        print(
            f"    Found in this region: signs={len(sign_records) - region_signs_before}, "
            f"bookEntries={len(book_entries) - region_books_before} | "
            f"Totals: signs={len(sign_records)}, bookEntries={len(book_entries)}",
            flush=True
        )

    print(
        f"  Parse stats for '{dimension}': parsed_ok={parsed_chunks_ok}, parsed_fail={parsed_chunks_fail}",
        flush=True
    )

    return sign_records, book_entries


# ----------------------------
# Output formatting
# ----------------------------

def make_plain_text_dump(export_data: Dict[str, Any]) -> str:
    lines: List[str] = []
    lines.append(f"Lore export generated: {export_data.get('generatedAt', '')}")
    lines.append(f"Root scanned: {export_data.get('scanRoot', '')}")
    lines.append("")

    signs = export_data.get("signs", [])
    lines.append(f"SIGNS: {len(signs)}")
    lines.append("-" * 70)
    for idx, sign in enumerate(signs, start=1):
        pos = sign.get("pos", {})
        dim = sign.get("dimension", "?")
        be_id = sign.get("blockEntityId", "")
        lines.append(f"[Sign {idx}] {dim} @ ({pos.get('x')}, {pos.get('y')}, {pos.get('z')}) {be_id}")

        front = sign.get("front", [])
        back = sign.get("back", [])

        if any(s.strip() for s in front):
            lines.append("  Front:")
            for line in front:
                if line.strip():
                    lines.append(f"    {line}")
        if any(s.strip() for s in back):
            lines.append("  Back:")
            for line in back:
                if line.strip():
                    lines.append(f"    {line}")
        lines.append("")

    book_entries = export_data.get("bookEntries", [])
    player_books = export_data.get("playerBooks", [])

    total_books = 0
    for entry in book_entries:
        total_books += int(entry.get("bookCount", 0))
    for entry in player_books:
        total_books += int(entry.get("bookCount", 0))

    lines.append("")
    lines.append(f"BOOKS (total found): {total_books}")
    lines.append("-" * 70)

    def dump_book(book: Dict[str, Any], prefix: str) -> None:
        title = (book.get("title") or "").strip()
        author = (book.get("author") or "").strip()
        pages = book.get("pages") or []

        lines.append(f"{prefix}Title: {title or '(untitled)'}")
        if author:
            lines.append(f"{prefix}Author: {author}")
        lines.append(f"{prefix}Pages: {len(pages)}")

        for page_index, page in enumerate(pages, start=1):
            page_clean = (page or "").strip()
            if page_clean:
                lines.append(f"{prefix}  [Page {page_index}]")
                for pline in page_clean.split("\n"):
                    lines.append(f"{prefix}    {pline}")
        lines.append("")

    for idx, entry in enumerate(book_entries, start=1):
        pos = entry.get("pos", {})
        dim = entry.get("dimension", "?")
        be_id = entry.get("blockEntityId", "")
        entry_type = entry.get("type", "bookEntry")
        lines.append(f"[{entry_type} {idx}] {dim} @ ({pos.get('x')}, {pos.get('y')}, {pos.get('z')}) {be_id}")
        for book in entry.get("books", []):
            dump_book(book, "  ")

    for idx, entry in enumerate(player_books, start=1):
        uuid_str = entry.get("uuid", "?")
        source = entry.get("source", "?")
        lines.append(f"[PlayerBooks {idx}] uuid={uuid_str} source={source}")
        for book in entry.get("books", []):
            dump_book(book, "  ")

    return "\n".join(lines)


# ----------------------------
# Main: detect world layout
# ----------------------------

def detect_targets(scan_root: Path) -> List[DimensionScanTarget]:
    targets: List[DimensionScanTarget] = []

    # If they pointed at a world folder directly
    if (scan_root / "level.dat").exists():
        targets.append(DimensionScanTarget("overworld", scan_root))
        # In direct-world layout, nether/end are usually under DIM-1/DIM1
        dim_minus_1 = scan_root / "DIM-1"
        dim_1 = scan_root / "DIM1"
        if dim_minus_1.exists():
            targets.append(DimensionScanTarget("nether", dim_minus_1))
        if dim_1.exists():
            targets.append(DimensionScanTarget("end", dim_1))
        return targets

    # If they pointed at a server root
    if (scan_root / "world" / "level.dat").exists():
        targets.append(DimensionScanTarget("overworld", scan_root / "world"))
        if (scan_root / "world_nether").is_dir():
            targets.append(DimensionScanTarget("nether", scan_root / "world_nether"))
        if (scan_root / "world_the_end").is_dir():
            targets.append(DimensionScanTarget("end", scan_root / "world_the_end"))
        return targets

    return targets


def main() -> int:
    scan_root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(".").resolve()

    targets = detect_targets(scan_root)
    if not targets:
        print("Error: couldn't find level.dat or a Spigot root with world/level.dat.")
        print(f"Looked in: {scan_root}")
        return 2

    print(f"Scan root: {scan_root}")
    print("Targets:")
    for t in targets:
        print(f"  - {t.dimension}: {t.world_folder}")

    all_signs: List[Dict[str, Any]] = []
    all_book_entries: List[Dict[str, Any]] = []
    all_player_books: List[Dict[str, Any]] = []

    for target in targets:
        signs, book_entries = scan_world_folder(target.dimension, target.world_folder)
        all_signs.extend(signs)
        all_book_entries.extend(book_entries)

    overworld_folder = next((t.world_folder for t in targets if t.dimension == "overworld"), None)
    if overworld_folder is not None:
        print("Scanning offline playerdata...")
        all_player_books = scan_playerdata(overworld_folder)
        print(f"  Player book entries: {len(all_player_books)}")

    export_data = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "scanRoot": str(scan_root),
        "signs": all_signs,
        "bookEntries": all_book_entries,
        "playerBooks": all_player_books,
        "counts": {
            "signCount": len(all_signs),
            "bookEntryCount": len(all_book_entries),
            "playerBookEntryCount": len(all_player_books),
        },
    }

    out_json = scan_root / "lore_export.json"
    out_txt = scan_root / "lore_export.txt"

    with out_json.open("w", encoding="utf-8") as f:
        json.dump(export_data, f, ensure_ascii=False, indent=2)

    with out_txt.open("w", encoding="utf-8") as f:
        f.write(make_plain_text_dump(export_data))

    print("")
    print(f"Wrote: {out_json}")
    print(f"Wrote: {out_txt}")
    print("Done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
