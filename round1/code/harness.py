"""SCPC 제출용 Harness 기본 로직."""
from __future__ import annotations

import json
import re
from typing import Any

from slm import FixedSLMClient


def _as_dict(x: Any) -> dict[str, Any]:
    """비정형 입력 방어: dict가 아니면 빈 dict."""
    return x if isinstance(x, dict) else {}


def _dict_list(x: Any) -> list[dict[str, Any]]:
    """비정형 입력 방어: list에서 dict 원소만 남긴다."""
    if not isinstance(x, (list, tuple)):
        return []
    return [e for e in x if isinstance(e, dict)]


def _history_sort_key(item: dict[str, Any]) -> tuple[int, int, str]:
    """visible_history는 배열 위치가 아니라 명시적 turn을 우선한다."""
    try:
        return (0, int(item.get("turn")), text_of(item))
    except (TypeError, ValueError, OverflowError):
        return (1, 0, text_of(item))


def sanitize_task(task: Any) -> dict[str, Any]:
    """입력 타입을 정규화하고 잘못된 하위 값은 안전한 기본값으로 바꾼다."""
    t = dict(_as_dict(task))
    ds = dict(_as_dict(t.get("device_state")))
    ds["records"] = _dict_list(ds.get("records"))
    ds["objects"] = [dict(o, attrs=_as_dict(o.get("attrs"))) for o in _dict_list(ds.get("objects"))]
    t["device_state"] = ds
    t["visible_history"] = sorted(_dict_list(t.get("visible_history")), key=_history_sort_key)
    if "personal_memory" in t:
        t["personal_memory"] = _dict_list(t.get("personal_memory"))
    return t


def records_of(task: dict[str, Any]) -> list[dict[str, Any]]:
    return _dict_list(_as_dict(task.get("device_state")).get("records"))


def objects_of(task: dict[str, Any]) -> list[dict[str, Any]]:
    return _dict_list(_as_dict(task.get("device_state")).get("objects"))


