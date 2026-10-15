[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$UnsignedApk,

    [Parameter(Mandatory = $true)]
    [string]$OutputApk,

    [string]$KeystorePath = (Join-Path $env:USERPROFILE ".scpc-signing\candidate_031-safeshare-release-v2.p12"),
    [string]$CredentialPath = (Join-Path $env:USERPROFILE ".scpc-signing\candidate_031-safeshare-release-v2.password.dpapi"),
    [string]$KeyAlias = "safeshare_release_v2",
    [string]$ApkSignerPath = ""
)

$ErrorActionPreference = "Stop"

function Resolve-ApkSigner([string]$RequestedPath) {
    if ($RequestedPath) {
        return (Resolve-Path -LiteralPath $RequestedPath).Path
    }

    $sdkCandidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique

    foreach ($sdkRoot in $sdkCandidates) {
        $candidate = Get-ChildItem -LiteralPath (Join-Path $sdkRoot "build-tools") `
            -Filter "apksigner.bat" -Recurse -File -ErrorAction SilentlyContinue |
            Sort-Object { [version]$_.Directory.Name } -Descending |
            Select-Object -First 1
        if ($candidate) {
            return $candidate.FullName
        }
    }
    throw "apksigner.bat was not found. Install Android SDK build-tools or pass -ApkSignerPath."
}

$resolvedUnsigned = (Resolve-Path -LiteralPath $UnsignedApk).Path
$resolvedKeystore = (Resolve-Path -LiteralPath $KeystorePath).Path
$resolvedCredential = (Resolve-Path -LiteralPath $CredentialPath).Path
$resolvedSigner = Resolve-ApkSigner $ApkSignerPath
$resolvedOutput = [IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputApk))
$outputDirectory = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

$encryptedPassword = Get-Content -LiteralPath $resolvedCredential -Raw -Encoding UTF8
$securePassword = ConvertTo-SecureString $encryptedPassword
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:SCPC_RELEASE_STOREPASS = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    & $resolvedSigner sign `
        --ks $resolvedKeystore `
        --ks-key-alias $KeyAlias `
        --ks-pass env:SCPC_RELEASE_STOREPASS `
        --key-pass env:SCPC_RELEASE_STOREPASS `
        --v1-signing-enabled false `
        --v2-signing-enabled true `
        --v3-signing-enabled false `
        --v4-signing-enabled false `
        --out $resolvedOutput `
        $resolvedUnsigned
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner sign failed with exit code $LASTEXITCODE"
    }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    Remove-Item Env:SCPC_RELEASE_STOREPASS -ErrorAction SilentlyContinue
}

& $resolvedSigner verify --verbose --print-certs $resolvedOutput
if ($LASTEXITCODE -ne 0) {
    throw "apksigner verification failed with exit code $LASTEXITCODE"
}

$apkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedOutput).Hash
Write-Output "SIGNED_APK=$resolvedOutput"
Write-Output "SIGNED_APK_SHA256=$apkHash"
