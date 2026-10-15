"""입력 task를 실행해 submission.csv를 생성한다."""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any

from harness import FinalHarness


SUBMISSION_SCHEMA = "scpc.final.answer.v1"
FIXED_SLM_ID = "scpc-final-fixed-slm-local-facade"
HARNESS_NAME = "final_v2"
PROJECT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_INPUT = PROJECT_DIR / "data" / "tasks.jsonl"
DEFAULT_OUTPUT = PROJECT_DIR / "output" / "submission.csv"

REMOVED_SCORING_KEYS = ("expected_events", "answer")
ANSWER_KEYS = {
    "focal_id", "target", "control", "content_scope", "policy",
    "plan_events", "user_response", "audit_tags", "counterfactual",
}


def load_tasks(path: Path) -> list[dict[str, Any]]:
    tasks: list[dict[str, Any]] = []
    seen: set[str] = set()
    with path.open(encoding="utf-8") as stream:
        for index, line in enumerate(stream):
            if not line.strip():
                continue
            task = json.loads(line)
            if not isinstance(task, dict) or task.get("id") in (None, ""):
                raise ValueError(f"invalid task at input line {index + 1}")
            item = dict(task)
            task_id = str(item["id"])
            if task_id in seen:
                raise ValueError(f"duplicate task id: {task_id}")
            seen.add(task_id)
            item["id"] = task_id
            item["session_id"] = str(item.get("session_id") or "")
            try:
                item["turn_index"] = int(item.get("turn_index", 0))
            except (TypeError, ValueError, OverflowError) as exc:
                raise ValueError(f"invalid turn_index for task {task_id}") from exc
            tasks.append(item)
    return tasks


def participant_view(task: dict[str, Any]) -> dict[str, Any]:
    view = json.loads(json.dumps(task, ensure_ascii=False))
    for key in list(view):
        if (
            key in REMOVED_SCORING_KEYS
            or key.startswith("expected_")
            or key.endswith(("_brief", "_notes", "_rubric", "_keywords", "_tags"))
        ):
            view.pop(key, None)
    return view


def answer_one(harness: FinalHarness, task: dict[str, Any], session: dict[str, Any]) -> dict[str, Any]:
    answer = harness.answer_task(participant_view(task), session)
    if not isinstance(answer, dict) or set(answer) != ANSWER_KEYS:
        raise ValueError(f"invalid answer for task {task['id']}")
    return answer


def build_payload(tasks: list[dict[str, Any]]) -> dict[str, Any]:
    ordered = sorted(
        tasks,
        key=lambda task: (
            str(task.get("session_id", "")),
            int(task.get("turn_index", 0)),
            str(task.get("id", "")),
        ),
    )
    harness = FinalHarness()
    harness.prepare([])
    sessions: dict[str, dict[str, Any]] = {}
    answers: dict[str, dict[str, Any]] = {}
    for task in ordered:
        session = sessions.setdefault(str(task.get("session_id") or ""), {})
        answers[str(task["id"])] = answer_one(harness, task, session)
    return {
        "schema": SUBMISSION_SCHEMA,
        "meta": {
            "harness_name": HARNESS_NAME,
            "uses_external_api": False,
            "fixed_slm_policy": "local_fixed_slm_only",
            "model_id": FIXED_SLM_ID,
            "temperature": 0.0,
            "seed": 2026,
        },
        "answers": answers,
    }


def validate_payload(payload: dict[str, Any], expected_ids: set[str]) -> None:
    if payload.get("schema") != SUBMISSION_SCHEMA:
        raise ValueError("invalid submission schema")
    answers = payload.get("answers")
    if not isinstance(answers, dict) or set(answers) != expected_ids:
        raise ValueError("submission answer ids do not match input ids")
    if not all(isinstance(answer, dict) and set(answer) == ANSWER_KEYS for answer in answers.values()):
        raise ValueError("invalid answer schema")


def write_submission(payload: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["submission"])
        writer.writerow([json.dumps(payload, ensure_ascii=False, separators=(",", ":"))])


def read_submission(path: Path) -> dict[str, Any]:
    csv.field_size_limit(1 << 27)
    with path.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.reader(stream))
    if len(rows) != 2 or rows[0] != ["submission"]:
        raise ValueError("invalid submission.csv layout")
    return json.loads(rows[1][0])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs="?", default="submit", choices=("submit",))
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    tasks = load_tasks(args.input)
    expected_ids = {str(task["id"]) for task in tasks}
    payload = build_payload(tasks)
    validate_payload(payload, expected_ids)
    write_submission(payload, args.output)
    restored = read_submission(args.output)
    validate_payload(restored, expected_ids)
    if restored != payload:
        raise ValueError("submission.csv JSON round-trip mismatch")
    print(f"[submit] answers={len(expected_ids)} -> {args.output}")


if __name__ == "__main__":
    main()