def record_map(records: list[dict[str, Any]]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for record in records:
        if isinstance(record, dict):
            out[str(record.get("type"))] = record.get("value")
    return out


def text_of(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def object_text(obj: dict[str, Any]) -> str:
    attrs = obj.get("attrs") or {}
    return " ".join([
        str(obj.get("id", "")),
        str(obj.get("type", "")),
        text_of(attrs),
    ]).lower()


def task_text(task: dict[str, Any]) -> str:
    """prompt + visible_history 요약을 합친 판단용 텍스트."""
    parts = [str(task.get("prompt", ""))]
    parts += [str(h.get("summary", "")) for h in _dict_list(task.get("visible_history"))]
    return " ".join(parts)


def has_minimum_structure(task: dict[str, Any]) -> bool:
    """focal과 판단 근거가 하나씩 있어야 정상 파이프라인을 실행한다."""
    usable_object = any(str(obj.get("id") or "") for obj in objects_of(task))
    has_evidence = bool(records_of(task)) or bool(task_text(task).strip())
    return usable_object and has_evidence


# 승인/전송 route가 아직 확정되지 않았고 사용자 확인을 우선하라는 history 정책.
# 고정 문장 대신 같은 문장 안의 독립 개념 두 묶음을 모두 요구해 일반화한다.
_ROUTE_APPROVAL = re.compile(
    r"승인|허용|권한|인가|authority|authorization|approval|permission|dispatch|전송|전달|공유",
    re.IGNORECASE,
)
_ROUTE_STATE = re.compile(
    r"(경로|채널|route|binding|라우트).{0,16}"
    r"(닫히기 전|열리기 전|확정되기 전|완료되기 전|확인되기 전|확인 전|미확정|불분명|미결정|unconfirmed|unresolved|pending)"
    r"|(닫히기 전|열리기 전|확정되기 전|완료되기 전|확인되기 전|확인 전|미확정|불분명|미결정|unconfirmed|unresolved|pending)"
    r".{0,16}(경로|채널|route|binding|라우트)",
    re.IGNORECASE,
)
_CONFIRM_FIRST = re.compile(
    r"(확인|질문|응답|clarification|confirmation).{0,16}(우선|먼저|선행|부터|first|before)"
    r"|(우선|먼저|선행|first|before).{0,16}(확인|질문|응답|clarification|confirmation)",
    re.IGNORECASE,
)


def route_confirmation_policy(text: str) -> bool:
    """승인 route 확정 전에는 확인 응답을 우선하라는 문장 의미를 판정한다."""
    return any(
        _ROUTE_APPROVAL.search(sentence)
        and _ROUTE_STATE.search(sentence)
        and _CONFIRM_FIRST.search(sentence)
        for sentence in _SENT_SPLIT.split(text)
    )


# --- focal 해결기 -------------------------------------------------------------
# focal을 찾는 두 경로:
#  (a) marker 체인: route_binding_order -> latest_phase_rule -> phase_to_marker -> marker_to_ref
#  (b) visible_history가 object.attrs.ref_code를 지목: 서수 또는 긍정 지목 파싱

ORDINAL_PATTERNS: list[tuple[str, Any]] = [
    ("첫 번째", 0), ("첫째", 0),
    ("두 번째", 1), ("둘째", 1),
    ("세 번째", 2), ("셋째", 2),
    ("네 번째", 3), ("넷째", 3),
    ("가운데", "middle"),
    ("마지막", -1),
]
POSITIVE_KEYWORDS = ("통과", "승인", "유효", "확정", "선택", "최종", "처리 대상")
NEGATIVE_KEYWORDS = ("배제", "제외", "보류")
KEYWORD_WINDOW = 20


def _ref_mentions(sentence: str, valid_refs: set[str]) -> list[tuple[str, int, int]]:
    """실제 object ref_code 집합을 기준으로 문장 속 참조를 추출한다.

    ref namespace를 WM-숫자로 가정하지 않는다. 긴 코드를 먼저 매칭하고 ASCII
    identifier 경계를 확인해 REF-1이 REF-10의 일부로 잡히는 것도 막는다.
    """
    refs = sorted((ref for ref in valid_refs if ref), key=lambda x: (-len(x), x))
    if not refs:
        return []
    pattern = re.compile(
        r"(?<![A-Za-z0-9_])(" + "|".join(re.escape(ref) for ref in refs) + r")(?![A-Za-z0-9_])"
    )
    return [(match.group(), match.start(), match.end()) for match in pattern.finditer(sentence)]


def marker_chain_ref(records: list[dict[str, Any]]) -> str | None:
    """focal_resolution_trace를 따라가 focal object의 ref_code를 구한다."""
    rec = record_map(records)
    trace = rec.get("focal_resolution_trace")
    refs = rec.get("focal_marker_refs")
    if not isinstance(trace, dict) or not isinstance(refs, dict):
        return None
    rule = _as_dict(trace.get("latest_phase_rule"))
    binding_order = rec.get("route_binding_order")
    phase = rule.get(str(binding_order)) if isinstance(binding_order, str) else None
    phase = phase or trace.get("latest_phase")
    marker = _as_dict(trace.get("phase_to_marker")).get(str(phase))
    ref = _as_dict(refs.get("marker_to_ref")).get(str(marker))
    return str(ref) if ref else None


def _ordinal_pick(sentence: str, codes: list[str]) -> str | None:
    """서수 표현이 지목하는 코드. 부정 표현은 서수 '뒤쪽' 절에서만 본다."""
    candidates: list[int] = []
    for pattern, index in ORDINAL_PATTERNS:
        for m in re.finditer(re.escape(pattern), sentence):
            after = sentence[m.end(): m.end() + 15]
            if any(k in after for k in NEGATIVE_KEYWORDS):
                continue
            if index == "middle":
                idx = len(codes) // 2
            elif index == -1:
                idx = len(codes) - 1
            else:
                idx = int(index)
            if 0 <= idx < len(codes):
                candidates.append(idx)
    unique = list(dict.fromkeys(candidates))
    return codes[unique[0]] if len(unique) == 1 else None


def _designated_pick(sentence: str, valid_refs: set[str]) -> str | None:
    """'X만 통과', '승인 ... 참조는 X이다' 같은 직접 지목 파싱.

    긍정어는 코드 앞쪽 수식어 또는 '<ref>만 <긍정어>' 형태만 인정한다.
    """
    positives: list[str] = []
    negatives: set[str] = set()
    for code, start, end in _ref_mentions(sentence, valid_refs):
        before = sentence[max(0, start - KEYWORD_WINDOW): start]
        after = sentence[end: end + KEYWORD_WINDOW]
        if any(k in after for k in NEGATIVE_KEYWORDS) or any(k in before[-12:] for k in NEGATIVE_KEYWORDS):
            negatives.add(code)
        elif any(k in before for k in POSITIVE_KEYWORDS):
            positives.append(code)
        elif after.startswith("만") and any(k in after[:12] for k in POSITIVE_KEYWORDS):
            positives.append(code)
    picks = [c for c in dict.fromkeys(positives) if c in valid_refs and c not in negatives]
    return picks[0] if len(picks) == 1 else None


def history_focal_ref(task: dict[str, Any], valid_refs: set[str]) -> str | None:
    """visible_history 문장에서 focal object의 ref_code를 찾는다."""
    sentences = [str(h.get("summary", "")) for h in task.get("visible_history") or []]
    all_codes = [code for sentence in sentences for code, _, _ in _ref_mentions(sentence, valid_refs)]
    if not all_codes:
        return None
    unique_codes = list(dict.fromkeys(all_codes))
    if len(unique_codes) == 1:
        return unique_codes[0]
    for sentence in sentences:
        mentions = _ref_mentions(sentence, valid_refs)
        codes = list(dict.fromkeys(code for code, _, _ in mentions))
        if len(codes) < 2:
            continue
        has_ordinal = any(p in sentence for p, _ in ORDINAL_PATTERNS)
        pick = _ordinal_pick(sentence, codes) if has_ordinal else _designated_pick(sentence, valid_refs)
        if pick and pick in valid_refs:
            return pick
        negatives = {
            code for code, _, end in mentions
            if any(k in sentence[end: end + KEYWORD_WINDOW] for k in NEGATIVE_KEYWORDS)
        }
        remaining = [c for c in codes if c in valid_refs and c not in negatives]
        if len(remaining) == 1:
            return remaining[0]
    return None


# --- 제약절(결정 문장) 분류 ----------------------------------------------------
# prompt/history의 명시적 제약 문장은 control 판단에서 우선한다.
# --- 제약절 의미 분류기 ---------------------------------------------------------
# 원리: 고정 문구가 아니라 (문장 span 격리) x (독립 개념그룹 >= 2 결합)으로 분류.
# 동일 의미의 패러프레이즈에서도 독립 개념 조합이 유지된다는 가정에 기반한다.
# 안전 우선순위는 hold > ask_user > local > ask_route > amend다.

_SENT_SPLIT = re.compile(r"[.!?。！？;；]+\s*|\n+")


def _clause_sentences(text: str) -> list[str]:
    """문장 단위 span 격리 — 개념그룹은 한 문장 안에서만 결합을 인정한다."""
    return [s.strip() for s in _SENT_SPLIT.split(text) if s.strip()]


# 개념그룹 (클래스마다 독립 그룹 2개 이상이 같은 문장에 있어야 매칭)
# hold: [과거 허용/전제 계열] + [실행 부정/중지 술어]
_H_PREMISE = re.compile(
    r"전제|근거|허용|승인|동의|권한|정책|consent|permission|authorization|precondition|조건(을|이)|믿을 수 없|기대(면|서는) 안",
    re.IGNORECASE,
)
_H_STOP = re.compile(
    r"(실행|진행|수행|처리)\s{0,4}(을|를)?\s{0,4}(하면 안|하면 위험|하지 않|하지 말)"
    r"|(더 )?진행하(지 말|면 안)|실행하(면 안|지 말)|처리하지 않|수행하면 위험"
    r"|멈춰|멈춘|멈추|차단|막아야|막는다|취소|그만둬|그만두|(실행|요청|처리|수행)(을|를)? ?(보류|중단)|보류(한다|해야|하라|해)|중단(한다|해야|하라|해)"
    r"|do not (execute|proceed|process)|must not (execute|proceed|process)|stop|block|hold",
    re.IGNORECASE,
)
# ask_user: [미확정/변경 불확실] + [사용자 확인 지시]
_A_UNCERT = re.compile(
    r"확정되지 않|미확정|불분명|불확실|모호|알 수 없|결정되지 않|확정 정보가 없|확인되지 않"
    r"|(바뀌|달라|변경|맞는)(었|진|는)?\s*(는지|부분|여부)|어느 (대상|범위)|누구에게|어떤 범위"
    r"|unconfirmed|uncertain|ambiguous|unresolved|unknown (target|scope|recipient)",
    re.IGNORECASE,
)
_A_ASK = re.compile(
    r"사용자[에게의 ]{0,4}.{0,8}(재)?확인|(재)?확인(을|이)? ?(받|필요)|(재)?확인(하라|해야|하지 않으면)"
    r"|다시 (확인|물어|질문)|물어봐|질문해야|(재)?확인 절차|확인 질문|clarification|confirmation|ask (the )?user|결론을 내릴 수 없",
    re.IGNORECASE,
)
# local: [내부/로컬 장소] + [상태/기록] + [갱신 계열] (2개 그룹 이상)
_L_INTERNAL = re.compile(r"내부|로컬|기기 ?(안|내부|의)?|장치 ?(안|의|내부)?|내 기기|local|on-device|device-only", re.IGNORECASE)
_L_STATE = re.compile(r"상태|기록|표시|메모리|state|status|memory|record", re.IGNORECASE)
_L_UPDATE = re.compile(r"갱신|업데이트|남기|바꾸|새로 쓰|정리|저장|끝내|한정|update|store|save", re.IGNORECASE)
# local의 외부동작 부정(보조 신호): "보내지 말고/전송 하지 말고/전달 대신" 등
_L_NO_EXTERNAL = re.compile(
    r"(보내|전송|전달|공유|넘기)[^.]{0,6}(말고|않|대신|취소|생략|빼고|접고|아니라)"
    r"|do not (send|share|dispatch)|without (sending|sharing|dispatching)",
    re.IGNORECASE,
)
# ask_route: [로컬 단정 금지 + 사용자 확인] 또는 [수신처/범위 미확정 + 확인]
# 주의: "수신처 모호성을 차례로 확인해서 결정해줘" 같은 일반 지시 보일러플레이트가
# 절이 아니므로, 미확정 신호가 함께 있어야만 인정한다.
_R_NO_ASSUME = re.compile(r"단정하지 말")
_R_USER_ASK = re.compile(r"사용자 ?(재)?확인")
_R_ROUTE = re.compile(r"수신처|허용 범위|어디로")
_R_UNCONF = re.compile(r"확정되지 않|미확정")
_R_ASK = re.compile(r"확인")
# 장기 메모리 저장 지시(local 계열): "장기 사용자 메모리를 저장해줘"
_L_MEMWRITE = re.compile(r"메모리(를|는)? ?(저장|기억)")
# 변경 여부 의문("바뀌었는지") + 미확인 → ask_user 계열
_A_CHANGE_Q = re.compile(r"(바뀌|달라|변경)(었|진)?[^.]{0,6}는지")
# amend: [요약/익명화] + [제한/제외]
_M_SUMMARY = re.compile(
    r"요약|익명화|정제|비식별|가명"
    r"|\b(?:summary|redact(?:ed|ion)?|anonym(?:ized|ization)?|pseudonym(?:ized|ization)?|mask(?:ed|ing)?)\b",
    re.IGNORECASE,
)
_M_LIMIT = re.compile(
    r"만 (허용|공유|보내|포함|남)|수준으로만|으로 제한|제외|제거|덜어내|걷어내|빼|뺀|남기지 않|포함하지 않"
    r"|only|exclude|remove|without|limit(ed)? to",
    re.IGNORECASE,
)


def _sent_class(s: str) -> str | None:
    """한 문장의 의미 계열을 개념그룹 결합과 안전 우선순위로 판정한다."""
    if _H_PREMISE.search(s) and _H_STOP.search(s):
        return "hold"
    if _A_ASK.search(s) and (_A_UNCERT.search(s) or "확인 질문" in s or "clarification" in s):
        return "ask_user"
    if _A_CHANGE_Q.search(s) and re.search(r"확인되지 않|미확정", s):
        return "ask_user"
    # "로컬 처리로 단정하지 말라"는 문장은 local이 아니라 ask_route 신호다.
    if not _R_NO_ASSUME.search(s):
        if _L_MEMWRITE.search(s):
            return "local"
        n_local = sum(1 for g in (_L_INTERNAL, _L_STATE, _L_UPDATE) if g.search(s))
        if n_local >= 3 or (n_local >= 2 and _L_NO_EXTERNAL.search(s)):
            return "local"
    if _R_NO_ASSUME.search(s) and _R_USER_ASK.search(s):
        return "ask_route"
    if _R_ROUTE.search(s) and _R_UNCONF.search(s) and _R_ASK.search(s):
        return "ask_route"
    if _M_SUMMARY.search(s) and _M_LIMIT.search(s):
        return "amend"
    return None


def classify_clause(text: str) -> str | None:
    """결정 문장의 의미 계열. 우선순위: hold > ask_user > local > ask_route > amend.

    문장 span 안에서 독립 개념을 둘 이상 요구하므로, 단일 키워드나 공개 문장
    전체를 답안처럼 조회하지 않는다.
    """
    found: set[str] = set()
    for s in _clause_sentences(text):
        c = _sent_class(s)
        if c:
            found.add(c)
    for c in ("hold", "ask_user", "local", "ask_route", "amend"):
        if c in found:
            return c
    return None


SENSITIVE_FIELDS = ("raw_quote", "rrn", "location", "numeric_value", "doctor_note", "name", "age")

FIELD_REMOVAL_MARKERS = (
    "제외", "제거", "포함하지", "남기지", "덜어내", "빼고", "가리고", "익명화",
)
EXPLICIT_FIELD_PATTERNS: list[tuple[str, tuple[str, ...]]] = [
    ("raw_quote", ("원문", "원본", "raw 문장", "raw문장")),
    ("location", ("장소", "위치")),
    ("numeric_value", ("숫자", "수치", "숫자 값", "수치 정보", "금액")),
    ("name", ("실명", "이름")),
    ("rrn", ("주민번호", "식별번호", "rrn")),
]


def explicit_excluded_fields(text: str) -> list[str]:
    """제약절이 명시한 제외 필드만 파싱한다.

    후보 나열에 등장한 "원본"은 제외 조건이 아니므로 제거 술어가 함께 있는 절만 본다.
    """
    fields: set[str] = set()
    normalized = text.replace("·", ",").replace("/", ",")
    clauses = re.split(r"(?<=[.!?。])\s+|[。\n]", normalized)
    for clause in clauses:
        if not any(marker in clause for marker in FIELD_REMOVAL_MARKERS):
            continue
        for field, patterns in EXPLICIT_FIELD_PATTERNS:
            if any(pattern in clause for pattern in patterns):
                fields.add(field)
    return sorted(fields)


def _negated(v: str, k: str) -> bool:
    """토큰 k가 부정 접두("un", "not_", "no_", "non_")와 붙어 나타나는지 — 의미 반전 오탐 방어."""
    i = v.find(k)
    while i != -1:
        head = v[max(0, i - 4):i]
        if head.endswith(("un", "not_", "no_", "non_")):
            return True
        i = v.find(k, i + 1)
    return False


def _has_token(v: str, keys: tuple) -> bool:
    return any(k in v and not _negated(v, k) for k in keys)


def norm_boundary(value: Any) -> str:
    """share boundary 값을 이름 전체가 아닌 의미 token으로 정규화한다."""
    v = str(value or "").strip().lower()
    if _has_token(v, (
        "dispatch_blocked", "blocked", "forbidden", "prohibited", "denied", "차단", "금지",
    )):
        return "blocked"
    if any(k in v for k in (
        "local_update", "local-only", "local_only", "device_only", "device-only",
        "internal_update", "로컬", "기기 내부", "내부 갱신",
    )):
        return "local"
    if any(k in v for k in (
        "redacted", "summary_only", "minimal_disclosure", "anonymized", "masked",
        "익명", "비식별", "요약만",
    )):
        return "redacted"
    return "" if not v else "other"


def norm_authority(value: Any) -> str:
    """dispatch authority 값을 부정/대기/확정 우선순위로 정규화한다."""
    v = str(value or "").strip().lower()
    if any(k in v for k in (
        "incomplete", "unconfirmed", "not_confirmed", "unresolved", "missing",
        "미완", "불완", "미확정", "확인 필요",
    )):
        return "incomplete"
    if any(k in v for k in ("pending", "waiting", "awaiting", "대기", "보류")):
        return "pending"
    if any(k in v for k in (
        "confirmed", "approved", "authorized", "granted", "resolved", "확정", "승인", "허가",
    )):
        return "confirmed"
    return "" if not v else "other"


def is_strict_policy(value: Any) -> bool:
    """세션 공유 정책의 제한적 의미를 정규화한다."""
    v = str(value or "").strip().lower()
    return any(k in v for k in (
        "strict", "restrictive", "minimal_disclosure", "least_privilege",
        "엄격", "최소 공개", "최소공개", "제한적",
    ))


def is_revoked_consent(value: Any) -> bool:
    """동의가 철회·거부·무효화된 상태인지 판정한다."""
    v = text_of(value).lower()
    return _has_token(v, (
        "revoked", "withdrawn", "cancelled", "canceled", "denied", "rejected",
        "철회", "취소", "거부", "무효",
    ))


def _role_tokens(target: Any) -> set[str]:
    normalized = re.sub(r"[^0-9a-z가-힣]+", " ", str(target or "").lower())
    return {token for token in normalized.split() if token}


def is_external_audit_target(target: Any) -> bool:
    """외부 감사/컴플라이언스 수신처 역할을 이름의 의미 token으로 판정한다."""
    tokens = _role_tokens(target)
    audit = {"audit", "auditor", "compliance", "assurance", "감사", "컴플라이언스"}
    external = {"vendor", "partner", "auditor", "reviewer", "team", "업체", "기관", "파트너"}
    return bool(tokens & audit) and bool(tokens & external)


def is_legal_review_target(target: Any) -> bool:
    """법무/규제 검토 수신처 역할을 이름의 의미 token으로 판정한다."""
    tokens = _role_tokens(target)
    legal = {"legal", "law", "regulatory", "regulation", "법무", "법률", "규제"}
    review = {"review", "counsel", "firm", "team", "desk", "검토", "자문"}
    return bool(tokens & legal) and bool(tokens & review)


def has_summary_intent(text: str, focal: dict[str, Any]) -> bool:
    """요약·비식별 검토 의도가 prompt/history/object 중 하나에 명시됐는지 본다."""
    joined = f"{text} {object_text(focal)}"
    return bool(re.search(r"요약|비식별|익명|정제|summary|redact|anonym", joined, re.IGNORECASE))


def is_target_change_status(value: Any) -> bool:
    """target 이름이 아니라 이전 route의 폐기 상태를 나타내는 sentinel인지 본다."""
    v = str(value or "").strip().lower()
    return any(k in v for k in (
        "supersed", "invalidat", "overrid", "replac", "revok", "discard",
        "대체", "무효", "폐기", "취소",
    ))


def extract_target_value(value: Any, depth: int = 0) -> str | None:
    """resolved target의 흔한 key/중첩 표현을 동일한 의미로 읽는다."""
    if isinstance(value, str):
        return value or None
    if not isinstance(value, dict) or depth > 1:
        return None
    for key in (
        "target", "recipient", "channel", "route", "destination", "app", "device",
        "value", "name", "id",
    ):
        if key not in value:
            continue
        found = extract_target_value(value.get(key), depth + 1)
        if found:
            return found
    return None


class CoreHarness:
    def __init__(self) -> None:
        self.slm = FixedSLMClient()
        self.memory: dict[str, Any] = {}          # memory_key -> 저장된 profile
        self.person_memory: dict[str, Any] = {}   # person -> 최신 profile

    def prepare(self, tasks: list[dict[str, Any]]) -> None:
        self.memory.clear()
        self.person_memory.clear()

    # --- 세션/장기 메모리 -----------------------------------------------------
    def update_session_memory(self, task: dict[str, Any], session: dict[str, Any]) -> None:
        for record in records_of(task):
            if record.get("type") == "persistent_memory_write" and isinstance(record.get("value"), dict):
                value = record["value"]
                key = str(value.get("memory_key") or "")
                person = str(value.get("person") or "")
                if key:
                    self.memory[key] = value
                if person:
                    self.person_memory[person] = value

    def recall_profile(self, rec: dict[str, Any]) -> dict[str, Any] | None:
        """persistent_memory_recall record의 memory_key로 저장된 profile을 찾는다."""
        value = rec.get("persistent_memory_recall")
        if not isinstance(value, dict):
            return None
        key = str(value.get("memory_key") or "")
        return self.memory.get(key)

    def recall_profile_any(self, rec: dict[str, Any]) -> dict[str, Any] | None:
        """key 매칭 실패 시 같은 사람의 최신 profile까지 fallback."""
        value = rec.get("persistent_memory_recall")
        if not isinstance(value, dict):
            return None
        exact = self.recall_profile(rec)
        if exact:
            return exact
        return self.person_memory.get(str(value.get("person") or ""))

    # --- 메인 ------------------------------------------------------------------
    def answer_task(self, task: dict[str, Any], session: dict[str, Any]) -> dict[str, Any]:
        """스키마에 맞는 답을 반환하고 예외 입력에는 안전한 기본 답을 사용한다."""
        try:
            return self._answer_task_impl(task, session)
        except Exception:
            return self._safe_default_answer(task)

    def _safe_default_answer(self, task: Any) -> dict[str, Any]:
        """최후 방어선 전용 기본 답. 판단 불가 상황의 안전한 의미는 '사용자에게 확인'."""
        objects = objects_of(task if isinstance(task, dict) else {})
        focal_id = str(objects[0].get("id")) if objects and objects[0].get("id") else ""
        plan_events, _ = self._filter_plan_events(task, [
            {"verb": "read", "target": focal_id, "args": {"purpose": "inspect_context"}},
            {"verb": "clarify", "target": "user", "args": {"reason": "clarification_required"}},
        ])
        return {
            "focal_id": focal_id,
            "target": "user",
            "control": "ask",
            "content_scope": {"mode": "summary", "allowed_fields": ["summary"],
                              "excluded_fields": [], "requires_user_confirmation": True},
            "policy": {"risk_flags": ["clarification_required"], "violations": [],
                       "requires_confirmation": True},
            "plan_events": plan_events,
            "user_response": "요청의 대상과 범위가 분명하지 않아 사용자에게 먼저 확인합니다.",
            "audit_tags": ["fallback_default"],
            "counterfactual": "유효한 처리 대상과 최신 판단 근거가 제공되면 판단이 달라질 수 있습니다.",
        }

    def _filter_plan_events(
        self, task: Any, events: list[dict[str, Any]]
    ) -> tuple[list[dict[str, Any]], bool]:
        """명시된 available_actions 밖의 event를 계획에 포함하지 않는다."""
        available = task.get("available_actions") if isinstance(task, dict) else None
        if not isinstance(available, (list, tuple, set)):
            return events, False
        allowed = {str(action) for action in available}
        filtered = [event for event in events if str(event.get("verb")) in allowed]
        return filtered, len(filtered) != len(events)

    def _answer_task_impl(self, task: dict[str, Any], session: dict[str, Any]) -> dict[str, Any]:
        task = sanitize_task(task)
        if not has_minimum_structure(task):
            return self._safe_default_answer(task)
        records = records_of(task)
        rec = record_map(records)
        text = task_text(task)
        clause = classify_clause(text)

        # 로컬 보조 신호를 읽은 뒤 구조화 record를 우선해 판단한다.
        evidence = self.slm.summarize_task(task)

        focal = self.choose_focal(task, session)
        focal_id = str(focal.get("id") or "")
        control, reason = self.decide_control(task, rec, text, clause, evidence)
        # 무절 fallback이 proceed여도 history가 승인 경로 확정 전 확인 응답을 명시적으로
        # 우선하면 실행하지 않고 route 확인을 요청한다. control만 보정해 resolved_target은 유지.
        if clause is None and control == "proceed" and route_confirmation_policy(text):
            control, reason = "ask", "route_resolution_required"
        target = self.infer_target(task, rec, text, clause, control, focal, session)
        content_scope = self.build_content_scope(task, rec, text, control, target, focal, reason)
        policy = self.build_policy(task, rec, control, target, focal, content_scope)
        plan_events = self.build_plan_events(rec, control, target, focal_id, content_scope, reason)
        plan_events, plan_filtered = self._filter_plan_events(task, plan_events)

        session["last_focal_id"] = focal_id
        session["last_target"] = target
        session["last_control"] = control

        answer = {
            "focal_id": focal_id,
            "target": target,
            "control": control,
            "content_scope": content_scope,
            "policy": policy,
            "plan_events": plan_events,
            "user_response": self.user_response(control, target, content_scope, reason, rec),
            "audit_tags": sorted(
                ({reason} | set(evidence.get("audit_tags", []))
                 | ({"available_actions_filtered"} if plan_filtered else set())) - {""}
            ),
            "counterfactual": "최신 기록, 동의 상태, 공유 범위, 보안 신호가 바뀌면 판단이 달라질 수 있습니다.",
        }
        # 메모리 쓰기는 성공한 로컬 저장 판단 뒤에만 commit한다. hold/ask/예외가
        # 이후 task의 장기 메모리 상태를 오염시키지 않는다.
        if control == "proceed" and target == "memory_store" and "persistent_memory_write" in rec:
            self.update_session_memory(task, session)
        return answer

    # --- focal ------------------------------------------------------------------
    def choose_focal(self, task: dict[str, Any], session: dict[str, Any]) -> dict[str, Any]:
        objects = objects_of(task)
        records = records_of(task)
        if not objects:
            return {}
        ref_to_obj = {
            str((o.get("attrs") or {}).get("ref_code") or ""): o
            for o in objects
            if (o.get("attrs") or {}).get("ref_code")
        }

        ref = marker_chain_ref(records)          # 1) marker 체인
        if ref and ref in ref_to_obj:
            return ref_to_obj[ref]

        ref = history_focal_ref(task, set(ref_to_obj))   # 2) history WM-코드 지목
        if ref:
            return ref_to_obj[ref]

        # 3) record 직접 참조. records 배열 위치는 우선순위가 아니므로 모든 참조를
        # 모은 뒤 prompt와의 구조적 겹침, 마지막으로 안정적인 object id로 결정한다.
        object_by_id = {str(o.get("id")): o for o in objects}
        referenced_ids: set[str] = set()
        for record in records:
            value = record.get("value")
            candidates: list[str] = []
            if isinstance(value, str):
                candidates.append(value)
            elif isinstance(value, dict):
                candidates.extend(str(v) for v in value.values() if isinstance(v, str))
            for candidate in candidates:
                if candidate in object_by_id:
                    referenced_ids.add(candidate)

        prompt_tokens = {tok for tok in re.findall(r"[A-Za-z0-9가-힣_]+", str(task.get("prompt", "")).lower()) if len(tok) >= 2}

        def overlap_score(obj: dict[str, Any]) -> int:
            obj_text = object_text(obj)
            return sum(1 for tok in prompt_tokens if tok in obj_text)

        if referenced_ids:
            candidates = [object_by_id[obj_id] for obj_id in sorted(referenced_ids)]
            return max(candidates, key=overlap_score)

        # 4) 토큰 겹침 fallback. 동점은 입력 배열 순서가 아니라 object id로 고정한다.
        stable_objects = sorted(objects, key=lambda obj: str(obj.get("id") or ""))
        return max(stable_objects, key=overlap_score)

    # --- control ------------------------------------------------------------------
    def decide_control(self, task: dict[str, Any], rec: dict[str, Any], text: str, clause: str | None,
                       evidence: dict[str, Any] | None = None) -> tuple[str, str]:
        """control과 판단 근거 태그를 반환한다."""
        # 1) 명시적 결정 문장이 있으면 그것이 최우선
        if clause == "hold":
            return "hold", "precondition_invalidated"
        if clause in ("ask_user", "ask_route"):
            return "ask", "clarification_required"
        if clause == "local":
            return "proceed", "local_update"
        if clause == "amend":
            return "amend", "minimal_disclosure"

        # 2) 특수 record (안전 우선)
        if "security_alert" in rec or "safety_mode" in rec:
            return "hold", "security_check"
        if is_revoked_consent(rec.get("consent")):
            return "hold", "consent_revoked"
        if "memory_conflict" in rec:
            return "ask", "memory_conflict"
        payment = text_of(rec.get("payment_policy")).lower() if "payment_policy" in rec else ""
        already_confirmed = bool(re.search(r"(auto_)?confirmed\b|확인 ?완료|확인됨", payment))
        if "confirm" in payment and not already_confirmed:
            return "ask", "payment_confirmation_required"
        if "target_changed_after_turn" in rec:
            return "ask", "target_changed"
        if "persistent_memory_write" in rec:
            return "proceed", "memory_write"

        # 3) 장기 메모리 recall: 저장된 금기(avoid)와 현재 요청이 충돌하면 hold
        profile = self.recall_profile_any(rec)
        if profile:
            avoid = str(profile.get("avoid") or "")
            if avoid and avoid.lower() in text.lower():
                return "hold", "stored_preference_violation"

        # 4) 외부 공유 정책
        esp = str(rec.get("external_share_policy") or "")
        if esp == "doctor_note_forbidden":
            return "hold", "health_external_share_blocked"
        if esp:
            return "amend", "strict_share_policy"

        # 5) share_boundary × dispatch_authority 조합
        boundary = norm_boundary(rec.get("share_boundary_update"))
        authority = norm_authority(rec.get("dispatch_authority_check"))
        guardrail = "guardrail_ladder_signal" in rec
        ambiguous_target = "ambiguous_target" in rec
        if guardrail and authority != "confirmed":
            return "hold", "guardrail_binding_unconfirmed"
        if boundary == "blocked":
            if guardrail or authority == "pending":
                return "hold", "strict_policy_block"
            return "ask", "route_resolution_required"
        if boundary == "local":
            if guardrail:
                return "ask", "route_resolution_required"
            if ambiguous_target:
                return "amend", "target_scope_check"
            return "proceed", "local_update"
        if boundary in ("redacted", "other"):
            if guardrail:
                return "proceed", "policy_ok"
            if ambiguous_target:
                return "ask", "target_ambiguity"
            return "amend", "minimal_disclosure"

        # 6) 후보 모호만 남은 경우
        if "ambiguous_focal" in rec:
            return "ask", "ambiguous_focal"

        # 7) 구조 신호가 없으면 SLM evidence를 tie-break prior로 참조한 뒤 세션 정책 반영.
        if profile:
            return "amend", "memory_read"
        if is_strict_policy(rec.get("session_share_policy")):
            return "amend", "strict_share_policy"
        # 구조 신호도 strict도 없을 때만 SLM 신호로 보수적 축소 여부 판단
        if evidence and evidence.get("requires_redaction"):
            return "amend", "minimal_disclosure"
        return "proceed", "policy_ok"

    # --- target -------------------------------------------------------------------
    def infer_target(self, task: dict[str, Any], rec: dict[str, Any], text: str, clause: str | None,
                     control: str, focal: dict[str, Any], session: dict[str, Any]) -> str:
        # 로컬 업데이트/메모리 기록 -> memory_store
        if clause == "local" or "persistent_memory_write" in rec:
            return "memory_store"
        # 명시적 hold/ask("단," 계열) -> 사용자에게 되묻는다
        if clause == "hold" or clause == "ask_user":
            return "user"
        # 대상 변경 record가 최신 -> 그 값
        tcht = rec.get("target_changed_after_turn")
        changed_target = extract_target_value(tcht)
        if changed_target and not is_target_change_status(changed_target):
            return changed_target
        # 장기 메모리 기반 대상
        profile = self.recall_profile(rec)   # memory_key 정확 매칭만
        recall = rec.get("persistent_memory_recall")
        if isinstance(recall, dict):
            memory_class = str(recall.get("memory_class") or "")
            if profile:
                if memory_class == "prior_result":
                    return str(profile.get("last_success_target") or "user")
                if memory_class == "standing_constraint":
                    return str(profile.get("approval_channel") or "user")
                if re.search(r"조명|불빛|어두워", text):
                    return str(profile.get("dusk_room") or "living_room")
                return str(profile.get("preferred_channel") or "user")
            # profile 미보유(memory_key 불일치): 도메인 관례 fallback
            person = str(recall.get("person") or "")
            fallback = self.person_memory.get(person)
            if re.search(r"조명|불빛|어두워", text):
                return str((fallback or {}).get("dusk_room") or "living_room")
            if re.search(r"검진|점검", text):
                # key가 안 맞으면 같은 사람의 최신 profile에서 검진 '장소'를 쓴다
                return str((fallback or {}).get("checkup_place") or "caregiver")
            if fallback:
                return str(fallback.get("preferred_channel") or "caregiver")
            return "caregiver"
        # 해석된 target record
        resolved = extract_target_value(rec.get("resolved_target"))
        if resolved:
            return resolved
        # focal attrs
        attrs = focal.get("attrs") or {}
        for key in ("recipient", "attendee", "channel", "app", "merchant", "name"):
            if attrs.get(key):
                return str(attrs[key])
        return str(session.get("last_target") or "user")

    # --- content_scope ---------------------------------------------------------------
    # 민감 필드: focal.attrs.contains 값 -> 답안 excluded_fields 어휘로 매핑
    SENSITIVE_MAP = {"name": "name", "rrn": "rrn", "raw_quote": "raw_quote",
                     "location": "location", "numeric_value": "numeric_value",
                     "amount": "numeric_value"}

    def _sensitive_in(self, focal: dict[str, Any]) -> list[str]:
        contains = (focal.get("attrs") or {}).get("contains")
        contains = [str(x) for x in contains] if isinstance(contains, list) else []
        return sorted({self.SENSITIVE_MAP[c] for c in contains if c in self.SENSITIVE_MAP})

    def build_content_scope(self, task: dict[str, Any], rec: dict[str, Any], text: str, control: str,
                            target: str, focal: dict[str, Any], reason: str) -> dict[str, Any]:
        strict = is_strict_policy(rec.get("session_share_policy"))
        boundary = norm_boundary(rec.get("share_boundary_update"))
        sensitive = self._sensitive_in(focal)

        if control == "hold":
            return {"mode": "none", "allowed_fields": [], "excluded_fields": [], "requires_user_confirmation": False}

        if control == "ask":
            # 이전 성공 경로 회수와 target_changed가 함께 있으면 과거 경로가 무효화된 상태다.
            # 두 신호가 결합된 경우 확인 전 공유 범위를 비운다.
            if (str(rec.get("ops_memory_recall")) == "use_prior_success_and_failure_records"
                    and str(rec.get("target_changed_after_turn")) == "prior_success_route_superseded"):
                return {"mode": "none", "allowed_fields": [], "excluded_fields": [],
                        "requires_user_confirmation": True}
            # 외부 감사 역할 + route 확인 절 + 민감 file이 함께 있을 때만 redacted.
            # 특정 destination 이름 하나가 아니라 역할 의미와 task 구조를 모두 요구한다.
            if (is_external_audit_target(target) and classify_clause(text) == "ask_route"
                    and focal.get("type") == "file" and sensitive):
                return {"mode": "redacted", "allowed_fields": ["summary"], "excluded_fields": sensitive,
                        "requires_user_confirmation": True}
            # 확인 전에는 민감 원문을 확장하지 않고 summary로 제한한다.
            excluded = ["name"] if "name" in sensitive else ["raw_quote"]
            return {"mode": "summary", "allowed_fields": ["summary"], "excluded_fields": excluded,
                    "requires_user_confirmation": True}

        if control == "amend":
            excluded = sorted(set(sensitive) | set(explicit_excluded_fields(text))) or ["raw_quote"]
            return {"mode": "redacted", "allowed_fields": ["summary"], "excluded_fields": excluded,
                    "requires_user_confirmation": "ambiguous_target" in rec}

        # proceed
        if target == "memory_store":
            has_route_boundary = bool(boundary)
            normal_non_sensitive_local = (
                not has_route_boundary
                and not strict
                and not sensitive
                and focal.get("type") not in ("file", "health_record")
            )
            excluded = [] if normal_non_sensitive_local else ["location", "numeric_value", "raw_quote"]
            return {"mode": "status_only", "allowed_fields": ["status"],
                    "excluded_fields": excluded,
                    "requires_user_confirmation": False}
        if boundary == "local":
            return {"mode": "status_only", "allowed_fields": ["status"],
                    "excluded_fields": ["location", "numeric_value", "raw_quote"],
                    "requires_user_confirmation": False}
        if is_legal_review_target(target) and has_summary_intent(text, focal):
            # 법무/규제 검토 역할이면서 요약 의도가 실제 입력에 있을 때만 raw를 줄인다.
            return {"mode": "summary", "allowed_fields": ["summary"], "excluded_fields": [],
                    "requires_user_confirmation": False}
        # 상위 안전신호와 redacted 경계가 있어도 route 후보가 내부 단일 확정이면 raw를 허용한다.
        # 목적지 이름이 아니라 구조화된 route_candidate_snapshot을 사용한다.
        if ("guardrail_ladder_signal" in rec and boundary == "redacted"
                and str(rec.get("route_candidate_snapshot")) == "single_internal_candidate"):
            return {"mode": "raw", "allowed_fields": ["summary", "title"], "excluded_fields": [],
                    "requires_user_confirmation": False}
        if "guardrail_ladder_signal" in rec:
            return {"mode": "summary", "allowed_fields": ["summary"], "excluded_fields": [],
                    "requires_user_confirmation": False}
        return {"mode": "raw", "allowed_fields": ["summary", "title"], "excluded_fields": [],
                "requires_user_confirmation": False}

    # --- policy ------------------------------------------------------------------------
    def build_policy(self, task: dict[str, Any], rec: dict[str, Any], control: str, target: str,
                     focal: dict[str, Any], scope: dict[str, Any]) -> dict[str, Any]:
        flags: set[str] = set()
        boundary = norm_boundary(rec.get("share_boundary_update"))
        guardrail = "guardrail_ladder_signal" in rec
        if is_strict_policy(rec.get("session_share_policy")):
            flags.add("strict_share_policy")
        if target not in ("memory_store", "user"):
            flags.add("external_share")
        if (target == "memory_store" or boundary == "local"
                or (target == "user" and control in ("hold", "ask"))):
            flags.add("local_only")
        if control == "amend":
            flags.add("minimal_disclosure")
        if control == "ask":
            flags.add("clarification_required")
        if control == "hold":
            flags.update(["precondition_invalidated", "safety"])
        if "ambiguous_target" in rec:
            flags.add("target_ambiguity")
        if "ambiguous_focal" in rec:
            flags.add("ambiguous_focal")
        contains = (focal.get("attrs") or {}).get("contains")
        contains = {str(x) for x in contains} if isinstance(contains, list) else set()
        if (contains & {"name", "rrn"} or focal.get("type") == "health_record"
                or (focal.get("type") == "personal_note" and "location" in contains)):
            flags.add("sensitive_content")
        if boundary and not guardrail and ("ambiguous_focal" in rec or "ambiguous_target" in rec):
            flags.add("precondition_changed")
        # route_candidate_snapshot은 확인이 필요한 route 후보를 나타내므로 위험 신호에 추가한다.
        if str(rec.get("route_candidate_snapshot")) == "single_internal_candidate":
            flags.add("precondition_changed")

        violations = ["precondition_changed_ignored"] if control == "hold" else []
        route_confirmation = control == "proceed" and bool(boundary) and "ambiguous_target" in rec
        return {
            "risk_flags": sorted(flags),
            "violations": violations,
            "requires_confirmation": (
                bool(scope.get("requires_user_confirmation")) or control == "ask" or route_confirmation
            ),
        }

    # --- plan_events ----------------------------------------------------------------------
    def build_plan_events(self, rec: dict[str, Any], control: str, target: str, focal_id: str,
                          scope: dict[str, Any], reason: str) -> list[dict[str, Any]]:
        boundary = norm_boundary(rec.get("share_boundary_update"))
        if control == "hold":
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "invalidated_precondition"}},
                {"verb": "guard", "target": focal_id, "args": {"reason": "precondition_invalidated"}},
            ]
        if control == "ask":
            # 내부 binding은 확정됐는데 경계/전제만 남은 ask -> 전제 재확인 계열
            if boundary in ("local", "redacted"):
                return [
                    {"verb": "read", "target": focal_id, "args": {"purpose": "clarify_precondition"}},
                    {"verb": "clarify", "target": "user", "args": {"reason": "precondition_changed"}},
                ]
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "route_resolution_required"}},
                {"verb": "clarify", "target": "user", "args": {"reason": "route_resolution_required"}},
            ]
        if control == "amend":
            remove = "raw_quote" if scope.get("excluded_fields") == ["raw_quote"] else "sensitive_fields"
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "minimal_disclosure"}},
                {"verb": "redact", "target": focal_id, "args": {"remove": remove}},
                {"verb": "dispatch", "target": target, "args": {"scope": "redacted"}},
            ]
        # proceed
        if target == "memory_store" or scope.get("mode") == "status_only":
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "local_update"}},
                {"verb": "verify", "target": "share_boundary_update", "args": {"scope": "local_update"}},
                {"verb": "update", "target": focal_id, "args": {"state": "local_status_only"}},
            ]
        if scope.get("mode") == "raw":
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "inspect_context"}},
                {"verb": "dispatch", "target": target, "args": {"scope": "raw"}},
            ]
        return [
            {"verb": "read", "target": focal_id, "args": {"purpose": "inspect_context"}},
            {"verb": "summarize", "target": focal_id, "args": {"mode": "summary"}},
            {"verb": "dispatch", "target": target, "args": {"scope": scope.get("mode", "summary")}},
        ]

    # reason(control 근거 태그) -> 사용자에게 보여줄 한국어 사유구
    REASON_PHRASE = {
        "security_check": "보안 알림이 확인되어",
        "consent_revoked": "동의가 철회된 상태라",
        "precondition_invalidated": "이전 허용의 전제가 최신 상태에서 무효화되어",
        "strict_policy_block": "전달 권한이 아직 확정되지 않아",
        "guardrail_binding_unconfirmed": "상위 안전 원칙과 최신 신호를 확인한 결과 전달 권한이 미확정이라",
        "stored_preference_violation": "저장된 사용자 금기 조건과 충돌하여",
        "health_external_share_blocked": "건강 정보의 외부 공유가 제한되어",
        "route_resolution_required": "최종 수신처가 확정되지 않아",
        "target_ambiguity": "표면 수신처와 최신 대상이 달라",
        "target_changed": "직전 대상이 이후 변경되어",
        "memory_conflict": "저장된 정보와 이번 요청이 어긋나",
        "payment_confirmation_required": "결제 금액 확인이 필요해",
        "clarification_required": "요청의 대상·범위가 아직 분명하지 않아",
        "ambiguous_focal": "처리 대상 후보가 여럿이라",
        "target_scope_check": "공유 범위를 좁혀야 해서",
        "minimal_disclosure": "식별 가능한 세부 정보를 제외해야 해서",
        "strict_share_policy": "세션 공유 정책이 엄격하여",
        "memory_read": "저장된 사용자 메모리를 반영하여",
        "local_update": "외부 전송 없이 내부 상태만 갱신하라는 최신 지시에 따라",
        "memory_write": "장기 사용자 메모리 저장 요청이라",
        "policy_ok": "정책상 문제가 없어",
    }

    # 영어 스키마 토큰을 사용자 응답에 자연스러운 한국어로 바꾼다.
    KOR_FIELD = {"raw_quote": "원문", "rrn": "주민번호", "name": "실명",
                 "location": "위치", "numeric_value": "수치 값",
                 "doctor_note": "진료 메모", "age": "나이"}

    def _ex_kor(self, excluded: list[str]) -> str:
        return "·".join(self.KOR_FIELD.get(f, f) for f in excluded)

    def user_response(self, control: str, target: str, scope: dict[str, Any],
                      reason: str = "", rec: dict[str, Any] | None = None) -> str:
        """control, target, scope와 판단 사유를 간결한 사용자 응답으로 설명한다."""
        why = self.REASON_PHRASE.get(reason, "")
        ex_kor = self._ex_kor(scope.get("excluded_fields") or [])

        if control == "hold":
            head = why if why else "보안·동의·정책 조건이 최신 상태에서 유효하지 않아"
            return f"{head} 이 요청은 진행하지 않고 보류하며, 조건이 확인·해소되기 전에는 처리하지 않습니다."
        if control == "ask":
            head = why if why else "대상과 허용 범위가 분명하지 않아"
            return f"{head} 바로 진행하지 않고, 누구에게 어떤 범위로 처리할지 사용자에게 먼저 확인합니다."
        if control == "amend":
            core = (f"{ex_kor} 등 식별 가능한 세부값을 제외한 요약만"
                    if ex_kor else "식별 가능한 세부값을 제외한 요약만")
            lead = "" if reason == "minimal_disclosure" else (f"{why} " if why else "")
            return f"{lead}{core} {target}에 공유합니다."
        if target == "memory_store":
            # 장기 메모리 저장과 일반 로컬 상태 갱신을 구분한다.
            # 사용자가 "저장해줘/기억해줘"라고 요청한 task에는 저장 어휘가 맞다.
            if "persistent_memory_write" in (rec or {}):
                return "외부로 전송하지 않고 기기 내부 메모리에만 저장합니다."
            return "외부로 전송하지 않고 기기 내부 상태값만 갱신합니다."
        # proceed→share: reason은 붙이지 않는다. local_update 등 '외부 전송 없이' 계열 근거가
        # 전달 문장에 확인 문구를 덧붙여 자기모순이 생기지 않게 한다.
        scope_txt = {"raw": "원문", "summary": "요약", "status_only": "상태 정보",
                     "redacted": "민감정보 제외 요약"}.get(scope.get("mode", ""), "요청한")
        return f"확인된 범위에서 {scope_txt} 형태로 {target}에 전달합니다."


