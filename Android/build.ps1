param(
    [string] $AndroidSdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }),
    [string] $BuildTools = '36.1.0',
    [int] $CompileSdk = 36,
    [string] $OutputDirectory,
    [string] $Keystore,
    [string] $KeyAlias = 'westeg'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$version = ([xml](Get-Content -Raw -Encoding UTF8 (Join-Path $root 'WeChatSteganography.csproj'))).Project.PropertyGroup.Version
$manifest = Join-Path $PSScriptRoot 'AndroidManifest.xml'
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$minSdk = ([xml](Get-Content -Raw -Encoding UTF8 $manifest)).manifest.'uses-sdk'.GetAttribute('minSdkVersion', $androidNamespace)
$tools = Join-Path $AndroidSdk "build-tools/$BuildTools"
$platform = Join-Path $AndroidSdk "platforms/android-$CompileSdk/android.jar"
$buildRoot = Join-Path $root '.build'
$stage = Join-Path $buildRoot ('android-package-' + [guid]::NewGuid().ToString('N'))
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $root "bin/Release/$version/android" }
$apk = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) "WeSteg-$version.apk"
$previousPassword = $env:WESTEG_ANDROID_KEY_PASSWORD

function Invoke-BuildTool([string] $Program, [string[]] $Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build command failed: $Program (exit $LASTEXITCODE)" }
}

if (Test-Path -LiteralPath $apk) { throw "Output already exists: $apk" }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
try {
    if (-not $Keystore) {
        if ($KeyAlias -ne 'westeg') { throw 'A custom key alias requires -Keystore.' }
        $keyDirectory = Join-Path $root '.signing'
        $Keystore = Join-Path $keyDirectory 'westeg-release.p12'
        $passwordFile = Join-Path $keyDirectory 'westeg-password.dpapi'
        if ((Test-Path -LiteralPath $Keystore) -ne (Test-Path -LiteralPath $passwordFile)) {
            throw 'The local signing key or its protected password is missing. Restore it before signing an update.'
        }

        if (-not (Test-Path -LiteralPath $Keystore)) {
            New-Item -ItemType Directory -Force -Path $keyDirectory | Out-Null
            $bytes = [byte[]]::new(32)
            $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
            $env:WESTEG_ANDROID_KEY_PASSWORD = [Convert]::ToBase64String($bytes)
            $pendingKey = Join-Path $stage 'release.p12'
            $pendingPassword = Join-Path $stage 'password.dpapi'
            Invoke-BuildTool 'keytool' @('-genkeypair', '-keystore', $pendingKey, '-storetype', 'PKCS12',
                '-storepass:env', 'WESTEG_ANDROID_KEY_PASSWORD', '-alias', $KeyAlias, '-keyalg', 'RSA', '-keysize', '3072',
                '-validity', '10000', '-dname', 'CN=WeSteg Android Release')
            ConvertTo-SecureString $env:WESTEG_ANDROID_KEY_PASSWORD -AsPlainText -Force |
                ConvertFrom-SecureString | Set-Content -LiteralPath $pendingPassword -Encoding ASCII
            Move-Item -LiteralPath $pendingKey -Destination $Keystore
            Move-Item -LiteralPath $pendingPassword -Destination $passwordFile
        } else {
            $securePassword = Get-Content -Raw -Encoding ASCII -LiteralPath $passwordFile | ForEach-Object { ConvertTo-SecureString $_.Trim() }
            $env:WESTEG_ANDROID_KEY_PASSWORD = [pscredential]::new('westeg', $securePassword).GetNetworkCredential().Password
        }
    } elseif (-not $env:WESTEG_ANDROID_KEY_PASSWORD) {
        throw 'Set WESTEG_ANDROID_KEY_PASSWORD when using an external keystore.'
    }

    $classes = Join-Path $stage 'classes'
    $dex = Join-Path $stage 'dex'
    $resources = Join-Path $stage 'res'
    $compiledResources = Join-Path $stage 'compiled-resources.zip'
    $jar = Join-Path $stage 'classes.jar'
    $unsigned = Join-Path $stage 'unsigned.apk'
    $aligned = Join-Path $stage 'aligned.apk'
    $signed = Join-Path $stage 'signed.apk'
    New-Item -ItemType Directory -Path $classes, $dex | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'res') -Destination $resources -Recurse
    $iconFolder = Join-Path $resources 'drawable-nodpi'
    New-Item -ItemType Directory -Path $iconFolder | Out-Null
    Copy-Item -LiteralPath (Join-Path $root 'Assets/AppIcon.png') -Destination (Join-Path $iconFolder 'ic_launcher.png')

    $sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
    Invoke-BuildTool 'javac' (@('--release', '8', '-encoding', 'UTF-8', '-classpath', $platform, '-d', $classes) + $sources)
    Invoke-BuildTool 'jar' @('--create', '--file', $jar, '-C', $classes, '.')
    Invoke-BuildTool (Join-Path $tools 'd8.bat') @('--release', '--min-api', $minSdk, '--lib', $platform, '--output', $dex, $jar)
    Invoke-BuildTool (Join-Path $tools 'aapt2.exe') @('compile', '--dir', $resources, '-o', $compiledResources)
    Invoke-BuildTool (Join-Path $tools 'aapt2.exe') @('link', '--manifest', $manifest, '--version-name', $version, '-I', $platform,
        '-o', $unsigned, $compiledResources)
    Invoke-BuildTool 'jar' @('--update', '--file', $unsigned, '--no-manifest', '-C', $dex, 'classes.dex')
    Invoke-BuildTool (Join-Path $tools 'zipalign.exe') @('-f', '4', $unsigned, $aligned)
    Invoke-BuildTool (Join-Path $tools 'apksigner.bat') @('sign', '--ks', $Keystore, '--ks-key-alias', $KeyAlias,
        '--ks-pass', 'env:WESTEG_ANDROID_KEY_PASSWORD', '--v4-signing-enabled', 'false', '--out', $signed, $aligned)
    Invoke-BuildTool (Join-Path $tools 'apksigner.bat') @('verify', '--verbose', $signed)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $apk) | Out-Null
    Move-Item -LiteralPath $signed -Destination $apk
    Write-Output $apk
} finally {
    $env:WESTEG_ANDROID_KEY_PASSWORD = $previousPassword
    $resolvedStage = (Resolve-Path -LiteralPath $stage).Path
    $resolvedBuild = (Resolve-Path -LiteralPath $buildRoot).Path.TrimEnd('\') + '\'
    if (-not $resolvedStage.StartsWith($resolvedBuild, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unexpected temporary build path: $resolvedStage"
    }
    Remove-Item -LiteralPath $resolvedStage -Force -Recurse
}
