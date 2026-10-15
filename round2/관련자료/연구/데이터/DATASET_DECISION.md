# SafeShare 공개 데이터 사용 결정

작성 기준일: 2026-08-02
결정 상태: **조건부 채택 — 원본 레코드는 다운로드·탑재하지 않음**

## 결론

SafeShare의 대회용 합성 데이터는 다음 역할 분리로 준비한다.

1. `nvidia/Nemotron-Personas-Korea`는 한국 성인 인구의 다양성을 빠뜨리지 않기 위한 **층화 설계 참고자료**로만 사용한다.
2. `BSC-LT/m-personas`의 한국어 split은 존대, 직접성, 문장 길이와 다중 turn 구조 같은 **발화 변형 설계 참고자료**로만 사용한다.
3. `huggingface-KREW/korean-role-playing`은 현재 제출 corpus에 포함하지 않고 **연구·수동 QA 참고자료**로만 남긴다.
4. 앱의 `ACT/ASK/WAIT/ABSTAIN`, 대상 선택, 보류와 복구 판단은 외부 데이터나 LLM이 아니라 기존 deterministic `SafeShareCore`가 수행한다.
5. APK에는 상황 input만 포함하며 test oracle, expected state, 점수와 정답 관계는 포함하지 않는다.

이 결정은 법률 자문이 아니라 제출 위험을 줄이기 위한 엔지니어링 기준이다. 외부 자료의 실제 레코드를 배포물에 넣는 것으로 범위를 바꾸면 별도 권리 검토가 필요하다.

## 고정 revision과 사용 범위

| source | pinned revision | 표시 license | 허용 범위 | 제외 범위 | 상태 |
|---|---|---|---|---|---|
| `nvidia/Nemotron-Personas-Korea` | `ada0f5b53a38bb5a30cce09358adde883c1ab63a` | CC BY 4.0 | `age`를 age band로, `province`를 region band로, `occupation`을 work context로 범주화하는 층화 설계 | UUID·이름·정확한 나이·시군구·원문 persona·가족 서술·음식 관련 필드의 배포와 직접 복제 | 채택 |
| `BSC-LT/m-personas` | `6fab205a86a9244c90a2782e28055027f45ddd03` | Apache 2.0 | `ko` split의 synthetic `messages`에서 발화 길이·존대·직접성·noise 변형의 분포를 참고 | `source_text`, 원문 대화의 verbatim 복제, source ID의 APK 포함, 성별·직업 고정관념 재사용 | 채택 |
| `huggingface-KREW/korean-role-playing` | `ceb55e96eddaee72dac60be5aa13e458125a1dc5` | Apache 2.0 | 수동으로 한국어 자연스러움을 비교하는 연구 참고 | 현재 네 subset 모두 생성 seed 또는 배포 material로 사용하지 않음 | 보류 |

## source별 판단 근거

### NVIDIA Nemotron-Personas-Korea

- 데이터 카드는 완전히 합성된 성인 persona 100만 레코드와 7종 persona 서술을 설명한다.
- 데이터 카드의 field 수와 name 포함 여부에 관한 설명은 서로 일치하지 않는 부분이 있다. 따라서 나중에 원본을 합법적으로 수집하더라도 문서 설명이 아니라 pinned Parquet 실제 schema에 allowlist를 적용하고, 예상하지 않은 열이 나오면 실패시킨다.
- 카드에는 KOSIS, 대법원, 국민건강보험공단, KREI와 NAVER Cloud가 통계·seed 출처로 적혀 있다. 특히 KREI 자료는 공공누리 제4유형이라고 명시된다. 제4유형은 출처표시·상업적 이용금지·변경금지 조건이므로 음식·소비 관련 persona는 사용하지 않는다.
- 생물학적 성별, 나이, 지역은 표현 스타일이나 안전 결정에 사용하지 않는다. 이 값들은 coverage audit에만 쓰고, 말투 축은 독립적으로 배정한다.

### BSC-LT/m-personas

- pinned revision의 한국어 split은 31,814건이다.
- persona와 대화는 synthetic이지만 seed document는 FineWeb2에서 왔다. 원문 provenance가 개별 레코드 수준에서 제출에 적합한지 재검토하지 않았으므로 `source_text`는 절대 사용하거나 재배포하지 않는다.
- 데이터 카드는 gender 및 gender-occupation bias를 명시한다. SafeShare의 업무 역할, 허용 범위와 말투를 demographic에 묶지 않는다.
- 실제 활용은 문장을 복사하는 것이 아니라 새 합성 문장의 길이·존대·간접성·오타 수준을 설계하는 데 한정한다.

### KREW korean-role-playing

- `youtube-couple-data`는 실제 YouTube 자막에서 만들었다고 설명하지만 영상별 source URL과 권리 근거가 없다. top-level Apache 2.0 표시만으로 제3자 영상 발화의 권리까지 확인됐다고 간주하지 않는다.
- `gf-persona-data`는 연인 관계와 성별 가정을 포함해 Mission과 맞지 않는다.
- `exa-data`는 특정 character 스타일 중심으로 Mission의 업무·개인 공유 안전성과 무관하다.
- `general-roleplay-data`는 후보 중 가장 유용하지만 upstream SmolTalk의 정확한 revision과 row mapping이 데이터 카드에 고정되어 있지 않다.
- 따라서 현재 release material에는 포함하지 않는다. 이후 provenance가 보강되더라도 `general-roleplay-data`만 별도 검토한다.

## 대회 계약과 경계

공식 규칙은 앱이 실행할 합성 input만 받고 oracle, expected state, anchor와 점수를 받지 않도록 요구한다. 따라서 다음 경계를 강제한다.

- 앱 input: 사실, 사용자 발화, 현재 context, 후보 room ID, 사용자·harness action만 포함
- test oracle: test source set에만 두고 APK 및 Probe export에서 제외
- runtime: 외부 dataset·LLM·backend 호출 없음
- side effect: 실제 메시지·게시·업로드 없음
- secret: `SCPC_SYNTHETIC_ONLY_*` 표식만 사용
- URL: `.invalid` 예약 domain만 사용

## 다음 release gate

외부 원본 사용 범위를 넓히기 전에는 다음 조건을 모두 충족해야 한다.

1. dataset revision과 모든 사용 shard의 SHA-256 고정
2. 실제 schema allowlist 검사
3. source별 license 원문 보관과 NOTICE 갱신
4. row selection log와 transformation prompt·model·seed 고정
5. source text와 8어절 또는 100자 이상의 연속 중복 0건
6. 실제 개인정보·실제 secret·운영 domain 0건
7. demographic-only metamorphic pair의 SafeShare 결정 변화 0건

## authoritative links

- NVIDIA dataset: <https://huggingface.co/datasets/nvidia/Nemotron-Personas-Korea>
- BSC dataset: <https://huggingface.co/datasets/BSC-LT/m-personas>
- KREW dataset: <https://huggingface.co/datasets/huggingface-KREW/korean-role-playing>
- CC BY 4.0 legal code: <https://creativecommons.org/licenses/by/4.0/legalcode>
- Apache License 2.0: <https://www.apache.org/licenses/LICENSE-2.0.txt>
- 공공누리 유형 안내: <https://www.kogl.or.kr/info/license.do>