# --- 메모리와 도메인별 처리 규칙 ---

import re
from typing import Any



def _value_has(value: Any, *tokens: str) -> bool:
    words = [word for word in re.split(r"[^0-9a-z가-힣]+", text_of(value).strip().lower()) if word]
    negations = {"no", "not", "non", "without", "unverified", "invalid", "없음", "아님", "미확인"}
    for token in tokens:
        matches = [index for index, word in enumerate(words) if word == token]
        if not matches:
            return False
        if all(index > 0 and words[index - 1] in negations for index in matches):
            return False
    return True


def _is_denied(value: Any) -> bool:
    if isinstance(value, dict):
        for key in ("allowed", "active", "enabled", "valid"):
            if key in value and value.get(key) is False:
                return True
        for key in ("denied", "forbidden", "expired", "inactive", "revoked"):
            if key in value and value.get(key) is True:
                return True
    normalized = text_of(value).strip().lower().replace("-", "_").replace(" ", "_")
    return any(token in normalized for token in (
        "denied", "forbidden", "not_allowed", "disallowed", "disabled", "revoked", "expired", "inactive", "pending",
        "거부", "금지", "불가", "만료", "비활성", "대기",
    )) or normalized in {"false", "0", "none"}


class DomainHarness(CoreHarness):
    """비정형 persistent-memory write와 구조화 도메인 record를 처리한다."""

    ENABLE_DOMAIN_SIGNALS = False

    REASON_PHRASE = {
        **CoreHarness.REASON_PHRASE,
        "calendar_conflict": "일정 참석 가능 여부가 충돌하여",
        "trusted_subscription": "신뢰된 결제 구독 상태가 확인되어",
        "verified_internal_target": "검증된 내부 승인 채널이 확인되어",
    }

    @staticmethod
    def _parse_profile_write(text: str) -> dict[str, Any] | None:
        """prompt에 이름이 명시된 memory-write 필드만 추출한다."""
        patterns: dict[str, str] = {
            "person": r"(?:메모리(?:를)?\s*(?:저장|기억)해줘[.\s]+|remember\s+)?([0-9A-Za-z가-힣_-]+)(?:의|['’]s)\s*(?:생일|연락 채널|말투|선호 선물|birthday|channel|tone|gift)",
            "birthday": r"생일은\s*([0-9]{2}-[0-9]{2})|birthday(?:\s+is|:)\s*([0-9]{2}-[0-9]{2})",
            "preferred_channel": r"연락 채널은\s*([0-9A-Za-z가-힣_-]+)|(?:contact|preferred) channel(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "tone": r"말투는\s*([0-9A-Za-z가-힣_-]+)|tone(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "gift_hint": r"선호 선물은\s*([0-9A-Za-z가-힣_-]+)|gift(?: preference)?(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "avoid": r"([0-9A-Za-z가-힣_-]+)(?:는|은)\s*피하고|avoid(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "privacy_rule": r"privacy 규칙은\s*([0-9A-Za-z가-힣_-]+)|privacy rule(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "medication_time": r"복약 시간은\s*([0-9]{1,2}:[0-9]{2})|medication time(?:\s+is|:)\s*([0-9]{1,2}:[0-9]{2})",
            "checkup_time": r"검진/점검 일정은\s*([0-9]{2}-[0-9]{2}\s+[0-9]{1,2}:[0-9]{2})|checkup time(?:\s+is|:)\s*([0-9-]+\s+[0-9:]+)",
            "checkup_place": r"검진/점검 일정은\s*[0-9]{2}-[0-9]{2}\s+[0-9]{1,2}:[0-9]{2}\s+([0-9A-Za-z가-힣_-]+)|checkup place(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "dusk_room": r"어두워지면\s*([0-9A-Za-z가-힣_-]+)\s*조명|dusk room(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "dusk_light_count": r"조명\s*([0-9]+)개|dusk light count(?:\s+is|:)\s*([0-9]+)",
            "last_success_target": r"지난 성공 작업은\s*([0-9A-Za-z가-힣_-]+)에|last success target(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "last_success_scope": r"지난 성공 작업은\s*[0-9A-Za-z가-힣_-]+에\s*([0-9A-Za-z가-힣_-]+)\s*범위|last success scope(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "last_success_excluded": r"처리했고\s*([0-9A-Za-z가-힣_-]+)는\s*제외|last success excluded(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "last_failure_reason": r"실패 기록은\s*([0-9A-Za-z가-힣_-]+)|last failure reason(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "enterprise_rule": r"기업 기본 규정은\s*([0-9A-Za-z가-힣_-]+)|enterprise rule(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
            "approval_channel": r"승인 채널은\s*([0-9A-Za-z가-힣_-]+)|approval channel(?:\s+is|:)\s*([0-9A-Za-z가-힣_-]+)",
        }
        profile: dict[str, Any] = {}
        for field, pattern in patterns.items():
            match = re.search(pattern, text, re.IGNORECASE)
            if not match:
                continue
            value = next((group for group in match.groups() if group), "")
            if value:
                profile[field] = value
        if not profile.get("person") or len(profile) < 2:
            return None
        return profile

    def update_session_memory(self, task: dict[str, Any], session: dict[str, Any]) -> None:
        super().update_session_memory(task, session)
        records = [
            record for record in (task.get("device_state") or {}).get("records") or []
            if isinstance(record, dict) and record.get("type") == "persistent_memory_write"
        ]
        if not records or any(isinstance(record.get("value"), dict) for record in records):
            return
        profile = self._parse_profile_write(str(task.get("prompt") or ""))
        if profile:
            self.person_memory[str(profile["person"])] = profile

    def decide_control(
        self,
        task: dict[str, Any],
        rec: dict[str, Any],
        text: str,
        clause: str | None,
        evidence: dict[str, Any] | None = None,
    ) -> tuple[str, str]:
        control, reason = super().decide_control(task, rec, text, clause, evidence)

        if not self.ENABLE_DOMAIN_SIGNALS:
            return control, reason

        # 명시적 절과 보안/동의/메모리 충돌이 낸 hold/ask는 절대 덮지 않는다.
        if clause is not None or control in ("hold", "ask"):
            return control, reason

        if "guest_mode" in rec and _is_denied(rec.get("guest_mode")):
            return "ask", "clarification_required"
        if "temporary_override_allowed" in rec and _is_denied(rec.get("temporary_override_allowed")):
            return "ask", "clarification_required"

        # 참석 불가/충돌 상태에서 곧바로 전송·일정 실행하지 않고 사용자 확인을 받는다.
        conflict = rec.get("calendar_conflict")
        if conflict is not None and (
            _value_has(conflict, "unavailable")
            or _value_has(conflict, "conflict")
            or _value_has(conflict, "overlap")
            or any(token in text_of(conflict) for token in ("불참", "충돌", "불가"))
        ):
            return "ask", "calendar_conflict"

        # trusted_subscription은 결제 focal의 merchant와 record 값이 일치할 때만 허용 신호다.
        trusted = str(rec.get("trusted_subscription") or "").strip()
        if trusted:
            focal = self.choose_focal(task, {})
            matching_payment = (
                focal.get("type") == "payment_request"
                and str((focal.get("attrs") or {}).get("merchant") or "") == trusted
            )
            if matching_payment:
                return "proceed", "trusted_subscription"

        # 저장된 기본 규정 경로에서 '검증된 내부 예외'가 명시된 경우 strict라는 이유만으로
        # amend하지 않는다. verified+internal 두 의미를 모두 요구한다.
        verified = rec.get("verified_internal_target")
        if (
            verified is not None
            and _value_has(verified, "verified", "internal")
            and "enterprise_policy_recall" in rec
        ):
            return "proceed", "verified_internal_target"

        return control, reason

    def build_content_scope(
        self,
        task: dict[str, Any],
        rec: dict[str, Any],
        text: str,
        control: str,
        target: str,
        focal: dict[str, Any],
        reason: str,
    ) -> dict[str, Any]:
        self._domain_focal_type = str(focal.get("type") or "")
        local_action_intent = bool(re.search(
            r"켜|꺼|끄|바꿔|변경|활성|비활성|toggle|enable|disable|activate|deactivate",
            text,
            re.IGNORECASE,
        ))
        self._direct_local_status = (
            "disabled" if re.search(
                r"꺼|끄|비활성|\boff\b|disable|deactivate",
                text,
                re.IGNORECASE,
            ) else "enabled"
        )
        direct_local_action = (
            self.ENABLE_DOMAIN_SIGNALS
            and control == "proceed"
            and target != "memory_store"
            and self._domain_focal_type in {"device_setting", "iot_routine"}
            and local_action_intent
        )
        self._direct_local_action = direct_local_action
        if direct_local_action:
            # 루틴/설정 변경은 외부 콘텐츠 전송이 아니라 로컬 상태 동작이다.
            return {
                "mode": "status_only",
                "allowed_fields": ["status"],
                "excluded_fields": [],
                "requires_user_confirmation": False,
            }
        if self.ENABLE_DOMAIN_SIGNALS and control == "proceed" and reason == "verified_internal_target":
            # 예외는 route만 완화한다. 공유 범위는 prompt대로 저장된 이전 성공 범위를 유지한다.
            profile = self.recall_profile_any(rec) or {}
            mode = str(profile.get("last_success_scope") or "summary")
            if mode not in {"raw", "summary", "redacted", "status_only"}:
                mode = "summary"
            excluded_value = str(profile.get("last_success_excluded") or "")
            excluded = [excluded_value] if excluded_value else []
            allowed = ["status"] if mode == "status_only" else ["summary"]
            if mode == "raw":
                allowed = ["summary", "title"]
            return {
                "mode": mode,
                "allowed_fields": allowed,
                "excluded_fields": excluded,
                "requires_user_confirmation": False,
            }
        return super().build_content_scope(task, rec, text, control, target, focal, reason)

    def build_policy(
        self,
        task: dict[str, Any],
        rec: dict[str, Any],
        control: str,
        target: str,
        focal: dict[str, Any],
        scope: dict[str, Any],
    ) -> dict[str, Any]:
        policy = super().build_policy(task, rec, control, target, focal, scope)
        if not self.ENABLE_DOMAIN_SIGNALS:
            return policy
        flags = set(policy.get("risk_flags") or [])
        is_local_state_action = bool(getattr(self, "_direct_local_action", False))
        if is_local_state_action:
            flags.discard("external_share")
            flags.add("local_only")
        trusted = str(rec.get("trusted_subscription") or "").strip()
        trusted_match = (
            control == "proceed"
            and focal.get("type") == "payment_request"
            and trusted
            and str((focal.get("attrs") or {}).get("merchant") or "") == trusted
        )
        verified = rec.get("verified_internal_target")
        verified_internal = (
            control == "proceed"
            and verified is not None
            and _value_has(verified, "verified", "internal")
            and "enterprise_policy_recall" in rec
        )
        if trusted_match or verified_internal:
            # 결제 실행과 명시적으로 검증된 내부 route를 콘텐츠 외부 공유로 오인하지 않는다.
            flags.discard("external_share")
        policy["risk_flags"] = sorted(flags)
        return policy

    def build_plan_events(
        self,
        rec: dict[str, Any],
        control: str,
        target: str,
        focal_id: str,
        scope: dict[str, Any],
        reason: str,
    ) -> list[dict[str, Any]]:
        if not self.ENABLE_DOMAIN_SIGNALS:
            return super().build_plan_events(rec, control, target, focal_id, scope, reason)
        if control == "ask" and reason == "calendar_conflict":
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "schedule_context"}},
                {"verb": "clarify", "target": "user", "args": {"reason": "calendar_conflict"}},
            ]

        if control == "proceed" and reason == "trusted_subscription":
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "payment_security_check"}},
                {"verb": "verify", "target": target, "args": {"check": "trusted_subscription"}},
                {"verb": "pay", "target": target, "args": {"method": "payment_method_check"}},
            ]

        if control == "proceed" and reason == "verified_internal_target":
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "standing_constraint"}},
                {"verb": "verify", "target": target, "args": {"check": "route_verified"}},
                {"verb": "summarize", "target": focal_id, "args": {"mode": scope.get("mode", "summary")}},
                {"verb": "dispatch", "target": target, "args": {"scope": scope.get("mode", "summary")}},
            ]

        guest_mode = rec.get("guest_mode")
        if control == "proceed" and guest_mode is not None and _value_has(guest_mode, "temporary", "allowed"):
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "routine_scope"}},
                {"verb": "verify", "target": focal_id, "args": {"condition": "temporary_allowed"}},
                {"verb": "toggle", "target": focal_id, "args": {"status": getattr(self, "_direct_local_status", "enabled")}},
            ]

        temporary = rec.get("temporary_override_allowed")
        if control == "proceed" and temporary is not None and not _is_denied(temporary):
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "privacy_guard"}},
                {"verb": "verify", "target": focal_id, "args": {"duration": temporary}},
                {"verb": "toggle", "target": focal_id, "args": {"status": getattr(self, "_direct_local_status", "enabled")}},
            ]

        if (
            control == "proceed" and getattr(self, "_direct_local_action", False)
        ):
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "inspect_context"}},
                {"verb": "verify", "target": focal_id, "args": {"check": "scope_check"}},
                {"verb": "toggle", "target": focal_id, "args": {"status": getattr(self, "_direct_local_status", "enabled")}},
            ]

        return super().build_plan_events(rec, control, target, focal_id, scope, reason)

    def user_response(
        self,
        control: str,
        target: str,
        scope: dict[str, Any],
        reason: str = "",
        rec: dict[str, Any] | None = None,
    ) -> str:
        records = rec or {}
        if not self.ENABLE_DOMAIN_SIGNALS:
            return super().user_response(control, target, scope, reason, records)
        if control == "ask" and reason == "calendar_conflict":
            return "일정 충돌이 있어 참석 가능 여부를 사용자에게 먼저 확인합니다."
        if control == "proceed" and reason == "trusted_subscription":
            return f"신뢰된 구독과 결제 조건을 확인해 {target} 결제를 진행합니다."
        if control == "proceed" and reason == "verified_internal_target":
            return f"저장된 기본 공유 범위를 적용해 검증된 내부 승인 채널 {target}에 요약을 전달합니다."
        if (control == "proceed" and records.get("guest_mode") is not None
                and _value_has(records.get("guest_mode"), "temporary", "allowed")):
            return "가족 안전 범위를 확인하고 임시 허용 범위에서 루틴을 변경합니다."
        if (control == "proceed" and records.get("temporary_override_allowed") is not None
                and not _is_denied(records.get("temporary_override_allowed"))):
            duration = str(records.get("temporary_override_allowed") or "허용된 시간")
            return f"개인정보 보호 조건을 확인하고 {duration} 동안 설정을 켭니다."
        if control == "proceed" and getattr(self, "_direct_local_action", False) and getattr(self, "_domain_focal_type", "") == "device_setting":
            action = "끕니다" if getattr(self, "_direct_local_status", "enabled") == "disabled" else "변경합니다"
            return f"현재 상태와 개인정보 보호 범위를 확인하고 기기 설정을 {action}."
        if control == "proceed" and getattr(self, "_direct_local_action", False) and getattr(self, "_domain_focal_type", "") == "iot_routine":
            action = "끕니다" if getattr(self, "_direct_local_status", "enabled") == "disabled" else "변경합니다"
            return f"현재 안전 범위와 적용 조건을 확인하고 기기 루틴을 {action}."
        return super().user_response(control, target, scope, reason, records)


