# SafeShare third-party dependency notice

이 문서는 `SOURCE.zip`에 포함되는 build/runtime/test 도구의 출처와 용도를 설명한다. 실제 배포 조건은 각 프로젝트의 원문 license가 우선한다.

| 구성요소 | 고정 버전 | 용도 | 표시 license / 원문 |
|---|---:|---|---|
| SCPC Probe starter AAR | `3.0.0-draft` | 대회 제공 protected Probe 연결부 | 대회 Kit와 참가 규칙에 따르는 공식 제공물 |
| Android Gradle Plugin | `8.7.3` | Android build | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| Kotlin Gradle plugin/runtime | `2.0.21` | 앱 구현/build | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| Gradle Wrapper | `8.9` | 재현 build bootstrap | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| JUnit | `4.13.2` | host unit test 전용 | [EPL-1.0](https://www.eclipse.org/legal/epl-v10.html) |
| JSON-java (`org.json`) | `20240303` | host unit test 전용 | [JSON License](https://www.json.org/license.html) |
| jsonschema | `4.26.0` | 합성 JSON Schema 검증 | [MIT](https://opensource.org/license/mit) |
| ReportLab | `4.4.9` | 완전합성 PDF fixture 생성 | BSD 계열(project distribution의 license metadata·text 참조) |
| pypdf | `6.10.0` | 합성 PDF fixture 검증 | BSD-3-Clause(project distribution의 license metadata·text 참조) |

`SOURCE.zip`의 binary 예외는 재현 build bootstrap인 Gradle Wrapper JAR 한 개뿐이다. 공식 Runner APK와 starter AAR는 Dacon Kit 제공물이며 참가자가 제출하는 binary가 아니므로 archive에 중복하지 않는다. Build 때 공식 Kit의 AAR를 `app/libs`에 staging한다. Maven/Gradle cache, 다른 JAR/AAR, APK, keystore, credential은 포함하지 않는다.

공개 데이터셋은 runtime dependency가 아니다. 참고 범위, pinned revision, license, 원문 비포함 판단은 같은 source archive의 다음 파일에 기록한다.

- `관련자료/연구/데이터/NOTICE_DATASETS.md`
- `관련자료/연구/데이터/SOURCE_PROVENANCE.lock.json`
- `관련자료/연구/데이터/DATASET_DECISION.md`
- `관련자료/연구/데이터/SAFESHARE_SYNTHETIC_DATA_CARD.md`

앱에는 외부 model/backend가 없고 Judge 계정, API key 또는 유료 구독을 요구하지 않는다.

## AI coding tool

개발에 사용한 AI coding tool은 OpenAI Codex와 Anthropic Claude 두 가지뿐이다. 그 외의 AI 도구나 코드 생성 서비스는 사용하지 않았다.

| 도구 | 제공자 | 사용 범위 | runtime 포함 |
|---|---|---|---|
| Codex | OpenAI | 구현·코드 감사·테스트·문서 초안 | 아니오 |
| Claude | Anthropic | 구현·코드 감사·테스트·문서 초안·릴리스 검증 자동화 | 아니오 |

두 도구는 개발 시점에만 사용했고 제출 APK는 runtime에 어떤 model이나 backend도 호출하지 않는다. 도구가 만든 결과는 local build, host unit test, 공식 public Runner, Android emulator 실행으로 다시 확인한 뒤 참가자 소유 코드로 통합했다. 상세 고지는 `관련자료/대회_제출_문서/제출_준비/BUILD_AND_SUBMISSION_INFO.md` 4.1절에 있다.
