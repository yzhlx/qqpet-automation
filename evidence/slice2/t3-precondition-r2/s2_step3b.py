# -*- coding: utf-8 -*-
"""t3 attempt-2 STEP 3 (final authorized setup tap, cumulative 2 -> 3).

Target: the ONLY explicitly identified control on the QQ pet 名画之旅 H5 activity page --
the non-consuming 返回 control, authoritative bounds [0,110][117,210] (content-desc="返回",
inner ImageView [33,140][71,178]), from the validated display-4 dump dump4-page.xml.

Per contract the tap point is re-derived on a FRESH frame immediately before input:
  capture -> template-match the dump-validated control patch -> require unique peak and
  low residual -> tap the matched centre, recording capture-to-input latency.

Read-only apart from that single authorized tap. Writes step3b-*.
"""
import io
import json
import os
import subprocess
import sys
import time

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\tools\android-build\sdk\platform-tools\adb.exe"
DEV = "DKS9K23420020242"
PNG = b"\x89PNG\r\n\x1a\n"
STAMP = time.strftime("%Y%m%d-%H%M%S")

CTRL = (0, 110, 117, 210)                       # dump-validated 返回 control box
REF_FRAME = "step2b-after-4-20261007-141033.png"  # the frame the validated dump describes
SEARCH = (0, 60, 240, 300)                      # search window inside the fresh frame
RNG = 30
MAE_ACCEPT = 6.0
LOG = []


def log(s):
    LOG.append(s)
    print(s, flush=True)


def adb(args, binary=False):
    p = subprocess.run([ADB, "-s", DEV] + args, capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def capture(name):
    t0 = time.time()
    raw = adb(["exec-out", "screencap", "-p", "-d", "4"], binary=True)
    if raw[:8] != PNG:
        adb(["shell", "screencap", "-p", "-d", "4", "/sdcard/_s3.png"])
        raw = adb(["exec-out", "cat", "/sdcard/_s3.png"], binary=True)
    p = os.path.join(HERE, name)
    open(p, "wb").write(raw)
    ok = raw[:8] == PNG
    return p, (Image.open(io.BytesIO(raw)).convert("RGB") if ok else None), t0, time.time(), len(raw), ok


def main():
    log("device=%s stamp=%s host_local=%s" % (DEV, STAMP, time.strftime("%Y-%m-%d %H:%M:%S")))
    log("cumulative authorized setup taps before this step = 2 (limit 3)")

    ref = Image.open(os.path.join(HERE, REF_FRAME)).convert("RGB")
    log("dump-validated reference frame %s size=%s" % (REF_FRAME, ref.size))

    p1, f1, t0, t1, n1, ok1 = capture("step3b-before-%s.png" % STAMP)
    log("fresh frame %s bytes=%d signature_ok=%s size=%s capture_ms=%.0f"
        % (os.path.basename(p1), n1, ok1, f1.size if f1 else None, (t1 - t0) * 1000))
    if not ok1 or f1.size != ref.size:
        log("STOP: unusable frame -> NO TAP")
        return 1

    sx1, sy1, sx2, sy2 = SEARCH
    patch = np.asarray(ref.crop(CTRL), dtype=np.int16)
    best = None
    scored = []
    for dx in range(-RNG, RNG + 1):
        for dy in range(-RNG, RNG + 1):
            x1, y1 = CTRL[0] + dx, CTRL[1] + dy
            x2, y2 = CTRL[2] + dx, CTRL[3] + dy
            win = np.asarray(f1.crop((x1, y1, x2, y2)), dtype=np.int16)
            if win.shape != patch.shape:
                continue
            scored.append((float(np.abs(win - patch).mean()), dx, dy))
    scored.sort()
    seen, uniq = set(), []
    for m, dx, dy in scored:
        if (dx, dy) in seen:
            continue
        seen.add((dx, dy))
        uniq.append((m, dx, dy))
    mae, bx, by = uniq[0]
    rival = next((r for r in uniq[1:] if abs(r[1] - bx) > 4 or abs(r[2] - by) > 4), None)
    unique = rival is None or (rival[0] - mae) > 0.5
    log("返回 control re-match MAE=%.3f shift=(%d,%d) top5=%s"
        % (mae, bx, by, [("%.2f" % m, a, b) for m, a, b in uniq[:5]]))
    log("unique peak vs rival >4px: %s (nearest rival=%s)"
        % (unique, ("%.2f @ (%d,%d)" % rival) if rival else "none"))

    box = (CTRL[0] + bx, CTRL[1] + by, CTRL[2] + bx, CTRL[3] + by)
    cx, cy = int((box[0] + box[2]) / 2), int((box[1] + box[3]) / 2)
    log("target box on FRESH frame=%s centre=(%d,%d)" % (str(box), cx, cy))
    json.dump({"mae": mae, "shift": [bx, by], "box": box, "x": cx, "y": cy,
               "unique": unique, "before": os.path.basename(p1)},
              open(os.path.join(HERE, "step3b-target.json"), "w"), ensure_ascii=False, indent=1)
    if mae > MAE_ACCEPT or not unique:
        log("STOP: control not positively confirmed on this frame -> NO TAP")
        return 2

    d = ImageDraw.Draw(f1)
    d.rectangle([box[0], box[1], box[2], box[3]], outline=(255, 0, 0), width=3)
    d.ellipse([cx - 8, cy - 8, cx + 8, cy + 8], outline=(0, 160, 0), width=3)
    f1.save(os.path.join(HERE, "step3b-before-annotated.png"))

    cmd = ["shell", "input", "-d", "4", "tap", str(cx), str(cy)]
    i0 = time.time()
    adb(cmd)
    i1 = time.time()
    log("TAP cmd=adb -s %s %s" % (DEV, " ".join(cmd)))
    log("source=com.android.shell (NOT APP_AUTOMATION)  display=-d 4")
    log("capture_begin_local=%s input_local=%s capture_to_input_ms=%.0f"
        % (time.strftime("%H:%M:%S", time.localtime(t0)),
           time.strftime("%H:%M:%S", time.localtime(i1)), (i1 - t0) * 1000))
    log("count_cumulative_setup=3")

    prev = f1
    for i, delay in enumerate((3, 8, 15, 25)):
        time.sleep(delay if i == 0 else delay - (3, 8, 15, 25)[i - 1])
        p, im, a, b, n, ok = capture("step3b-after-%d-%s.png" % (i, STAMP))
        d1 = int(np.abs(np.asarray(im, dtype=np.int16) - np.asarray(prev, dtype=np.int16)).mean() * 100)
        log("after[+%2ds] %s bytes=%d ok=%s mean|diff|vs-prev=%d/100" % (delay, os.path.basename(p), n, ok, d1))
        prev = im

    open(os.path.join(HERE, "step3b-attribution.txt"), "w", encoding="utf-8").write("\n".join(LOG) + "\n")
    log("wrote step3b-attribution.txt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
