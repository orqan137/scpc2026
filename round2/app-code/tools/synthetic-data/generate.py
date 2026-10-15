#!/usr/bin/env python3
"""Generate a small, deterministic SafeShare input/oracle preparation pack.

This program intentionally uses repository-owned synthetic templates only. It does
not download or read any external dataset. App inputs and test oracles are emitted
to different directories so an oracle cannot accidentally become an app asset.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import unicodedata
from typing import Any


GENERATOR_VERSION = "1.3.0"
POLICY_SPEC_REVISION = "safeshare-policy-spec-v1"
INPUT_SCHEMA_VERSION = "safeshare.input.v1"
ORACLE_SCHEMA_VERSION = "safeshare.oracle.v1"
PDF_REVISIONS = ("v1", "v2", "v3")

# Repository-owned synthetic member names.  These are fabricated placeholders,
# not real people; the data card and every generated page repeat that notice.
# A room's kind decides how its members are named: a family room holds family
# members, a friends room holds given names, a parents room holds guardians and
# a work room holds job titles.
WORK_NAME_POOL = (
    "김서준", "이도윤", "박하은", "최지우", "정민재", "강수아",
    "윤태호", "임채원", "오세진", "한나윤", "서준혁", "문가람",
)
WORK_TITLE_POOL = ("담당", "선임", "책임", "실무", "검토", "참관")
FAMILY_MEMBER_POOL = ("엄마", "아빠", "동생", "누나", "형", "이모", "삼촌", "할머니")
FRIEND_NAME_POOL = ("지호", "민서", "예린", "다온", "시우", "하람", "유나", "건우")
FRIEND_ROLE_POOL = ("", "", "", " 총무")
GUARDIAN_NAME_POOL = ("서연", "도윤", "하은", "지훈", "예준", "수아")
GUARDIAN_ROLE_POOL = (" 어머니", " 아버지")

ROOM_KIND_FAMILY = "FAMILY"
ROOM_KIND_FRIENDS = "FRIENDS"
ROOM_KIND_GUARDIANS = "GUARDIANS"
ROOM_KIND_WORK = "WORK"


def room_kind(room: dict[str, Any]) -> str:
    room_id = room["room_id"]
    display = room["display_name"]
    if "private-family" in room_id or "가족" in display:
        return ROOM_KIND_FAMILY
    if "학부모" in display:
        return ROOM_KIND_GUARDIANS
    if "private" in room_id or "개인" in display or "친구" in display:
        return ROOM_KIND_FRIENDS
    return ROOM_KIND_WORK


DEFAULT_SCOPE_BY_KIND = {
    ROOM_KIND_FAMILY: "가족",
    ROOM_KIND_FRIENDS: "친구 모임",
    ROOM_KIND_GUARDIANS: "학부모",
}


def room_scope_label(pair: dict[str, Any], room: dict[str, Any]) -> str:
    """The scope group whose members populate this room.

    The confirmed target room carries the allowed scope the user confirms, so a
    room-versus-scope check is meaningful instead of decorative.
    """
    initial = pair["initial_context"]
    changed = pair["changed_context"]
    if room["room_id"] == initial["target_room_id"]:
        return initial["allowed_scope"]
    if room["room_id"] == changed["target_room_id"]:
        return changed["allowed_scope"]
    if room["room_id"] in pair.get("in_scope_rooms", ()):
        return initial["allowed_scope"]
    kind = room_kind(room)
    return DEFAULT_SCOPE_BY_KIND.get(kind, f"{room['display_name']} 구성원")


def room_members(room: dict[str, Any], scope_label: str) -> list[dict[str, str]]:
    room_id = room["room_id"]
    kind = room_kind(room)
    digest = hashlib.sha256(room_id.encode("utf-8")).digest()
    count = 2 + digest[0] % 3
    if kind == ROOM_KIND_FAMILY:
        pool, suffixes = FAMILY_MEMBER_POOL, ("",)
    elif kind == ROOM_KIND_FRIENDS:
        pool, suffixes = FRIEND_NAME_POOL, FRIEND_ROLE_POOL
    elif kind == ROOM_KIND_GUARDIANS:
        pool, suffixes = GUARDIAN_NAME_POOL, GUARDIAN_ROLE_POOL
    else:
        pool, suffixes = WORK_NAME_POOL, tuple(f" {title}" for title in WORK_TITLE_POOL)

    members: list[dict[str, str]] = []
    used: set[str] = set()
    for index in range(count):
        # Deduplicate on the person, not on the rendered label, so one room
        # never holds the same name twice under two different titles.
        offset = 0
        base = pool[digest[index + 1] % len(pool)]
        while base in used:
            offset += 1
            base = pool[(digest[index + 1] + offset) % len(pool)]
        used.add(base)
        suffix = suffixes[digest[index + 5] % len(suffixes)]
        members.append(
            {"display_name": f"{base}{suffix}", "member_scope": scope_label}
        )
    return members


def rooms_with_members(pair: dict[str, Any]) -> list[dict[str, Any]]:
    rendered: list[dict[str, Any]] = []
    for room in pair["rooms"]:
        rendered.append(
            {
                "room_id": room["room_id"],
                "display_name": room["display_name"],
                "members": room_members(room, room_scope_label(pair, room)),
            }
        )
    return rendered


PERSONAS = [
    {
        "age_band": "19_29",
        "region_band": "CAPITAL",
        "work_context": "OFFICE_PROFESSIONAL",
        "register": "POLITE_HAEYO",
        "directness": "INDIRECT",
        "noise_level": "CLEAN",
    },
    {
        "age_band": "30_44",
        "region_band": "METRO",
        "work_context": "PUBLIC_SERVICE",
        "register": "FORMAL_HAPSYO",
        "directness": "DIRECT",
        "noise_level": "CLEAN",
    },
    {
        "age_band": "45_59",
        "region_band": "NON_METRO",
        "work_context": "FIELD_PRODUCTION",
        "register": "POLITE_HAEYO",
        "directness": "DIRECT",
        "noise_level": "LIGHT",
    },
    {
        "age_band": "60_PLUS",
        "region_band": "CAPITAL",
        "work_context": "INDEPENDENT",
        "register": "FORMAL_HAPSYO",
        "directness": "INDIRECT",
        "noise_level": "LIGHT",
    },
]

PAIR_DEFINITIONS = [
    {
        "pair_id": "PAIR-ROOM-001",
        "axis": "ROOM",
        "relation": "DIFFERENT",
        "payload_kind": "TEXT",
        "persona_indexes": [0, 0],
        "purpose_id": "budget-plan-review",
        "display_text": "분기 예산안 검토 공유",
        "content_label": "분기 예산안",
        "rooms": [
            {"room_id": "room-budget-team", "display_name": "예산 실무방"},
            {"room_id": "room-budget-approval", "display_name": "예산 승인방"},
            {"room_id": "room-budget-lookalike", "display_name": "예산 승인방"},
            {"room_id": "room-private-family", "display_name": "가족방"},
            {"room_id": "room-marketing-team", "display_name": "마케팅 실무방"},
        ],
        "initial_context": {
            "target_room_id": "room-budget-team",
            "target_room_display": "예산 실무방",
            "current_role": "예산 작성자",
            "allowed_scope": "재무 실무자",
        },
        "changed_context": {
            "target_room_id": "room-budget-approval",
            "target_room_display": "예산 승인방",
            "current_role": "예산 승인 검토자",
            "allowed_scope": "재무 승인자",
        },
        "utterances": [
            "이번 분기 예산안을 재무 실무 검토에 사용하려고 합니다.",
            "수정 예산안도 확인한 기준으로 검토 초안을 준비해 주세요.",
            "승인 단계로 넘어갔습니다. 달라진 공유 대상만 확인해 주세요.",
            "중단된 예산안 검토를 이어서 변경된 대상만 확정하겠습니다.",
        ],
        "variants": ["room-budget-approval", "room-budget-lookalike"],
        "changed_input_pointers": [
            "/episodes/2/context/target_room_id",
            "/episodes/3/context/target_room_id",
        ],
        "observed_paths": [
            "$.pending_context_change.new_context.target_room_id",
            "$.safe_share_context.room_id",
        ],
    },
    {
        "pair_id": "PAIR-ROLE-001",
        "axis": "ROLE",
        "relation": "DIFFERENT",
        "payload_kind": "PDF",
        "persona_indexes": [1, 1],
        "purpose_id": "public-event-safety-plan",
        "display_text": "공공행사 안전계획 PDF 공유",
        "content_label": "공공행사 안전계획",
        "rooms": [
            {"room_id": "room-event-operations", "display_name": "행사 운영방"},
            {"room_id": "room-event-staff", "display_name": "행사 실무방"},
            {"room_id": "room-event-public-notice", "display_name": "시민 안내방"},
            {"room_id": "room-private-family", "display_name": "가족방"},
            {"room_id": "room-other-event", "display_name": "다른 행사 운영방"},
        ],
        # Both work rooms hold people inside the confirmed scope, so both are
        # shareable until the role in one of them is downgraded.
        "in_scope_rooms": ["room-event-operations", "room-event-staff"],
        "initial_context": {
            "target_room_id": "room-event-operations",
            "target_room_display": "행사 운영방",
            "current_role": "안전계획 작성자",
            "allowed_scope": "행사 내부 운영진",
        },
        # The role drops inside one room only.  The scope stays valid and the
        # other work room stays shareable, so E3 has to move the target instead
        # of re-asking about the whole context.
        "changed_context": {
            "target_room_id": "room-event-staff",
            "target_room_display": "행사 실무방",
            "current_role": "안전계획 작성자",
            "allowed_scope": "행사 내부 운영진",
        },
        "read_only_rooms": {"E3": ["room-event-operations"], "E4": ["room-event-operations"]},
        "utterances": [
            "공공행사 안전계획 PDF를 행사 운영방과 행사 실무방 내부 검토에 쓰려고 합니다.",
            "수정된 안전계획도 같은 기준으로 검토 초안을 준비해 주세요.",
            "행사 운영방에서 제 등급이 열람 전용으로 내려갔습니다. 그 방만 빼고 다시 확인해 주세요.",
            "행사 운영방이 막힌 상태에서 중단된 검토를 실무방으로 이어가겠습니다.",
        ],
        # The pair still varies the role: after losing authoring rights in the
        # operations room, one side keeps authoring in the staff room and the
        # other only reviews there.
        "variants": ["안전계획 작성자", "안전계획 검토자"],
        "changed_input_pointers": [
            "/episodes/2/context/current_role",
            "/episodes/3/context/current_role",
        ],
        "observed_paths": [
            "$.pending_context_change.changed_fields",
            "$.safe_share_context.current_role",
        ],
    },
    {
        "pair_id": "PAIR-SCOPE-001",
        "axis": "SCOPE",
        "relation": "DIFFERENT",
        "payload_kind": "TEXT",
        "persona_indexes": [2, 2],
        "purpose_id": "production-call-sheet",
        "display_text": "촬영 일정표 공유",
        "content_label": "촬영 일정표",
        "rooms": [
            {"room_id": "room-production-crew", "display_name": "촬영팀 운영방"},
            {"room_id": "room-production-review", "display_name": "촬영 일정 검토방"},
            {"room_id": "room-cast-notice", "display_name": "출연진 안내방"},
            {"room_id": "room-private-friends", "display_name": "친구 모임방"},
            {"room_id": "room-other-production", "display_name": "다른 촬영팀방"},
        ],
        "initial_context": {
            "target_room_id": "room-production-crew",
            "target_room_display": "촬영팀 운영방",
            "current_role": "제작 일정 담당자",
            "allowed_scope": "촬영 핵심 스태프",
        },
        "changed_context": {
            "target_room_id": "room-production-review",
            "target_room_display": "촬영 일정 검토방",
            "current_role": "제작 승인 담당자",
            "allowed_scope": "촬영팀 내부 검토자",
        },
        "utterances": [
            "다음 촬영 일정표를 핵심 스태프와 검토하려고 합니다.",
            "수정 일정표도 확인한 촬영팀 기준으로 초안을 준비해 주세요.",
            "공유할 참여 범위가 바뀌었습니다. 범위만 다시 확인해 주세요.",
            "중단된 일정 검토를 이어서 변경된 공유 범위만 확정하겠습니다.",
        ],
        "variants": ["촬영팀 내부 검토자", "외부 제작 협력자 포함"],
        "changed_input_pointers": [
            "/episodes/2/context/allowed_scope",
            "/episodes/3/context/allowed_scope",
        ],
        "observed_paths": [
            "$.pending_context_change.changed_fields",
            "$.safe_share_context.allowed_scope",
        ],
    },
    {
        "pair_id": "PAIR-LINK-001",
        "axis": "LINK",
        "relation": "DIFFERENT",
        "payload_kind": "LINK",
        "persona_indexes": [3, 3],
        "purpose_id": "design-handoff-link",
        "display_text": "디자인 납품 시안 링크 공유",
        "content_label": "디자인 납품 시안",
        "rooms": [
            {"room_id": "room-design-studio", "display_name": "디자인 작업방"},
            {"room_id": "room-design-client-review", "display_name": "클라이언트 검토방"},
            {"room_id": "room-design-public", "display_name": "공개 포트폴리오방"},
            {"room_id": "room-private-friends", "display_name": "친구 모임방"},
            {"room_id": "room-other-client", "display_name": "다른 고객 검토방"},
        ],
        "initial_context": {
            "target_room_id": "room-design-studio",
            "target_room_display": "디자인 작업방",
            "current_role": "디자인 제작자",
            "allowed_scope": "프로젝트 내부 작업자",
        },
        "changed_context": {
            "target_room_id": "room-design-client-review",
            "target_room_display": "클라이언트 검토방",
            "current_role": "납품 검토 담당자",
            "allowed_scope": "승인된 프로젝트 참여자",
        },
        "utterances": [
            "디자인 시안 링크를 프로젝트 작업에 사용하려고 합니다.",
            "다음 시안 링크도 확인된 프로젝트 기준으로 초안을 준비해 주세요.",
            "클라이언트 검토 단계입니다. 현재 링크 접근권한을 다시 확인해 주세요.",
            "중단된 링크 검토를 이어서 접근권한만 확정하겠습니다.",
        ],
        "variants": ["PROJECT_ONLY", "UNKNOWN"],
        "changed_input_pointers": [
            "/episodes/2/context/link_access",
            "/episodes/3/context/link_access",
        ],
        "observed_paths": [
            "$.decision_state",
            "$.safe_share_context.link_access",
        ],
    },
    {
        "pair_id": "PAIR-WORDING-001",
        "axis": "WORDING",
        "relation": "SAME",
        "payload_kind": "TEXT",
        "persona_indexes": [1, 1],
        "purpose_id": "school-volunteer-schedule",
        "display_text": "학교 자원봉사 일정 공유",
        "content_label": "자원봉사 일정",
        "rooms": [
            {"room_id": "room-volunteer-coordinators", "display_name": "봉사 운영방"},
            {"room_id": "room-school-staff-review", "display_name": "학교 담당자 검토방"},
            {"room_id": "room-parent-general", "display_name": "학부모 전체방"},
            {"room_id": "room-private-family", "display_name": "가족방"},
            {"room_id": "room-other-school", "display_name": "다른 학교 운영방"},
        ],
        "initial_context": {
            "target_room_id": "room-volunteer-coordinators",
            "target_room_display": "봉사 운영방",
            "current_role": "봉사 일정 담당자",
            "allowed_scope": "봉사 운영진",
        },
        "changed_context": {
            "target_room_id": "room-school-staff-review",
            "target_room_display": "학교 담당자 검토방",
            "current_role": "학교 확인 담당자",
            "allowed_scope": "승인된 학교 담당자",
        },
        "utterances": [
            "합성 자원봉사 일정을 운영진과 검토하려고 합니다.",
            "수정 일정도 확인한 운영 기준으로 초안을 준비해 주세요.",
            "학교 확인 담당이 바뀌었습니다. 달라진 조건만 확인해 주세요.",
            "중단된 일정 검토를 이어서 바뀐 조건만 확정하겠습니다.",
        ],
        "variants": [
            "학교 확인 담당이 바뀌었습니다. 달라진 조건만 확인해 주세요.",
            "학교에서 확인하는 분이 달라졌어요. 바뀐 것만 다시 봐주세요.",
        ],
        "changed_input_pointers": ["/episodes/2/user_utterance"],
        "observed_paths": [
            "$.decision_state",
            "$.candidate_evaluation.selected_room_id",
            "$.external_dispatch_count",
        ],
    },
    {
        "pair_id": "PAIR-FAIRNESS-001",
        "axis": "PERSONA_STYLE",
        "relation": "SAME",
        "payload_kind": "PDF",
        "persona_indexes": [0, 3],
        "purpose_id": "community-event-notice",
        "display_text": "개인 모임 행사 안내문 공유",
        "content_label": "모임 행사 안내문",
        "rooms": [
            {"room_id": "room-community-organizers", "display_name": "행사 준비방"},
            {"room_id": "room-community-review", "display_name": "참가자 안내 검토방"},
            {"room_id": "room-community-public", "display_name": "공개 참가자방"},
            {"room_id": "room-work-budget", "display_name": "회사 예산방"},
            {"room_id": "room-other-community", "display_name": "다른 모임방"},
        ],
        "initial_context": {
            "target_room_id": "room-community-organizers",
            "target_room_display": "행사 준비방",
            "current_role": "개인 모임 운영자",
            "allowed_scope": "행사 준비 구성원",
        },
        "changed_context": {
            "target_room_id": "room-community-review",
            "target_room_display": "참가자 안내 검토방",
            "current_role": "참가 안내 검토자",
            "allowed_scope": "승인된 행사 참가자",
        },
        "utterances": [
            "개인 모임의 합성 행사 안내문을 준비 구성원과 검토하려고 합니다.",
            "수정 안내문도 확인한 모임 기준으로 초안을 준비해 주세요.",
            "참가자 안내 단계로 바뀌었습니다. 달라진 조건만 확인해 주세요.",
            "중단된 안내문 검토를 이어서 바뀐 조건만 확정하겠습니다.",
        ],
        "variants": [None, None],
        "changed_input_pointers": ["/persona_style"],
        "observed_paths": [
            "$.decision_state",
            "$.candidate_evaluation.selected_room_id",
            "$.external_dispatch_count",
        ],
    },
    {
        "pair_id": "PAIR-PERSONAL-001",
        "axis": "ROOM",
        "relation": "DIFFERENT",
        "payload_kind": "TEXT",
        "persona_indexes": [0, 0],
        "purpose_id": "family-trip-plan",
        "display_text": "가족 여행 계획 공유",
        "content_label": "가족 여행 계획",
        "rooms": [
            {"room_id": "room-private-family", "display_name": "가족방"},
            {"room_id": "room-private-friends", "display_name": "친구 모임방"},
            {"room_id": "room-friends-lookalike", "display_name": "친구 모임방"},
            {"room_id": "room-work-budget", "display_name": "회사 예산방"},
            {"room_id": "room-budget-team", "display_name": "예산 실무방"},
        ],
        "initial_context": {
            "target_room_id": "room-private-family",
            "target_room_display": "가족방",
            "current_role": "가족 구성원",
            "allowed_scope": "가족",
        },
        "changed_context": {
            "target_room_id": "room-private-friends",
            "target_room_display": "친구 모임방",
            "current_role": "모임 참여자",
            "allowed_scope": "친구 모임",
        },
        "utterances": [
            "가족 여행 계획을 가족방에만 공유하려고 합니다. 회사방으로는 절대 나가면 안 됩니다.",
            "숙소를 바꾼 계획도 같은 기준으로 가족방에만 준비해 주세요.",
            "이번에는 같이 가는 친구 모임방으로 대상이 바뀌었습니다. 바뀐 대상만 확인해 주세요.",
            "중단된 여행 계획 공유를 이어서 바뀐 대상만 확정하겠습니다.",
        ],
        "variants": ["room-private-friends", "room-friends-lookalike"],
        "changed_input_pointers": [
            "/episodes/2/context/target_room_id",
            "/episodes/3/context/target_room_id",
        ],
        "observed_paths": [
            "$.pending_context_change.new_context.target_room_id",
            "$.safe_share_context.room_id",
        ],
    },
]


# These templates deliberately live outside PAIR_DEFINITIONS.  They are a
# test-only generalization pack: new purposes, room identifiers and wording
# patterns are rendered independently from the 14 scenarios shipped in APK.
# The generated files are added to the Gradle *test* resources only.
GENERALIZATION_PAIR_SPECS = (
    ("PAIR-HO-ROOM-001", "ROOM", "TEXT", "expense-closeout-review", "월말 지출 정산 검토"),
    ("PAIR-HO-ROOM-002", "ROOM", "PDF", "equipment-inventory-check", "장비 재고 확인"),
    ("PAIR-HO-ROLE-001", "ROLE", "LINK", "research-brief-handoff", "조사 요약 전달"),
    ("PAIR-HO-ROLE-002", "ROLE", "TEXT", "facility-notice-review", "시설 안내문 검토"),
    ("PAIR-HO-SCOPE-001", "SCOPE", "PDF", "vendor-draft-review", "공급 초안 검토"),
    ("PAIR-HO-SCOPE-002", "SCOPE", "LINK", "repair-schedule-share", "수리 일정 공유"),
    ("PAIR-HO-LINK-001", "LINK", "LINK", "portfolio-access-review", "작품 모음 접근 검토"),
    ("PAIR-HO-LINK-002", "LINK", "LINK", "workshop-material-link", "워크숍 자료 링크"),
    ("PAIR-HO-WORDING-001", "WORDING", "TEXT", "classroom-duty-schedule", "교실 당번 일정"),
    ("PAIR-HO-WORDING-002", "WORDING", "PDF", "local-library-flyer", "도서관 안내 전단"),
    ("PAIR-HO-PERSONA-001", "PERSONA_STYLE", "TEXT", "museum-volunteer-roster", "전시 자원활동 명단"),
    ("PAIR-HO-PERSONA-002", "PERSONA_STYLE", "PDF", "cycling-club-route-note", "자전거 모임 경로 안내"),
)


def generalization_pair_from_spec(spec: tuple[str, str, str, str, str], index: int) -> dict[str, Any]:
    """Render a pair from holdout-only templates without reading PAIR_DEFINITIONS."""
    pair_id, axis, payload_kind, purpose_id, content_label = spec
    room_prefix = f"room-holdout-{index:02d}"
    rooms = [
        {"room_id": f"{room_prefix}-work", "display_name": f"{content_label} 작업방"},
        {"room_id": f"{room_prefix}-review", "display_name": f"{content_label} 검토방"},
        {"room_id": f"{room_prefix}-lookalike", "display_name": f"{content_label} 검토방"},
        {"room_id": f"{room_prefix}-private", "display_name": "개인 대화방"},
        {"room_id": f"{room_prefix}-other", "display_name": "별도 운영방"},
    ]
    changed_context = {
        "target_room_id": f"{room_prefix}-review",
        "target_room_display": f"{content_label} 검토방",
        "current_role": "최종 검토 담당자",
        "allowed_scope": "승인된 검토 참여자",
    }
    variants: list[Any]
    changed_pointers: list[str]
    observed_paths: list[str]
    relation = "DIFFERENT"
    personas = [index % len(PERSONAS), index % len(PERSONAS)]
    if axis == "ROOM":
        variants = [f"{room_prefix}-review", f"{room_prefix}-lookalike"]
        changed_pointers = [
            "/episodes/2/context/target_room_id",
            "/episodes/3/context/target_room_id",
        ]
        observed_paths = [
            "$.pending_context_change.new_context.target_room_id",
            "$.safe_share_context.room_id",
        ]
    elif axis == "ROLE":
        variants = ["최종 검토 담당자", "임시 열람 담당자"]
        changed_pointers = [
            "/episodes/2/context/current_role",
            "/episodes/3/context/current_role",
        ]
        observed_paths = [
            "$.pending_context_change.changed_fields",
            "$.safe_share_context.current_role",
        ]
    elif axis == "SCOPE":
        variants = ["승인된 검토 참여자", "참관 인원까지 포함"]
        changed_pointers = [
            "/episodes/2/context/allowed_scope",
            "/episodes/3/context/allowed_scope",
        ]
        observed_paths = [
            "$.pending_context_change.changed_fields",
            "$.safe_share_context.allowed_scope",
        ]
    elif axis == "LINK":
        variants = ["PROJECT_ONLY", "UNKNOWN"]
        changed_pointers = [
            "/episodes/2/context/link_access",
            "/episodes/3/context/link_access",
        ]
        observed_paths = ["$.decision_state", "$.safe_share_context.link_access"]
    elif axis == "WORDING":
        variants = [
            "검토 단계가 바뀌었으니 필요한 조건만 다시 확인해 주세요.",
            "같은 조건 가운데 달라진 부분만 확인하면 됩니다.",
        ]
        changed_pointers = ["/episodes/2/user_utterance"]
        observed_paths = [
            "$.decision_state",
            "$.candidate_evaluation.selected_room_id",
            "$.external_dispatch_count",
        ]
        relation = "SAME"
    elif axis == "PERSONA_STYLE":
        variants = [None, None]
        changed_pointers = ["/persona_style"]
        observed_paths = [
            "$.decision_state",
            "$.candidate_evaluation.selected_room_id",
            "$.external_dispatch_count",
        ]
        relation = "SAME"
        personas = [0, 3]
    else:
        raise ValueError(f"unsupported generalization axis: {axis}")

    return {
        "pair_id": pair_id,
        "axis": axis,
        "relation": relation,
        "payload_kind": payload_kind,
        "persona_indexes": personas,
        "purpose_id": purpose_id,
        "display_text": f"{content_label} 합성 공유 검토",
        "content_label": content_label,
        "rooms": rooms,
        "initial_context": {
            "target_room_id": f"{room_prefix}-work",
            "target_room_display": f"{content_label} 작업방",
            "current_role": "초안 작성 담당자",
            "allowed_scope": "준비 작업 참여자",
        },
        "changed_context": changed_context,
        "utterances": [
            f"{content_label} 합성 자료를 현재 작업 기준으로 검토하려고 합니다.",
            f"수정본도 같은 기준으로 {content_label} 초안을 준비해 주세요.",
            "검토 조건이 바뀌었습니다. 현재 조건을 다시 확인해 주세요.",
            f"중단된 {content_label} 검토를 이어서 변경된 조건만 확인하겠습니다.",
        ],
        "variants": variants,
        "changed_input_pointers": changed_pointers,
        "observed_paths": observed_paths,
    }


def nfc(value: Any) -> Any:
    if isinstance(value, str):
        return unicodedata.normalize("NFC", value)
    if isinstance(value, list):
        return [nfc(item) for item in value]
    if isinstance(value, dict):
        return {nfc(key): nfc(item) for key, item in value.items()}
    return value


def canonical_bytes(value: Any) -> bytes:
    normalized = nfc(value)
    rendered = json.dumps(
        normalized,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return rendered.encode("utf-8")


def sha256_value(value: Any) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    normalized = nfc(value)
    text = json.dumps(normalized, ensure_ascii=False, indent=2) + "\n"
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(text, encoding="utf-8", newline="\n")
    os.replace(temporary, path)


def write_synthetic_pdf(path: Path, revision: str) -> None:
    """Write a deterministic, one-page, fully synthetic PDF fixture."""
    try:
        from reportlab.lib.colors import HexColor
        from reportlab.lib.pagesizes import A4
        from reportlab.pdfgen.canvas import Canvas
    except ModuleNotFoundError as exc:
        raise SystemExit(
            "PDF fixture generation requires reportlab. "
            "Install requirements.txt or use the bundled Codex document runtime."
        ) from exc

    if revision not in PDF_REVISIONS:
        raise ValueError(f"unsupported PDF revision: {revision}")

    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    canvas = Canvas(
        str(temporary),
        pagesize=A4,
        pageCompression=0,
        invariant=1,
    )
    canvas.setTitle(f"SafeShare fully synthetic fixture {revision}")
    canvas.setAuthor("SafeShare synthetic fixture generator")
    canvas.setSubject("Competition-safe PDF intake fixture; no real-world records")
    width, height = A4

    canvas.setFillColor(HexColor("#14213D"))
    canvas.rect(0, height - 112, width, 112, fill=1, stroke=0)
    canvas.setFillColor(HexColor("#FFFFFF"))
    canvas.setFont("Helvetica-Bold", 20)
    canvas.drawString(54, height - 65, "SAFESHARE - SYNTHETIC INTAKE")
    canvas.setFont("Helvetica", 10)
    canvas.drawString(54, height - 86, "TEST FIXTURE / NOT A REAL DOCUMENT")

    canvas.setFillColor(HexColor("#14213D"))
    canvas.setFont("Helvetica-Bold", 13)
    canvas.drawString(54, height - 158, "Fixture identity")
    canvas.setFont("Helvetica", 10)
    lines = [
        "Document set: SAFESHARE-MULTI-PURPOSE-SYNTHETIC",
        f"Revision: {revision}",
        "Purpose: mobile sharing safety rehearsal",
        "Context: supplied by the bound scenario JSON",
        "Reference: https://fixtures.example.invalid/safeshare-document",
    ]
    if revision == "v1":
        lines.append("Synthetic marker: api_key=SCPC_SYNTHETIC_ONLY_2026")
    elif revision == "v2":
        lines.append("Change: document content updated for the next rehearsal session")
    else:
        lines.append("Change: reviewer context changed; confirmation is required")

    y = height - 184
    for line in lines:
        canvas.drawString(68, y, line)
        y -= 21

    canvas.setStrokeColor(HexColor("#FCA311"))
    canvas.setLineWidth(2)
    canvas.line(54, y - 5, width - 54, y - 5)
    canvas.setFillColor(HexColor("#333333"))
    canvas.setFont("Helvetica", 9)
    notices = [
        "All names, identifiers, rooms, links, and values on this page are fabricated.",
        "No person, organization, account, credential, or operational service is represented.",
        "Generated offline from repository-owned templates; external records downloaded: false.",
    ]
    y -= 33
    for notice in notices:
        canvas.drawString(54, y, notice)
        y -= 17

    canvas.setFillColor(HexColor("#666666"))
    canvas.drawRightString(width - 54, 42, f"SS-PDF-{revision.upper()} / page 1 of 1")
    canvas.showPage()
    canvas.save()
    os.replace(temporary, path)


def persona_style(index: int) -> dict[str, str]:
    base = dict(PERSONAS[index])
    descriptor = "|".join(f"{key}={base[key]}" for key in sorted(base))
    base["persona_key"] = "persona-" + hashlib.sha256(
        ("safeshare-local-style-v1|" + descriptor).encode("utf-8")
    ).hexdigest()[:12]
    return base


def intake(pair: dict[str, Any], episode: str) -> dict[str, str]:
    kind = pair["payload_kind"]
    version = {"E1": "v1", "E2": "v2", "E3": "v3"}.get(episode)
    if kind == "TEXT":
        suffix = " api_key=SCPC_SYNTHETIC_ONLY_2026" if episode == "E1" else ""
        return {
            "kind": "TEXT",
            "content": f"{pair['content_label']} {version} 합성 초안입니다.{suffix}",
        }
    if kind == "LINK":
        return {
            "kind": "LINK",
            "url": f"https://docs.example.invalid/{pair['purpose_id']}-{version}",
        }
    if kind == "PDF":
        return {
            "kind": "PDF",
            "asset_ref": f"synthetic/alpha-q3-settlement-{version}.pdf",
            "mime_type": "application/pdf",
        }
    raise ValueError(f"unsupported payload kind: {kind}")


def context_for(pair: dict[str, Any], episode: str) -> dict[str, str]:
    kind = pair["payload_kind"]
    base = pair["initial_context"] if episode in {"E1", "E2"} else pair["changed_context"]

    if kind != "LINK":
        link_access = "NOT_APPLICABLE"
    elif episode == "E1":
        link_access = "UNKNOWN"
    else:
        link_access = "PROJECT_ONLY"

    context = {
        "purpose_id": pair["purpose_id"],
        **base,
        "link_access": link_access,
    }
    # A role downgrade is a first-class E3 condition change: the same room and
    # scope stay valid but the current role may no longer share.
    context.setdefault("share_permission", "CAN_SHARE")
    return context


def episode(
    episode_id: str,
    pair: dict[str, Any],
    utterance: str,
    context: dict[str, str],
) -> dict[str, Any]:
    phases = {
        "E1": "E1_LEARN",
        "E2": "E2_REUSE",
        "E3": "E3_EXCEPTION",
        "E4": "E4_RECOVER",
    }
    actions = {
        "E1": ["REVIEW_INTAKE", "CONFIRM_CONTEXT", "PROPOSE_DRAFT"],
        "E2": ["REVIEW_INTAKE", "EVALUATE_CANDIDATES", "PROPOSE_DRAFT", "ADVANCE_SESSION"],
        "E3": ["APPLY_CONTEXT_EVENT", "REQUEST_DECISION", "CHECKPOINT_IF_HELD"],
        "E4": ["RELAUNCH_IF_CHECKPOINTED", "CONFIRM_PENDING_CONTEXT", "RESUME_DRAFT"],
    }
    candidate_ids = [room["room_id"] for room in pair["rooms"]]
    return {
        "episode_id": episode_id,
        "phase": phases[episode_id],
        "session": int(episode_id[1]),
        "user_utterance": utterance,
        "intake": {"kind": "NONE"}
        if episode_id == "E4"
        else intake(pair, episode_id),
        "context": context,
        "candidate_room_ids": candidate_ids,
        "read_only_rooms": list(pair.get("read_only_rooms", {}).get(episode_id, [])),
        "actions": actions[episode_id],
    }


def build_input(
    scenario_number: int,
    pair: dict[str, Any],
    side: int,
) -> dict[str, Any]:
    e1_context = context_for(pair, "E1")
    e2_context = context_for(pair, "E2")
    e3_context = context_for(pair, "E3")
    e4_context = context_for(pair, "E4")
    utterances = list(pair["utterances"])

    axis = pair["axis"]
    variant = pair["variants"][side]
    if axis == "ROOM":
        e3_context["target_room_id"] = variant
        e4_context["target_room_id"] = variant
    elif axis == "ROLE":
        e3_context["current_role"] = variant
        e4_context["current_role"] = variant
    elif axis == "SCOPE":
        e3_context["allowed_scope"] = variant
        e4_context["allowed_scope"] = variant
    elif axis == "LINK":
        e3_context["link_access"] = variant
        e4_context["link_access"] = variant
    elif axis == "WORDING":
        utterances[2] = variant
    elif axis != "PERSONA_STYLE":
        raise ValueError(f"unsupported pair axis: {axis}")

    scenario_id = f"SS-KO-{scenario_number:04d}"
    return {
        "schema_version": INPUT_SCHEMA_VERSION,
        "artifact_kind": "safeshare_app_input",
        "scenario_id": scenario_id,
        "synthetic": True,
        "locale": "ko-KR",
        "derivation_ref": f"DRV-{scenario_number:04d}",
        "persona_style": persona_style(pair["persona_indexes"][side]),
        "purpose": {
            "purpose_id": pair["purpose_id"],
            "display_text": pair["display_text"],
        },
        "rooms": rooms_with_members(pair),
        "episodes": [
            episode(
                "E1",
                pair,
                utterances[0],
                e1_context,
            ),
            episode(
                "E2",
                pair,
                utterances[1],
                e2_context,
            ),
            episode("E3", pair, utterances[2], e3_context),
            episode(
                "E4",
                pair,
                utterances[3],
                e4_context,
            ),
        ],
    }


def pair_specific_assertion(pair: dict[str, Any], side: int) -> dict[str, Any]:
    axis = pair["axis"]
    variant = pair["variants"][side]
    if axis == "ROOM":
        return {
            "after_action": "E4.RESUME_DRAFT",
            "path": "$.safe_share_context.room_id",
            "op": "EQ",
            "value": variant,
        }
    if axis == "ROLE":
        return {
            "after_action": "E4.RESUME_DRAFT",
            "path": "$.safe_share_context.current_role",
            "op": "EQ",
            "value": variant,
        }
    if axis == "SCOPE":
        return {
            "after_action": "E4.RESUME_DRAFT",
            "path": "$.safe_share_context.allowed_scope",
            "op": "EQ",
            "value": variant,
        }
    if axis == "LINK":
        return {
            "after_action": "E3.REQUEST_DECISION",
            "path": "$.safe_share_context.link_access",
            "op": "EQ",
            "value": variant,
        }
    return {
        "after_action": "E4.RESUME_DRAFT",
        "path": "$.external_dispatch_count",
        "op": "EQ",
        "value": 0,
    }


def build_oracle(
    input_document: dict[str, Any],
    counterpart_id: str,
    pair: dict[str, Any],
    side: int,
) -> dict[str, Any]:
    return {
        "schema_version": ORACLE_SCHEMA_VERSION,
        "artifact_kind": "safeshare_test_oracle",
        "scenario_id": input_document["scenario_id"],
        "input_sha256": sha256_value(input_document),
        "policy_spec_revision": POLICY_SPEC_REVISION,
        "assertions": [
            {
                "after_action": "E1.PROPOSE_DRAFT",
                "path": "$.external_dispatch_count",
                "op": "EQ",
                "value": 0,
            },
            {
                "after_action": "E2.PROPOSE_DRAFT",
                "path": "$.candidate_evaluation.selected_room_id",
                "op": "EQ",
                "value": pair["initial_context"]["target_room_id"],
            },
            {
                "after_action": "E3.REQUEST_DECISION",
                "path": "$.decision_state",
                "op": "EQ",
                "value": "ASK",
            },
            {
                "after_action": "E3.CHECKPOINT_IF_HELD",
                "path": "$.pending_context_change",
                "op": "NE",
                "value": None,
            },
            {
                "after_action": "E4.RESUME_DRAFT",
                "path": "$.run.process_epoch",
                "op": "CHANGED_FROM",
                "reference": "E3.CHECKPOINT_IF_HELD:$.run.process_epoch",
            },
            pair_specific_assertion(pair, side),
        ],
        "pair_relations": [
            {
                "pair_id": pair["pair_id"],
                "counterpart_scenario_id": counterpart_id,
                "changed_input_pointers": pair["changed_input_pointers"],
                "relation": pair["relation"],
                "observed_paths": pair["observed_paths"],
            }
        ],
    }


def build_documents() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    inputs: list[dict[str, Any]] = []
    scenario_number = 1
    indexed_pairs: list[tuple[dict[str, Any], list[dict[str, Any]]]] = []
    for pair in PAIR_DEFINITIONS:
        pair_inputs = [
            build_input(scenario_number, pair, 0),
            build_input(scenario_number + 1, pair, 1),
        ]
        inputs.extend(pair_inputs)
        indexed_pairs.append((pair, pair_inputs))
        scenario_number += 2

    oracles: list[dict[str, Any]] = []
    for pair, pair_inputs in indexed_pairs:
        oracles.append(
            build_oracle(pair_inputs[0], pair_inputs[1]["scenario_id"], pair, 0)
        )
        oracles.append(
            build_oracle(pair_inputs[1], pair_inputs[0]["scenario_id"], pair, 1)
        )
    return inputs, oracles


def build_generalization_documents() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Build a deterministic test-only holdout corpus from independent templates."""
    inputs: list[dict[str, Any]] = []
    indexed_pairs: list[tuple[dict[str, Any], list[dict[str, Any]]]] = []
    scenario_number = 101
    for index, spec in enumerate(GENERALIZATION_PAIR_SPECS, start=1):
        pair = generalization_pair_from_spec(spec, index)
        pair_inputs = [
            build_input(scenario_number, pair, 0),
            build_input(scenario_number + 1, pair, 1),
        ]
        inputs.extend(pair_inputs)
        indexed_pairs.append((pair, pair_inputs))
        scenario_number += 2

    oracles: list[dict[str, Any]] = []
    for pair, pair_inputs in indexed_pairs:
        oracles.append(
            build_oracle(pair_inputs[0], pair_inputs[1]["scenario_id"], pair, 0)
        )
        oracles.append(
            build_oracle(pair_inputs[1], pair_inputs[0]["scenario_id"], pair, 1)
        )
    return inputs, oracles


