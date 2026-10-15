# Third-Party Dataset Notice

이 문서는 SafeShare synthetic scenario 설계에 참고한 공개 데이터와 변경·비포함 범위를 기록한다. 현재 repository generator와 generated pack에는 외부 dataset의 원본 record, source text 또는 verbatim 대화를 포함하지 않는다.

## NVIDIA Nemotron-Personas-Korea

- Title: `Nemotron-Personas-Korea: Synthetic Personas Aligned to Real-World Distributions for Korea`
- Creator: NVIDIA Corporation 및 dataset card에 기재된 contributors
- Source: <https://huggingface.co/datasets/nvidia/Nemotron-Personas-Korea>
- Pinned revision: `ada0f5b53a38bb5a30cce09358adde883c1ab63a`
- License: [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/legalcode)
- 현재 사용: 한국 성인 persona coverage를 age band, region band, work context로 설계할 때 참고
- 변경 표시: 정확한 demographic·identifier를 사용하지 않고 coarse strata로 재설계했으며, source persona text를 복제하지 않고 완전히 새로운 가상 SafeShare 상황을 작성함
- 비포함: UUID, 이름, 정확한 나이, 시군구, 원문 persona, culinary/food-derived persona

권리자나 contributor가 SafeShare 또는 본 대회 제출을 보증한다는 의미가 아니다.

## BSC-LT mPersonas

- Title: `mPersonas: Multilingual Persona-Driven Conversational Dataset`
- Creators: Severino Da Dalt, Iñigo Pikabea, Jaume Prats, Marc Pamies
- Source: <https://huggingface.co/datasets/BSC-LT/m-personas>
- Pinned revision: `6fab205a86a9244c90a2782e28055027f45ddd03`
- License displayed by pinned card: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)
- 현재 사용: 한국어 발화 변형의 register, directness, turn 구조를 설계할 때 참고
- 변경 표시: source sentence를 복제하지 않고 repository-owned synthetic 문장을 사용함
- 비포함: FineWeb2 `source_text`, source ID, verbatim persona·prompt·messages

## Hugging Face KREW korean-role-playing

- Source: <https://huggingface.co/datasets/huggingface-KREW/korean-role-playing>
- Pinned revision: `ceb55e96eddaee72dac60be5aa13e458125a1dc5`
- License displayed by pinned card: Apache License 2.0
- 현재 상태: 연구·수동 QA 참고문헌
- release material 포함 여부: 포함하지 않음

특히 실제 YouTube 자막 subset은 개별 영상별 권리 provenance가 확인되지 않았으므로 사용하지 않는다. KREW material을 향후 포함하려면 subset별 source mapping과 권리를 다시 검토하고 이 NOTICE를 갱신한다.

## 공공누리 관련 provenance note

NVIDIA dataset card는 KREI의 `2024 식품소비행태조사 결과발표대회 논문집`을 공공누리 제4유형 source로 적고 있다. 공공누리 제4유형은 출처표시·상업적 이용금지·변경금지 조건이다.

- 공공누리 공식 유형 안내: <https://www.kogl.or.kr/info/license.do>

현재 SafeShare pack은 해당 자료나 음식 관련 persona를 포함하지 않는다. NVIDIA의 최종 CC BY 4.0 표시와 upstream source 조건의 관계에 관해 별도 권리 결론을 내리지 않는다.

## 앱 및 제출 문서 표기

현재 앱 runtime에는 외부 dataset 또는 model이 없으므로 `modelConfigured=false`를 유지한다. 제출용 `BUILD_AND_SUBMISSION_INFO.md`에는 다음을 명시한다.

- 공개 데이터는 오프라인 synthetic scenario 설계 참고에만 사용
- APK가 외부 dataset·model·backend로 데이터를 보내지 않음
- 실제 개인정보·계정·운영 secret이 없음
- 앱 input과 test oracle이 물리적으로 분리됨
