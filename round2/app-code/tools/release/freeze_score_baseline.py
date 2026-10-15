#!/usr/bin/env python3
"""Freeze a reproducible local self-score baseline without claiming an official score.

The script hashes the signed-APK public Runner result, the self-score input/result,
public evidence exports, and the independently stored scenario audit.  It is a
comparison anchor for later candidate revisions, not a competition submission
artifact and not an official evaluation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise SystemExit(f"expected JSON object: {path}")
    return value


def require_file(path: Path) -> Path:
    if not path.is_file():
        raise SystemExit(f"required baseline evidence is missing: {path}")
    return path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", type=Path, required=True)
    parser.add_argument("--scenario-audit-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    release_dir = args.release_dir.resolve()
    audit_dir = args.scenario_audit_dir.resolve()
    output = args.output.resolve()
    if output.exists():
        raise SystemExit(f"refusing to overwrite an existing baseline: {output}")

    self_score = require_file(release_dir / "SELF_SCORE_FINAL.json")
    result = require_file(release_dir / "SELF_SCORE_RESULT_FINAL.json")
    probe = require_file(release_dir / "PUBLIC_RUN_FINAL" / "PROBE_RESULT.json")
    evidence_dir = release_dir / "EVIDENCE_FINAL"
    if not evidence_dir.is_dir():
        raise SystemExit(f"evidence directory is missing: {evidence_dir}")

    result_json = load_json(result)
    if result_json.get("official_score") is not False:
        raise SystemExit("baseline may only freeze a non-official self-score result")
    if result_json.get("q_maximum") != 80:
        raise SystemExit("unexpected self-score maximum; expected 80")

    probe_json = load_json(probe)
    evidence_ids = probe_json.get("evidence_ids")
    if not isinstance(evidence_ids, list) or not evidence_ids:
        raise SystemExit("probe result has no evidence_ids")
    evidence_files = {path.stem: path for path in evidence_dir.glob("*.json")}
    missing_evidence = sorted(set(evidence_ids) - set(evidence_files))
    if missing_evidence:
        raise SystemExit(f"public Runner evidence export missing: {missing_evidence}")

    audit_files = sorted(audit_dir.glob("SS-KO-*.observed.json"))
    if len(audit_files) != 12:
        raise SystemExit(
            f"expected 12 scenario audit traces in {audit_dir}, found {len(audit_files)}"
        )

    output.parent.mkdir(parents=True, exist_ok=True)
    baseline = {
        "schema_version": "safeshare.score-baseline.v1",
        "artifact_kind": "local_score_comparison_baseline",
        "official_score": False,
        "purpose": "candidate revision comparison only; not a hidden or official result",
        "release_dir_name": release_dir.name,
        "run_id": probe_json.get("run_id"),
        "self_score": {
            "q_total": result_json.get("q_total"),
            "q_maximum": result_json.get("q_maximum"),
            "anchors": result_json.get("anchors"),
            "readiness_band": result_json.get("readiness_band"),
        },
        "files": {
            "self_score_input": {"path": self_score.name, "sha256": sha256_file(self_score)},
            "self_score_result": {"path": result.name, "sha256": sha256_file(result)},
            "public_probe_result": {
                "path": "PUBLIC_RUN_FINAL/PROBE_RESULT.json",
                "sha256": sha256_file(probe),
            },
            "runner_evidence": [
                {"id": evidence_id, "sha256": sha256_file(evidence_files[evidence_id])}
                for evidence_id in sorted(evidence_ids)
            ],
            "scenario_audit": [
                {"path": path.name, "sha256": sha256_file(path)} for path in audit_files
            ],
        },
    }
    output.write_text(
        json.dumps(baseline, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        "FROZEN local self-score baseline: "
        f"Q={result_json.get('q_total')}/{result_json.get('q_maximum')}, "
        f"runner_evidence={len(evidence_ids)}, scenario_audit={len(audit_files)}"
    )
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