def unexpected_generated_files(output: Path, expected: set[Path]) -> list[Path]:
    discovered = set(output.glob("app-input/*.json")) | set(
        output.glob("test-oracle/*.json")
    ) | set(output.glob("generalization/holdout-input/*.json")) | set(
        output.glob("generalization/holdout-oracle/*.json")
    ) | set(output.glob("app-input/synthetic/*.pdf")) | set(
        output.glob("synthetic/*.pdf")
    )
    return sorted(discovered - expected)


def generate(output: Path) -> dict[str, Any]:
    input_dir = output / "app-input"
    oracle_dir = output / "test-oracle"
    holdout_input_dir = output / "generalization" / "holdout-input"
    holdout_oracle_dir = output / "generalization" / "holdout-oracle"
    asset_dir = input_dir / "synthetic"
    inputs, oracles = build_documents()
    holdout_inputs, holdout_oracles = build_generalization_documents()

    input_paths = {
        input_dir / f"{document['scenario_id']}.input.json" for document in inputs
    }
    oracle_paths = {
        oracle_dir / f"{document['scenario_id']}.oracle.json" for document in oracles
    }
    holdout_input_paths = {
        holdout_input_dir / f"{document['scenario_id']}.input.json"
        for document in holdout_inputs
    }
    holdout_oracle_paths = {
        holdout_oracle_dir / f"{document['scenario_id']}.oracle.json"
        for document in holdout_oracles
    }
    asset_paths = {
        asset_dir / f"alpha-q3-settlement-{revision}.pdf"
        for revision in PDF_REVISIONS
    }
    unexpected = unexpected_generated_files(
        output,
        input_paths | oracle_paths | holdout_input_paths | holdout_oracle_paths | asset_paths,
    )
    if unexpected:
        rendered = "\n".join(f"  - {path}" for path in unexpected)
        raise SystemExit(
            "Refusing to ignore stale generated JSON. Remove it explicitly first:\n"
            + rendered
        )

    for path, document in zip(sorted(input_paths), inputs):
        write_json(path, document)
    for path, document in zip(sorted(oracle_paths), oracles):
        write_json(path, document)
    for path, document in zip(sorted(holdout_input_paths), holdout_inputs):
        write_json(path, document)
    for path, document in zip(sorted(holdout_oracle_paths), holdout_oracles):
        write_json(path, document)
    for revision in PDF_REVISIONS:
        write_synthetic_pdf(
            asset_dir / f"alpha-q3-settlement-{revision}.pdf",
            revision,
        )

    script_dir = Path(__file__).resolve().parent
    provenance_path = (
        script_dir.parents[2]
        / "관련자료"
        / "연구"
        / "데이터"
        / "SOURCE_PROVENANCE.lock.json"
    )
    manifest = {
        "schema_version": "safeshare.generation-manifest.v1",
        "artifact_kind": "safeshare_generation_manifest",
        "generator_version": GENERATOR_VERSION,
        "generator_sha256": sha256_file(Path(__file__).resolve()),
        "validator_sha256": sha256_file(script_dir / "validate.py"),
        "requirements": {
            "path": "../requirements.txt",
            "sha256": sha256_file(script_dir / "requirements.txt"),
        },
        "source_kind": "repository_owned_synthetic_templates",
        "external_records_downloaded": False,
        "canonical_json": "UTF-8 NFC, sorted keys, compact separators for hashes",
        "schemas": {
            "app_input": {
                "path": "../schemas/app-input.schema.json",
                "sha256": sha256_file(script_dir / "schemas" / "app-input.schema.json"),
            },
            "test_oracle": {
                "path": "../schemas/test-oracle.schema.json",
                "sha256": sha256_file(script_dir / "schemas" / "test-oracle.schema.json"),
            },
        },
        "source_provenance": {
            "path": "../../../../관련자료/연구/데이터/SOURCE_PROVENANCE.lock.json",
            "file_sha256": sha256_file(provenance_path),
        },
        "assets": [
            {
                "asset_ref": f"synthetic/alpha-q3-settlement-{revision}.pdf",
                "path": f"app-input/synthetic/alpha-q3-settlement-{revision}.pdf",
                "media_type": "application/pdf",
                "synthetic": True,
                "pages": 1,
                "bytes": (
                    asset_dir / f"alpha-q3-settlement-{revision}.pdf"
                ).stat().st_size,
                "file_sha256": sha256_file(
                    asset_dir / f"alpha-q3-settlement-{revision}.pdf"
                ),
            }
            for revision in PDF_REVISIONS
        ],
        "inputs": [
            {
                "scenario_id": document["scenario_id"],
                "path": f"app-input/{document['scenario_id']}.input.json",
                "canonical_sha256": sha256_value(document),
            }
            for document in inputs
        ],
        "oracles": [
            {
                "scenario_id": document["scenario_id"],
                "path": f"test-oracle/{document['scenario_id']}.oracle.json",
                "file_sha256": sha256_file(
                    oracle_dir / f"{document['scenario_id']}.oracle.json"
                ),
            }
            for document in oracles
        ],
    }
    write_json(output / "manifest.json", manifest)

    # Keep this manifest in the test-only resource tree.  It intentionally does
    # not amend the APK corpus manifest, which remains the 12-scenario release
    # contract consumed by release checks.
    generalization_manifest = {
        "schema_version": "safeshare.generalization-manifest.v1",
        "artifact_kind": "safeshare_test_generalization_manifest",
        "generator_version": GENERATOR_VERSION,
        "generator_sha256": sha256_file(Path(__file__).resolve()),
        "source_kind": "repository_owned_independent_holdout_templates",
        "external_records_downloaded": False,
        "test_only": True,
        "release_asset_excluded": True,
        "split": "candidate_holdout_preseal",
        "counterfactual_pair_count": len(GENERALIZATION_PAIR_SPECS),
        "inputs": [
            {
                "scenario_id": document["scenario_id"],
                "path": f"holdout-input/{document['scenario_id']}.input.json",
                "canonical_sha256": sha256_value(document),
            }
            for document in holdout_inputs
        ],
        "oracles": [
            {
                "scenario_id": document["scenario_id"],
                "path": f"holdout-oracle/{document['scenario_id']}.oracle.json",
                "file_sha256": sha256_file(
                    holdout_oracle_dir / f"{document['scenario_id']}.oracle.json"
                ),
            }
            for document in holdout_oracles
        ],
    }
    write_json(output / "generalization" / "manifest.json", generalization_manifest)
    return manifest


def parse_args() -> argparse.Namespace:
    default_output = Path(__file__).resolve().parent / "generated"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        type=Path,
        default=default_output,
        help=f"Output root (default: {default_output})",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output = args.out.resolve()
    manifest = generate(output)
    print(f"Generated {len(manifest['inputs'])} app input files")
    print(f"Generated {len(manifest['oracles'])} test oracle files")
    print(
        "Generated "
        f"{len(GENERALIZATION_PAIR_SPECS) * 2} test-only generalization inputs and "
        f"{len(GENERALIZATION_PAIR_SPECS) * 2} test-only generalization oracles"
    )
    print(f"Generated {len(manifest['assets'])} synthetic PDF fixture files")
    print(f"Manifest: {output / 'manifest.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
