$ErrorActionPreference = 'Stop'
$testRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = [IO.Path]::GetFullPath((Join-Path $testRoot '..\..\..'))
$testOutput = Join-Path $repoRoot 'out\native-audio-tests'
New-Item -ItemType Directory -Path $testOutput -Force | Out-Null

if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    $env:PATH = (Split-Path -Parent $vswhere) + ';' + $env:PATH
    $vsInstall = & $vswhere -latest -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $vsInstall) { throw 'The native policy tests require the Visual Studio C compiler.' }
    & (Join-Path $vsInstall 'Common7\Tools\Launch-VsDevShell.ps1') -Arch amd64 -HostArch amd64 -SkipAutomaticLocation
}

$nativeSource = Join-Path $repoRoot 'app\src\main\jni\moonlight-core'
Push-Location $testOutput
try {
    & cl.exe /nologo /std:c11 /W4 /WX /O2 /I $nativeSource `
        (Join-Path $nativeSource 'audio_buffer_policy.c') `
        (Join-Path $PSScriptRoot 'audio_buffer_policy_test.c') /Fe:audio-buffer-policy-tests.exe
    if ($LASTEXITCODE -ne 0) { throw 'Native audio policy compilation failed.' }
    & (Join-Path $testOutput 'audio-buffer-policy-tests.exe')
    if ($LASTEXITCODE -ne 0) { throw 'Native audio policy tests failed.' }
}
finally { Pop-Location }