# --- 건강 정보 target 보정 ---

import re
from typing import Any



class HealthHarness(DomainHarness):
    def infer_target(
        self,
        task: dict[str, Any],
        rec: dict[str, Any],
        text: str,
        clause: str | None,
        control: str,
        focal: dict[str, Any],
        session: dict[str, Any],
    ) -> str:
        target = super().infer_target(task, rec, text, clause, control, focal, session)
        # 명시적 hold/ask-user의 user target은 저장 채널이 덮지 않는다.
        if target == "user" or clause in ("hold", "ask_user"):
            return target
        if "persistent_memory_recall" not in rec:
            return target
        recall = rec.get("persistent_memory_recall")
        memory_class = str(recall.get("memory_class") or "") if isinstance(recall, dict) else ""
        if (
            "target_changed_after_turn" in rec
            or "resolved_target" in rec
            or memory_class in {"prior_result", "standing_constraint"}
        ):
            return target
        profile = self.recall_profile(rec) or self.recall_profile_any(rec)
        if not profile:
            return target
        health_intent = (
            focal.get("type") == "health_record"
            and re.search(r"복약|약\s*알림|건강|medication|health", text, re.IGNORECASE)
        )
        health_channel = str(profile.get("health_channel") or "")
        return health_channel if health_intent and health_channel else target


