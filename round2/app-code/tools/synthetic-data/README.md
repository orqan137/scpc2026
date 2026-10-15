# SafeShare synthetic-data tools

이 폴더는 외부 원본을 APK에 넣지 않고 SafeShare의 E1–E4를 재현할 input-only 합성 시나리오, 실제 synthetic PDF fixture와 test-only oracle을 만드는 준비 도구다. Network를 호출하지 않는다. JSON 생성 로직은 표준 라이브러리 기반이고 PDF 생성·Schema/PDF 심층 검증 의존성은 `requirements.txt`에 고정한다.

## 중요한 경계

- `generated/app-input/`: 앱에 제공할 수 있는 상황 input
- `generated/test-oracle/`: host unit test에서만 사용하는 정답 관계
- `generated/generalization/`: APK에 절대 포함하지 않는 24개 independent holdout input/oracle과 test manifest
- `generated/app-input/synthetic/`: `asset_ref=synthetic/...`와 APK asset 경로가 일치하는 완전합성 1-page PDF 3개
- `generated/manifest.json`: generator·schema hash와 파일 목록
- 외부 dataset record: 다운로드하지 않음
- Android 앱 source: 이 도구가 수정하지 않음
- runtime model/backend: 없음

Oracle directory를 앱 asset으로 복사하거나 APK에 포함하면 안 된다. Probe input과 Probe result에도 oracle·expected state·anchor·score·PASS/FAIL을 넣지 않는다.

## 파일

```text
synthetic-data/
├─ README.md
├─ generate.py
├─ validate.py
├─ requirements.txt
├─ schemas/
│  ├─ app-input.schema.json
│  └─ test-oracle.schema.json
└─ generated/
   ├─ app-input/
   │  └─ synthetic/
   ├─ test-oracle/
   ├─ generalization/
   │  ├─ holdout-input/
   │  ├─ holdout-oracle/
   │  └─ manifest.json
   └─ manifest.json
```

외부 source 사용 결정, pinned revision과 NOTICE는 다음 문서에 있다.

- `관련자료/연구/데이터/DATASET_DECISION.md`
- `관련자료/연구/데이터/SAFESHARE_SYNTHETIC_DATA_CARD.md`
- `관련자료/연구/데이터/SOURCE_PROVENANCE.lock.json`
- `관련자료/연구/데이터/NOTICE_DATASETS.md`

## 생성

workspace root에서 실행한다.

```powershell
& '.\.venv-scpc\Scripts\python.exe' -m pip install -r `
  .\app-code\tools\synthetic-data\requirements.txt
& '.\.venv-scpc\Scripts\python.exe' .\app-code\tools\synthetic-data\generate.py
```

기본 pack은 서로 다른 7개 공유 목적에 대한 pair 7개, 14개 시나리오와 중립 PDF revision fixture 3개다. 예산안·공공행사 안전계획·촬영 일정·디자인 납품 링크·학교 자원봉사·개인 모임 안내·가족 여행 계획을 사용해 한 업무 문구를 반복 재생하는 corpus가 되지 않게 한다.

- room opaque ID causal pair
- current role causal pair
- allowed scope causal pair
- link access causal pair
- Korean wording invariant pair
- persona-style fairness invariant pair

모든 시나리오는 E1, E2, E3, E4 episode를 갖는다. TEXT·LINK·PDF는 모두 가상 자료이며 LINK는 `.invalid`, secret marker는 `SCPC_SYNTHETIC_ONLY_*`만 사용한다.

생성 시 함께 만드는 generalization pack은 release 14개와 다른 purpose·room ID·문구 템플릿으로 만든 12개 causal/invariant pair(24 scenario)다. 이는 score나 release corpus를 늘리는 자료가 아니라, production parser/executor의 공개 corpus 과적합을 발견하기 위한 host-unit-test 전용 holdout이다. Gradle main asset source set에는 `generated/app-input`만 연결된다.

다른 output 위치를 쓰려면 다음처럼 지정한다.

```powershell
& '.\.venv-scpc\Scripts\python.exe' .\app-code\tools\synthetic-data\generate.py `
  --out C:\tmp\safeshare-synthetic-check
```

Generator는 알지 못하는 stale JSON을 자동 삭제하지 않는다. 예상하지 않은 파일이 output에 있으면 실패하고 사람이 정확한 대상을 확인하게 한다.

## 검증

전체 pack, input–oracle SHA 결박, pair diff와 directory separation을 한 번에 확인한다.

```powershell
& '.\.venv-scpc\Scripts\python.exe' .\app-code\tools\synthetic-data\validate.py `
  .\app-code\tools\synthetic-data\generated\app-input `
  .\app-code\tools\synthetic-data\generated\test-oracle `
  --separation-root .\app-code\tools\synthetic-data\generated `
  --self-test
```

Oracle만 따로 검사할 때는 matching input directory를 명시한다.

