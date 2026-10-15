# SafeShare

SafeShare는 업무·개인 대화방을 오가며 공유할 자료를 먼저 로컬 초안으로 검토하고, 사용자가 확인한 목적·opaque room ID·역할·허용 범위를 다음 세션에 재사용하되 달라진 조건만 다시 묻는 Android 에이전트다.

- package: `org.scpc.r2.safeshare`
- candidate / mission: `candidate_031 / mission_031`
- Android: min 28, target/compile 35, JVM 17
- 외부 전송·실제 메시지 dispatch: 없음
- Android standard permission: 없음
- runtime LLM/model/backend: 없음 (`modelConfigured=false`, invocation 0)

## 제품 흐름

1. **E1 Learn** — TEXT/LINK/PDF를 검토해 secret을 가리고, 목적·대상·현재 권위·허용 범위를 명시 확인한다. PDF는 최대 8 MiB만 app-private storage에 제한 복사한다.
2. **E2 Reuse** — ACTIVE STABLE fact가 뒷받침하는 확인된 route만 재사용해 app-local draft를 제안한다. 다른 목표·개인방·distractor는 선택하지 않는다.
3. **E3 Exception** — room, role, scope, link access가 달라지면 영향받은 draft만 `HELD`로 동기 저장하고 main process를 실제 종료한다.
4. **E4 Recover** — 새 PID/process epoch에서 checkpoint를 읽고, 바뀐 의존성만 취소·재계획한 뒤 사용자 확인으로 복구한다.

정정·철회·삭제·세션 만료는 직접 의존한 proposal/draft/part와 delayed outcome까지 무효화한다. 삭제 tombstone은 relaunch/replay 뒤 stale 값이 부활하지 못하게 하며, 숫자·불리언·문자열 role ID는 JSON scalar 타입까지 구분한다. OFFLINE·DELAYED·UNKNOWN은 성공을 가장하지 않고 `WAIT`한다.

## 합성 입력

APK에는 repository-owned 합성 input만 포함된다.

- `SS-KO-0001`–`SS-KO-0014`: 14개 scenario, E1–E4 56개 episode
- intake surface: TEXT, LINK, PDF, NONE
- PDF fixture: 완전합성 1-page PDF 3개
- causal/invariant pair: room, role, scope, link, wording, fairness 7쌍
- 서로 다른 목적: 예산안, 공공행사 안전계획, 촬영 일정, 디자인 납품 링크, 학교 자원봉사, 개인 모임 안내, 가족 여행 계획
- 실제 URL은 없고 `.invalid` 예약 domain과 `SCPC_SYNTHETIC_ONLY_*` 표식만 사용

14개 test oracle은 `generated/test-oracle/`에 물리적으로 분리되며 test source set에서만 읽는다. APK·production asset·Probe input/result에는 oracle, expected state, answer, anchor 또는 점수가 들어가지 않는다. NVIDIA/BSC/KREW 공개 데이터는 coverage·한국어 표현 연구 참고로만 기록했고 원본 row나 대화문은 내려받아 배포물에 넣지 않았다.

## 핵심 구현

- `SafeShareCore.kt`: 제품 UI와 Probe가 공유하는 durable reducer, fact/route/draft/action/outcome/tombstone ledger
- `SafeSharePolicy.kt`, `SafeShareIntake.kt`: secret 가림, link 확인, bounded PDF intake
- `SafeShareProbeAdapter.kt`, `SafeShareProbeBridge.kt`: 공식 starter AAR protected callback과 동일 production core 연결
- `PublicProbeActivity.kt`: 접근 가능한 `SCPC_PROBE_IMPORT`, `SCPC_PROBE_RUN`, `SCPC_PROBE_EXPORT` 공개 rehearsal controls
- `SafeShareComparison.kt`: byte-identical snapshot을 격리된 full/claim-off namespace에서 실행하는 3-surface paired observation
- `ScenarioParser.kt`, `ScenarioExecutor.kt`: APK input을 실제 production core에 통과시키는 합성 trace
- `RunEvidenceArchive.kt`: 한 run의 step/receipt evidence를 원자적으로 보존·검사
- `MISSION_ADAPTER.json`: Mission role/operation과 실제 state/callback/evidence 경로 연결표

Signature mechanism은 **확인된 route 재사용 + dependency-scoped recovery**다. comparison은 이 두 switch만 끄며 UI, fixture, event order, virtual clock, network와 model 조건은 유지한다. 출력은 affected/replacement/confirmation 수, persisted state bytes, active ephemeral value 수, tombstone 수와 external dispatch 수 같은 원시 관찰값만 내고 점수나 판정을 만들지 않는다.

## clean build와 검증

SOURCE에서 빌드할 때 공식 Kit의 starter AAR를 먼저 staging한다.

```powershell
$kitRoot = Resolve-Path 'C:\path\to\candidate_release_v3'
$starter = Join-Path $kitRoot 'probe\scpc-probe-starter-3.0.0-draft.aar'
$starterDest = '.\app-code\candidate_app\app\libs\scpc-probe-starter-3.0.0-draft.aar'
New-Item -ItemType Directory -Force -Path (Split-Path $starterDest) | Out-Null
Copy-Item -LiteralPath $starter -Destination $starterDest

py -3 -m venv .venv
& .\.venv\Scripts\python.exe -m pip install -r `
  .\app-code\tools\synthetic-data\requirements.txt
& .\.venv\Scripts\python.exe .\app-code\tools\synthetic-data\generate.py

Push-Location .\app-code\candidate_app
.\gradlew.bat --no-daemon clean testDebugUnitTest assembleDebug `
  assembleRelease verifyReleaseCorpusBoundary
Pop-Location
```

최종 source 기준 20개 test suite의 139개 unit test가 모두 통과했다. 별도의 holdout 검증에서는 24개 scenario, 96개 episode와 312개 action을 확인했으며 external dispatch는 0이었다. `verifyReleaseCorpusBoundary`는 release APK의 input JSON 14개·PDF 3개와 oracle 계열 asset 0개를 확인한다.

산출물:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

두 번째 파일은 컴파일·payload 검사용이며 제출물이 아니다. 참가자 소유 release keystore로 한 번 서명한 `APP.apk`만 제출하고, 그 동일 APK로 public Runner, Variant A/B, evidence finalizer와 strict preflight를 다시 실행해야 한다. debug key를 최종 서명에 사용하지 않는다.

공개 저장소에는 과거 `verification/` 기록과 대회 정본인 `정보/` 디렉터리를 포함하지 않는다. 이 자료는 제출 evidence로 재사용하거나 공개하지 않는다.
