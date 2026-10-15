# SafeShare Synthetic Scenario Pack — Data Card

## 기본 정보

- 데이터셋 이름: `SafeShare Synthetic Scenario Pack`
- 내부 버전: `1.1.0-prep`
- 언어: `ko-KR`
- 상태: 대회 준비용 deterministic seed pack
- 개인정보: 없음
- 실제 회사·학교·계정·대화방·secret: 없음
- 외부 원본 record 포함 여부: 없음
- 앱 runtime model/backend: 없음

## 목적

이 pack은 한 Android 기기에서 업무·개인 대화방을 오가며 자료를 공유할 때 SafeShare가 다음 인과 흐름을 재현하는지 확인한다.

1. E1에서 목적, opaque room ID, 역할, 허용 범위와 일회성 payload 상태를 확인한다.
2. E2에서 유효한 confirmed fact만 재사용하고 개인방·다른 프로젝트·미확인 방을 제외한다.
3. E3에서 room, role, scope 또는 link access가 바뀌면 관련 draft를 보류하고 checkpoint를 만든다.
4. E4에서 새 process가 전체 초기화 없이 영향받은 작업만 복구한다.

이 데이터는 model 학습용 corpus가 아니라 앱 상태기계의 재현·회귀·수동 시연을 위한 synthetic input이다.

## 데이터 구성

### 앱 input

앱 input에는 다음만 있다.

- synthetic persona의 표현 스타일 범주
- 가상 목적과 opaque room ID
- TEXT, LINK, PDF intake descriptor
- 사용자가 제공한 current role, allowed scope와 link access
- 후보 room ID
- 실행할 사용자·harness action 순서

다음은 포함하지 않는다.

- expected action 또는 expected state
- oracle, gold answer, score, PASS/FAIL
- 평가 anchor 또는 hidden criterion
- 실제 개인정보, 실제 URL, 실제 API key
- source dataset의 UUID·이름·원문 persona·원문 대화

### test oracle

test oracle은 앱 input과 별도 directory에 저장한다. `scenario_id`와 canonical input SHA-256으로만 연결하며 다음을 포함할 수 있다.

- 특정 action 이후 관찰 path와 연산자
- 비교할 값 또는 이전 capture
- 반사실 pair의 `SAME`/`DIFFERENT` 관계
- 변경을 허용한 input JSON pointer

Oracle은 APK, production asset, Probe input과 Probe result에 포함하지 않는다.

## v1 층화 설계

목표 corpus는 다음 48개 persona anchor를 기본으로 한다.

| axis | buckets |
|---|---|
| age band | `19_29`, `30_44`, `45_59`, `60_PLUS` |
| region band | `CAPITAL`, `METRO`, `NON_METRO` |
| work context | `OFFICE_PROFESSIONAL`, `PUBLIC_SERVICE`, `FIELD_PRODUCTION`, `INDEPENDENT` |

`4 × 3 × 4 = 48` anchor다. 표현 축은 demographic과 독립적으로 순환 배정한다.

| expression axis | buckets |
|---|---|
| register | `FORMAL_HAPSYO`, `POLITE_HAEYO`, `CASUAL_HAE` |
| directness | `DIRECT`, `INDIRECT` |
| noise | `CLEAN`, `LIGHT` |

말투와 digital fluency를 나이·성별·지역에서 추론하지 않는다. `persona_style`은 사용자 발화 표현을 바꾸는 데만 쓰며 SafeShare 정책 입력에는 사용하지 않는다.

## 반사실 및 metamorphic 설계

목표 v1 규모는 480 input이다.

- causal pair: `48 anchors × 4 axes × 2 sides = 384`
- wording/fairness invariant pair: `48 anchors × 2 paraphrases = 96`
- 총 480 input

Causal pair는 다음 네 축 가운데 하나만 바꾼다.

1. 같은 display name이지만 다른 opaque room ID
2. current role
3. allowed scope
4. link access (`PROJECT_ONLY`, `UNKNOWN`, `EXPIRED` 등)

각 pair의 normalized JSON diff는 선언된 pointer만 허용한다. 발화의 존대·간접성·띄어쓰기·가벼운 오타만 다른 invariant pair는 SafeShare의 대상·결정·draft 결과가 같아야 한다.

