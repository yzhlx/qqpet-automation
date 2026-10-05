$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskAdb = Join-Path $taskRoot 'tools\android-build\sdk\platform-tools\adb.exe'
$taskFixtureCopy = @'
import pathlib, shutil, subprocess, sys, tempfile
adb, source = sys.argv[1:]
target = pathlib.Path(tempfile.gettempdir()) / "QQPetRecordedFixtures"
target.mkdir(exist_ok=True)
names = ["result-before-continue", "continue-after", "course-detail-confirmed", "learning-start",
         "observed-03", "health-values-before", "cookie-after-3"]
remote = "/sdcard/Android/data/com.ynk.virtualdisplay/files/pet_mvp_fixtures/"
subprocess.run([adb,"shell","mkdir","-p",remote],check=True)
for name in names:
    local = target / (name + ".png")
    shutil.copyfile(pathlib.Path(source) / local.name, local)
    subprocess.run([adb,"push",str(local),remote+local.name],check=True)
'@
& python3.12.exe -c $taskFixtureCopy $taskAdb (Join-Path $PSScriptRoot 'deliverables\gate-c')
if ($LASTEXITCODE -ne 0) { throw 'Fixture copy failed' }
& $taskAdb shell am instrument -w -e class com.ynk.virtualdisplay.PetRecordedStateTest com.ynk.virtualdisplay.test/androidx.test.runner.AndroidJUnitRunner
