"""Collect the latest bounded run's local evidence through ADB binary reads."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent
adb = root.parent / "tools/android-build/sdk/platform-tools/adb.exe"
remote = "/sdcard/Android/data/com.ynk.virtualdisplay/files/pet_experimental/"
def call(*args):
    return subprocess.check_output([str(adb), *args])
names = call("shell", "ls", remote).decode().split()
runs = sorted((n for n in names if n.isdigit()), key=int)
if not runs:
    raise RuntimeError("No run evidence; inspect setup failure log instead")
name = runs[-1]
dest = root / "deliverables/experimental" / name
dest.mkdir(parents=True, exist_ok=True)
for filename in call("shell", "ls", remote + name).decode().split():
    if "/" in filename or "\\" in filename:
        raise RuntimeError("Unexpected filename")
    data = call("exec-out", "cat", remote + name + "/" + filename)
    if filename.endswith(".png") and not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("Invalid PNG " + filename)
    (dest / filename).write_bytes(data)
print(dest)
if (dest / "verdict.json").exists():
    print((dest / "verdict.json").read_text())
