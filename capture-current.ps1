param([Parameter(Mandatory=$true)][string]$Name)
$ErrorActionPreference = 'Stop'
$taskAdb = Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\android-build\sdk\platform-tools\adb.exe'
$taskCapture = @'
import pathlib, subprocess, sys, time
adb, output = sys.argv[1:]
folder = "/sdcard/Android/data/com.ynk.virtualdisplay/files/pet_mvp_debug/"
def latest():
    return subprocess.check_output([adb,"shell","ls","-t",folder],text=True).splitlines()[0].strip()
before = latest()
subprocess.run([adb,"shell","input","-d","0","tap","341","1556"],check=True)
time.sleep(1)
after = latest()
assert after != before, "No fresh debug frame saved"
data = subprocess.check_output([adb,"exec-out","cat",folder+after])
assert data.startswith(b"\x89PNG\r\n\x1a\n")
pathlib.Path(output).write_bytes(data)
print(after)
'@
& python3.12.exe -c $taskCapture $taskAdb (Join-Path $PSScriptRoot "deliverables\gate-c\$Name.png")
if ($LASTEXITCODE -ne 0) { throw 'Capture failed; do not inspect an older frame.' }
