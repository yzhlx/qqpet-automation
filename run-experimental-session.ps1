param([switch]$Authorized, [string]$ResumeRun)
$ErrorActionPreference='Stop'
if (!$Authorized) { throw 'Explicit authorization is required for this real three-round experiment.' }
$taskAdb=Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\android-build\sdk\platform-tools\adb.exe'
$taskLog=Join-Path $PSScriptRoot 'deliverables\experimental\instrumentation.log'
$instrumentArgs=@('shell','am','instrument','-w','-e','class','com.ynk.virtualdisplay.PetExperimentalSessionTest','-e','experimental_authorized','yes','-e','display_id','4','-e','mode','session','-e','rounds','3')
if ($ResumeRun) {
    if ($ResumeRun -notin @('1791203355328','1791204208048','1791204552957')) { throw 'Only the verified upgrade-interrupted run can resume.' }
    $instrumentArgs+=@('-e','resume_run',$ResumeRun)
}
$instrumentArgs+='com.ynk.virtualdisplay.test/androidx.test.runner.AndroidJUnitRunner'
& $taskAdb @instrumentArgs *> $taskLog
$taskJUnitExit=$LASTEXITCODE
Get-Content -LiteralPath $taskLog -Tail 25
& python3.12.exe (Join-Path $PSScriptRoot 'collect-experimental-evidence.py')
if ($LASTEXITCODE -ne 0) { throw 'No valid experiment evidence was collected.' }
$taskVerdict=Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'deliverables\experimental') -Directory |
    Where-Object { $_.Name -match '^\d+$' } | Sort-Object { [long]$_.Name } -Descending | Select-Object -First 1
if (!$taskVerdict) { throw 'No experiment verdict exists.' }
$taskResult=Get-Content -LiteralPath (Join-Path $taskVerdict.FullName 'verdict.json') -Raw | ConvertFrom-Json
if ($taskJUnitExit -ne 0 -or $taskResult.verdict -ne '3_ROUNDS_PASS' -or $taskResult.completed -ne 3) {
    throw "Session did not pass: verdict=$($taskResult.verdict) completed=$($taskResult.completed) stopReason=$($taskResult.stopReason)"
}



