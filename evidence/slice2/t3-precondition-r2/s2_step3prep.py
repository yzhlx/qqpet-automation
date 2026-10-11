# -*- coding: utf-8 -*-
"""t3 attempt-2 STEP 3 prep: locate the non-consuming return/back control on the
名画之旅 activity page, from a FRESH frame.

Tries a validated display-4 uiautomator dump first (bounds must be 720x1600 and the
package must be QQ); otherwise falls back to re-matching the visually confirmed
back-button patch against the fresh frame.

Read-only. Writes step3-prep.txt + crops. Takes NO input.
"""
import io
import os
import re
import subprocess
import time

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\tools\android-build\sdk\platform-tools\adb.exe"
DEV = "DKS9K23420020242"
PNG = b"\x89PNG\r\n\x1a\n"
OUT = []
PAGE = "step2b-after-4-20261007-141033.png"


def log(s):
    OUT.append(s)
    print(s, flush=True)


def adb(args, binary=False):
    p = subprocess.run([ADB, "-s", DEV] + args, capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


log("=== validated display-4 dump attempt ===")
for i in range(2):
    adb(["shell", "rm", "-f", "/sdcard/_b3.xml"])
    err = adb(["shell", "uiautomator", "dump", "--display", "4", "/sdcard/_b3.xml"]).strip()
    xml = adb(["shell", "cat", "/sdcard/_b3.xml"])
    j = xml.find("<?xml")
    if j < 0:
        log("  attempt%d NO XML %r" % (i + 1, err[:100]))
        continue
    xml = xml[j:]
    rb = re.match(r'.*?bounds="(\[[0-9,]+\]\[[0-9,]+\])"', xml)
    pk = sorted(set(re.findall(r'package="([^"]+)"', xml)))
    ok = (rb and rb.group(1) == "[0,0][720,1600]" and pk == ["com.tencent.mobileqq"])
    log("  attempt%d bytes=%d root=%s pkgs=%s VALID=%s"
        % (i + 1, len(xml), rb.group(1) if rb else "?", pk, ok))
    if ok:
        open(os.path.join(HERE, "dump4-page.xml"), "w", encoding="utf-8").write(xml)
        log("  -> saved dump4-page.xml")
        break

log("")
log("=== fresh frame ===")
raw = adb(["exec-out", "screencap", "-p", "-d", "4"], binary=True)
ok = raw[:8] == PNG
fp = os.path.join(HERE, "step3-before.png")
open(fp, "wb").write(raw)
fresh = Image.open(io.BytesIO(raw)).convert("RGB") if ok else None
log("  bytes=%d signature_ok=%s size=%s" % (len(raw), ok, fresh.size))

ref = Image.open(os.path.join(HERE, PAGE)).convert("RGB")
a = np.asarray(ref, dtype=np.int16)
b = np.asarray(fresh, dtype=np.int16)
log("  whole-frame mean|diff| vs %s = %.3f" % (PAGE, np.abs(a - b).mean()))
prev2 = Image.open(os.path.join(HERE, "step2b-after-3-20261007-141033.png")).convert("RGB")
log("  whole-frame mean|diff| vs step2b-after-3 = %.3f"
    % np.abs(np.asarray(prev2, dtype=np.int16) - b).mean())

# report sampled colours across the top-left band to find the round control
log("")
log("=== top-left scan (find the round back control) ===")
for y in range(110, 210, 10):
    row = []
    for x in range(8, 90, 10):
        px = fresh.getpixel((x, y))
        row.append("%3d,%3d,%3d" % px)
    log("  y=%3d  %s" % (y, " | ".join(row)))

crops = {
    "zoom4-backbutton.png": (0, 100, 130, 220, 5),
    "zoom4-pageheader.png": (0, 80, 720, 320, 1),
}
for name, (x1, y1, x2, y2, k) in crops.items():
    c = fresh.crop((x1, y1, x2, y2))
    c = c.resize((c.width * k, c.height * k), Image.LANCZOS)
    c.save(os.path.join(HERE, name))
    log("wrote %s %s x%d -> %s" % (name, (x1, y1, x2, y2), k, c.size))

open(os.path.join(HERE, "step3-prep.txt"), "w", encoding="utf-8").write("\n".join(OUT) + "\n")