현재 generator의 기본 seed pack은 대회 준비를 빠르게 검증하기 위한 14개 scenario다. 14개 모두 E1–E4 episode를 갖고, room·role·scope·link causal pair와 wording/fairness pair를 포함한다. 480개 full pack은 동일한 schema와 validator를 사용해 확장한다.

## payload 분포

- `TEXT`: 일반 업무문과 `SCPC_SYNTHETIC_ONLY_*` secret marker
- `LINK`: `https://*.invalid/...`만 사용
- `PDF`: 실제 외부 PDF가 아닌, generator가 offline으로 만든 1-page synthetic PDF 3개와 그 `asset_ref` 사용

PDF fixture `v1`, `v2`, `v3`에는 가상 프로젝트 ID, `.invalid` 링크, 합성 표식과 revision별 변경사항만 있다. 모든 페이지에 `NOT A REAL DOCUMENT`를 표시하며 실제 인물·조직·계정·서비스를 나타내지 않는다는 문구를 포함한다. Manifest가 각 PDF의 byte 수, page 수와 SHA-256을 input reference에 결박한다.

`.invalid` 이외 HTTP(S) domain은 validator가 거부한다. 주민등록번호, 전화번호, email, 카드번호처럼 실제 개인정보로 오인될 수 있는 형식도 거부한다.

## 생성 과정

현재 준비 pack은 외부 원본 없이 repository 내부 synthetic template에서 결정론적으로 생성한다.

1. 고정된 synthetic persona style bucket을 선택한다.
2. 가상 프로젝트·room·payload template를 결합한다.
3. 한 causal axis 또는 표현 axis만 바꿔 pair를 만든다.
4. canonical JSON SHA-256을 계산한다.
5. PDF intake가 참조하는 deterministic synthetic PDF 3개를 ReportLab invariant mode로 생성한다.
6. 앱 input과 test oracle을 서로 다른 directory에 기록한다.
7. validator가 Draft 2020-12 schema, 금지 key, privacy, domain, PDF 구조, hash와 pair separation을 검사한다.

향후 외부 dataset을 실제 generation input으로 사용하는 경우 `SOURCE_PROVENANCE.lock.json`에 shard hash, row selection, prompt hash, model revision, decoding parameter와 output hash를 추가하기 전에는 release pack으로 승격하지 않는다.

## QC gate

| gate | 기준 |
|---|---|
| JSON 구조 | schema·custom validator 100% 통과 |
| 금지 key | app input에서 0건 |
| 실제 PII·secret·domain | 0건 |
| app/oracle separation | 잘못된 directory artifact 0건 |
| scenario ID | directory 내 중복 0건 |
| input-oracle binding | canonical SHA-256 불일치 0건 |
| causal pair | 허용 pointer 밖 diff 0건 |
| PDF fixture | 참조 누락·미참조 파일·symlink·header/EOF/page/hash 오류 0건 |
| schema 자체 | 두 schema strict JSON parse 및 Draft 2020-12 meta-schema 검사 통과 |
| wording/fairness pair | 정책 관찰값 변화 0건 |
| E2 | E1보다 확인·수동 입력 감소 |
| E3 | `HELD`, checkpoint 존재, external dispatch 0 |
| E4 | PID 변경, process epoch 증가, 관련 draft만 revision |
| 사람 검토 | 앱 demo 14개 전수, regression 최소 10% 독립 재검토 |

## 알려진 한계

- 현재 14개 seed pack은 population 대표성을 주장하지 않는다.
- 공개 dataset의 문장을 실제로 추출하지 않았기 때문에 현재 발화 자연스러움은 template 품질에 의존한다.
- demographic strata는 coverage 도구이지 한국 인구에 대한 사실 예측이나 성격 추론 도구가 아니다.
- test oracle은 independent policy specification으로 검토해야 하며 production core의 현재 출력을 복사해 만들면 안 된다.
- local synthetic data 통과는 official hidden input 통과를 보장하지 않는다.

## 배포 및 라이선스

현재 generated input은 외부 record나 source text를 포함하지 않는 자체 synthetic material이다. NVIDIA와 BSC는 설계 참고자료로 attribution한다. KREW는 material source가 아니라 연구 참고자료로만 기록한다. 상세 결정과 고정 revision은 `DATASET_DECISION.md`, `SOURCE_PROVENANCE.lock.json`, `NOTICE_DATASETS.md`를 따른다.
