$ErrorActionPreference = 'Stop'
$taskAdb = Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\android-build\sdk\platform-tools\adb.exe'
$taskArtifacts = Join-Path $PSScriptRoot 'deliverables'
# Preconditions: phone unlocked, Shizuku running, permission granted to this app.
# This test creates its own display and opens only GateTestActivity. No QQ.
& $taskAdb install -r (Join-Path $taskArtifacts 'qqpet-mvp-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'MVP installation failed' }
& $taskAdb install -r (Join-Path $taskArtifacts 'qqpet-mvp-gates.apk')
if ($LASTEXITCODE -ne 0) { throw 'Gate test installation failed' }
& $taskAdb shell am instrument -w -r -e class com.ynk.virtualdisplay.PetDisplayGateTest com.ynk.virtualdisplay.test/androidx.test.runner.AndroidJUnitRunner |
    Tee-Object -FilePath (Join-Path $taskArtifacts 'gate-instrumentation.log')
# Instrumentation may return shell success even when a JUnit assertion fails.
if (!(Select-String -LiteralPath (Join-Path $taskArtifacts 'gate-instrumentation.log') -SimpleMatch 'OK (1 test)' -Quiet)) {
    throw 'Gate test failed; inspect gate-instrumentation.log. Do not proceed to QQ.'
}
& $taskAdb logcat -d -s 'PetDisplayGates:I' '*:S' |
    Set-Content -LiteralPath (Join-Path $taskArtifacts 'gate-logcat.log') -Encoding utf8
$taskEvidence = Join-Path ([System.IO.Path]::GetTempPath()) 'QQPetMvpEvidence'
New-Item -ItemType Directory -Path $taskEvidence -Force | Out-Null
& $taskAdb pull /sdcard/Android/data/com.ynk.virtualdisplay/files/pet_mvp_gates $taskEvidence
if ($LASTEXITCODE -ne 0) { throw 'Gate screenshot retrieval failed' }
Copy-Item -LiteralPath (Join-Path $taskEvidence 'pet_mvp_gates') -Destination $taskArtifacts -Recurse -Force
