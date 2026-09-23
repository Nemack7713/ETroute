#!/usr/bin/env python3
"""Deterministically compare two ETroute APK inventory records.

The comparator is read-only. It reports factual changes between a baseline APK
inventory and a later APK inventory without modifying either APK or assigning a
trust score.

Typical use:
    python tools/compare_apk_inventory.py before.json after.json -o comparison.json

If an inventory document contains more than one APK, select the record with
--before-file and/or --after-file.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Iterable, Sequence

VERSION = "1.0.0"
SCHEMA_VERSION = 1


class ComparisonError(RuntimeError):
    pass


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ComparisonError(f"cannot read {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise ComparisonError(f"invalid JSON in {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ComparisonError(f"inventory root must be an object: {path}")
    return value


def select_apk_record(
    document: dict[str, Any],
    *,
    file_name: str | None,
    label: str,
) -> dict[str, Any]:
    if "apks" in document:
        records = document.get("apks")
        if not isinstance(records, list):
            raise ComparisonError(f"{label} inventory 'apks' must be an array")
    else:
        records = [document]

    records = [record for record in records if isinstance(record, dict)]
    if file_name is not None:
        matches = [record for record in records if record.get("file") == file_name]
        if len(matches) != 1:
            raise ComparisonError(
                f"{label} inventory selector {file_name!r} matched {len(matches)} records"
            )
        return matches[0]

    if len(records) != 1:
        raise ComparisonError(
            f"{label} inventory contains {len(records)} APK records; "
            f"use --{label}-file"
        )
    return records[0]


def _sorted_strings(values: Iterable[Any]) -> list[str]:
    return sorted(str(value) for value in values)


def _set_diff(before: Iterable[Any], after: Iterable[Any]) -> dict[str, list[str]]:
    before_set = {str(value) for value in before}
    after_set = {str(value) for value in after}
    return {
        "added": sorted(after_set - before_set),
        "removed": sorted(before_set - after_set),
        "unchanged": sorted(before_set & after_set),
    }


def _scalar_diff(before: Any, after: Any) -> dict[str, Any]:
    return {
        "before": before,
        "after": after,
        "changed": before != after,
    }


def _changed_fields(before: dict[str, Any], after: dict[str, Any], fields: Sequence[str]) -> dict[str, Any]:
    changes: dict[str, Any] = {}
    for field in fields:
        if before.get(field) != after.get(field):
            changes[field] = {
                "before": before.get(field),
                "after": after.get(field),
            }
    return changes


def _index_by(items: Iterable[dict[str, Any]], key: str) -> dict[str, dict[str, Any]]:
    indexed: dict[str, dict[str, Any]] = {}
    for item in items:
        value = item.get(key)
        if value is None:
            continue
        indexed[str(value)] = item
    return indexed


def compare_named_records(
    before_items: Iterable[dict[str, Any]],
    after_items: Iterable[dict[str, Any]],
    *,
    key: str,
    fields: Sequence[str],
) -> dict[str, Any]:
    before = _index_by(before_items, key)
    after = _index_by(after_items, key)
    before_keys = set(before)
    after_keys = set(after)

    changed = []
    unchanged = []
    for name in sorted(before_keys & after_keys):
        field_changes = _changed_fields(before[name], after[name], fields)
        if field_changes:
            changed.append({
                key: name,
                "fields": field_changes,
            })
        else:
            unchanged.append(name)

    return {
        "added": sorted(after_keys - before_keys),
        "removed": sorted(before_keys - after_keys),
        "changed": changed,
        "unchanged": unchanged,
    }


def compare_manifest_identity(
    before: dict[str, Any],
    after: dict[str, Any],
) -> dict[str, Any]:
    before_structure = before.get("structure") or {}
    after_structure = after.get("structure") or {}
    before_badging = before.get("aapt2_badging") or {}
    after_badging = after.get("aapt2_badging") or {}

    identity_fields = [
        "package",
        "version_code",
        "version_name",
        "compile_sdk_version",
        "min_sdk",
        "target_sdk",
        "max_sdk",
        "debuggable",
        "launchable_activity",
    ]

    permissions = _set_diff(
        before_badging.get("permissions") or [],
        after_badging.get("permissions") or [],
    )
    native_code = _set_diff(
        before_badging.get("native_code") or [],
        after_badging.get("native_code") or [],
    )

    return {
        "manifestPresence": _scalar_diff(
            before_structure.get("manifest") is not None,
            after_structure.get("manifest") is not None,
        ),
        "manifestSha256": _scalar_diff(
            (before_structure.get("manifest") or {}).get("sha256"),
            (after_structure.get("manifest") or {}).get("sha256"),
        ),
        "badgingAvailable": {
            "before": bool(before_badging),
            "after": bool(after_badging),
        },
        "fields": _changed_fields(before_badging, after_badging, identity_fields),
        "permissions": permissions,
        "nativeCode": native_code,
    }


def compare_dex(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    fields = ["sha256", "size_bytes", "compression"]
    return compare_named_records(
        (before.get("structure") or {}).get("dex") or [],
        (after.get("structure") or {}).get("dex") or [],
        key="name",
        fields=fields,
    )


def compare_native_libraries(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    before_items = (before.get("structure") or {}).get("native_libraries") or []
    after_items = (after.get("structure") or {}).get("native_libraries") or []

    before_index = _index_by(before_items, "path")
    after_index = _index_by(after_items, "path")
    before_keys = set(before_index)
    after_keys = set(after_index)

    changed = []
    unchanged = []
    for path in sorted(before_keys & after_keys):
        left = before_index[path]
        right = after_index[path]
        top_level = _changed_fields(
            left,
            right,
            [
                "abi",
                "sha256",
                "size_bytes",
                "compression",
                "data_offset",
                "aligned_4k",
                "aligned_16k",
            ],
        )
        elf_changes = _changed_fields(
            left.get("elf") or {},
            right.get("elf") or {},
            [
                "valid",
                "class",
                "endianness",
                "type",
                "machine",
                "machine_name",
                "interpreter",
                "dt_needed",
                "rpath",
                "runpath",
                "pie",
                "gnu_relro",
                "bind_now",
                "nx_stack",
            ],
        )
        if top_level or elf_changes:
            changed.append({
                "path": path,
                "fields": top_level,
                "elf": elf_changes,
            })
        else:
            unchanged.append(path)

    return {
        "added": sorted(after_keys - before_keys),
        "removed": sorted(before_keys - after_keys),
        "changed": changed,
        "unchanged": unchanged,
        "abis": _set_diff(
            [item.get("abi") for item in before_items if item.get("abi")],
            [item.get("abi") for item in after_items if item.get("abi")],
        ),
    }


def compare_resources(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    left = (before.get("structure") or {}).get("resources_arsc")
    right = (after.get("structure") or {}).get("resources_arsc")
    if left is None and right is None:
        return {
            "presence": _scalar_diff(False, False),
            "fields": {},
        }

    return {
        "presence": _scalar_diff(left is not None, right is not None),
        "fields": _changed_fields(
            left or {},
            right or {},
            [
                "sha256",
                "size_bytes",
                "compression",
                "data_offset",
                "aligned_4k",
                "aligned_16k",
            ],
        ),
    }


def compare_signing(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    left = before.get("structure") or {}
    right = after.get("structure") or {}
    left_block = left.get("signing_block") or {}
    right_block = right.get("signing_block") or {}

    return {
        "v1Entries": _set_diff(
            left.get("v1_signature_entries") or [],
            right.get("v1_signature_entries") or [],
        ),
        "apkSigningBlockPresent": _scalar_diff(
            bool(left_block.get("present")),
            bool(right_block.get("present")),
        ),
        "apkSigningBlockSize": _scalar_diff(
            left_block.get("size_bytes"),
            right_block.get("size_bytes"),
        ),
    }


def compare_zip_structure(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    left = before.get("structure") or {}
    right = after.get("structure") or {}

    return {
        "validZip": _scalar_diff(
            bool(left.get("valid_zip")),
            bool(right.get("valid_zip")),
        ),
        "entryCount": _scalar_diff(
            left.get("entry_count"),
            right.get("entry_count"),
        ),
        "compressedBytes": _scalar_diff(
            left.get("compressed_bytes"),
            right.get("compressed_bytes"),
        ),
        "uncompressedBytes": _scalar_diff(
            left.get("uncompressed_bytes"),
            right.get("uncompressed_bytes"),
        ),
        "unsafePaths": _set_diff(
            left.get("unsafe_paths") or [],
            right.get("unsafe_paths") or [],
        ),
        "duplicateEntries": _set_diff(
            left.get("duplicate_entries") or [],
            right.get("duplicate_entries") or [],
        ),
    }


def build_alerts(
    before: dict[str, Any],
    after: dict[str, Any],
    manifest: dict[str, Any],
    native: dict[str, Any],
    zip_structure: dict[str, Any],
) -> list[dict[str, Any]]:
    alerts: list[dict[str, Any]] = []

    package_change = manifest["fields"].get("package")
    if package_change:
        alerts.append({
            "code": "PACKAGE_ID_CHANGED",
            "before": package_change["before"],
            "after": package_change["after"],
        })

    if manifest["manifestPresence"]["before"] and not manifest["manifestPresence"]["after"]:
        alerts.append({"code": "ANDROID_MANIFEST_REMOVED"})

    for path in zip_structure["unsafePaths"]["added"]:
        alerts.append({
            "code": "UNSAFE_ZIP_PATH_INTRODUCED",
            "path": path,
        })
    for path in zip_structure["duplicateEntries"]["added"]:
        alerts.append({
            "code": "DUPLICATE_ZIP_ENTRY_INTRODUCED",
            "path": path,
        })

    before_native = _index_by(
        (before.get("structure") or {}).get("native_libraries") or [],
        "path",
    )
    after_native = _index_by(
        (after.get("structure") or {}).get("native_libraries") or [],
        "path",
    )
    for path in sorted(set(before_native) & set(after_native)):
        before_elf = before_native[path].get("elf") or {}
        after_elf = after_native[path].get("elf") or {}

        if before_elf.get("valid") is True and after_elf.get("valid") is not True:
            alerts.append({
                "code": "NATIVE_ELF_VALIDITY_LOST",
                "path": path,
            })

        for field, code in [
            ("pie", "NATIVE_PIE_LOST"),
            ("gnu_relro", "NATIVE_RELRO_LOST"),
            ("bind_now", "NATIVE_BIND_NOW_LOST"),
            ("nx_stack", "NATIVE_NX_STACK_LOST"),
        ]:
            if before_elf.get(field) is True and after_elf.get(field) is False:
                alerts.append({
                    "code": code,
                    "path": path,
                })

    return alerts


def _section_changed(section: dict[str, Any]) -> bool:
    if "changed" in section and isinstance(section["changed"], bool):
        return section["changed"]

    for key in ("added", "removed", "changed"):
        value = section.get(key)
        if isinstance(value, list) and value:
            return True

    for key, value in section.items():
        if key in {"unchanged", "before", "after"}:
            continue
        if isinstance(value, dict) and _section_changed(value):
            return True

    return False


def compare_apk_records(
    before: dict[str, Any],
    after: dict[str, Any],
) -> dict[str, Any]:
    manifest = compare_manifest_identity(before, after)
    dex = compare_dex(before, after)
    native = compare_native_libraries(before, after)
    resources = compare_resources(before, after)
    signing = compare_signing(before, after)
    zip_structure = compare_zip_structure(before, after)

    sections = {
        "manifestIdentity": manifest,
        "dex": dex,
        "nativeLibraries": native,
        "resourcesArsc": resources,
        "signing": signing,
        "zipStructure": zip_structure,
    }
    changed_sections = [
        name for name, section in sections.items()
        if _section_changed(section)
    ]

    alerts = build_alerts(
        before,
        after,
        manifest,
        native,
        zip_structure,
    )

    return {
        "schemaVersion": SCHEMA_VERSION,
        "comparatorVersion": VERSION,
        "before": {
            "file": before.get("file"),
            "path": before.get("path"),
            "sha256": before.get("sha256"),
            "sizeBytes": before.get("size_bytes"),
        },
        "after": {
            "file": after.get("file"),
            "path": after.get("path"),
            "sha256": after.get("sha256"),
            "sizeBytes": after.get("size_bytes"),
        },
        "wholeApk": {
            "sha256": _scalar_diff(
                before.get("sha256"),
                after.get("sha256"),
            ),
            "sizeBytes": _scalar_diff(
                before.get("size_bytes"),
                after.get("size_bytes"),
            ),
        },
        **sections,
        "alerts": alerts,
        "summary": {
            "changed": bool(changed_sections) or before.get("sha256") != after.get("sha256"),
            "changedSections": sorted(changed_sections),
            "alertCount": len(alerts),
            "trustDecisionMade": False,
        },
    }


def write_comparison(comparison: dict[str, Any], output: str | None) -> None:
    rendered = json.dumps(
        comparison,
        indent=2,
        sort_keys=True,
        ensure_ascii=False,
    ) + "\n"
    if output:
        Path(output).write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("before", type=Path, help="baseline inventory JSON")
    parser.add_argument("after", type=Path, help="later inventory JSON")
    parser.add_argument("--before-file", help="APK filename selector in baseline inventory")
    parser.add_argument("--after-file", help="APK filename selector in later inventory")
    parser.add_argument("-o", "--output", help="comparison JSON output; default stdout")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    try:
        before_doc = load_json(args.before)
        after_doc = load_json(args.after)
        before = select_apk_record(
            before_doc,
            file_name=args.before_file,
            label="before",
        )
        after = select_apk_record(
            after_doc,
            file_name=args.after_file,
            label="after",
        )
        comparison = compare_apk_records(before, after)
        write_comparison(comparison, args.output)
    except ComparisonError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