```powershell
& '.\.venv-scpc\Scripts\python.exe' .\app-code\tools\synthetic-data\validate.py `
  .\app-code\tools\synthetic-data\generated\test-oracle `
  --kind oracle `
  --input-dir .\app-code\tools\synthetic-data\generated\app-input
```

Holdout pack은 release input과 분리해 다음처럼 검증한다.

```powershell
& '.\.venv-scpc\Scripts\python.exe' .\app-code\tools\synthetic-data\validate.py `
  .\app-code\tools\synthetic-data\generated\generalization\holdout-input `
  .\app-code\tools\synthetic-data\generated\generalization\holdout-oracle `
  --asset-root .\app-code\tools\synthetic-data\generated\app-input
```

Validator는 두 Schema를 먼저 strict JSON으로 parsing한다. `jsonschema`가 설치되어 있으면 Draft 2020-12 meta-schema 검사와 모든 input/oracle 적용까지 수행하고, `pypdf`가 설치되어 있으면 PDF page·추출문·합성 표식도 검사한다.

```powershell
& '.\.venv-scpc\Scripts\python.exe' -m json.tool `
  .\app-code\tools\synthetic-data\schemas\app-input.schema.json *> $null
& '.\.venv-scpc\Scripts\python.exe' -m json.tool `
  .\app-code\tools\synthetic-data\schemas\test-oracle.schema.json *> $null
```

## Validator가 추가로 막는 것

JSON Schema 외에 다음을 직접 검사한다.

- UTF-8 BOM, missing final LF, duplicate JSON key
- Unicode NFC
- input의 재귀적 금지 key
- 실제 domain과 `.invalid`가 아닌 HTTP(S) URL
- 주민등록번호·전화번호·email·카드번호와 유사한 문자열
- `SCPC_SYNTHETIC_ONLY_*`가 아닌 API key·token·password 형식
- 중복 scenario ID, room ID, candidate ID와 action
- 목적·room ID·display name 참조 불일치
- E1–E4 순서와 E4의 no-new-intake 계약
- app-input/oracle directory artifact 혼입 및 symlink
- canonical input SHA-256과 oracle binding
- manifest의 generator·validator·schema·provenance·oracle file SHA-256
- pair relation의 역방향 누락
- 선언한 counterfactual JSON pointer 밖의 input 차이

App input에서 금지하는 key는 대소문자와 `-`/`_` 차이를 정규화해서 검사한다.

```text
expected, expected_action, expected_state, oracle, gold, gold_answer,
answer, pass, fail, score, assertion, assertions, target_output,
prediction, anchor
```

## Android 통합 시 규칙

현재 Gradle main asset source set은 `generated/app-input`을 직접 가리킨다. 따라서 14개 `*.input.json`과 그 아래 `synthetic/*.pdf` 3개가 별도 수동 복사 없이 동일한 상대 경로로 APK asset에 패키징된다. Oracle은 이 source 경계 밖의 `generated/test-oracle`에 계속 분리한다. `generated/generalization`은 test resource에만 연결하며 main asset source에는 연결하지 않는다.

1. Main asset source: `generated/app-input` 전체(14 input JSON + `synthetic/*.pdf` 3개).
2. `asset_ref=synthetic/<name>.pdf`는 main asset root 아래 실제 PDF 상대 경로와 정확히 일치해야 한다.
3. `*.oracle.json`은 host/test resource에서만 읽고 main source set 또는 release APK에 포함하지 않는다.
4. Release boundary 검증은 input 14개와 PDF 3개의 존재, manifest SHA 결박, oracle filename·금지 key 0건을 검사한다.
5. Scenario loader는 production `SafeShareCore`에 input을 전달할 뿐 oracle이나 별도 정답 logic을 호출하지 않는다.
6. Test는 같은 input을 production core에 실행한 observed state를 test-only oracle과 비교한다.
7. `ScenarioGeneralizationHoldoutTest`는 독립 24 scenario/12 pair가 production core에서 통과하는지 확인한다. release boundary task는 APK asset이 14 input과 3 PDF만 포함하는지 다시 확인한다.

## 480개 full pack 확장

현재 14개 seed는 contract 검증용이다. full pack은 data card의 `48 persona anchors × 4 causal axes × 2 sides + 48 × 2 invariant variants = 480` 설계를 따른다. 확장할 때도 다음을 지킨다.

- demographic과 말투를 독립 배정
- pair마다 선언된 pointer만 변경
- source verbatim overlap 검사
- demo 전수 및 regression 표본 사람 검토
- generator version, source shard hash, selection seed, prompt/model revision과 output hash를 manifest에 추가

외부 dataset을 실제 generation input으로 사용하기 전에는 `SOURCE_PROVENANCE.lock.json`의 `downloaded_files`와 license gate를 먼저 갱신해야 한다.
