# SafeShare release signing

The participant signing private key is intentionally stored outside the repository and must not be added to `SOURCE.zip`, the APK assets, submission documents, or any evidence export.

Active local signing identity:

- Keystore: `%USERPROFILE%\.scpc-signing\candidate_031-safeshare-release-v2.p12`
- Alias: `safeshare_release_v2`
- Password envelope: `%USERPROFILE%\.scpc-signing\candidate_031-safeshare-release-v2.password.dpapi`
- Password protection: Windows DPAPI, decryptable only by the Windows user that created it

The non-v2 files in the same directory are an aborted first-generation attempt and must never be used for signing.

Build and sign:

```powershell
.\app-code\tools\scpc-gradle.cmd 17 .\app-code\candidate_app clean testDebugUnitTest assembleRelease verifyReleaseCorpusBoundary --no-daemon --console=plain

.\app-code\tools\release\sign_release.ps1 `
  -UnsignedApk .\app-code\candidate_app\app\build\outputs\apk\release\app-release-unsigned.apk `
  -OutputApk .\관련자료\대회_제출_문서\제출후보\APP.apk
```

`sign_release.ps1` decrypts the password only in the current process, passes it to `apksigner` through an environment variable, clears the variable, and verifies the resulting APK certificate. Never print or copy the raw password into a document or command history.

Back up the active v2 keystore and its DPAPI envelope together before submission. A DPAPI envelope moved to another Windows account cannot be decrypted; if cross-machine recovery is required, create an offline recovery copy through a deliberate credential handoff rather than committing a password to the repository.
