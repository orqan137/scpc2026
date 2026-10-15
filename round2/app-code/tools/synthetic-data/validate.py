#!/usr/bin/env python3
"""Validate SafeShare synthetic app inputs, oracles, and referenced fixtures.

The JSON Schema files are always parsed as strict UTF-8 JSON. When ``jsonschema``
is installed, Draft 2020-12 is also applied to every document. The explicit
competition/security checks remain mandatory and cover cross-file invariants that
JSON Schema cannot express.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata
from urllib.parse import urlparse
from typing import Any, Iterable

try:
    from jsonschema import Draft202012Validator
except ModuleNotFoundError:  # The explicit contract checks remain available.
    Draft202012Validator = None  # type: ignore[assignment,misc]

try:
    from pypdf import PdfReader
except ModuleNotFoundError:  # Header/EOF checks remain available.
    PdfReader = None  # type: ignore[assignment,misc]


INPUT_SCHEMA_VERSION = "safeshare.input.v1"
ORACLE_SCHEMA_VERSION = "safeshare.oracle.v1"

FORBIDDEN_INPUT_KEYS = {
    "expected",
    "expected_action",
    "expected_state",
    "oracle",
    "gold",
    "gold_answer",
    "answer",
    "pass",
    "fail",
    "score",
    "assertion",
    "assertions",
    "target_output",
    "prediction",
    "anchor",
}

AGE_BANDS = {"19_29", "30_44", "45_59", "60_PLUS"}
REGION_BANDS = {"CAPITAL", "METRO", "NON_METRO"}
WORK_CONTEXTS = {
    "OFFICE_PROFESSIONAL",
    "PUBLIC_SERVICE",
    "FIELD_PRODUCTION",
    "INDEPENDENT",
}
REGISTERS = {"FORMAL_HAPSYO", "POLITE_HAEYO", "CASUAL_HAE"}
DIRECTNESS = {"DIRECT", "INDIRECT"}
NOISE_LEVELS = {"CLEAN", "LIGHT"}
LINK_ACCESS = {
    "NOT_APPLICABLE",
    "UNKNOWN",
    "PROJECT_ONLY",
    "EXTERNAL_ALLOWED",
    "EXPIRED",
}
PHASES = ["E1_LEARN", "E2_REUSE", "E3_EXCEPTION", "E4_RECOVER"]
EPISODE_IDS = ["E1", "E2", "E3", "E4"]
ACTIONS = {
    "REVIEW_INTAKE",
    "CONFIRM_CONTEXT",
    "EVALUATE_CANDIDATES",
    "PROPOSE_DRAFT",
    "ADVANCE_SESSION",
    "APPLY_CONTEXT_EVENT",
    "REQUEST_DECISION",
    "CHECKPOINT_IF_HELD",
    "RELAUNCH_IF_CHECKPOINTED",
    "CONFIRM_PENDING_CONTEXT",
    "RESUME_DRAFT",
}
ORACLE_OPS = {"EQ", "NE", "GT", "ABSENT", "COUNT_EQ", "SAME_AS", "CHANGED_FROM"}

SCENARIO_ID_PATTERN = re.compile(r"^SS-KO-[0-9]{4}$")
DERIVATION_PATTERN = re.compile(r"^DRV-[0-9]{4}$")
PERSONA_KEY_PATTERN = re.compile(r"^persona-[0-9a-f]{12}$")
ROOM_ID_PATTERN = re.compile(r"^room-[a-z0-9-]{3,80}$")
PURPOSE_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{2,63}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
PAIR_ID_PATTERN = re.compile(r"^PAIR-[A-Z0-9-]{3,80}$")
POLICY_REVISION_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{2,80}$")

RRN_PATTERN = re.compile(r"(?<!\d)\d{6}-?[1-4]\d{6}(?!\d)")
PHONE_PATTERN = re.compile(r"(?<!\d)01[016789]-?\d{3,4}-?\d{4}(?!\d)")
EMAIL_PATTERN = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
CARD_PATTERN = re.compile(r"(?<!\d)(?:\d[ -]?){13,19}(?!\d)")
SECRET_PATTERN = re.compile(
    r"(?i)\b(?:api[_-]?key|access[_-]?token|secret|password)\s*[:=]\s*([^\s,;]+)"
)
URL_PATTERN = re.compile(r"https?://[^\s\"'<>]+", re.IGNORECASE)
PDF_ASSET_REF_PATTERN = re.compile(r"^synthetic/[a-z0-9][a-z0-9._/-]*\.pdf$")


class DuplicateKeyError(ValueError):
    pass


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def normalize_key(key: str) -> str:
    snake = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", key.strip())
    return re.sub(r"[-\s]+", "_", snake.lower())


def canonical_bytes(value: Any) -> bytes:
    def nfc(item: Any) -> Any:
        if isinstance(item, str):
            return unicodedata.normalize("NFC", item)
        if isinstance(item, list):
            return [nfc(child) for child in item]
        if isinstance(item, dict):
            return {nfc(key): nfc(child) for key, child in item.items()}
        return item

    return json.dumps(
        nfc(value),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pointer_escape(value: str) -> str:
    return value.replace("~", "~0").replace("/", "~1")


def walk(value: Any, path: str = "") -> Iterable[tuple[str, Any]]:
    yield path or "/", value
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk(child, f"{path}/{pointer_escape(key)}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk(child, f"{path}/{index}")


def load_json(path: Path, errors: list[str]) -> Any | None:
    try:
        raw = path.read_bytes()
    except OSError as exc:
        errors.append(f"{path}: cannot read: {exc}")
        return None
    if raw.startswith(b"\xef\xbb\xbf"):
        errors.append(f"{path}: UTF-8 BOM is not allowed")
    if not raw.endswith(b"\n"):
        errors.append(f"{path}: JSON file must end with LF")
    try:
        text = raw.decode("utf-8-sig" if raw.startswith(b"\xef\xbb\xbf") else "utf-8")
        return json.loads(text, object_pairs_hook=unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError, DuplicateKeyError) as exc:
        errors.append(f"{path}: invalid JSON: {exc}")
        return None


def load_schema_validators(
    schema_dir: Path,
    errors: list[str],
) -> tuple[dict[str, Any], str]:
    """Strictly parse both schemas and prepare Draft 2020-12 validators."""
    validators: dict[str, Any] = {}
    documents: dict[str, Any] = {}
    for kind, filename in (
        ("input", "app-input.schema.json"),
        ("oracle", "test-oracle.schema.json"),
    ):
        path = schema_dir / filename
        document = load_json(path, errors)
        if not isinstance(document, dict):
            errors.append(f"{path}: schema root must be an object")
            continue
        if document.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            errors.append(f"{path}: must declare JSON Schema Draft 2020-12")
        documents[kind] = document

    if Draft202012Validator is None:
        return validators, "strict-JSON+explicit-contract"

    for kind, document in documents.items():
        filename = (
            "app-input.schema.json" if kind == "input" else "test-oracle.schema.json"
        )
        path = schema_dir / filename
        try:
            Draft202012Validator.check_schema(document)
            validators[kind] = Draft202012Validator(document)
        except Exception as exc:  # jsonschema raises several schema-specific types.
            errors.append(f"{path}: invalid Draft 2020-12 schema: {exc}")
    return validators, "jsonschema-draft-2020-12"


def schema_path(parts: Iterable[Any]) -> str:
    rendered = "".join(f"/{pointer_escape(str(part))}" for part in parts)
    return rendered or "/"


def apply_schema(
    document: Any,
    source: Path,
    validator: Any | None,
    errors: list[str],
) -> None:
    if validator is None:
        return
    for problem in sorted(
        validator.iter_errors(document),
        key=lambda item: (list(item.absolute_path), item.message),
    ):
        add_error(
            errors,
            source,
            schema_path(problem.absolute_path),
            f"JSON Schema: {problem.message}",
        )


def add_error(errors: list[str], source: Path, path: str, message: str) -> None:
    errors.append(f"{source}:{path}: {message}")


def exact_keys(
    value: Any,
    required: set[str],
    allowed: set[str],
    source: Path,
    path: str,
    errors: list[str],
) -> bool:
    if not isinstance(value, dict):
        add_error(errors, source, path, "must be an object")
        return False
    keys = set(value)
    for key in sorted(required - keys):
        add_error(errors, source, path, f"missing required key {key!r}")
    for key in sorted(keys - allowed):
        add_error(errors, source, path, f"unexpected key {key!r}")
    return required <= keys and keys <= allowed


def expect_string(
    value: Any,
    source: Path,
    path: str,
    errors: list[str],
    *,
    minimum: int = 1,
    maximum: int = 1000,
    pattern: re.Pattern[str] | None = None,
) -> bool:
    if not isinstance(value, str):
        add_error(errors, source, path, "must be a string")
        return False
    if not minimum <= len(value) <= maximum:
        add_error(
            errors,
            source,
            path,
            f"length must be between {minimum} and {maximum}",
        )
        return False
    if pattern is not None and pattern.fullmatch(value) is None:
        add_error(errors, source, path, f"does not match {pattern.pattern}")
        return False
    if not unicodedata.is_normalized("NFC", value):
        add_error(errors, source, path, "must use Unicode NFC")
        return False
    return True


def expect_enum(
    value: Any,
    choices: set[str],
    source: Path,
    path: str,
    errors: list[str],
) -> bool:
    if value not in choices:
        add_error(errors, source, path, f"must be one of {sorted(choices)}")
        return False
    return True


def validate_safe_strings(document: Any, source: Path, errors: list[str]) -> None:
    for path, value in walk(document):
        if not isinstance(value, str):
            continue
        if not unicodedata.is_normalized("NFC", value):
            add_error(errors, source, path, "string must use Unicode NFC")
        field_name = path.rsplit("/", 1)[-1]
        if (field_name == "sha256" or field_name.endswith("_sha256")) and SHA256_PATTERN.fullmatch(value):
            # Cryptographic digests are opaque hexadecimal identifiers. Running
            # phone/card heuristics over them creates random false positives.
            continue
        if RRN_PATTERN.search(value):
            add_error(errors, source, path, "resident-registration-like data is prohibited")
        if PHONE_PATTERN.search(value):
            add_error(errors, source, path, "phone-number-like data is prohibited")
        if EMAIL_PATTERN.search(value):
            add_error(errors, source, path, "email-like data is prohibited")
        if CARD_PATTERN.search(value):
            add_error(errors, source, path, "payment-card-like data is prohibited")
        for match in SECRET_PATTERN.finditer(value):
            marker = match.group(1).strip("\"'")
            if not marker.startswith("SCPC_SYNTHETIC_ONLY_"):
                add_error(errors, source, path, "only SCPC_SYNTHETIC_ONLY_* secrets are allowed")
        for url_match in URL_PATTERN.finditer(value):
            parsed = urlparse(url_match.group(0))
            host = (parsed.hostname or "").lower()
            if parsed.scheme not in {"http", "https"} or not (
                host == "invalid" or host.endswith(".invalid")
            ):
                add_error(errors, source, path, "only HTTP(S) .invalid URLs are allowed")


def validate_forbidden_keys(document: Any, source: Path, errors: list[str]) -> None:
    for path, value in walk(document):
        if not isinstance(value, dict):
            continue
        for key in value:
            if normalize_key(key) in FORBIDDEN_INPUT_KEYS:
                add_error(errors, source, f"{path}/{pointer_escape(key)}", "forbidden app-input key")


def validate_persona(value: Any, source: Path, path: str, errors: list[str]) -> None:
    keys = {
        "persona_key",
        "age_band",
        "region_band",
        "work_context",
        "register",
        "directness",
        "noise_level",
    }
    if not exact_keys(value, keys, keys, source, path, errors):
        return
    expect_string(value["persona_key"], source, f"{path}/persona_key", errors, maximum=20, pattern=PERSONA_KEY_PATTERN)
    expect_enum(value["age_band"], AGE_BANDS, source, f"{path}/age_band", errors)
    expect_enum(value["region_band"], REGION_BANDS, source, f"{path}/region_band", errors)
    expect_enum(value["work_context"], WORK_CONTEXTS, source, f"{path}/work_context", errors)
    expect_enum(value["register"], REGISTERS, source, f"{path}/register", errors)
    expect_enum(value["directness"], DIRECTNESS, source, f"{path}/directness", errors)
    expect_enum(value["noise_level"], NOISE_LEVELS, source, f"{path}/noise_level", errors)


def validate_intake(value: Any, source: Path, path: str, errors: list[str]) -> None:
    if not isinstance(value, dict):
        add_error(errors, source, path, "must be an object")
        return
    kind = value.get("kind")
    variants = {
        "TEXT": {"kind", "content"},
        "LINK": {"kind", "url"},
        "PDF": {"kind", "asset_ref", "mime_type"},
        "NONE": {"kind"},
    }
    if kind not in variants:
        add_error(errors, source, f"{path}/kind", "must be TEXT, LINK, PDF, or NONE")
        return
    keys = variants[kind]
    if not exact_keys(value, keys, keys, source, path, errors):
        return
    if kind == "TEXT":
        expect_string(value["content"], source, f"{path}/content", errors, maximum=2000)
    elif kind == "LINK":
        if expect_string(value["url"], source, f"{path}/url", errors, maximum=500):
            parsed = urlparse(value["url"])
            host = (parsed.hostname or "").lower()
            if parsed.scheme not in {"http", "https"} or not (
                host == "invalid" or host.endswith(".invalid")
            ):
                add_error(errors, source, f"{path}/url", "must use an HTTP(S) .invalid domain")
    elif kind == "PDF":
        if expect_string(value["asset_ref"], source, f"{path}/asset_ref", errors, maximum=200):
            if (
                PDF_ASSET_REF_PATTERN.fullmatch(value["asset_ref"]) is None
                or ".." in value["asset_ref"]
                or "//" in value["asset_ref"]
            ):
                add_error(errors, source, f"{path}/asset_ref", "must be a safe synthetic/ reference")
        if value["mime_type"] != "application/pdf":
            add_error(errors, source, f"{path}/mime_type", "must be application/pdf")


SHARE_PERMISSIONS = ("CAN_SHARE", "READ_ONLY")


def validate_members(value: Any, source: Path, path: str, errors: list[str]) -> None:
    if not isinstance(value, list) or not 1 <= len(value) <= 8:
        add_error(errors, source, path, "must contain one to eight members")
        return
    seen: set[str] = set()
    for index, member in enumerate(value):
        item_path = f"{path}/{index}"
        keys = {"display_name", "member_scope"}
        if not exact_keys(member, keys, keys, source, item_path, errors):
            continue
        expect_string(member["display_name"], source, f"{item_path}/display_name", errors, maximum=40)
        expect_string(member["member_scope"], source, f"{item_path}/member_scope", errors, maximum=100)
        if member["display_name"] in seen:
            add_error(errors, source, f"{item_path}/display_name", "duplicate member")
        seen.add(member["display_name"])


def validate_context(
    value: Any,
    room_names: dict[str, str],
    purpose_id: str,
    source: Path,
    path: str,
    errors: list[str],
) -> None:
    keys = {
        "purpose_id",
        "target_room_id",
        "target_room_display",
        "current_role",
        "allowed_scope",
        "link_access",
        "share_permission",
    }
    if not exact_keys(value, keys, keys, source, path, errors):
        return
    if value["purpose_id"] != purpose_id:
        add_error(errors, source, f"{path}/purpose_id", "must match top-level purpose_id")
    room_id = value["target_room_id"]
    if room_id not in room_names:
        add_error(errors, source, f"{path}/target_room_id", "must reference a declared room")
    elif room_names[room_id] != value["target_room_display"]:
        add_error(errors, source, f"{path}/target_room_display", "must match the declared room display name")
    expect_string(value["current_role"], source, f"{path}/current_role", errors, maximum=100)
    expect_string(value["allowed_scope"], source, f"{path}/allowed_scope", errors, maximum=100)
    expect_enum(value["link_access"], LINK_ACCESS, source, f"{path}/link_access", errors)
    expect_enum(
        value["share_permission"],
        SHARE_PERMISSIONS,
        source,
        f"{path}/share_permission",
        errors,
    )


def validate_input(document: Any, source: Path, errors: list[str]) -> None:
    if isinstance(document, dict):
        validate_forbidden_keys(document, source, errors)
        validate_safe_strings(document, source, errors)
    root_keys = {
        "schema_version",
        "artifact_kind",
        "scenario_id",
        "synthetic",
        "locale",
        "derivation_ref",
        "persona_style",
        "purpose",
        "rooms",
        "episodes",
    }
    if not exact_keys(document, root_keys, root_keys, source, "/", errors):
        return

    if document["schema_version"] != INPUT_SCHEMA_VERSION:
        add_error(errors, source, "/schema_version", f"must be {INPUT_SCHEMA_VERSION}")
    if document["artifact_kind"] != "safeshare_app_input":
        add_error(errors, source, "/artifact_kind", "must be safeshare_app_input")
    expect_string(document["scenario_id"], source, "/scenario_id", errors, maximum=20, pattern=SCENARIO_ID_PATTERN)
    if document["synthetic"] is not True:
        add_error(errors, source, "/synthetic", "must be true")
    if document["locale"] != "ko-KR":
        add_error(errors, source, "/locale", "must be ko-KR")
    expect_string(document["derivation_ref"], source, "/derivation_ref", errors, maximum=20, pattern=DERIVATION_PATTERN)
    validate_persona(document["persona_style"], source, "/persona_style", errors)

    purpose_keys = {"purpose_id", "display_text"}
    purpose = document["purpose"]
    if exact_keys(purpose, purpose_keys, purpose_keys, source, "/purpose", errors):
        expect_string(purpose["purpose_id"], source, "/purpose/purpose_id", errors, maximum=64, pattern=PURPOSE_ID_PATTERN)
        expect_string(purpose["display_text"], source, "/purpose/display_text", errors, maximum=100)
        purpose_id = purpose["purpose_id"]
    else:
        purpose_id = ""

    rooms = document["rooms"]
    room_names: dict[str, str] = {}
    if not isinstance(rooms, list) or len(rooms) < 3:
        add_error(errors, source, "/rooms", "must contain at least three rooms")
    else:
        for index, room in enumerate(rooms):
            path = f"/rooms/{index}"
            keys = {"room_id", "display_name", "members"}
            if not exact_keys(room, keys, keys, source, path, errors):
                continue
            room_id = room["room_id"]
            if not expect_string(room_id, source, f"{path}/room_id", errors, maximum=85, pattern=ROOM_ID_PATTERN):
                continue
            expect_string(room["display_name"], source, f"{path}/display_name", errors, maximum=100)
            validate_members(room["members"], source, f"{path}/members", errors)
            if room_id in room_names:
                add_error(errors, source, f"{path}/room_id", "duplicate room_id")
            room_names[room_id] = room["display_name"]

    episodes = document["episodes"]
    if not isinstance(episodes, list) or len(episodes) != 4:
        add_error(errors, source, "/episodes", "must contain exactly E1, E2, E3 and E4")
        return
    episode_keys = {
        "episode_id",
        "phase",
        "session",
        "user_utterance",
        "intake",
        "context",
        "candidate_room_ids",
        "read_only_rooms",
        "actions",
    }
    for index, item in enumerate(episodes):
        path = f"/episodes/{index}"
        if not exact_keys(item, episode_keys, episode_keys, source, path, errors):
            continue
        if item["episode_id"] != EPISODE_IDS[index]:
            add_error(errors, source, f"{path}/episode_id", f"must be {EPISODE_IDS[index]}")
        if item["phase"] != PHASES[index]:
            add_error(errors, source, f"{path}/phase", f"must be {PHASES[index]}")
        if type(item["session"]) is not int or item["session"] != index + 1:
            add_error(errors, source, f"{path}/session", f"must be integer {index + 1}")
        expect_string(item["user_utterance"], source, f"{path}/user_utterance", errors, maximum=500)
        validate_intake(item["intake"], source, f"{path}/intake", errors)
        if index == 3 and isinstance(item["intake"], dict) and item["intake"].get("kind") != "NONE":
            add_error(errors, source, f"{path}/intake/kind", "E4 must resume without a new intake")
        if index < 3 and isinstance(item["intake"], dict) and item["intake"].get("kind") == "NONE":
            add_error(errors, source, f"{path}/intake/kind", "E1-E3 require synthetic intake")
        validate_context(item["context"], room_names, purpose_id, source, f"{path}/context", errors)

        candidates = item["candidate_room_ids"]
        if not isinstance(candidates, list) or not candidates:
            add_error(errors, source, f"{path}/candidate_room_ids", "must be a non-empty array")
        else:
            if len(candidates) != len(set(candidates)):
                add_error(errors, source, f"{path}/candidate_room_ids", "must not contain duplicates")
            for candidate_index, room_id in enumerate(candidates):
                if room_id not in room_names:
                    add_error(errors, source, f"{path}/candidate_room_ids/{candidate_index}", "must reference a declared room")

        read_only = item["read_only_rooms"]
        if not isinstance(read_only, list) or len(read_only) > 8:
            add_error(errors, source, f"{path}/read_only_rooms", "must be an array of at most eight rooms")
        else:
            if len(set(read_only)) != len(read_only):
                add_error(errors, source, f"{path}/read_only_rooms", "must not contain duplicates")
            for read_index, room_id in enumerate(read_only):
                if room_id not in room_names:
                    add_error(errors, source, f"{path}/read_only_rooms/{read_index}", "must reference a declared room")

        actions = item["actions"]
        if not isinstance(actions, list) or not actions:
            add_error(errors, source, f"{path}/actions", "must be a non-empty array")
        else:
            if len(actions) != len(set(actions)):
                add_error(errors, source, f"{path}/actions", "must not contain duplicates")
            for action_index, action in enumerate(actions):
                expect_enum(action, ACTIONS, source, f"{path}/actions/{action_index}", errors)


def validate_oracle(document: Any, source: Path, errors: list[str]) -> None:
    root_keys = {
        "schema_version",
        "artifact_kind",
        "scenario_id",
        "input_sha256",
        "policy_spec_revision",
        "assertions",
        "pair_relations",
    }
    if not exact_keys(document, root_keys, root_keys, source, "/", errors):
        return
    validate_safe_strings(document, source, errors)
    if document["schema_version"] != ORACLE_SCHEMA_VERSION:
        add_error(errors, source, "/schema_version", f"must be {ORACLE_SCHEMA_VERSION}")
    if document["artifact_kind"] != "safeshare_test_oracle":
        add_error(errors, source, "/artifact_kind", "must be safeshare_test_oracle")
    expect_string(document["scenario_id"], source, "/scenario_id", errors, maximum=20, pattern=SCENARIO_ID_PATTERN)
    expect_string(document["input_sha256"], source, "/input_sha256", errors, minimum=64, maximum=64, pattern=SHA256_PATTERN)
    expect_string(document["policy_spec_revision"], source, "/policy_spec_revision", errors, maximum=80, pattern=POLICY_REVISION_PATTERN)

    assertions = document["assertions"]
    if not isinstance(assertions, list) or not assertions:
        add_error(errors, source, "/assertions", "must be a non-empty array")
    else:
        for index, assertion in enumerate(assertions):
            path = f"/assertions/{index}"
            required = {"after_action", "path", "op"}
            allowed = required | {"value", "reference"}
            if not exact_keys(assertion, required, allowed, source, path, errors):
                continue
            expect_string(assertion["after_action"], source, f"{path}/after_action", errors, maximum=100)
            if not assertion["after_action"].startswith(("E1.", "E2.", "E3.", "E4.")):
                add_error(errors, source, f"{path}/after_action", "must start with E1., E2., E3., or E4.")
            if not expect_string(assertion["path"], source, f"{path}/path", errors, maximum=200) or not assertion["path"].startswith("$."):
                add_error(errors, source, f"{path}/path", "must be a $. observable path")
            expect_enum(assertion["op"], ORACLE_OPS, source, f"{path}/op", errors)
            if assertion["op"] in {"SAME_AS", "CHANGED_FROM"} and "reference" not in assertion:
                add_error(errors, source, path, f"{assertion['op']} requires reference")
            if assertion["op"] in {"EQ", "NE", "GT", "COUNT_EQ"} and "value" not in assertion:
                add_error(errors, source, path, f"{assertion['op']} requires value")

    relations = document["pair_relations"]
    if not isinstance(relations, list) or not relations:
        add_error(errors, source, "/pair_relations", "must be a non-empty array")
    else:
        for index, relation in enumerate(relations):
            path = f"/pair_relations/{index}"
            keys = {
                "pair_id",
                "counterpart_scenario_id",
                "changed_input_pointers",
                "relation",
                "observed_paths",
            }
            if not exact_keys(relation, keys, keys, source, path, errors):
                continue
            expect_string(relation["pair_id"], source, f"{path}/pair_id", errors, maximum=85, pattern=PAIR_ID_PATTERN)
            expect_string(relation["counterpart_scenario_id"], source, f"{path}/counterpart_scenario_id", errors, maximum=20, pattern=SCENARIO_ID_PATTERN)
            expect_enum(relation["relation"], {"SAME", "DIFFERENT"}, source, f"{path}/relation", errors)
            pointers = relation["changed_input_pointers"]
            if not isinstance(pointers, list) or not pointers:
                add_error(errors, source, f"{path}/changed_input_pointers", "must be a non-empty array")
            elif len(pointers) != len(set(pointers)):
                add_error(errors, source, f"{path}/changed_input_pointers", "must not contain duplicates")
            else:
                for pointer_index, pointer in enumerate(pointers):
                    if not isinstance(pointer, str) or not pointer.startswith("/"):
                        add_error(errors, source, f"{path}/changed_input_pointers/{pointer_index}", "must be an absolute JSON pointer")
            observed = relation["observed_paths"]
            if not isinstance(observed, list) or not observed:
                add_error(errors, source, f"{path}/observed_paths", "must be a non-empty array")
            elif len(observed) != len(set(observed)):
                add_error(errors, source, f"{path}/observed_paths", "must not contain duplicates")
            else:
                for observed_index, observed_path in enumerate(observed):
                    if not isinstance(observed_path, str) or not observed_path.startswith("$."):
                        add_error(errors, source, f"{path}/observed_paths/{observed_index}", "must be a $. observable path")


def discover_json(paths: list[Path]) -> list[Path]:
    discovered: set[Path] = set()
    for path in paths:
        if path.is_dir():
            discovered.update(candidate for candidate in path.rglob("*.json") if candidate.is_file())
        elif path.is_file():
            discovered.add(path)
    return sorted(discovered)


def json_diff_paths(left: Any, right: Any, path: str = "") -> set[str]:
    if type(left) is not type(right):
        return {path or "/"}
    if isinstance(left, dict):
        differences: set[str] = set()
        keys = set(left) | set(right)
        for key in keys:
            child_path = f"{path}/{pointer_escape(key)}"
            if key not in left or key not in right:
                differences.add(child_path)
            else:
                differences.update(json_diff_paths(left[key], right[key], child_path))
        return differences
    if isinstance(left, list):
        differences = set()
        if len(left) != len(right):
            differences.add(path or "/")
        for index, (left_item, right_item) in enumerate(zip(left, right)):
            differences.update(json_diff_paths(left_item, right_item, f"{path}/{index}"))
        return differences
    return set() if left == right else {path or "/"}


def pointer_allows(pointer: str, difference: str) -> bool:
    normalized = pointer.rstrip("/") or "/"
    return difference == normalized or difference.startswith(normalized + "/")


def validate_pair_bindings(
    inputs: dict[str, Any],
    oracles: dict[str, Any],
    oracle_sources: dict[str, Path],
    errors: list[str],
) -> None:
    checked_pairs: set[tuple[str, str, str]] = set()
    for scenario_id, oracle in oracles.items():
        source = oracle_sources[scenario_id]
        input_document = inputs.get(scenario_id)
        if input_document is None:
            add_error(errors, source, "/scenario_id", "has no matching app input")
            continue
        actual_hash = canonical_sha256(input_document)
        if oracle.get("input_sha256") != actual_hash:
            add_error(errors, source, "/input_sha256", f"does not match canonical input SHA-256 {actual_hash}")
        for relation_index, relation in enumerate(oracle.get("pair_relations", [])):
            if not isinstance(relation, dict):
                continue
            counterpart_id = relation.get("counterpart_scenario_id")
            pair_id = relation.get("pair_id")
            if not isinstance(counterpart_id, str) or not isinstance(pair_id, str):
                continue
            counterpart = inputs.get(counterpart_id)
            counterpart_oracle = oracles.get(counterpart_id)
            relation_path = f"/pair_relations/{relation_index}"
            if counterpart is None:
                add_error(errors, source, f"{relation_path}/counterpart_scenario_id", "matching app input is missing")
                continue
            if counterpart_oracle is None:
                add_error(errors, source, f"{relation_path}/counterpart_scenario_id", "matching oracle is missing")
                continue
            reverse_matches = [
                item
                for item in counterpart_oracle.get("pair_relations", [])
                if isinstance(item, dict)
                and item.get("pair_id") == pair_id
                and item.get("counterpart_scenario_id") == scenario_id
            ]
            if not reverse_matches:
                add_error(errors, source, relation_path, "pair relation is not symmetric")
                continue
            reverse = reverse_matches[0]
            for key in ("relation", "changed_input_pointers", "observed_paths"):
                if relation.get(key) != reverse.get(key):
                    add_error(errors, source, f"{relation_path}/{key}", "does not match counterpart oracle")

            pair_key = (pair_id, *sorted((scenario_id, counterpart_id)))
            if pair_key in checked_pairs:
                continue
            checked_pairs.add(pair_key)
            left = copy.deepcopy(input_document)
            right = copy.deepcopy(counterpart)
            for document in (left, right):
                document.pop("scenario_id", None)
                document.pop("derivation_ref", None)
            differences = json_diff_paths(left, right)
            pointers = relation.get("changed_input_pointers", [])
            if not differences:
                add_error(errors, source, relation_path, "pair inputs are identical after metadata removal")
                continue
            unexpected = sorted(
                difference
                for difference in differences
                if not any(pointer_allows(pointer, difference) for pointer in pointers)
            )
            for difference in unexpected:
                add_error(errors, source, relation_path, f"undeclared counterfactual difference at {difference}")
            for pointer in pointers:
                if not any(pointer_allows(pointer, difference) for difference in differences):
                    add_error(errors, source, f"{relation_path}/changed_input_pointers", f"declared pointer has no difference: {pointer}")


def check_separation(root: Path, errors: list[str]) -> None:
    input_dir = root / "app-input"
    oracle_dir = root / "test-oracle"
    asset_dir = input_dir / "synthetic"
    if not input_dir.is_dir():
        errors.append(f"{input_dir}: required input directory is missing")
    if not oracle_dir.is_dir():
        errors.append(f"{oracle_dir}: required oracle directory is missing")
    if not asset_dir.is_dir():
        errors.append(f"{asset_dir}: required synthetic asset directory is missing")
    for directory, expected_kind in (
        (input_dir, "safeshare_app_input"),
        (oracle_dir, "safeshare_test_oracle"),
    ):
        if not directory.is_dir():
            continue
        for path in directory.rglob("*"):
            if path.is_symlink():
                errors.append(f"{path}: symlinks are prohibited in generated packs")
        for path in directory.rglob("*.json"):
            local_errors: list[str] = []
            document = load_json(path, local_errors)
            errors.extend(local_errors)
            if isinstance(document, dict) and document.get("artifact_kind") != expected_kind:
                errors.append(
                    f"{path}:/artifact_kind: expected {expected_kind} in {directory.name}"
                )
    if asset_dir.is_dir():
        for path in asset_dir.rglob("*"):
            if path.is_symlink():
                errors.append(f"{path}: symlinks are prohibited in generated packs")


def referenced_pdf_assets(inputs: dict[str, Any]) -> set[str]:
    references: set[str] = set()
    for document in inputs.values():
        for episode in document.get("episodes", []):
            if not isinstance(episode, dict):
                continue
            intake = episode.get("intake")
            if isinstance(intake, dict) and intake.get("kind") == "PDF":
                asset_ref = intake.get("asset_ref")
                if isinstance(asset_ref, str):
                    references.add(asset_ref)
    return references


def validate_pdf_assets(
    inputs: dict[str, Any],
    asset_root: Path,
    errors: list[str],
) -> set[str]:
    references = referenced_pdf_assets(inputs)
    resolved_root = asset_root.resolve()
    for asset_ref in sorted(references):
        source = resolved_root / asset_ref
        resolved = source.resolve()
        if not resolved.is_relative_to(resolved_root):
            errors.append(f"{source}: PDF asset escapes the asset root")
            continue
        if source.is_symlink():
            errors.append(f"{source}: PDF asset must not be a symlink")
            continue
        if not source.is_file():
            errors.append(f"{source}: referenced PDF asset is missing")
            continue
        try:
            raw = source.read_bytes()
        except OSError as exc:
            errors.append(f"{source}: cannot read PDF asset: {exc}")
            continue
        if not raw.startswith(b"%PDF-"):
            errors.append(f"{source}: missing PDF header")
        if not raw.rstrip().endswith(b"%%EOF"):
            errors.append(f"{source}: missing PDF EOF marker")
        if len(raw) < 1000:
            errors.append(f"{source}: PDF fixture is unexpectedly small")

        if PdfReader is not None:
            try:
                reader = PdfReader(source, strict=True)
                if reader.is_encrypted:
                    errors.append(f"{source}: PDF fixture must not be encrypted")
                    continue
                if len(reader.pages) != 1:
                    errors.append(f"{source}: PDF fixture must contain exactly one page")
                extracted = "\n".join(page.extract_text() or "" for page in reader.pages)
                if "SYNTHETIC INTAKE" not in extracted or "NOT A REAL DOCUMENT" not in extracted:
                    errors.append(f"{source}: PDF synthetic-only notice is missing")
                validate_safe_strings(extracted, source, errors)
            except Exception as exc:
                errors.append(f"{source}: cannot parse PDF fixture: {exc}")

    synthetic_dir = resolved_root / "synthetic"
    if synthetic_dir.is_dir():
        actual = {
            path.relative_to(resolved_root).as_posix()
            for path in synthetic_dir.rglob("*.pdf")
            if path.is_file()
        }
        for extra in sorted(actual - references):
            errors.append(f"{resolved_root / extra}: unreferenced synthetic PDF asset")
        for missing in sorted(references - actual):
            errors.append(f"{resolved_root / missing}: referenced PDF asset is absent")
    return references


def validate_manifest(
    manifest_path: Path,
    inputs: dict[str, Any],
    oracles: dict[str, Any],
    errors: list[str],
) -> None:
    document = load_json(manifest_path, errors)
    root_keys = {
        "schema_version",
        "artifact_kind",
        "generator_version",
        "generator_sha256",
        "validator_sha256",
        "requirements",
        "source_kind",
        "external_records_downloaded",
        "canonical_json",
        "schemas",
        "source_provenance",
        "assets",
        "inputs",
        "oracles",
    }
    if not exact_keys(document, root_keys, root_keys, manifest_path, "/", errors):
        return
    if document["schema_version"] != "safeshare.generation-manifest.v1":
        add_error(errors, manifest_path, "/schema_version", "must be safeshare.generation-manifest.v1")
    if document["artifact_kind"] != "safeshare_generation_manifest":
        add_error(errors, manifest_path, "/artifact_kind", "must be safeshare_generation_manifest")
    if document["source_kind"] != "repository_owned_synthetic_templates":
        add_error(errors, manifest_path, "/source_kind", "must use repository-owned synthetic templates")
    if document["external_records_downloaded"] is not False:
        add_error(errors, manifest_path, "/external_records_downloaded", "must be false for this preparation pack")
    expect_string(document["generator_version"], manifest_path, "/generator_version", errors, maximum=40)
    expect_string(document["generator_sha256"], manifest_path, "/generator_sha256", errors, minimum=64, maximum=64, pattern=SHA256_PATTERN)
    expect_string(document["validator_sha256"], manifest_path, "/validator_sha256", errors, minimum=64, maximum=64, pattern=SHA256_PATTERN)
    expect_string(document["canonical_json"], manifest_path, "/canonical_json", errors, maximum=200)

    tool_root = manifest_path.parent.parent
    for field, filename in (("generator_sha256", "generate.py"), ("validator_sha256", "validate.py")):
        tool_path = tool_root / filename
        if not tool_path.is_file():
            add_error(errors, manifest_path, f"/{field}", f"missing tool file {tool_path}")
        elif document[field] != file_sha256(tool_path):
            add_error(errors, manifest_path, f"/{field}", f"does not match {filename}")

    requirements = document["requirements"]
    requirement_keys = {"path", "sha256"}
    if exact_keys(
        requirements,
        requirement_keys,
        requirement_keys,
        manifest_path,
        "/requirements",
        errors,
    ):
        path_ok = expect_string(
            requirements["path"],
            manifest_path,
            "/requirements/path",
            errors,
            maximum=300,
        )
        expect_string(
            requirements["sha256"],
            manifest_path,
            "/requirements/sha256",
            errors,
            minimum=64,
            maximum=64,
            pattern=SHA256_PATTERN,
        )
        if path_ok:
            requirements_path = (manifest_path.parent / requirements["path"]).resolve()
            if not requirements_path.is_file():
                add_error(
                    errors,
                    manifest_path,
                    "/requirements/path",
                    f"missing requirements lock {requirements_path}",
                )
            elif requirements["sha256"] != file_sha256(requirements_path):
                add_error(
                    errors,
                    manifest_path,
                    "/requirements/sha256",
                    "does not match requirements lock",
                )

    schemas = document["schemas"]
    if exact_keys(schemas, {"app_input", "test_oracle"}, {"app_input", "test_oracle"}, manifest_path, "/schemas", errors):
        for name, entry in schemas.items():
            path = f"/schemas/{pointer_escape(name)}"
            keys = {"path", "sha256"}
            if not exact_keys(entry, keys, keys, manifest_path, path, errors):
                continue
            if not expect_string(entry["path"], manifest_path, f"{path}/path", errors, maximum=300):
                continue
            expect_string(entry["sha256"], manifest_path, f"{path}/sha256", errors, minimum=64, maximum=64, pattern=SHA256_PATTERN)
            schema_path = (manifest_path.parent / entry["path"]).resolve()
            if not schema_path.is_file():
                add_error(errors, manifest_path, f"{path}/path", f"missing schema {schema_path}")
            elif entry["sha256"] != file_sha256(schema_path):
                add_error(errors, manifest_path, f"{path}/sha256", "does not match referenced schema")

    provenance = document["source_provenance"]
    provenance_keys = {"path", "file_sha256"}
    if exact_keys(provenance, provenance_keys, provenance_keys, manifest_path, "/source_provenance", errors):
        if expect_string(provenance["path"], manifest_path, "/source_provenance/path", errors, maximum=400):
            provenance_path = (manifest_path.parent / provenance["path"]).resolve()
            if not provenance_path.is_file():
                add_error(errors, manifest_path, "/source_provenance/path", f"missing provenance lock {provenance_path}")
            elif provenance["file_sha256"] != file_sha256(provenance_path):
                add_error(errors, manifest_path, "/source_provenance/file_sha256", "does not match provenance lock")
        expect_string(provenance["file_sha256"], manifest_path, "/source_provenance/file_sha256", errors, minimum=64, maximum=64, pattern=SHA256_PATTERN)

    asset_entries = document["assets"]
    manifest_asset_refs: set[str] = set()
    expected_asset_refs = referenced_pdf_assets(inputs)
    if not isinstance(asset_entries, list) or not asset_entries:
        add_error(errors, manifest_path, "/assets", "must be a non-empty array")
    else:
        for index, entry in enumerate(asset_entries):
            path = f"/assets/{index}"
            keys = {
                "asset_ref",
                "path",
                "media_type",
                "synthetic",
                "pages",
                "bytes",
                "file_sha256",
            }
            if not exact_keys(entry, keys, keys, manifest_path, path, errors):
                continue
            asset_ref = entry["asset_ref"]
            if not isinstance(asset_ref, str):
                add_error(errors, manifest_path, f"{path}/asset_ref", "must be a string")
                continue
            if asset_ref in manifest_asset_refs:
                add_error(errors, manifest_path, f"{path}/asset_ref", "duplicate asset_ref")
            manifest_asset_refs.add(asset_ref)
            expected_path = f"app-input/{asset_ref}"
            if entry["path"] != expected_path:
                add_error(
                    errors,
                    manifest_path,
                    f"{path}/path",
                    f"must equal {expected_path}",
                )
            if entry["media_type"] != "application/pdf":
                add_error(errors, manifest_path, f"{path}/media_type", "must be application/pdf")
            if entry["synthetic"] is not True:
                add_error(errors, manifest_path, f"{path}/synthetic", "must be true")
            if type(entry["pages"]) is not int or entry["pages"] != 1:
                add_error(errors, manifest_path, f"{path}/pages", "must be integer 1")
            if type(entry["bytes"]) is not int or entry["bytes"] < 1000:
                add_error(errors, manifest_path, f"{path}/bytes", "must be an integer of at least 1000")
            expect_string(
                entry["file_sha256"],
                manifest_path,
                f"{path}/file_sha256",
                errors,
                minimum=64,
                maximum=64,
                pattern=SHA256_PATTERN,
            )
            referenced = (manifest_path.parent / entry["path"]).resolve()
            if not referenced.is_file():
                add_error(errors, manifest_path, f"{path}/path", f"missing asset {referenced}")
            else:
                if entry["bytes"] != referenced.stat().st_size:
                    add_error(errors, manifest_path, f"{path}/bytes", "does not match asset size")
                if entry["file_sha256"] != file_sha256(referenced):
                    add_error(errors, manifest_path, f"{path}/file_sha256", "does not match asset file")
    if manifest_asset_refs != expected_asset_refs:
        add_error(
            errors,
            manifest_path,
            "/assets",
            "manifest asset_ref set does not match PDF references in loaded inputs",
        )

    input_entries = document["inputs"]
    manifest_input_ids: set[str] = set()
    if not isinstance(input_entries, list):
        add_error(errors, manifest_path, "/inputs", "must be an array")
    else:
        for index, entry in enumerate(input_entries):
            path = f"/inputs/{index}"
            keys = {"scenario_id", "path", "canonical_sha256"}
            if not exact_keys(entry, keys, keys, manifest_path, path, errors):
                continue
            scenario_id = entry["scenario_id"]
            if scenario_id in manifest_input_ids:
                add_error(errors, manifest_path, f"{path}/scenario_id", "duplicate manifest input")
            manifest_input_ids.add(scenario_id)
            if scenario_id not in inputs:
                add_error(errors, manifest_path, f"{path}/scenario_id", "input is not loaded")
            else:
                actual = canonical_sha256(inputs[scenario_id])
                if entry["canonical_sha256"] != actual:
                    add_error(errors, manifest_path, f"{path}/canonical_sha256", "does not match loaded input")
            referenced = (manifest_path.parent / entry["path"]).resolve()
            if not referenced.is_file():
                add_error(errors, manifest_path, f"{path}/path", f"missing input {referenced}")
    if manifest_input_ids != set(inputs):
        add_error(errors, manifest_path, "/inputs", "manifest input ID set does not match loaded input set")

    oracle_entries = document["oracles"]
    manifest_oracle_ids: set[str] = set()
    if not isinstance(oracle_entries, list):
        add_error(errors, manifest_path, "/oracles", "must be an array")
    else:
        for index, entry in enumerate(oracle_entries):
            path = f"/oracles/{index}"
            keys = {"scenario_id", "path", "file_sha256"}
            if not exact_keys(entry, keys, keys, manifest_path, path, errors):
                continue
            scenario_id = entry["scenario_id"]
            if scenario_id in manifest_oracle_ids:
                add_error(errors, manifest_path, f"{path}/scenario_id", "duplicate manifest oracle")
            manifest_oracle_ids.add(scenario_id)
            if scenario_id not in oracles:
                add_error(errors, manifest_path, f"{path}/scenario_id", "oracle is not loaded")
            referenced = (manifest_path.parent / entry["path"]).resolve()
            if not referenced.is_file():
                add_error(errors, manifest_path, f"{path}/path", f"missing oracle {referenced}")
            elif entry["file_sha256"] != file_sha256(referenced):
                add_error(errors, manifest_path, f"{path}/file_sha256", "does not match oracle file")
    if manifest_oracle_ids != set(oracles):
        add_error(errors, manifest_path, "/oracles", "manifest oracle ID set does not match loaded oracle set")


def load_input_directory(
    input_dir: Path,
    errors: list[str],
    schema_validator: Any | None = None,
) -> tuple[dict[str, Any], dict[str, Path]]:
    inputs: dict[str, Any] = {}
    sources: dict[str, Path] = {}
    for path in sorted(input_dir.rglob("*.json")):
        document = load_json(path, errors)
        if document is None:
            continue
        apply_schema(document, path, schema_validator, errors)
        validate_input(document, path, errors)
        if not isinstance(document, dict):
            continue
        scenario_id = document.get("scenario_id")
        if not isinstance(scenario_id, str):
            continue
        if scenario_id in inputs:
            errors.append(f"{path}:/scenario_id: duplicate scenario_id also in {sources[scenario_id]}")
        inputs[scenario_id] = document
        sources[scenario_id] = path
    return inputs, sources


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path, help="JSON files or directories")
    parser.add_argument(
        "--kind",
        choices=("auto", "input", "oracle"),
        default="auto",
        help="Expected artifact kind for positional paths",
    )
    parser.add_argument(
        "--input-dir",
        type=Path,
        help="App-input directory used to bind and pair-check oracles",
    )
    parser.add_argument(
        "--separation-root",
        type=Path,
        help="Root containing app-input/ and test-oracle/",
    )
    parser.add_argument(
        "--asset-root",
        type=Path,
        help="Root against which synthetic/*.pdf references are resolved",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run negative checks against a cloned valid input",
    )
    parser.set_defaults(default_root=script_dir / "generated")
    return parser.parse_args()


def run_self_test(
    inputs: dict[str, Any],
    asset_root: Path,
    errors: list[str],
) -> None:
    if not inputs:
        errors.append("self-test requires at least one valid input")
        return
    sample = copy.deepcopy(next(iter(inputs.values())))
    sample["expectedState"] = "ACT"
    negative_errors: list[str] = []
    validate_input(sample, Path("<self-test-forbidden-key>"), negative_errors)
    if not any("forbidden app-input key" in item for item in negative_errors):
        errors.append("self-test failed: expected_state was not rejected")

    link_sample = copy.deepcopy(next(iter(inputs.values())))
    link_sample["episodes"][0]["intake"] = {
        "kind": "LINK",
        "url": "https://example.com/real-service",
    }
    negative_errors = []
    validate_input(link_sample, Path("<self-test-real-domain>"), negative_errors)
    if not any(".invalid" in item for item in negative_errors):
        errors.append("self-test failed: real domain was not rejected")

    secret_sample = copy.deepcopy(next(iter(inputs.values())))
    secret_sample["episodes"][0]["intake"] = {
        "kind": "TEXT",
        "content": "api_key=REAL_OPERATIONAL_VALUE",
    }
    negative_errors = []
    validate_input(secret_sample, Path("<self-test-real-secret>"), negative_errors)
    if not any("SCPC_SYNTHETIC_ONLY" in item for item in negative_errors):
        errors.append("self-test failed: operational-looking secret was not rejected")

    pii_sample = copy.deepcopy(next(iter(inputs.values())))
    pii_sample["episodes"][0]["user_utterance"] = "식별값 900101-1234567"
    negative_errors = []
    validate_input(pii_sample, Path("<self-test-pii>"), negative_errors)
    if not any("resident-registration-like" in item for item in negative_errors):
        errors.append("self-test failed: personal-data-like value was not rejected")

    try:
        json.loads(r'{"pattern":"^\$.+"}')
    except json.JSONDecodeError:
        pass
    else:
        errors.append("self-test failed: invalid JSON escape was unexpectedly accepted")

    pdf_inputs = copy.deepcopy(inputs)
    changed_pdf_ref = False
    for document in pdf_inputs.values():
        for episode in document.get("episodes", []):
            intake = episode.get("intake") if isinstance(episode, dict) else None
            if isinstance(intake, dict) and intake.get("kind") == "PDF":
                intake["asset_ref"] = "synthetic/missing-fixture.pdf"
                changed_pdf_ref = True
                break
        if changed_pdf_ref:
            break
    if changed_pdf_ref:
        negative_errors = []
        validate_pdf_assets(pdf_inputs, asset_root, negative_errors)
        if not any("referenced PDF asset is missing" in item for item in negative_errors):
            errors.append("self-test failed: missing PDF fixture was not rejected")


def main() -> int:
    args = parse_args()
    errors: list[str] = []
    script_dir = Path(__file__).resolve().parent
    schema_validators, schema_engine = load_schema_validators(
        script_dir / "schemas", errors
    )
    default_root: Path = args.default_root
    paths = args.paths or [default_root / "app-input", default_root / "test-oracle"]
    json_paths = discover_json([path.resolve() for path in paths])
    if not json_paths:
        errors.append("no JSON files found")

    inputs: dict[str, Any] = {}
    input_sources: dict[str, Path] = {}
    oracles: dict[str, Any] = {}
    oracle_sources: dict[str, Path] = {}

    for path in json_paths:
        document = load_json(path, errors)
        if document is None:
            continue
        kind = args.kind
        if kind == "auto" and isinstance(document, dict):
            artifact_kind = document.get("artifact_kind")
            if artifact_kind == "safeshare_app_input":
                kind = "input"
            elif artifact_kind == "safeshare_test_oracle":
                kind = "oracle"
            else:
                errors.append(f"{path}:/artifact_kind: unknown artifact kind")
                continue
        if kind == "input":
            apply_schema(document, path, schema_validators.get("input"), errors)
            validate_input(document, path, errors)
            if isinstance(document, dict) and isinstance(document.get("scenario_id"), str):
                scenario_id = document["scenario_id"]
                if scenario_id in inputs:
                    errors.append(f"{path}:/scenario_id: duplicate scenario_id also in {input_sources[scenario_id]}")
                inputs[scenario_id] = document
                input_sources[scenario_id] = path
        elif kind == "oracle":
            apply_schema(document, path, schema_validators.get("oracle"), errors)
            validate_oracle(document, path, errors)
            if isinstance(document, dict) and isinstance(document.get("scenario_id"), str):
                scenario_id = document["scenario_id"]
                if scenario_id in oracles:
                    errors.append(f"{path}:/scenario_id: duplicate scenario_id also in {oracle_sources[scenario_id]}")
                oracles[scenario_id] = document
                oracle_sources[scenario_id] = path

    if args.input_dir:
        external_inputs, external_sources = load_input_directory(
            args.input_dir.resolve(), errors, schema_validators.get("input")
        )
        for scenario_id, document in external_inputs.items():
            if scenario_id not in inputs:
                inputs[scenario_id] = document
                input_sources[scenario_id] = external_sources[scenario_id]

    if oracles:
        if not inputs:
            errors.append("oracle validation requires matching inputs; pass --input-dir")
        else:
            validate_pair_bindings(inputs, oracles, oracle_sources, errors)

    asset_root = default_root.resolve() / "app-input"
    if inputs:
        if args.asset_root:
            asset_root = args.asset_root.resolve()
        elif args.separation_root:
            asset_root = args.separation_root.resolve() / "app-input"
        else:
            asset_root = default_root.resolve() / "app-input"
        validate_pdf_assets(inputs, asset_root, errors)

    if args.separation_root:
        separation_root = args.separation_root.resolve()
        check_separation(separation_root, errors)
        validate_manifest(separation_root / "manifest.json", inputs, oracles, errors)

    if args.self_test:
        run_self_test(inputs, asset_root, errors)

    if errors:
        print(f"FAILED: {len(errors)} validation problem(s)", file=sys.stderr)
        for problem in errors:
            print(f"- {problem}", file=sys.stderr)
        return 1

    print(
        "OK: "
        f"{len(json_paths)} JSON file(s), "
        f"{len(inputs)} input(s), {len(oracles)} oracle(s)"
    )
    print(f"Schema contract: {schema_engine}")
    print(
        "PDF contract: "
        + ("pypdf+header+EOF" if PdfReader is not None else "header+EOF")
    )
    if args.separation_root:
        print(f"Separation checked: {args.separation_root.resolve()}")
    if args.self_test:
        print(
            "Negative self-test checked forbidden key, real domain, "
            "operational secret, personal-data, missing PDF and invalid-schema "
            "escape rejection"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