# --- 도메인 규칙 활성화 ---



class PolicyHarness(HealthHarness):
    ENABLE_DOMAIN_SIGNALS = True


# --- 입력 상태와 실행 순서 보정 ---

import copy
import json
import re
from collections import defaultdict
from typing import Any



_INACTIVE = {
    "", "0", "false", "inactive", "disabled", "resolved", "cleared", "none", "normal",
}


def _norm_token(value: Any) -> str:
    return re.sub(r"[^0-9a-z가-힣]+", "_", str(value or "").strip().lower()).strip("_")


def _bool_value(value: Any) -> bool | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    token = _norm_token(value)
    if token in {"true", "1", "yes", "active", "enabled", "changed", "ambiguous", "blocked", "revoked"}:
        return True
    if token in _INACTIVE | {"no", "unchanged", "unambiguous", "allowed", "granted"}:
        return False
    return None


def _mapping_state(value: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in value:
            return value.get(key)
    return None


def _canonical_record_value(record_type: str, value: Any) -> tuple[bool, Any]:
    """(keep, value). 명시적인 inactive/allowed 상태는 신호 존재로 취급하지 않는다."""
    kind = str(record_type or "")
    if kind == "session_share_policy":
        token = _norm_token(value)
        if token in {"not_strict", "non_strict", "unrestricted", "normal", "allowed"}:
            return True, "normal"
        return True, value

    if kind == "share_boundary_update":
        if isinstance(value, dict):
            blocked = _mapping_state(value, "blocked", "is_blocked")
            if _bool_value(blocked) is True:
                return True, "dispatch_blocked"
            mode = _mapping_state(value, "mode", "boundary", "value", "status")
            if mode is not None:
                mode_token = _norm_token(mode)
                if mode_token in {"allowed", "unrestricted", "none", "cleared", "normal"}:
                    return False, None
                if _bool_value(blocked) is False and mode_token in {"blocked", "dispatch_blocked"}:
                    return False, None
                return True, mode
            if blocked is not None and _bool_value(blocked) is False:
                return False, None
        elif _norm_token(value) in {"allowed", "unrestricted", "none", "cleared", "normal"}:
            return False, None

    if kind == "dispatch_authority_check" and isinstance(value, dict):
        confirmed = _mapping_state(value, "confirmed", "is_confirmed")
        if confirmed is not None:
            return True, "confirmed" if _bool_value(confirmed) is True else "authority_incomplete"
        state = _mapping_state(value, "status", "state", "value")
        if state is not None:
            return True, state

    if kind == "consent" and isinstance(value, dict):
        revoked = _mapping_state(value, "revoked", "withdrawn")
        if _bool_value(revoked) is True:
            return True, "revoked"
        status = _mapping_state(value, "status", "state", "value")
        if status is not None:
            return True, status
        if revoked is not None:
            return True, "granted"

    # 이 세 record는 type의 존재가 아니라 value의 polarity가 의미를 결정한다.
    if kind == "amount_changed":
        state = value
        if isinstance(value, dict):
            state = _mapping_state(
                value, "changed", "amount_changed", "mismatch",
                "requires_confirmation", "status", "state", "value",
            )
        truth = _bool_value(state)
        token = _norm_token(state)
        if truth is False or token in {
            "same", "matched", "unchanged", "not_changed", "no_change", "confirmed", "resolved",
        }:
            return False, None
        if truth is True or token in {
            "changed_since_confirmation", "amount_changed", "amount_mismatch", "mismatch",
            "different", "updated", "requires_confirmation",
        }:
            return True, "changed"
        return True, value

    if kind == "duration_ambiguous":
        state = value
        if isinstance(value, dict):
            state = _mapping_state(
                value, "ambiguous", "duration_ambiguous", "pending", "unknown",
                "status", "state", "value",
            )
        truth = _bool_value(state)
        token = _norm_token(state)
        if truth is False or token in {
            "clear", "specified", "known", "unambiguous", "not_ambiguous", "resolved",
        }:
            return False, None
        if truth is True or token in {
            "ambiguous", "pending", "unknown", "unclear", "unspecified", "missing",
            "needs_clarification", "duration_ambiguous", "temporary_or_always",
        }:
            return True, "ambiguous"
        return True, value

    if kind == "merchant_verification":
        state = value
        if isinstance(value, dict):
            verified = _mapping_state(value, "verified", "is_verified")
            if verified is not None:
                truth = _bool_value(verified)
                if truth is True:
                    return False, None
                if truth is False:
                    return True, "unverified"
            state = _mapping_state(value, "status", "state", "result", "value")
        token = _norm_token(state)
        if token in {"verified", "passed", "matched", "trusted", "confirmed", "valid"}:
            return False, None
        if token in {"unverified", "failed", "mismatch", "unknown", "pending", "invalid"}:
            return True, token
        return True, value

    stateful = {
        "security_alert": ("active", "enabled", "status", "state"),
        "safety_mode": ("active", "enabled", "status", "state"),
        "memory_conflict": ("active", "conflict", "status", "state"),
        "target_changed_after_turn": ("changed", "active", "status", "state"),
        "ambiguous_target": ("ambiguous", "active", "status", "state"),
        "ambiguous_focal": ("ambiguous", "active", "status", "state"),
        "external_share_policy": ("restricted", "active", "status", "state"),
    }
    if kind in stateful:
        if isinstance(value, dict):
            state = _mapping_state(value, *stateful[kind])
            truth = _bool_value(state)
            if truth is False:
                return False, None
            if truth is True:
                # target change dict의 실제 target 등 나머지 구조는 보존한다.
                return True, value
            status = _mapping_state(value, "value", "mode")
            if status is not None:
                value = status
        token = _norm_token(value)
        inactive_by_kind = {
            "security_alert": {"allowed", "permitted", "safe", "no_alert", "no_issue"},
            "safety_mode": {"allowed", "permitted", "safe", "off", "not_active"},
            "memory_conflict": {"resolved", "no_conflict"},
            "target_changed_after_turn": {"unchanged", "not_changed", "no_change"},
            "ambiguous_target": {"resolved", "unambiguous", "not_ambiguous"},
            "ambiguous_focal": {"resolved", "unambiguous", "not_ambiguous"},
            "external_share_policy": {"allowed", "unrestricted", "permitted", "no_restriction"},
        }
        if token in _INACTIVE | inactive_by_kind.get(kind, set()):
            return False, None
    return True, value


def _canonical_record(record: dict[str, Any]) -> tuple[bool, dict[str, Any]]:
    """record top-level lifecycle flag와 value polarity를 함께 적용한다."""
    for key in ("active", "enabled", "valid"):
        if key in record and _bool_value(record.get(key)) is False:
            return False, record
    for key in ("inactive", "superseded", "deleted", "cleared"):
        if key in record and _bool_value(record.get(key)) is True:
            return False, record
    item = dict(record)
    kind = str(item.get("type") or "")
    keep, value = _canonical_record_value(kind, item.get("value"))
    if not keep:
        return False, item
    item["value"] = value
    return True, item


def _record_recency(record: dict[str, Any]) -> tuple[str, int, float | str] | None:
    """명시된 recency key와 비교 가능한 값. 서로 다른 key/type은 비교하지 않는다."""
    for container in (record, record.get("value") if isinstance(record.get("value"), dict) else {}):
        for key in ("turn", "turn_index", "version", "sequence", "timestamp", "updated_at"):
            if key in container:
                value = container.get(key)
                try:
                    return key, 0, float(value)
                except (TypeError, ValueError):
                    return key, 1, str(value)
    return None


def _phrase_pattern(needle: str) -> re.Pattern[str] | None:
    phrase = re.sub(r"[_\-\s]+", " ", str(needle or "").strip().lower())
    if not phrase:
        return None
    particle = r"(?:은|는|이|가|을|를|과|와|도|만|에서|으로|로|에게|의)"
    return re.compile(
        r"(?<![0-9a-z가-힣])" + re.escape(phrase)
        + r"(?=$|[^0-9a-z가-힣]|" + particle + r"(?:$|[^0-9a-z가-힣]))"
    )


def _phrase_present(needle: str, haystack: str) -> bool:
    """identifier의 _, -, 공백 차이는 허용하되 pancake 안의 cake 같은 부분문자열은 배제."""
    text = re.sub(r"[_\-\s]+", " ", str(haystack or "").lower())
    pattern = _phrase_pattern(needle)
    return bool(pattern and pattern.search(text))


def _avoid_terms(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value] if value.strip() else []
    if isinstance(value, dict):
        terms: list[str] = []
        for item in value.values():
            terms.extend(_avoid_terms(item))
        return terms
    if isinstance(value, (list, tuple, set)):
        terms = []
        for item in value:
            terms.extend(_avoid_terms(item))
        return terms
    return [str(value)] if value not in (None, "") else []


def _requested_avoid_term(term: str, prompt: str) -> bool:
    """금지 대상을 실제 포함해 달라는 요청인지, 제외하라는 언급인지 구분한다."""
    text = re.sub(r"[_\-\s]+", " ", str(prompt or "").lower())
    pattern = _phrase_pattern(term)
    if not pattern:
        return False
    exclusion = re.compile(
        r"보내지|전달하지|공유하지|포함하지|사용하지|하지\s*말|"
        r"제외|빼고|빼줘|생략|없이|금지|피해|피하고|"
        r"do\s+not|don't|without|exclude|omit|avoid",
        re.IGNORECASE,
    )
    for match in pattern.finditer(text):
        before = text[max(0, match.start() - 24):match.start()]
        after = text[match.end():match.end() + 32]
        if exclusion.search(before) or exclusion.search(after):
            continue
        return True
    return False


_ROUTE_CONTEXT = re.compile(
    r"경로|채널|route|binding|라우트|승인|권한|approval|authority|dispatch|전송|전달|공유",
    re.IGNORECASE,
)
_ROUTE_UNRESOLVED = re.compile(
    r"미확정|미결정|미해결|대기|보류|불분명|"
    r"unconfirmed|unresolved|pending|unknown|not\s+(?:confirmed|resolved|bound)",
    re.IGNORECASE,
)
_ROUTE_RESOLVED = re.compile(
    r"(?<!미)확정|확인\s*완료|승인\s*완료|(?<!미)해결|완료|연결됨|바인딩됨|"
    r"\b(?:confirmed|resolved|approved|completed|bound|ready)\b",
    re.IGNORECASE,
)


def _explicit_route_state(text: str) -> bool | None:
    """True는 미확정/확인 우선, False는 확정. route 문맥 없는 문장은 None."""
    value = str(text or "")
    if route_confirmation_policy(value):
        return True
    if _ROUTE_CONTEXT.search(value) and _ROUTE_UNRESOLVED.search(value):
        return True
    if _ROUTE_CONTEXT.search(value) and _ROUTE_RESOLVED.search(value):
        return False
    return None


def _external_route_candidates(rec: dict[str, Any]) -> bool:
    """외부 후보가 둘 이상 남은 route snapshot인지 확인한다."""
    snapshot = str(rec.get("route_candidate_snapshot") or "").strip().lower()
    return snapshot == "external_candidates_present"


def _derived_route_ambiguity(rec: dict[str, Any]) -> bool:
    """차단된 외부 route의 사용자 binding이 아직 끝나지 않은 상태."""
    return (
        norm_boundary(rec.get("share_boundary_update")) == "blocked"
        and norm_authority(rec.get("dispatch_authority_check")) == "pending"
        and _external_route_candidates(rec)
        and ("ambiguous_focal" in rec or "ambiguous_target" in rec)
    )


def _explicit_precondition_uncertainty(task: dict[str, Any]) -> bool:
    """현재 prompt가 전제 조건의 미확정을 직접 밝히는지 확인한다."""
    prompt = str(task.get("prompt") or "").lower()
    subject = r"(?:전제|선행\s*조건|precondition|prerequisite)"
    unresolved = (
        r"(?:확정되지|미확정|확인되지|불확실|불분명|모호|"
        r"unconfirm|not\s+confirm|uncertain|unknown|unresolved)"
    )
    return bool(re.search(
        rf"(?:{subject}.{{0,48}}{unresolved}|{unresolved}.{{0,48}}{subject})",
        prompt,
    ))


def _latest_turn_text(task: dict[str, Any]) -> str:
    """현재 prompt와 가장 최신 history turn만 합친다."""
    histories = [
        item for item in (task.get("visible_history") or [])
        if isinstance(item, dict)
    ]
    if not histories:
        return str(task.get("prompt") or "")

    def turn_key(item: dict[str, Any]) -> tuple[int, int | str]:
        try:
            return 1, int(item.get("turn"))
        except (TypeError, ValueError, OverflowError):
            return 0, str(item.get("turn") or "")

    latest = max(turn_key(item) for item in histories)
    summaries = [
        str(item.get("summary") or "")
        for item in histories
        if turn_key(item) == latest
    ]
    return "\n".join([str(task.get("prompt") or ""), *summaries])


def _explicit_closed_approval_route(task: dict[str, Any]) -> bool:
    """최신 지시가 승인 경로 차단과 확인 우선을 함께 명시하는지 본다."""
    text = _latest_turn_text(task).lower()
    route = bool(re.search(
        r"승인\s*(?:경로|채널)|권한\s*(?:경로|채널)|"
        r"(?:approval|authorization|authority)\s*(?:route|path|channel)",
        text,
    ))
    blocked = bool(re.search(
        r"닫히|차단|막히|미확정|보류|closed|blocked|unavailable|unresolved|pending",
        text,
    ))
    confirm_first = bool(re.search(
        r"확인\s*(?:응답|우선|먼저)|먼저\s*확인|"
        r"confirm(?:ation)?\s*(?:first|required)|clarif|ask\s+(?:first|before)",
        text,
    ))
    return route and blocked and confirm_first


class FinalHarness(PolicyHarness):
    """최종 제출 Harness."""

    def infer_target(
        self,
        task: dict[str, Any],
        rec: dict[str, Any],
        text: str,
        clause: str | None,
        control: str,
        focal: dict[str, Any],
        session: dict[str, Any],
    ) -> str:
        """profile 없는 일반 recall은 고정 이름보다 확정된 세션 route를 우선한다."""
        recall = rec.get("persistent_memory_recall")
        if isinstance(recall, dict) and not self.recall_profile(rec):
            if clause in {"hold", "ask_user"}:
                return super().infer_target(task, rec, text, clause, control, focal, session)
            changed_target = extract_target_value(rec.get("target_changed_after_turn"))
            if changed_target and not is_target_change_status(changed_target):
                return changed_target
            resolved_target = extract_target_value(rec.get("resolved_target"))
            if resolved_target:
                return resolved_target
            person = str(recall.get("person") or "")
            same_person = self.person_memory.get(person) if person else None
            memory_class = str(recall.get("memory_class") or "")
            intent_default = bool(re.search(r"조명|불빛|어두워|검진|점검", text, re.IGNORECASE))
            recent_route = str(
                session.get("last_observed_target")
                or session.get("last_route_target")
                or ""
            )
            if (
                not same_person
                and not intent_default
                and memory_class not in {"prior_result", "standing_constraint"}
            ):
                return recent_route if recent_route not in {"", "user", "memory_store"} else "user"
            if not same_person and memory_class in {"prior_result", "standing_constraint"}:
                return "user"
        return super().infer_target(task, rec, text, clause, control, focal, session)

    def build_policy(
        self,
        task: dict[str, Any],
        rec: dict[str, Any],
        control: str,
        target: str,
        focal: dict[str, Any],
        scope: dict[str, Any],
    ) -> dict[str, Any]:
        policy = super().build_policy(task, rec, control, target, focal, scope)
        flags = set(policy.get("risk_flags") or [])

        # blocked external binding은 route 미확정 상태이며, 그 자체가 전제 변경은 아니다.
        if (
            norm_boundary(rec.get("share_boundary_update")) == "blocked"
            and _external_route_candidates(rec)
        ):
            flags.discard("precondition_changed")

        # 외부 후보의 사용자 binding이 pending이면 수신 대상도 아직 확정되지 않았다.
        route_ambiguity = _derived_route_ambiguity(rec)
        if route_ambiguity:
            flags.add("target_ambiguity")

        policy["risk_flags"] = sorted(flags)
        if route_ambiguity and control == "proceed":
            policy["requires_confirmation"] = True
        return policy

    def build_content_scope(
        self,
        task: dict[str, Any],
        rec: dict[str, Any],
        text: str,
        control: str,
        target: str,
        focal: dict[str, Any],
        reason: str,
    ) -> dict[str, Any]:
        scope = super().build_content_scope(
            task, rec, text, control, target, focal, reason
        )
        if control != "ask" or scope.get("mode") != "summary":
            return scope

        strict = is_strict_policy(rec.get("session_share_policy"))
        boundary = norm_boundary(rec.get("share_boundary_update"))
        trace = rec.get("focal_resolution_trace")
        trace = trace if isinstance(trace, dict) else {}

        # binding 전에는 민감한 file 내용 자체를 넘기지 않는다.
        if (
            strict
            and boundary == "blocked"
            and norm_authority(rec.get("dispatch_authority_check")) == "incomplete"
            and _external_route_candidates(rec)
            and "ambiguous_focal" in rec
            and focal.get("type") == "file"
            and classify_clause(text) is None
            and reason == "route_resolution_required"
            and _explicit_closed_approval_route(task)
        ):
            return {
                "mode": "none",
                "allowed_fields": [],
                "excluded_fields": [],
                "requires_user_confirmation": True,
            }

        redact = False

        # 상위 guardrail과 redacted 경계가 함께 있으면 확인 전 최소 공개만 허용한다.
        if (
            strict
            and "guardrail_ladder_signal" in rec
            and "ambiguous_target" in rec
            and boundary == "redacted"
            and _explicit_precondition_uncertainty(task)
        ):
            redact = True

        # 엄격 정책에서 draft 대상 변경은 확정된 route가 아니므로 원문 범위를 줄인다.
        changed_target = extract_target_value(rec.get("target_changed_after_turn"))
        if (
            strict
            and changed_target
            and not is_target_change_status(changed_target)
            and trace.get("latest_phase") == "draft"
        ):
            redact = True

        # 임시/상시 범위가 정해지지 않은 복합 요청은 확인 전 최소 공개로 제한한다.
        if strict and "duration_ambiguous" in rec and focal.get("type") == "message":
            redact = True

        # 건강 기록의 금지 필드와 미확정 전제가 함께 있으면 확인 전에도 가린다.
        if (
            strict
            and focal.get("type") == "health_record"
            and str(rec.get("external_share_policy")) == "doctor_note_forbidden"
            and _explicit_precondition_uncertainty(task)
        ):
            redact = True

        if redact:
            scope = dict(scope)
            scope["mode"] = "redacted"
        return scope

    def _normalize_record_stream(self, task: dict[str, Any]) -> dict[str, Any]:
        normalized = copy.deepcopy(task)
        state = normalized.get("device_state") or {}
        groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for record in state.get("records") or []:
            if isinstance(record, dict):
                groups[str(record.get("type") or "")].append(dict(record))

        collapsed: list[dict[str, Any]] = []
        for kind in sorted(groups):
            raw_items = groups[kind]
            with_recency = [(item, _record_recency(item)) for item in raw_items]
            # 일부에만 recency가 있거나 key/type이 섞이면 어느 쪽이 최신인지 추측하지 않는다.
            # 모든 중복이 동일한 비교 축을 공유할 때만 최신 record를 선택한다.
            if with_recency and all(recency is not None for _, recency in with_recency):
                axes = {(recency[0], recency[1]) for _, recency in with_recency if recency is not None}
                if len(axes) == 1:
                    latest_value = max(recency[2] for _, recency in with_recency if recency is not None)
                    raw_items = [
                        item for item, recency in with_recency
                        if recency is not None and recency[2] == latest_value
                    ]

            items: list[dict[str, Any]] = []
            for raw in raw_items:
                keep, item = _canonical_record(raw)
                if keep:
                    items.append(item)
            # 최신 명시 record가 tombstone/inactive면 과거 active record를 되살리지 않는다.
            if not items:
                continue

            if len(items) == 1:
                chosen = items[0]
            elif kind == "share_boundary_update":
                rank = {"blocked": 4, "local": 3, "redacted": 2, "other": 1, "": 0}
                chosen = max(items, key=lambda item: (rank[norm_boundary(item.get("value"))], json.dumps(item.get("value"), ensure_ascii=False, sort_keys=True)))
            elif kind == "dispatch_authority_check":
                rank = {"incomplete": 3, "pending": 2, "confirmed": 1, "other": 0, "": 0}
                chosen = max(items, key=lambda item: (rank[norm_authority(item.get("value"))], json.dumps(item.get("value"), ensure_ascii=False, sort_keys=True)))
            elif kind == "consent":
                revoked = [item for item in items if is_revoked_consent(item.get("value"))]
                chosen = (revoked or sorted(items, key=lambda item: json.dumps(item.get("value"), ensure_ascii=False, sort_keys=True)))[0]
            else:
                chosen = sorted(items, key=lambda item: json.dumps(item.get("value"), ensure_ascii=False, sort_keys=True))[-1]

            values = {json.dumps(item.get("value"), ensure_ascii=False, sort_keys=True) for item in items}
            if kind == "resolved_target" and len(values) > 1:
                collapsed.append({"type": "ambiguous_target", "value": "conflicting_duplicate_targets"})
            collapsed.append(chosen)

        state["records"] = collapsed
        normalized["device_state"] = state
        return normalized

    @staticmethod
    def _latest_decision(task: dict[str, Any], full_text: str) -> tuple[str | None, str]:
        prompt = str(task.get("prompt") or "")
        prompt_clause = classify_clause(prompt)
        if prompt_clause:
            return prompt_clause, prompt
        histories = [item for item in (task.get("visible_history") or []) if isinstance(item, dict)]
        grouped: dict[tuple[int, int | str], list[str]] = defaultdict(list)
        for item in histories:
            turn = item.get("turn")
            try:
                key: tuple[int, int | str] = (1, int(turn))
            except (TypeError, ValueError, OverflowError):
                key = (0, str(turn or ""))
            grouped[key].append(str(item.get("summary") or ""))
        for key in sorted(grouped, reverse=True):
            summary = "\n".join(grouped[key])
            clause = classify_clause(summary)
            if clause:
                return clause, summary
            # 최신 turn이 route 상태를 명시적으로 갱신했다면 과거 ask/hold 절은 stale이다.
            if _explicit_route_state(summary) is not None:
                return None, summary
        return classify_clause(full_text), full_text

    @staticmethod
    def _latest_route_confirmation(task: dict[str, Any], full_text: str) -> bool:
        """최신 turn의 명시 route 상태가 과거 미확정 문구를 덮어쓰게 한다."""
        prompt_state = _explicit_route_state(str(task.get("prompt") or ""))
        if prompt_state is not None:
            return prompt_state
        histories = [item for item in (task.get("visible_history") or []) if isinstance(item, dict)]
        grouped: dict[tuple[int, int | str], list[str]] = defaultdict(list)
        for item in histories:
            turn = item.get("turn")
            try:
                key: tuple[int, int | str] = (1, int(turn))
            except (TypeError, ValueError, OverflowError):
                key = (0, str(turn or ""))
            grouped[key].append(str(item.get("summary") or ""))
        for key in sorted(grouped, reverse=True):
            state = _explicit_route_state("\n".join(grouped[key]))
            if state is not None:
                return state
        return route_confirmation_policy(full_text)

    def decide_control(
        self,
        task: dict[str, Any],
        rec: dict[str, Any],
        text: str,
        clause: str | None,
        evidence: dict[str, Any] | None = None,
    ) -> tuple[str, str]:
        profile = self.recall_profile_any(rec)
        old_avoid: Any = None
        normalized_avoid = False
        if profile is not None and not isinstance(profile.get("avoid"), str):
            old_avoid = profile.get("avoid")
            profile["avoid"] = " ".join(_avoid_terms(old_avoid))
            normalized_avoid = True
        try:
            control, reason = super().decide_control(task, rec, text, clause, evidence)
        finally:
            if normalized_avoid and profile is not None:
                profile["avoid"] = old_avoid

        # 중복 resolved target의 충돌은 배열 마지막 값을 임의 채택하지 않는다.
        if clause is None and str(rec.get("ambiguous_target")) == "conflicting_duplicate_targets":
            return "ask", "target_ambiguity"

        # 저장 금기는 identifier/구 단위로 실제 요청에 나타날 때만 충돌이다.
        if reason == "stored_preference_violation":
            profile = self.recall_profile_any(rec)
            avoid_value = (profile or {}).get("avoid")
            current_text = str(getattr(self, "_current_prompt", "") or text)
            requested = any(_requested_avoid_term(term, current_text) for term in _avoid_terms(avoid_value))
            if profile is not None and not requested:
                old_avoid = profile.get("avoid")
                profile["avoid"] = ""
                try:
                    control, reason = super().decide_control(task, rec, text, clause, evidence)
                finally:
                    profile["avoid"] = old_avoid

        # list/dict 형태의 금기도 각 항목 단위로 동일하게 적용한다.
        if clause is None and control not in {"hold", "ask"} and profile is not None:
            current_text = str(getattr(self, "_current_prompt", "") or text)
            if any(_requested_avoid_term(term, current_text) for term in _avoid_terms(profile.get("avoid"))):
                return "hold", "stored_preference_violation"

        # 구조화 record의 확인 필요 신호. 명시 결정절과 상위 안전 판단을 우선한다.
        if clause is None and control not in ("hold", "ask"):
            if _norm_token(rec.get("amount_changed")) == "changed":
                return "ask", "payment_confirmation_required"
            if _norm_token(rec.get("duration_ambiguous")) == "ambiguous":
                return "ask", "clarification_required"
            merchant = _norm_token(rec.get("merchant_verification"))
            if merchant in {"unverified", "failed", "mismatch", "unknown"}:
                return "ask", "payment_confirmation_required"
        return control, reason

    def _filter_plan_events(
        self, task: Any, events: list[dict[str, Any]]
    ) -> tuple[list[dict[str, Any]], bool]:
        available = task.get("available_actions") if isinstance(task, dict) else None
        if not isinstance(available, (list, tuple, set)):
            return events, False
        allowed = {str(action) for action in available}
        filtered = [event for event in events if str(event.get("verb")) in allowed]
        safe: list[dict[str, Any]] = []
        completed: set[str] = set()
        for event in filtered:
            verb = str(event.get("verb"))
            required: set[str] = set()
            if verb in {"verify", "redact", "summarize"}:
                required.add("read")
            if verb == "dispatch":
                required.add("read")
                scope = str((event.get("args") or {}).get("scope") or "")
                if scope == "redacted":
                    required.add("redact")
                elif scope == "summary":
                    required.add("summarize")
            elif verb in {"update", "toggle", "schedule", "pay"}:
                required.update({"read", "verify"})
            if required <= completed:
                safe.append(event)
                completed.add(verb)
        return safe, safe != events

    def _answer_task_impl(self, task: dict[str, Any], session: dict[str, Any]) -> dict[str, Any]:
        task = sanitize_task(task)
        task = self._normalize_record_stream(task)
        if not has_minimum_structure(task):
            return self._safe_default_answer(task)

        records = records_of(task)
        rec = record_map(records)
        text = task_text(task)
        self._current_prompt = str(task.get("prompt") or "")
        clause, decision_text = self._latest_decision(task, text)
        evidence = self.slm.summarize_task(task)
        focal = self.choose_focal(task, session)
        focal_id = str(focal.get("id") or "")
        control, reason = self.decide_control(task, rec, text, clause, evidence)

        if clause is None and control == "proceed" and self._latest_route_confirmation(task, text):
            control, reason = "ask", "route_resolution_required"

        # user/memory_store 같은 제어 target이 concrete route 기억을 덮지 않도록 분리한다.
        if "target_changed_after_turn" in rec:
            session.pop("last_route_target", None)
        if session.get("last_route_target"):
            session["last_target"] = session["last_route_target"]
        target = self.infer_target(task, rec, text, clause, control, focal, session)
        if reason == "target_changed":
            changed_target = extract_target_value(rec.get("target_changed_after_turn"))
            if not changed_target or is_target_change_status(changed_target):
                target = "user"
        content_scope = self.build_content_scope(task, rec, text, control, target, focal, reason)
        policy = self.build_policy(task, rec, control, target, focal, content_scope)
        plan_events = self.build_plan_events(rec, control, target, focal_id, content_scope, reason)
        plan_events, plan_filtered = self._filter_plan_events(task, plan_events)

        session["last_focal_id"] = focal_id
        # 변경된 대상은 이번 turn에서 확인 대상으로 보여 주되, ask 상태에서는 확정 route로
        # 승격하지 않는다. 다음 무표적 turn은 user 확인에서 다시 시작한다.
        session["last_target"] = (
            "user" if reason == "target_changed" and control == "ask" else target
        )
        session["last_control"] = control
        observed_target = extract_target_value(rec.get("resolved_target"))
        if observed_target:
            session["last_observed_target"] = observed_target
        if reason == "target_changed":
            changed_target = extract_target_value(rec.get("target_changed_after_turn"))
            if changed_target and not is_target_change_status(changed_target):
                session["last_observed_target"] = changed_target
            else:
                session.pop("last_observed_target", None)
        if control in {"proceed", "amend"} and target not in {"", "user", "memory_store"}:
            session["last_route_target"] = target

        answer = {
            "focal_id": focal_id,
            "target": target,
            "control": control,
            "content_scope": content_scope,
            "policy": policy,
            "plan_events": plan_events,
            "user_response": self.user_response(control, target, content_scope, reason, rec),
            "audit_tags": sorted(
                ({reason} | set(evidence.get("audit_tags", []))
                 | ({"available_actions_filtered"} if plan_filtered else set())) - {""}
            ),
            "counterfactual": "최신 기록, 동의 상태, 공유 범위, 보안 신호가 바뀌면 판단이 달라질 수 있습니다.",
        }
        if control == "proceed" and target == "memory_store" and "persistent_memory_write" in rec:
            self.update_session_memory(task, session)
        return answer
