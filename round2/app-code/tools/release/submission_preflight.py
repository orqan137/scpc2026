#!/usr/bin/env python3
"""Strict SafeShare submission preflight.

This is a local release gate, not an official scorer.  It checks the seven
required artifacts, release APK properties, SOURCE.zip hygiene/rebuild parity,
sample export structure, and immutable competition-material baselines.  It
never signs an APK and never writes a participant certificate/hash into a
submission document.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import unicodedata
import zipfile


INFO_EXPECTED = {
    "file_count": 83,
    "directory_count": 32,
    "total_bytes": 5_872_292,
    "manifest_sha256": "a0d7aae12193c90d673704ad51637b65933be1aeaa66b874297413a33155cf5d",
}
MISSION_LOCK_SHA256 = "39425e9d1e7fae50e944546f9fdc5d0087c4991b079a3ffac3dcc8fd508742b3"
MISSION_PDF_SHA256 = "31f852f55d94db8cbb56f1583e84591ce55203e98e451d83a3e024eb78f4f810"
FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
REQUIRED_ARTIFACTS = (
    "APP.apk",
    "SOURCE.zip",
    "MISSION_AND_TECHNICAL_NOTE.pdf",
    "INSTALL_AND_USE_GUIDE.pdf",
    "BUILD_AND_SUBMISSION_INFO.md",
    "SAMPLE_EXPORT",
    "DEMO_VIDEO.mp4",
)
REQUIRED_SAMPLE_FILES = (
    "MISSION_LOCK.json",
    "RUNTIME_IDENTITY.json",
    "EVIDENCE_INDEX.json",
    "EXPORT_INDEX.json",
    "PUBLIC_PROBE_RESULT.json",
)
SOURCE_ALLOWED_BINARIES = {
    "app-code/candidate_app/gradle/wrapper/gradle-wrapper.jar",
}
SOURCE_FORBIDDEN_SEGMENTS = {
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
SOURCE_FORBIDDEN_SUFFIXES = {
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
SOURCE_FORBIDDEN_FILENAMES = {
    ".env",
    "google-services.json",
    "id_dsa",
    "id_ed25519",
    "id_rsa",
    "local.properties",
}
ORACLE_KEY_NORMALIZED = {
    "anchor",
    "answer",
    "assertion",
    "assertions",
    "expected",
    "expectedaction",
    "expectedstate",
    "fail",
    "gold",
    "goldanswer",
    "oracle",
    "pass",
    "prediction",
    "score",
    "targetoutput",
}
SECRET_PATTERNS = {
    "private_key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "aws_access_key": re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
    "github_token": re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    "google_api_key": re.compile(rb"\bAIza[0-9A-Za-z_-]{30,}\b"),
    "openai_style_key": re.compile(rb"\bsk-[A-Za-z0-9_-]{20,}\b"),
    "slack_token": re.compile(rb"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"),
}


@dataclass
class Check:
    check_id: str
    status: str
    summary: str
    details: dict[str, object] = field(default_factory=dict)
    rule: str = ""


class Gate:
    def __init__(self) -> None:
        self.checks: list[Check] = []

    def add(
        self,
        check_id: str,
        status: str,
        summary: str,
        details: dict[str, object] | None = None,
        rule: str = "",
    ) -> None:
        if status not in {"PASS", "BLOCKED", "WARN", "INFO"}:
            raise ValueError(f"invalid check status: {status}")
        self.checks.append(Check(check_id, status, summary, details or {}, rule))

    @property
    def overall(self) -> str:
        return "BLOCKED" if any(item.status == "BLOCKED" for item in self.checks) else "READY"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def relative_display(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return str(path.resolve())


def find_workspace_root(explicit: str | None) -> Path:
    root = Path(explicit).resolve() if explicit else Path(__file__).resolve().parents[3]
    required = (
        root / "정보" / "대회" / "candidate_release_v3" / "candidate_kit" / "04_SUBMISSION_GUIDE.md",
        root / "app-code" / "candidate_app" / "settings.gradle.kts",
        root / "관련자료" / "대회_제출_문서" / "MISSION_LOCK_orqan137.json",
    )
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise ValueError(f"invalid workspace root; missing {missing}")
    return root


def is_reparse(path: Path) -> bool:
    try:
        attrs = getattr(path.lstat(), "st_file_attributes", 0)
    except OSError:
        return True
    return path.is_symlink() or bool(attrs & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))


def compute_info_manifest(info_root: Path) -> dict[str, object]:
    files: list[tuple[str, Path]] = []
    directory_count = 0
    reparse_points: list[str] = []
    for current_root, dir_names, file_names in os.walk(info_root, followlinks=False):
        current = Path(current_root)
        kept_dirs: list[str] = []
        for name in dir_names:
            path = current / name
            directory_count += 1
            if is_reparse(path):
                reparse_points.append(path.relative_to(info_root).as_posix())
            else:
                kept_dirs.append(name)
        dir_names[:] = kept_dirs
        for name in file_names:
            path = current / name
            if is_reparse(path):
                reparse_points.append(path.relative_to(info_root).as_posix())
                continue
            relative = unicodedata.normalize("NFC", path.relative_to(info_root).as_posix())
            files.append((relative, path))
    files.sort(key=lambda item: item[0])
    manifest = b"".join(
        f"{sha256_file(path)}  {relative}\n".encode("utf-8") for relative, path in files
    )
    return {
        "file_count": len(files),
        "directory_count": directory_count,
        "total_bytes": sum(path.stat().st_size for _, path in files),
        "manifest_sha256": hashlib.sha256(manifest).hexdigest(),
        "reparse_points": reparse_points,
    }


def check_immutable_materials(gate: Gate, root: Path) -> None:
    actual = compute_info_manifest(root / "정보")
    comparable = {key: actual[key] for key in INFO_EXPECTED}
    matches = comparable == INFO_EXPECTED and not actual["reparse_points"]
    gate.add(
        "immutable_info_manifest",
        "PASS" if matches else "BLOCKED",
        "정보/ official material matches the frozen canonical manifest."
        if matches
        else "정보/ changed from the frozen canonical manifest; stop submission.",
        {**comparable, "reparse_point_count": len(actual["reparse_points"])},
        "Official competition material is read-only; local frozen baseline from 작업계획서.md.",
    )

    lock = root / "관련자료" / "대회_제출_문서" / "MISSION_LOCK_orqan137.json"
    mission_pdf = (
        root
        / "관련자료"
        / "대회_제출_문서"
        / "output"
        / "pdf"
        / "SCPC2026_R2_MISSION_orqan137.pdf"
    )
    lock_ok = lock.is_file() and sha256_file(lock) == MISSION_LOCK_SHA256
    pdf_ok = mission_pdf.is_file() and sha256_file(mission_pdf) == MISSION_PDF_SHA256
    gate.add(
        "mission_contract_baseline",
        "PASS" if lock_ok and pdf_ok else "BLOCKED",
        "Mission Lock and frozen Mission PDF are byte-identical to their baselines."
        if lock_ok and pdf_ok
        else "Mission Lock or frozen Mission PDF changed; stop submission.",
        {"mission_lock_unchanged": lock_ok, "mission_pdf_unchanged": pdf_ok},
        "04_SUBMISSION_GUIDE: keep the Dacon Mission Lock unchanged.",
    )


def check_artifact_presence(gate: Gate, submission: Path) -> None:
    statuses: dict[str, bool] = {}
    reparse_artifacts: list[str] = []
    for name in REQUIRED_ARTIFACTS:
        path = submission / name
        if path.exists() and is_reparse(path):
            reparse_artifacts.append(name)
            statuses[name] = False
        else:
            statuses[name] = path.is_dir() if name == "SAMPLE_EXPORT" else path.is_file()
    missing = [name for name, present in statuses.items() if not present]
    gate.add(
        "required_artifacts_7",
        "PASS" if not missing else "BLOCKED",
        "All seven required submission artifacts are present."
        if not missing
        else f"Required submission artifacts are missing: {', '.join(missing)}",
        {
            "present": [name for name, value in statuses.items() if value],
            "missing": missing,
            "reparse_artifacts": reparse_artifacts,
        },
        "04_SUBMISSION_GUIDE §1: seven mandatory artifacts.",
    )

    if not submission.is_dir():
        return
    actual = sorted(path.name for path in submission.iterdir())
    allowed = set(REQUIRED_ARTIFACTS)
    unexpected = [name for name in actual if name not in allowed]
    gate.add(
        "submission_root_hygiene",
        "PASS" if not unexpected else "BLOCKED",
        "Submission assembly root contains only the seven named artifacts."
        if not unexpected
        else "Remove unrelated files from the submission assembly root.",
        {"unexpected": unexpected},
        "04_SUBMISSION_GUIDE §1: one final submission set.",
    )


def check_pdf(path: Path) -> tuple[bool, str]:
    if not path.is_file() or is_reparse(path):
        return False, "missing"
    size = path.stat().st_size
    if size < 64:
        return False, "empty_or_truncated"
    with path.open("rb") as handle:
        head = handle.read(8)
        handle.seek(max(0, size - 2048))
        tail = handle.read()
    if not head.startswith(b"%PDF-") or b"%%EOF" not in tail:
        return False, "invalid_pdf_envelope"
    return True, "valid_pdf_envelope"


def check_documents(gate: Gate, submission: Path) -> None:
    results: dict[str, str] = {}
    all_ok = True
    for filename in ("MISSION_AND_TECHNICAL_NOTE.pdf", "INSTALL_AND_USE_GUIDE.pdf"):
        ok, reason = check_pdf(submission / filename)
        results[filename] = reason
        all_ok = all_ok and ok
    gate.add(
        "required_pdf_envelopes",
        "PASS" if all_ok else "BLOCKED",
        "Both required PDFs have non-empty PDF envelopes."
        if all_ok
        else "A required PDF is missing or structurally truncated.",
        results,
        "04_SUBMISSION_GUIDE §§1–3.",
    )

    content_errors: dict[str, object] = {}
    if all_ok:
        try:
            from pypdf import PdfReader

            extracted: dict[str, str] = {}
            for filename in ("MISSION_AND_TECHNICAL_NOTE.pdf", "INSTALL_AND_USE_GUIDE.pdf"):
                reader = PdfReader(str(submission / filename))
                extracted[filename] = "\n".join(page.extract_text() or "" for page in reader.pages).casefold()
            tech_groups = {
                "episodes": ("e1", "e2", "e3", "e4"),
                "core_anchors": ("core-1", "core-2", "core-3", "core-4", "core-5", "core-6"),
                "authority_delete": ("authority", "tombstone"),
                "failure_states": ("process", "network", "permission"),
                "signature_comparison": ("signature", "full", "claim-off", "comparison"),
                "mobile_counterfactual": ("mobile", "counterfactual"),
                "probe_parity": ("probe", "accessibility", "production core"),
                "runtime_accounting": ("model", "backend", "invocation", "retry"),
                "evidence_path": ("evidence",),
            }
            guide_groups = {
                "install_reset": ("install", "reset"),
                "episodes": ("e1", "e2", "e3", "e4"),
                "restart": ("kill", "relaunch"),
                "export_end": ("export", "end"),
                "probe_evidence": ("probe", "evidence"),
                "synthetic": ("synthetic",),
            }
            for filename, groups in (
                ("MISSION_AND_TECHNICAL_NOTE.pdf", tech_groups),
                ("INSTALL_AND_USE_GUIDE.pdf", guide_groups),
            ):
                text = extracted[filename]
                missing_groups = [
                    name for name, terms in groups.items() if not all(term in text for term in terms)
                ]
                if missing_groups:
                    content_errors[filename] = missing_groups
                if len(text.strip()) < 500:
                    content_errors.setdefault(filename, []).append("insufficient_extractable_text")
        except Exception as exc:  # pypdf exposes several format/encryption exception classes.
            content_errors["extractor"] = str(exc)
    else:
        content_errors["prerequisite"] = "valid PDF envelopes are required"
    gate.add(
        "required_pdf_content",
        "PASS" if not content_errors else "BLOCKED",
        "Required PDFs contain extractable first-reader, Mission, safety, comparison, Probe, and runtime topics."
        if not content_errors
        else "Required PDF content is missing, not extractable, or omits mandatory topic groups.",
        {"errors": content_errors},
        "04_SUBMISSION_GUIDE §§2–3.",
    )

    build_info = submission / "BUILD_AND_SUBMISSION_INFO.md"
    if not build_info.is_file() or is_reparse(build_info):
        gate.add(
            "build_info_no_manual_identity",
            "BLOCKED",
            "BUILD_AND_SUBMISSION_INFO.md is missing.",
            rule="04_SUBMISSION_GUIDE §§1,4–5.",
        )
        return
    try:
        text = build_info.read_text(encoding="utf-8")
    except UnicodeError as exc:
        gate.add(
            "build_info_no_manual_identity",
            "BLOCKED",
            f"BUILD_AND_SUBMISSION_INFO.md is not valid UTF-8: {exc}",
            rule="04_SUBMISSION_GUIDE §5.",
        )
        return
    placeholders = sorted(set(re.findall(r"\bFINAL_[A-Z0-9_]+\b", text)))
    copied_identity = bool(
        re.search(
            r"(?im)(?:APP\.apk|SOURCE\.zip|signing certificate|release attestation)[^\n]{0,100}\b[0-9a-f]{64}\b",
            text,
        )
    )
    required_topics = {
        "jdk17": bool(re.search(r"(?i)JDK\s*17|Java\s*17", text)),
        "sdk35": "35" in text and bool(re.search(r"(?i)SDK|build-tools", text)),
        "model_backend": bool(re.search(r"(?i)model|backend|LLM", text)),
        "source_build": "gradlew" in text and "generate.py" in text,
        "no_judge_secret": bool(re.search(r"(?i)Judge|API key|개인계정", text)),
    }
    ok = not placeholders and not copied_identity and all(required_topics.values())
    gate.add(
        "build_info_no_manual_identity",
        "PASS" if ok else "BLOCKED",
        "Build information is reproducible and leaves release identity to official tooling."
        if ok
        else "Build information has placeholders/manual release identity or omits a required reproducibility topic.",
        {
            "placeholders": placeholders,
            "manual_release_identity_detected": copied_identity,
            "required_topics": required_topics,
        },
        "04_SUBMISSION_GUIDE §§4–5: finalizer computes SHA/certificate/attestation; participant does not copy them.",
    )


def parse_mp4_duration(path: Path) -> float | None:
    if not path.is_file() or is_reparse(path) or path.stat().st_size < 16:
        return None

    def boxes(handle, start: int, end: int):
        offset = start
        while offset + 8 <= end:
            handle.seek(offset)
            header = handle.read(8)
            if len(header) != 8:
                return
            size, kind = struct.unpack(">I4s", header)
            header_size = 8
            if size == 1:
                extended = handle.read(8)
                if len(extended) != 8:
                    return
                size = struct.unpack(">Q", extended)[0]
                header_size = 16
            elif size == 0:
                size = end - offset
            if size < header_size or offset + size > end:
                return
            yield offset, size, header_size, kind
            offset += size

    with path.open("rb") as handle:
        file_size = path.stat().st_size
        has_ftyp = False
        duration_seconds: float | None = None
        for offset, size, header_size, kind in boxes(handle, 0, file_size):
            if kind == b"ftyp":
                has_ftyp = True
            if kind != b"moov":
                continue
            for child_offset, child_size, child_header, child_kind in boxes(
                handle, offset + header_size, offset + size
            ):
                if child_kind != b"mvhd":
                    continue
                payload = child_offset + child_header
                handle.seek(payload)
                version_flags = handle.read(4)
                if len(version_flags) != 4:
                    return None
                version = version_flags[0]
                if version == 0:
                    raw = handle.read(16)
                    if len(raw) != 16:
                        return None
                    _, _, timescale, duration = struct.unpack(">IIII", raw)
                elif version == 1:
                    raw = handle.read(28)
                    if len(raw) != 28:
                        return None
                    _, _, timescale, duration = struct.unpack(">QQIQ", raw)
                else:
                    return None
                if timescale <= 0:
                    return None
                duration_seconds = duration / timescale
        return duration_seconds if has_ftyp else None


def check_video(gate: Gate, submission: Path) -> None:
    video = submission / "DEMO_VIDEO.mp4"
    duration = parse_mp4_duration(video)
    ok = duration is not None and 0 < duration <= 180.0
    gate.add(
        "demo_video_duration",
        "PASS" if ok else "BLOCKED",
        "Demo video duration is at most three minutes."
        if ok
        else "Demo video is missing, unreadable as MP4, zero-length, or longer than three minutes.",
        {"duration_seconds": round(duration, 3) if duration is not None else None},
        "04_SUBMISSION_GUIDE §1: DEMO_VIDEO.mp4 must be at most three minutes.",
    )


def locate_android_tools(android_sdk: str | None) -> tuple[Path, Path, Path]:
    if android_sdk:
        sdk = Path(android_sdk).resolve()
    elif os.environ.get("ANDROID_SDK_ROOT"):
        sdk = Path(os.environ["ANDROID_SDK_ROOT"]).resolve()
    elif os.environ.get("ANDROID_HOME"):
        sdk = Path(os.environ["ANDROID_HOME"]).resolve()
    elif os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        sdk = Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"
    else:
        sdk = Path.home() / "Android" / "Sdk"
    suffix = ".bat" if os.name == "nt" else ""
    return (
        sdk,
        sdk / "cmdline-tools" / "latest" / "bin" / f"apkanalyzer{suffix}",
        sdk / "build-tools" / "35.0.0" / f"apksigner{suffix}",
    )


def tool_command(tool: Path, args: list[str]) -> list[str]:
    if os.name == "nt" and tool.suffix.lower() in {".bat", ".cmd"}:
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(tool), *args]
    return [str(tool), *args]


def run_command(command: list[str], cwd: Path | None = None, timeout: int = 120, env=None):
    return subprocess.run(
        command,
        cwd=str(cwd) if cwd else None,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        check=False,
    )


def normalize_oracle_key(key: str) -> str:
    return re.sub(r"[-_]", "", key).lower()


def find_oracle_keys(value, path: str = "$") -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{path}.{key}"
            if normalize_oracle_key(str(key)) in ORACLE_KEY_NORMALIZED:
                found.append(child_path)
            found.extend(find_oracle_keys(child, child_path))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            found.extend(find_oracle_keys(child, f"{path}[{index}]"))
    return found


def scan_apk_zip(apk: Path) -> dict[str, object]:
    result: dict[str, object] = {
        "zip_valid": False,
        "duplicate_entries": [],
        "input_asset_count": 0,
        "synthetic_pdf_count": 0,
        "oracle_name_leaks": [],
        "oracle_key_leaks": [],
        "native_abis": [],
        "arm64_compatible": False,
        "asset_names": [],
    }
    try:
        with zipfile.ZipFile(apk) as archive:
            infos = archive.infolist()
            names = [item.filename for item in infos]
            duplicates = sorted({name for name in names if names.count(name) > 1})
            result["duplicate_entries"] = duplicates
            result["zip_valid"] = archive.testzip() is None and not duplicates
            assets = sorted(name for name in names if name.startswith("assets/") and not name.endswith("/"))
            result["asset_names"] = assets
            input_assets = [name for name in assets if re.fullmatch(r"assets/SS-KO-\d{4}\.input\.json", name)]
            pdf_assets = [name for name in assets if re.fullmatch(r"assets/synthetic/.+\.pdf", name)]
            result["input_asset_count"] = len(input_assets)
            result["synthetic_pdf_count"] = len(pdf_assets)
            forbidden_name_tokens = (
                "oracle",
                "expected",
                "score",
                "answer",
                "gold",
                "anchor",
                "prediction",
                "assertion",
            )
            result["oracle_name_leaks"] = [
                name
                for name in assets
                if any(token in name.lower() for token in forbidden_name_tokens)
                or bool(re.search(r"(?:^|[._/\-])(pass|fail)(?:$|[._/\-])", name.lower()))
            ]
            key_leaks: list[str] = []
            for name in assets:
                if not name.lower().endswith(".json"):
                    continue
                try:
                    document = json.loads(archive.read(name).decode("utf-8"))
                except (UnicodeError, json.JSONDecodeError) as exc:
                    key_leaks.append(f"{name}:invalid_json:{exc}")
                    continue
                key_leaks.extend(f"{name}:{item}" for item in find_oracle_keys(document))
            result["oracle_key_leaks"] = key_leaks
            abis = sorted(
                {
                    PurePosixPath(name).parts[1]
                    for name in names
                    if len(PurePosixPath(name).parts) == 3
                    and PurePosixPath(name).parts[0] == "lib"
                    and name.endswith(".so")
                }
            )
            result["native_abis"] = abis
            result["arm64_compatible"] = not abis or "arm64-v8a" in abis
    except (OSError, zipfile.BadZipFile):
        pass
    return result


def check_apk(gate: Gate, submission: Path, apkanalyzer: Path, apksigner: Path) -> dict[str, object] | None:
    apk = submission / "APP.apk"
    if not apk.is_file() or is_reparse(apk):
        gate.add(
            "apk_release_identity",
            "BLOCKED",
            "APP.apk is absent; participant release signing has not been supplied.",
            rule="03_TECHNICAL_AND_SAFETY_RULES §1.",
        )
        return None
    missing_tools = [str(path) for path in (apkanalyzer, apksigner) if not path.is_file()]
    if missing_tools:
        gate.add(
            "apk_release_identity",
            "BLOCKED",
            "Android SDK cmdline-tools latest and build-tools 35.0.0 are required for release inspection.",
            {"missing_tools": missing_tools},
            "04_SUBMISSION_GUIDE §4.",
        )
        return None

    # Android's Windows aapt subprocess corrupts non-ASCII APK paths for some
    # manifest queries. Inspect an identical byte copy from an ASCII temp path.
    with tempfile.TemporaryDirectory(prefix="scpc-apk-inspect-") as temporary:
        inspection_apk = Path(temporary) / "APP.apk"
        shutil.copyfile(apk, inspection_apk)
        commands = {
            "application_id": ["manifest", "application-id", str(inspection_apk)],
            "version_name": ["manifest", "version-name", str(inspection_apk)],
            "version_code": ["manifest", "version-code", str(inspection_apk)],
            "min_sdk": ["manifest", "min-sdk", str(inspection_apk)],
            "target_sdk": ["manifest", "target-sdk", str(inspection_apk)],
            "debuggable": ["manifest", "debuggable", str(inspection_apk)],
            "permissions": ["manifest", "permissions", str(inspection_apk)],
        }
        manifest_values: dict[str, str] = {}
        analyzer_ok = True
        for name, args in commands.items():
            completed = run_command(tool_command(apkanalyzer, args))
            if completed.returncode != 0:
                analyzer_ok = False
            manifest_values[name] = completed.stdout.strip()

        signing = run_command(
            tool_command(
                apksigner,
                ["verify", "--verbose", "--print-certs", str(inspection_apk)],
            )
        )
    signer_output = signing.stdout
    signer_count_match = re.search(r"Number of signers:\s*(\d+)", signer_output)
    signer_count = int(signer_count_match.group(1)) if signer_count_match else 0
    signer_dns = re.findall(r"Signer #\d+ certificate DN:\s*(.+)", signer_output)
    debug_signer = any("android debug" in value.lower() for value in signer_dns)
    v2_or_newer = any(
        re.search(rf"Verified using v{version}(?:\.1)? scheme .*:\s*true", signer_output)
        for version in (2, 3, 4)
    )
    signed_ok = signing.returncode == 0 and signer_count == 1 and not debug_signer and v2_or_newer

    permissions = [line.strip() for line in manifest_values["permissions"].splitlines() if line.strip()]
    apk_zip = scan_apk_zip(apk)
    manifest_ok = (
        analyzer_ok
        and manifest_values["application_id"] == "org.scpc.r2.safeshare"
        and manifest_values["version_name"] == "1.0-safeshare"
        and manifest_values["version_code"] == "1"
        and manifest_values["target_sdk"] == "35"
        and manifest_values["debuggable"].lower() == "false"
        and not permissions
    )
    boundary_ok = (
        bool(apk_zip["zip_valid"])
        and bool(apk_zip["arm64_compatible"])
        and apk_zip["input_asset_count"] == 14
        and apk_zip["synthetic_pdf_count"] == 3
        and not apk_zip["oracle_name_leaks"]
        and not apk_zip["oracle_key_leaks"]
    )

    gate.add(
        "apk_release_identity",
        "PASS" if signed_ok and manifest_ok else "BLOCKED",
        "APP.apk is single-signed with a non-debug certificate, non-debuggable, target SDK 35, and permission-free."
        if signed_ok and manifest_ok
        else "APP.apk is unsigned/debug-signed, debuggable, mis-targeted, mispackaged, or requests a permission.",
        {
            "package": manifest_values["application_id"],
            "version_name": manifest_values["version_name"],
            "version_code": manifest_values["version_code"],
            "min_sdk": manifest_values["min_sdk"],
            "target_sdk": manifest_values["target_sdk"],
            "debuggable": manifest_values["debuggable"],
            "permissions": permissions,
            "signature_verified": signing.returncode == 0,
            "single_signer": signer_count == 1,
            "debug_certificate_detected": debug_signer,
            "v2_or_newer_signature": v2_or_newer,
        },
        "03_TECHNICAL_AND_SAFETY_RULES §1; 04_SUBMISSION_GUIDE §4.",
    )
    gate.add(
        "apk_corpus_boundary",
        "PASS" if boundary_ok else "BLOCKED",
        "APK contains the 14 input-only scenarios and three synthetic PDFs with no oracle/score/expected keys."
        if boundary_ok
        else "APK corpus/ABI boundary is invalid or test knowledge leaked into assets.",
        {
            key: apk_zip[key]
            for key in (
                "zip_valid",
                "input_asset_count",
                "synthetic_pdf_count",
                "oracle_name_leaks",
                "oracle_key_leaks",
                "native_abis",
                "arm64_compatible",
            )
        },
        "03_TECHNICAL_AND_SAFETY_RULES §§1,6,8.",
    )
    return apk_zip


def validate_zip_member(name: str) -> str | None:
    pure = PurePosixPath(name)
    if pure.is_absolute() or not pure.parts or ".." in pure.parts or "\\" in name:
        return "unsafe_path"
    if unicodedata.normalize("NFC", name) != name:
        return "non_nfc_path"
    parts = tuple(part.lower() for part in pure.parts)
    if any(part in SOURCE_FORBIDDEN_SEGMENTS for part in parts):
        return "generated_or_cache_path"
    filename = pure.name.lower()
    if filename in SOURCE_FORBIDDEN_FILENAMES or filename.startswith(".env."):
        return "secret_filename"
    if pure.suffix.lower() in SOURCE_FORBIDDEN_SUFFIXES:
        return "forbidden_suffix"
    if pure.suffix.lower() in {".jar", ".aar"} and name not in SOURCE_ALLOWED_BINARIES:
        return "unapproved_binary"
    if ".oracle." in filename or filename.endswith("oracle.json"):
        return "generated_oracle"
    return None


def expected_source_zip_mode(name: str) -> int:
    pure = PurePosixPath(name)
    return 0o755 if pure.name == "gradlew" or pure.suffix.lower() == ".sh" else 0o644


def scan_secret_bytes(payload: bytes) -> list[str]:
    return [name for name, pattern in SECRET_PATTERNS.items() if pattern.search(payload)]


def inspect_source_zip(path: Path) -> tuple[bool, dict[str, object], dict[str, bytes]]:
    details: dict[str, object] = {
        "member_count": 0,
        "bad_members": {},
        "duplicate_members": [],
        "fixed_timestamps": False,
        "fixed_permissions": False,
        "sorted_members": False,
        "mission_adapter_count": 0,
        "secret_pattern_hits": {},
        "required_members_missing": [],
        "generated_oracle_artifacts": 0,
    }
    payloads: dict[str, bytes] = {}
    if not path.is_file() or is_reparse(path):
        return False, details, payloads
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            names = [item.filename for item in infos]
            details["member_count"] = len(names)
            details["duplicate_members"] = sorted({name for name in names if names.count(name) > 1})
            details["fixed_timestamps"] = all(item.date_time == FIXED_ZIP_TIME for item in infos)
            details["fixed_permissions"] = all(
                ((item.external_attr >> 16) & 0o777) == expected_source_zip_mode(item.filename)
                for item in infos
            )
            details["sorted_members"] = names == sorted(names)
            bad: dict[str, str] = {}
            secrets: dict[str, list[str]] = {}
            for info in infos:
                reason = validate_zip_member(info.filename)
                mode = (info.external_attr >> 16) & 0o170000
                if mode == stat.S_IFLNK:
                    reason = "symlink"
                if reason:
                    bad[info.filename] = reason
                    continue
                data = archive.read(info.filename)
                payloads[info.filename] = data
                hits = scan_secret_bytes(data)
                if hits:
                    secrets[info.filename] = hits
            details["bad_members"] = bad
            details["secret_pattern_hits"] = secrets
            adapters = [name for name in names if PurePosixPath(name).name == "MISSION_ADAPTER.json"]
            details["mission_adapter_count"] = len(adapters)
            details["generated_oracle_artifacts"] = sum(
                1 for name in names if ".oracle." in PurePosixPath(name).name.lower()
            )
            required = {
                "app-code/candidate_app/app/src/main/assets/MISSION_ADAPTER.json",
                "app-code/candidate_app/app/src/main/AndroidManifest.xml",
                "app-code/candidate_app/app/build.gradle.kts",
                "app-code/candidate_app/app/src/main/java/org/scpc/r2/safeshare/SafeShareCore.kt",
                "app-code/candidate_app/app/src/main/java/org/scpc/r2/safeshare/SafeShareProbeAdapter.kt",
                "app-code/candidate_app/app/src/main/java/org/scpc/r2/safeshare/SafeShareProbeBridge.kt",
                "app-code/candidate_app/app/src/test/java/org/scpc/r2/safeshare/SafeShareProbeBridgeTest.kt",
                "app-code/candidate_app/app/src/test/java/org/scpc/r2/safeshare/ScenarioOracleEvaluator.kt",
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
                "관련자료/대회_제출_문서/제출_준비/THIRD_PARTY_NOTICES.md",
            }
            details["required_members_missing"] = sorted(required.difference(names))
            ok = (
                archive.testzip() is None
                and not details["duplicate_members"]
                and bool(details["fixed_timestamps"])
                and bool(details["fixed_permissions"])
                and bool(details["sorted_members"])
                and not bad
                and not secrets
                and adapters == ["app-code/candidate_app/app/src/main/assets/MISSION_ADAPTER.json"]
                and not details["required_members_missing"]
                and details["generated_oracle_artifacts"] == 0
            )
            return ok, details, payloads
    except (OSError, zipfile.BadZipFile):
        details["bad_members"] = {"SOURCE.zip": "invalid_zip"}
        return False, details, payloads


def safe_extract_source(source_zip: Path, destination: Path) -> None:
    with zipfile.ZipFile(source_zip) as archive:
        for info in archive.infolist():
            if validate_zip_member(info.filename):
                raise ValueError(f"unsafe SOURCE.zip member: {info.filename}")
            target = destination.joinpath(*PurePosixPath(info.filename).parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(info.filename))


def zip_payload_map(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as archive:
        result: dict[str, bytes] = {}
        for info in archive.infolist():
            upper = info.filename.upper()
            if upper == "META-INF/MANIFEST.MF" or upper.endswith((".SF", ".RSA", ".DSA", ".EC")):
                continue
            result[info.filename] = archive.read(info.filename)
        return result


def compare_payload_maps(first: dict[str, bytes], second: dict[str, bytes]) -> list[str]:
    names = sorted(set(first).union(second))
    return [name for name in names if first.get(name) != second.get(name)]


def source_generation_and_rebuild(
    source_zip: Path,
    apk: Path,
    apk_asset_names: list[str],
    android_sdk: Path,
    rebuild: bool,
    official_starter_aar: Path,
) -> tuple[dict[str, object], dict[str, object]]:
    generation: dict[str, object] = {
        "generator_exit_code": None,
        "generated_asset_count": 0,
        "asset_mismatches": [],
        "official_starter_available": False,
    }
    rebuild_result: dict[str, object] = {
        "requested": rebuild,
        "gradle_exit_code": None,
        "payload_mismatches": [],
    }
    # Gradle/Windows may retain a checksum lock for a few milliseconds after a
    # successful single-use daemon exits.  A cleanup race must not turn a valid
    # parity result into an internal preflight error.
    with tempfile.TemporaryDirectory(
        prefix="safeshare-source-preflight-", ignore_cleanup_errors=True
    ) as raw:
        extracted = Path(raw)
        safe_extract_source(source_zip, extracted)
        generator = extracted / "app-code" / "tools" / "synthetic-data" / "generate.py"
        env = os.environ.copy()
        env["PYTHONHASHSEED"] = "0"
        env["TZ"] = "UTC"
        generated = run_command([sys.executable, str(generator)], cwd=extracted, timeout=180, env=env)
        generation["generator_exit_code"] = generated.returncode
        if generated.returncode != 0:
            generation["generator_output_tail"] = generated.stdout[-2000:]
            return generation, rebuild_result

        generated_root = extracted / "app-code" / "tools" / "synthetic-data" / "generated" / "app-input"
        generated_files = sorted(path for path in generated_root.rglob("*") if path.is_file())
        generation["generated_asset_count"] = len(generated_files)
        expected_asset_payloads: dict[str, bytes] = {}
        if not official_starter_aar.is_file():
            generation["error"] = "official Kit starter AAR is missing"
            return generation, rebuild_result
        generation["official_starter_available"] = True
        with zipfile.ZipFile(official_starter_aar) as aar:
            for name in aar.namelist():
                if name.startswith("assets/") and not name.endswith("/"):
                    expected_asset_payloads[name] = aar.read(name)
        main_assets = (
            extracted / "app-code" / "candidate_app" / "app" / "src" / "main" / "assets"
        )
        if main_assets.is_dir():
            for path in sorted(item for item in main_assets.rglob("*") if item.is_file()):
                expected_asset_payloads[f"assets/{path.relative_to(main_assets).as_posix()}"] = path.read_bytes()
        for path in generated_files:
            expected_asset_payloads[f"assets/{path.relative_to(generated_root).as_posix()}"] = path.read_bytes()
        with zipfile.ZipFile(apk) as archive:
            apk_names = set(archive.namelist())
            mismatches: list[str] = []
            expected_names = set(expected_asset_payloads)
            for name, expected_payload in expected_asset_payloads.items():
                if name not in apk_names or archive.read(name) != expected_payload:
                    mismatches.append(name)
            extra = sorted(set(apk_asset_names).difference(expected_names))
            missing = sorted(expected_names.difference(apk_names))
            generation["asset_mismatches"] = sorted(set(mismatches + extra + missing))

        if not rebuild:
            return generation, rebuild_result

        candidate = extracted / "app-code" / "candidate_app"
        staged_starter = candidate / "app" / "libs" / "scpc-probe-starter-3.0.0-draft.aar"
        staged_starter.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(official_starter_aar, staged_starter)
        gradle = candidate / ("gradlew.bat" if os.name == "nt" else "gradlew")
        if not gradle.is_file():
            rebuild_result["gradle_exit_code"] = -1
            rebuild_result["error"] = f"missing wrapper for current OS: {gradle.name}"
            return generation, rebuild_result
        build_env = env.copy()
        build_env["ANDROID_HOME"] = str(android_sdk)
        build_env["ANDROID_SDK_ROOT"] = str(android_sdk)
        command = tool_command(
            gradle,
            [
                "--no-daemon",
                "clean",
                "testDebugUnitTest",
                "assembleRelease",
                "verifyReleaseCorpusBoundary",
            ],
        )
        completed = run_command(command, cwd=candidate, timeout=900, env=build_env)
        rebuild_result["gradle_exit_code"] = completed.returncode
        if completed.returncode != 0:
            rebuild_result["gradle_output_tail"] = completed.stdout[-4000:]
            return generation, rebuild_result
        rebuilt_apk = candidate / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk"
        if not rebuilt_apk.is_file():
            rebuild_result["error"] = "Gradle completed without app-release-unsigned.apk"
            return generation, rebuild_result
        mismatches = compare_payload_maps(zip_payload_map(apk), zip_payload_map(rebuilt_apk))
        rebuild_result["payload_mismatches"] = mismatches[:50]
        rebuild_result["rebuilt_payload_entry_count"] = len(zip_payload_map(rebuilt_apk))
        return generation, rebuild_result


def check_source(
    gate: Gate,
    submission: Path,
    apk_zip: dict[str, object] | None,
    android_sdk: Path,
    rebuild: bool,
    workspace_root: Path,
) -> None:
    source = submission / "SOURCE.zip"
    ok, details, payloads = inspect_source_zip(source)
    gate.add(
        "source_zip_hygiene",
        "PASS" if ok else "BLOCKED",
        "SOURCE.zip is deterministic, source-only, secret-free, and contains required build/integration/license material."
        if ok
        else "SOURCE.zip is missing or contains cache/build/oracle/secret/unapproved binary material.",
        details,
        "04_SUBMISSION_GUIDE §5.",
    )
    adapter_name = "app-code/candidate_app/app/src/main/assets/MISSION_ADAPTER.json"
    adapter_errors: list[str] = []
    adapter_payload = payloads.get(adapter_name)
    if adapter_payload is None:
        adapter_errors.append("source adapter is missing")
    else:
        try:
            adapter_document = json.loads(adapter_payload.decode("utf-8"))
            schema = json.loads(
                (
                    workspace_root
                    / "정보"
                    / "대회"
                    / "candidate_release_v3"
                    / "candidate_kit"
                    / "MISSION_ADAPTER.schema.json"
                ).read_text(encoding="utf-8")
            )
            try:
                import jsonschema

                validator_class = jsonschema.validators.validator_for(schema)
                validator_class.check_schema(schema)
                validator = validator_class(schema)
                for error in sorted(validator.iter_errors(adapter_document), key=lambda item: list(item.path)):
                    pointer = "/" + "/".join(str(part) for part in error.path)
                    adapter_errors.append(f"{pointer}: {error.message}")
            except ImportError:
                adapter_errors.append("jsonschema is not installed; install synthetic-data/requirements.txt")
            source_names = set(payloads)
            for declared in adapter_document.get("source_paths", []):
                archive_path = f"app-code/candidate_app/{declared}"
                if archive_path not in source_names:
                    adapter_errors.append(f"declared source path missing from SOURCE.zip: {declared}")
        except (UnicodeError, json.JSONDecodeError, OSError, ValueError) as exc:
            adapter_errors.append(str(exc))
    gate.add(
        "mission_adapter_contract",
        "PASS" if not adapter_errors else "BLOCKED",
        "The sole source MISSION_ADAPTER.json satisfies the official schema and all declared source paths exist."
        if not adapter_errors
        else "MISSION_ADAPTER.json is missing, schema-invalid, or references absent source.",
        {"errors": adapter_errors[:50]},
        "04_SUBMISSION_GUIDE §§1,1.1; 08_PROBE_MODE_CONTRACT.",
    )
    if not ok or apk_zip is None or not (submission / "APP.apk").is_file():
        gate.add(
            "source_apk_asset_parity",
            "BLOCKED",
            "SOURCE→APK synthetic asset parity cannot run until valid APP.apk and SOURCE.zip exist.",
            rule="03_TECHNICAL_AND_SAFETY_RULES §§1,8; 04_SUBMISSION_GUIDE §5.",
        )
        gate.add(
            "source_clean_rebuild_parity",
            "BLOCKED",
            "Clean source rebuild parity cannot run until valid APP.apk and SOURCE.zip exist.",
            {"requested": rebuild},
            "03_TECHNICAL_AND_SAFETY_RULES §1.",
        )
        return

    build_gradle = payloads.get("app-code/candidate_app/app/build.gradle.kts", b"").decode(
        "utf-8", errors="replace"
    )
    source_config_ok = (
        bool(re.search(r'applicationId\s*=\s*"org\.scpc\.r2\.safeshare"', build_gradle))
        and bool(re.search(r"compileSdk\s*=\s*35\b", build_gradle))
        and bool(re.search(r"targetSdk\s*=\s*35\b", build_gradle))
        and bool(re.search(r"versionCode\s*=\s*1\b", build_gradle))
        and bool(re.search(r'versionName\s*=\s*"1\.0-safeshare"', build_gradle))
        and bool(
            re.search(
                r'getByName\("main"\)\.assets\.srcDir\(\s*"\.\./\.\./tools/synthetic-data/generated/app-input"\s*\)',
                build_gradle,
            )
        )
    )
    generation, rebuild_result = source_generation_and_rebuild(
        source,
        submission / "APP.apk",
        list(apk_zip.get("asset_names", [])),
        android_sdk,
        rebuild,
        workspace_root
        / "정보"
        / "대회"
        / "candidate_release_v3"
        / "probe"
        / "scpc-probe-starter-3.0.0-draft.aar",
    )
    generation_ok = (
        source_config_ok
        and generation["official_starter_available"] is True
        and generation["generator_exit_code"] == 0
        and generation["generated_asset_count"] == 17
        and not generation["asset_mismatches"]
    )
    gate.add(
        "source_apk_asset_parity",
        "PASS" if generation_ok else "BLOCKED",
        "SOURCE.zip regenerates exactly the input-only assets packaged in APP.apk."
        if generation_ok
        else "SOURCE.zip generator/config does not reproduce APP.apk assets.",
        {"source_config_ok": source_config_ok, **generation},
        "03_TECHNICAL_AND_SAFETY_RULES §§1,8; 04_SUBMISSION_GUIDE §5.",
    )

    rebuild_ok = (
        rebuild
        and rebuild_result["gradle_exit_code"] == 0
        and not rebuild_result["payload_mismatches"]
    )
    gate.add(
        "source_clean_rebuild_parity",
        "PASS" if rebuild_ok else "BLOCKED",
        "Clean SOURCE.zip build has the same unsigned APK payload as APP.apk."
        if rebuild_ok
        else (
            "Clean rebuild was not requested; rerun final preflight with --rebuild-source."
            if not rebuild
            else "Clean SOURCE.zip build failed or its APK payload differs from APP.apk."
        ),
        rebuild_result,
        "03_TECHNICAL_AND_SAFETY_RULES §1: signed APK and SOURCE.zip must match.",
    )


def check_sample_export(gate: Gate, submission: Path, root: Path, android_sdk: Path) -> None:
    sample = submission / "SAMPLE_EXPORT"
    if not sample.is_dir() or is_reparse(sample):
        gate.add(
            "sample_export_structure",
            "BLOCKED",
            "SAMPLE_EXPORT/ is missing; run the official finalizer after the final Runner run.",
            rule="04_SUBMISSION_GUIDE §§1.1,4.",
        )
        gate.add(
            "official_sample_validator",
            "BLOCKED",
            "Official SAMPLE_EXPORT validator cannot run before finalization.",
            rule="04_SUBMISSION_GUIDE §4.",
        )
        return
    files = [path for path in sample.rglob("*") if path.is_file()]
    reparse = [relative_display(path, sample) for path in sample.rglob("*") if is_reparse(path)]
    total = sum(path.stat().st_size for path in files)
    missing = [name for name in REQUIRED_SAMPLE_FILES if not (sample / name).is_file()]
    json_errors: dict[str, str] = {}
    for path in files:
        if path.suffix.lower() != ".json":
            continue
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            json_errors[relative_display(path, sample)] = str(exc)
    expected_lock = root / "관련자료" / "대회_제출_문서" / "MISSION_LOCK_orqan137.json"
    sample_lock = sample / "MISSION_LOCK.json"
    lock_matches = sample_lock.is_file() and sample_lock.read_bytes() == expected_lock.read_bytes()
    evidence = sample / "evidence"
    evidence_files = [path for path in evidence.rglob("*") if path.is_file()] if evidence.is_dir() else []
    structure_ok = (
        not missing
        and total <= 20_971_520
        and not reparse
        and not json_errors
        and lock_matches
        and bool(evidence_files)
    )
    gate.add(
        "sample_export_structure",
        "PASS" if structure_ok else "BLOCKED",
        "SAMPLE_EXPORT has the five common JSON files, evidence, unchanged Mission Lock, and is at most 20 MiB."
        if structure_ok
        else "SAMPLE_EXPORT structure/size/JSON/Mission Lock/evidence is invalid.",
        {
            "missing": missing,
            "total_bytes": total,
            "reparse_points": reparse,
            "json_errors": json_errors,
            "mission_lock_matches": lock_matches,
            "evidence_file_count": len(evidence_files),
        },
        "04_SUBMISSION_GUIDE §§1.1,4.",
    )

    official_validator = (
        root
        / "정보"
        / "대회"
        / "candidate_release_v3"
        / "candidate_kit"
        / "VALIDATE_SAMPLE_EXPORT.py"
    )
    validator = (
        root / "app-code" / "tools" / "validate_sample_export_windows.py"
        if os.name == "nt"
        else official_validator
    )
    public_input: Path | None = None
    result_path = sample / "PUBLIC_PROBE_RESULT.json"
    if result_path.is_file():
        try:
            expected_input_sha256 = json.loads(
                result_path.read_text(encoding="utf-8")
            ).get("probe_input_sha256")
            verification = root / "app-code" / "candidate_app" / "verification"
            if expected_input_sha256 and verification.is_dir():
                public_input = next(
                    (
                        candidate
                        for candidate in verification.rglob("PROBE_INPUT.json")
                        if sha256_file(candidate) == expected_input_sha256
                    ),
                    None,
                )
        except (OSError, UnicodeError, json.JSONDecodeError):
            public_input = None
    env = os.environ.copy()
    env["ANDROID_HOME"] = str(android_sdk)
    env["ANDROID_SDK_ROOT"] = str(android_sdk)
    command = [
        sys.executable,
        str(validator),
        str(sample),
        "--app-apk",
        str(submission / "APP.apk"),
        "--source-zip",
        str(submission / "SOURCE.zip"),
        "--schema-root",
        str(official_validator.parent),
        "--sdk-root",
        str(android_sdk),
    ]
    if public_input is not None:
        command.extend(["--public-input", str(public_input)])
    completed = run_command(command, cwd=submission, timeout=300, env=env)
    official_ok = completed.returncode == 0
    gate.add(
        "official_sample_validator",
        "PASS" if official_ok else "BLOCKED",
        "Official VALIDATE_SAMPLE_EXPORT.py accepted the finalized sample export."
        if official_ok
        else "Official VALIDATE_SAMPLE_EXPORT.py did not accept SAMPLE_EXPORT.",
        {"exit_code": completed.returncode, "output_tail": completed.stdout[-3000:]},
        "04_SUBMISSION_GUIDE §4.",
    )


def render_summary(report: dict[str, object]) -> str:
    lines = [
        "# SafeShare submission preflight",
        "",
        f"- Overall: **{report['overall_status']}**",
        f"- Generated (UTC): `{report['generated_at_utc']}`",
        "- 성격: 로컬 탈락 방지 gate이며 공식 채점이나 release identity 생성 도구가 아님",
        "",
        "| Check | Status | Summary |",
        "|---|---:|---|",
    ]
    for item in report["checks"]:
        summary = str(item["summary"]).replace("|", "\\|").replace("\n", " ")
        lines.append(f"| `{item['check_id']}` | **{item['status']}** | {summary} |")
    blockers = [item for item in report["checks"] if item["status"] == "BLOCKED"]
    lines.extend(["", "## Blocking items", ""])
    if blockers:
        lines.extend(f"- `{item['check_id']}` — {item['summary']}" for item in blockers)
    else:
        lines.append("- 없음. 이 결과만으로 공식 적격/점수를 보장하지는 않는다.")
    lines.extend(
        [
            "",
            "## Release identity rule",
            "",
            "APK/SOURCE SHA-256, signing certificate fingerprint, runtime identity, release attestation과 release ID는 이 요약에서 제출 문서로 복사하지 않는다. 공식 `FINALIZE_SAMPLE_EXPORT.py`가 실제 파일에서 자동 계산한다.",
            "",
        ]
    )
    return "\n".join(lines)


def write_reports(out_dir: Path, report: dict[str, object]) -> tuple[Path, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / "preflight-report.json"
    md_path = out_dir / "preflight-summary.md"
    json_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    md_path.write_text(render_summary(report), encoding="utf-8", newline="\n")
    return json_path, md_path


def make_box(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I4s", len(payload) + 8, kind) + payload


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="safeshare-preflight-selftest-") as raw:
        base = Path(raw)
        info = base / "정보"
        (info / "나").mkdir(parents=True)
        (info / "a.txt").write_bytes(b"a")
        (info / "나" / "b.txt").write_bytes(b"b")
        result = compute_info_manifest(info)
        if result["file_count"] != 2 or result["directory_count"] != 1 or result["total_bytes"] != 2:
            raise AssertionError("canonical information manifest self-test failed")

        mvhd = bytes([0, 0, 0, 0]) + struct.pack(">IIII", 0, 0, 1000, 180000)
        video = base / "demo.mp4"
        video.write_bytes(make_box(b"ftyp", b"isom\x00\x00\x00\x00") + make_box(b"moov", make_box(b"mvhd", mvhd)))
        if parse_mp4_duration(video) != 180.0:
            raise AssertionError("MP4 duration self-test failed")

        fake_apk = base / "fake.apk"
        with zipfile.ZipFile(fake_apk, "w") as archive:
            archive.writestr("assets/example.input.json", '{"oracle":"leak"}')
        scan = scan_apk_zip(fake_apk)
        if not scan["oracle_key_leaks"]:
            raise AssertionError("APK oracle-key self-test failed")
        if validate_zip_member("app-code/candidate_app/build/app.apk") is None:
            raise AssertionError("SOURCE forbidden-path self-test failed")
        synthetic_private_key_marker = b"-----BEGIN " + b"PRIVATE KEY-----"
        if not scan_secret_bytes(synthetic_private_key_marker):
            raise AssertionError("secret scanner self-test failed")
    print("SELF_TEST_OK submission_preflight")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run strict SafeShare submission gates and write machine-readable JSON plus Markdown summary. "
            "Exit 0 means READY under these known checks; exit 2 means BLOCKED. The script never signs APKs."
        )
    )
    parser.add_argument("submission_dir", nargs="?", help="directory containing exactly the seven artifacts")
    parser.add_argument("--workspace-root", help="SafeShare workspace root (auto-detected by default)")
    parser.add_argument("--out-dir", help="report output directory (default: workspace tmp/submission-preflight)")
    parser.add_argument("--android-sdk", help="Android SDK root; defaults to ANDROID_SDK_ROOT/ANDROID_HOME")
    parser.add_argument(
        "--rebuild-source",
        action="store_true",
        help="extract SOURCE.zip, clean test/build it, and compare unsigned APK payload with APP.apk (required for READY)",
    )
    parser.add_argument("--self-test", action="store_true", help="run parser/scanner policy self-tests")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.self_test:
        self_test()
        return 0
    if not args.submission_dir:
        print("error: submission_dir is required unless --self-test is used", file=sys.stderr)
        return 2
    try:
        root = find_workspace_root(args.workspace_root)
        submission = Path(args.submission_dir).resolve()
        out_dir = Path(args.out_dir).resolve() if args.out_dir else root / "tmp" / "submission-preflight"
        try:
            out_dir.relative_to(submission)
        except ValueError:
            pass
        else:
            raise ValueError("report output must be outside the seven-artifact submission directory")
        sdk, apkanalyzer, apksigner = locate_android_tools(args.android_sdk)

        gate = Gate()
        check_immutable_materials(gate, root)
        check_artifact_presence(gate, submission)
        check_documents(gate, submission)
        check_video(gate, submission)
        apk_zip = check_apk(gate, submission, apkanalyzer, apksigner)
        check_source(gate, submission, apk_zip, sdk, args.rebuild_source, root)
        check_sample_export(gate, submission, root, sdk)

        report = {
            "schema_version": "safeshare.submission-preflight.v1",
            "artifact_kind": "local_submission_preflight",
            "generated_at_utc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "overall_status": gate.overall,
            "official_score_or_receipt": False,
            "submission_directory": str(submission),
            "source_rebuild_requested": args.rebuild_source,
            "checks": [asdict(item) for item in gate.checks],
        }
        json_path, md_path = write_reports(out_dir, report)
        print(f"PREFLIGHT_{gate.overall} checks={len(gate.checks)} blocked={sum(c.status == 'BLOCKED' for c in gate.checks)}")
        print(f"JSON_REPORT={json_path}")
        print(f"SUMMARY={md_path}")
        return 0 if gate.overall == "READY" else 2
    except (OSError, ValueError, subprocess.SubprocessError, zipfile.BadZipFile) as exc:
        print(f"PREFLIGHT_ERROR: {exc}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
