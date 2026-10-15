# SafeShare SOURCE 클린 빌드

이 압축 파일에는 사람이 작성한 앱 소스, 빌드 설정, 라이선스·출처 문서와 검증 도구만 포함합니다. 생성된 앱 입력·테스트 oracle, 빌드·캐시·검증 결과, APK, 키·비밀값과 공식 Runner·starter 바이너리는 포함하지 않습니다.

JDK 17, Python 3.10 이상, Android SDK Platform 35, Build Tools 35.0.0, 최신 command-line tools와 Dacon `candidate_release_v3` 공식 Kit가 필요합니다. Windows PowerShell과 macOS·Linux 셸에서 모두 빌드할 수 있도록 `gradlew`와 `gradlew.bat` wrapper를 함께 넣었습니다. release APK 서명은 Windows DPAPI keystore를 사용하는 참가자 전용 단계이며 SOURCE에는 포함하지 않습니다.

압축 파일을 빈 폴더에 푼 뒤 루트 디렉터리에서 실행합니다. `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`는 위 JDK와 SDK를 가리켜야 합니다.

Windows PowerShell:

```powershell
$kitRoot = Resolve-Path 'C:\path\to\candidate_release_v3'
$starter = Join-Path $kitRoot 'probe\scpc-probe-starter-3.0.0-draft.aar'
$starterDest = '.\app-code\candidate_app\app\libs\scpc-probe-starter-3.0.0-draft.aar'
if (-not (Test-Path -LiteralPath $starter)) { throw 'Official starter AAR is missing' }
New-Item -ItemType Directory -Force -Path (Split-Path $starterDest) | Out-Null
Copy-Item -LiteralPath $starter -Destination $starterDest

py -3 -m venv .venv
& .\.venv\Scripts\python.exe -m pip install -r `
  .\app-code\tools\synthetic-data\requirements.txt
& .\.venv\Scripts\python.exe `
  .\app-code\tools\synthetic-data\generate.py

Push-Location .\app-code\candidate_app
.\gradlew.bat --no-daemon clean testDebugUnitTest `
  assembleRelease verifyReleaseCorpusBoundary
Pop-Location
```

macOS·Linux:

```bash
kit_root=/path/to/candidate_release_v3
starter="$kit_root/probe/scpc-probe-starter-3.0.0-draft.aar"
test -f "$starter" || { echo 'Official starter AAR is missing'; exit 1; }
mkdir -p ./app-code/candidate_app/app/libs
cp "$starter" ./app-code/candidate_app/app/libs/

python3 -m venv .venv
./.venv/bin/python -m pip install -r ./app-code/tools/synthetic-data/requirements.txt
./.venv/bin/python ./app-code/tools/synthetic-data/generate.py

cd ./app-code/candidate_app
chmod +x ./gradlew
./gradlew --no-daemon clean testDebugUnitTest \
  assembleRelease verifyReleaseCorpusBoundary
cd -
```

성공하면 컴파일 검증용 unsigned APK가 다음 위치에 생성됩니다.

```text
app-code/candidate_app/app/build/outputs/apk/release/app-release-unsigned.apk
```

이 파일은 제출용 `APP.apk`가 아닙니다. 참가자의 release key는 SOURCE에 포함하지 않으며 Dacon에도 제출하지 않습니다. 공식 도구가 최종 제출 APP과 SOURCE의 identity를 실제 파일에서 자동으로 계산합니다.

합성 generator는 `app-code/tools/synthetic-data/generated/app-input`에 JSON 14개와 PDF 3개를, `app-code/tools/synthetic-data/generated/test-oracle`에 JSON 14개를 로컬에서 다시 만듭니다. Gradle main asset에는 입력 전용 폴더만 들어가며 `verifyReleaseCorpusBoundary`가 APK의 oracle 누수를 차단합니다.
