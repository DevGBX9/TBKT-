# Runs the deterministic TBKT Plus selftests on Fabric via the Gradle Loom run.
# Expects the log marker: TBKT_SELFTEST_RESULT=PASS
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "=== TBKT Plus selftest: Fabric (Minecraft 26.3) ===" -ForegroundColor Cyan
$log = Join-Path $root "build/selftest-fabric.log"
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null

& .\gradlew.bat ":fabric:runSelftest" --console=plain *>&1 | Tee-Object -FilePath $log
$exit = $LASTEXITCODE

$content = Get-Content $log -Raw
if ($content -notmatch 'TBKT_SELFTEST_RESULT=PASS') {
    Write-Host "FAIL: Fabric selftest missing TBKT_SELFTEST_RESULT=PASS (exit=$exit)" -ForegroundColor Red
    if ($content -match 'TBKT_SELFTEST_RESULT=FAIL[^\r\n]*') {
        Write-Host $Matches[0] -ForegroundColor Red
    }
    exit 1
}

if ($exit -ne 0) {
    Write-Host "FAIL: Fabric selftest exited with code $exit" -ForegroundColor Red
    exit 1
}

Write-Host "PASS: Fabric" -ForegroundColor Green
Write-Host "All TBKT Plus selftests passed." -ForegroundColor Green
