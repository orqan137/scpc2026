#!/usr/bin/env python3
"""Create the reproducible SafeShare SOURCE.zip submission artifact.

The archive contains authored source/build/config/license material only.  In
particular, generated synthetic inputs and generated test oracles are rebuilt
from the included generator and are not copied into SOURCE.zip.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import tempfile
import unicodedata
import zipfile


FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
ALLOWED_BINARY_PATHS = {
    "app-code/candidate_app/gradle/wrapper/gradle-wrapper.jar",
}
OFFICIAL_KIT_EXTERNAL_PATHS = {
    "app-code/candidate_app/app/libs/scpc-probe-starter-3.0.0-draft.aar",
}
FORBIDDEN_SEGMENTS = {
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    "__pycache__",
    "build",
    "generated",
    "tmp",
    "verification",
}
FORBIDDEN_SUFFIXES = {
    ".apk",
    ".aab",
    ".class",
    ".dex",
    ".dll",
    ".dylib",
    ".exe",
    ".jks",
    ".keystore",
    ".p12",
    ".pfx",
    ".pem",
    ".pyc",
    ".so",
    ".o",
    ".obj",
}
FORBIDDEN_FILENAMES = {
    ".env",
    "google-services.json",
    "id_dsa",
    "id_ed25519",
    "id_rsa",
    "local.properties",
}


def find_workspace_root(explicit: str | None) -> Path:
    if explicit:
        root = Path(explicit).resolve()
    else:
        root = Path(__file__).resolve().parents[3]
    required = (
        root / "app-code" / "candidate_app" / "settings.gradle.kts",
        root / "app-code" / "tools" / "synthetic-data" / "generate.py",
        root / "관련자료" / "연구" / "데이터" / "SOURCE_PROVENANCE.lock.json",
    )
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise ValueError(f"workspace root is invalid; missing: {missing}")
    return root


def is_reparse_point(path: Path) -> bool:
    try:
        attrs = getattr(path.lstat(), "st_file_attributes", 0)
    except OSError:
        return True
    return bool(attrs & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))


def archive_name(root: Path, path: Path) -> str:
    relative = path.relative_to(root).as_posix()
    normalized = unicodedata.normalize("NFC", relative)
    pure = PurePosixPath(normalized)
    if pure.is_absolute() or not pure.parts or ".." in pure.parts:
        raise ValueError(f"unsafe source path: {relative}")
    return normalized


def validate_source_path(name: str) -> None:
    pure = PurePosixPath(name)
    lower_parts = tuple(part.lower() for part in pure.parts)
    if any(part in FORBIDDEN_SEGMENTS for part in lower_parts):
        raise ValueError(f"generated/cache path cannot enter SOURCE.zip: {name}")
    filename = pure.name.lower()
    if filename in FORBIDDEN_FILENAMES or filename.startswith(".env."):
        raise ValueError(f"secret-bearing filename cannot enter SOURCE.zip: {name}")
    suffix = pure.suffix.lower()
    if suffix in FORBIDDEN_SUFFIXES:
        raise ValueError(f"forbidden binary/generated file cannot enter SOURCE.zip: {name}")
    if suffix in {".jar", ".aar"} and name not in ALLOWED_BINARY_PATHS:
        raise ValueError(f"unapproved binary dependency cannot enter SOURCE.zip: {name}")
    if ".oracle." in filename or filename.endswith("oracle.json"):
        raise ValueError(f"generated oracle artifact cannot enter SOURCE.zip: {name}")


def archive_mode(name: str) -> int:
    pure = PurePosixPath(name)
    return 0o755 if pure.name == "gradlew" or pure.suffix.lower() == ".sh" else 0o644


def iter_tree(root: Path, subtree: Path):
    for current_root, dir_names, file_names in os.walk(subtree, followlinks=False):
        current = Path(current_root)
        kept_dirs: list[str] = []
        for name in sorted(dir_names):
            candidate = current / name
            if name.lower() in FORBIDDEN_SEGMENTS:
                continue
            if candidate.is_symlink() or is_reparse_point(candidate):
                raise ValueError(f"symlink/reparse point is not allowed: {candidate}")
            kept_dirs.append(name)
        dir_names[:] = kept_dirs
        for name in sorted(file_names):
            candidate = current / name
            if candidate.is_symlink() or is_reparse_point(candidate):
                raise ValueError(f"symlink/reparse point is not allowed: {candidate}")
            archive = archive_name(root, candidate)
            if archive in OFFICIAL_KIT_EXTERNAL_PATHS:
                continue
            validate_source_path(archive)
            yield archive, candidate


def collect_entries(root: Path) -> list[tuple[str, Path]]:
    candidate_starter = (
        root
        / "app-code"
        / "candidate_app"
        / "app"
        / "libs"
        / "scpc-probe-starter-3.0.0-draft.aar"
    )
    official_starter = (
        root
        / "정보"
        / "대회"
        / "candidate_release_v3"
        / "probe"
        / "scpc-probe-starter-3.0.0-draft.aar"
    )
    if candidate_starter.is_file():
        if not official_starter.is_file():
            raise ValueError("candidate app has a starter AAR but the official Kit copy is unavailable")
        if hashlib.sha256(candidate_starter.read_bytes()).digest() != hashlib.sha256(
            official_starter.read_bytes()
        ).digest():
            raise ValueError("candidate starter AAR differs from the immutable official Kit copy")

    entries: list[tuple[str, Path]] = []
    entries.extend(iter_tree(root, root / "app-code" / "candidate_app"))
    entries.extend(iter_tree(root, root / "app-code" / "tools" / "synthetic-data"))
    entries.extend(iter_tree(root, root / "app-code" / "tools" / "release"))

    data_dir = root / "관련자료" / "연구" / "데이터"
    for filename in (
        "DATASET_DECISION.md",
        "NOTICE_DATASETS.md",
        "SAFESHARE_SYNTHETIC_DATA_CARD.md",
        "SOURCE_PROVENANCE.lock.json",
    ):
        path = data_dir / filename
        if not path.is_file():
            raise ValueError(f"required data/license record is missing: {path}")
        name = archive_name(root, path)
        validate_source_path(name)
        entries.append((name, path))

    notice = (
        root
        / "관련자료"
        / "대회_제출_문서"
        / "제출_준비"
        / "THIRD_PARTY_NOTICES.md"
    )
    if not notice.is_file():
        raise ValueError(f"required dependency notice is missing: {notice}")
    entries.append((archive_name(root, notice), notice))

    source_readme = (
        root
        / "관련자료"
        / "대회_제출_문서"
        / "제출_준비"
        / "SOURCE_BUILD_README.md"
    )
    if not source_readme.is_file():
        source_readme = root / "SOURCE_BUILD_README.md"
    if not source_readme.is_file():
        raise ValueError("required SOURCE_BUILD_README.md is missing")
    validate_source_path("SOURCE_BUILD_README.md")
    entries.append(("SOURCE_BUILD_README.md", source_readme))

    entries.sort(key=lambda item: item[0])
    names = [name for name, _ in entries]
    if len(names) != len(set(names)):
        raise ValueError("NFC archive path collision detected")

    required = {
        "app-code/candidate_app/app/src/main/assets/MISSION_ADAPTER.json",
        "app-code/candidate_app/app/src/main/AndroidManifest.xml",
        "app-code/candidate_app/app/src/main/java/org/scpc/r2/safeshare/SafeShareProbeAdapter.kt",
        "app-code/candidate_app/app/src/main/java/org/scpc/r2/safeshare/SafeShareProbeBridge.kt",
        "app-code/candidate_app/app/src/test/java/org/scpc/r2/safeshare/SafeShareProbeBridgeTest.kt",
        "app-code/candidate_app/app/build.gradle.kts",
        "app-code/candidate_app/gradle/wrapper/gradle-wrapper.jar",
        "app-code/candidate_app/gradlew",
        "app-code/candidate_app/gradlew.bat",
        "app-code/tools/synthetic-data/generate.py",
        "app-code/tools/synthetic-data/validate.py",
        "app-code/tools/synthetic-data/schemas/app-input.schema.json",
        "app-code/tools/synthetic-data/schemas/test-oracle.schema.json",
        "app-code/tools/release/make_source_zip.py",
        "app-code/tools/release/submission_preflight.py",
        "SOURCE_BUILD_README.md",
        "관련자료/연구/데이터/SOURCE_PROVENANCE.lock.json",
    }
    missing = sorted(required.difference(names))
    if missing:
        raise ValueError(f"SOURCE.zip selection is incomplete: {missing}")

    adapters = [name for name in names if PurePosixPath(name).name == "MISSION_ADAPTER.json"]
    if adapters != ["app-code/candidate_app/app/src/main/assets/MISSION_ADAPTER.json"]:
        raise ValueError(f"SOURCE.zip must contain exactly one source MISSION_ADAPTER.json: {adapters}")
    return entries


def zip_bytes(entries: list[tuple[str, Path]]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(
        buffer,
        "w",
        compression=zipfile.ZIP_STORED,
        strict_timestamps=True,
    ) as archive:
        for name, path in entries:
            info = zipfile.ZipInfo(name, FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_STORED
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | archive_mode(name)) << 16
            info.flag_bits |= 0x800
            archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_STORED)
    return buffer.getvalue()


def inspect_created_archive(payload: bytes, expected_names: list[str]) -> None:
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        if names != expected_names:
            raise ValueError("archive member order changed or member is missing")
        if len(names) != len(set(names)):
            raise ValueError("archive has duplicate member names")
        for info in infos:
            validate_source_path(info.filename)
            if info.date_time != FIXED_ZIP_TIME:
                raise ValueError(f"non-deterministic ZIP timestamp: {info.filename}")
            if info.is_dir():
                raise ValueError(f"unexpected directory entry: {info.filename}")
            mode = (info.external_attr >> 16) & 0o777
            if mode != archive_mode(info.filename):
                raise ValueError(f"non-deterministic ZIP permission: {info.filename}")
        if archive.testzip() is not None:
            raise ValueError("archive CRC verification failed")


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="safeshare-source-selftest-") as raw:
        base = Path(raw)
        first = base / "가.txt"
        second = base / "z.txt"
        first.write_text("alpha\n", encoding="utf-8", newline="\n")
        second.write_text("beta\n", encoding="utf-8", newline="\n")
        entries = [("fixture/z.txt", second), ("fixture/가.txt", first)]
        entries.sort(key=lambda item: item[0])
        a = zip_bytes(entries)
        b = zip_bytes(entries)
        if hashlib.sha256(a).digest() != hashlib.sha256(b).digest():
            raise AssertionError("deterministic archive self-test failed")
        inspect_created_archive(a, [name for name, _ in entries])
        try:
            validate_source_path("app-code/candidate_app/build/output.apk")
        except ValueError:
            pass
        else:
            raise AssertionError("forbidden path self-test failed")
    print("SELF_TEST_OK make_source_zip")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Create deterministic SOURCE.zip from SafeShare authored source. "
            "Generated app-input/test-oracle files, caches, APKs, verification output, "
            "keystores and secrets are excluded."
        )
    )
    parser.add_argument("output", nargs="?", help="output SOURCE.zip path")
    parser.add_argument("--workspace-root", help="SafeShare workspace root (auto-detected by default)")
    parser.add_argument("--force", action="store_true", help="atomically replace an existing output ZIP")
    parser.add_argument("--list", action="store_true", help="print selected member paths without writing")
    parser.add_argument("--self-test", action="store_true", help="run deterministic ZIP/path-policy self-test")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.self_test:
        self_test()
        return 0
    if not args.output and not args.list:
        print("error: output is required unless --list or --self-test is used", file=sys.stderr)
        return 2
    try:
        root = find_workspace_root(args.workspace_root)
        entries = collect_entries(root)
        if args.list:
            for name, _ in entries:
                print(name)
            if not args.output:
                return 0

        output = Path(args.output).resolve()
        if output.name != "SOURCE.zip":
            raise ValueError("output filename must be exactly SOURCE.zip")
        selected_trees = (
            root / "app-code" / "candidate_app",
            root / "app-code" / "tools" / "synthetic-data",
            root / "app-code" / "tools" / "release",
        )
        for selected in selected_trees:
            try:
                output.relative_to(selected.resolve())
            except ValueError:
                continue
            raise ValueError(f"output must be outside selected source trees: {selected}")
        if output.exists() and not args.force:
            raise ValueError(f"output already exists; use --force after confirming target: {output}")
        output.parent.mkdir(parents=True, exist_ok=True)

        first = zip_bytes(entries)
        second = zip_bytes(entries)
        if hashlib.sha256(first).digest() != hashlib.sha256(second).digest():
            raise ValueError("two in-process archive builds differ; refusing non-deterministic output")
        inspect_created_archive(first, [name for name, _ in entries])

        temporary = output.with_name(f".{output.name}.tmp")
        if temporary.exists():
            temporary.unlink()
        try:
            temporary.write_bytes(first)
            os.replace(temporary, output)
        finally:
            if temporary.exists():
                temporary.unlink()
        print(f"SOURCE_ZIP_READY path={output} files={len(entries)} bytes={len(first)} deterministic=true")
        print("Generated app-input/test-oracle and the official Kit starter AAR are intentionally excluded.")
        print("Run generate.py and stage the Kit-provided starter AAR before build/test.")
        return 0
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"SOURCE_ZIP_BLOCKED: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
