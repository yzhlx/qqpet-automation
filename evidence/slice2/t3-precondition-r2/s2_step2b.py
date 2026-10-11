# -*- coding: utf-8 -*-
"""t3 attempt-2 STEP 2 (authorized, <=3 setup taps total; 1 already used).

Target: the positively identified QQ-pet launch card in the unique conversation whose
preview says 宠物名画之旅 -- instance at [122,280][590,541], which is NOT occluded by the
forbidden floating-sprite overlay (that overlay starts at y>=1093).

The tap point is derived on a FRESH frame immediately before the input:
  1. capture display 4 (screencap, signature-validated);
  2. re-match the already semantically confirmed card patch from the dump-validated
     frame (coarse->fine integer shift search) to prove the card is where it was;
  3. accept only if the peak is unique and the residual is small; else STOP (no tap);
  4. tap the card centre derived on THAT frame; record capture->input latency.

Read-only except for the single authorized tap. Writes step2-*.{txt,json,png}.
"""
import io
import json
import os
import re
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

CARD1 = (122, 280, 591, 542)          # dump-validated clickable box of card instance 1
REF_FRAME = "state-now.png"           # dump-validated frame (13:46 inventory)
OVERLAYS = [(183, 1093, 423, 1493), (303, 1093, 543, 1493)]   # forbidden floating sprites
RNG = 24                              # +/- px search radius
MAE_ACCEPT = 9.0                      # accept only a tight re-match
LOG = []


def log(s):
    LOG.append(s)
    print(s, flush=True)


def adb(args, binary=False):
    p = subprocess.run([ADB, "-s", DEV] + args, capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def capture(name):
    """Capture display 4 through the reliable path; validate the PNG signature."""
    t0 = time.time()
    raw = adb(["exec-out", "screencap", "-p", "-d", "4"], binary=True)
    if raw[:8] != PNG:
        adb(["shell", "screencap", "-p", "-d", "4", "/sdcard/_s2.png"])
        raw = adb(["exec-out", "cat", "/sdcard/_s2.png"], binary=True)
    path = os.path.join(HERE, name)
    with open(path, "wb") as f:
        f.write(raw)
    ok = raw[:8] == PNG
    im = Image.open(io.BytesIO(raw)).convert("RGB") if ok else None
    t1 = time.time()
    return path, im, t0, t1, len(raw), ok


def locate(ref, frame, region, rng=RNG):
    """Coarse->fine integer shift search of `ref` crop over `frame`."""
    x1, y1, x2, y2 = region
    patch = np.asarray(ref.crop(region), dtype=np.int16)
    scored = []
    for step in (4, 1):
        cand = ([(dx, dy) for dx in range(-rng, rng + 1, step)
                 for dy in range(-rng, rng + 1, step)] if step == 4 else
                [(dx, dy) for dx in range(bx - 3, bx + 4) for dy in range(by - 3, by + 4)])
        for dx, dy in cand:
            win = np.asarray(frame.crop((x1 + dx, y1 + dy, x2 + dx, y2 + dy)), dtype=np.int16)
            if win.shape != patch.shape:
                continue
            scored.append((float(np.abs(win - patch).mean()), dx, dy))
        scored.sort()
        bx, by = scored[0][1], scored[0][2]
    # dedupe identical shifts (coarse and fine sweeps can both yield the same (dx,dy))
    seen, uniq = set(), []
    for m, dx, dy in scored:
        if (dx, dy) in seen:
            continue
        seen.add((dx, dy))
        uniq.append((m, dx, dy))
    uniq.sort()
    best = uniq[0]
    # a genuine rival must be a DIFFERENT location, not a neighbour of the peak
    rival = next((r for r in uniq[1:] if abs(r[1] - best[1]) > 3 or abs(r[2] - best[2]) > 3), None)
    return uniq[:5], best, rival


def main():
    facts = adb(["shell", "getprop", "ro.sf.lcd_density"]).strip()
    log("device=%s stamp=%s host_local=%s" % (DEV, STAMP, time.strftime("%Y-%m-%d %H:%M:%S")))
    log("display 4 declared: 720x1600 density 320 rotation 0  (ro.sf.lcd_density=%s is display 0)" % facts)

    ref = Image.open(os.path.join(HERE, REF_FRAME)).convert("RGB")
    log("reference frame %s size=%s" % (REF_FRAME, ref.size))

    path1, f1, t0, t1, n1, ok1 = capture("step2b-before-%s.png" % STAMP)
    log("captured %s bytes=%d signature_ok=%s size=%s took=%.0fms"
        % (os.path.basename(path1), n1, ok1, f1.size if f1 else None, (t1 - t0) * 1000))
    if not ok1 or f1.size != ref.size:
        log("STOP: unusable frame (signature/size)")
        return 1

    top5, (mae, dx, dy), rival = locate(ref, f1, CARD1)
    log("card-1 re-match: MAE=%.3f shift=(%d,%d)   top5=%s"
        % (mae, dx, dy, [("%.2f" % m, a, b) for m, a, b in top5]))
    unique = rival is None or (rival[0] - mae) > 0.5
    log("peak unique vs nearest rival >3px away: %s (nearest rival=%s)"
        % (unique, ("%.2f @ (%d,%d)" % rival) if rival else "none in +/-%d px" % RNG))

    box = (CARD1[0] + dx, CARD1[1] + dy, CARD1[2] + dx, CARD1[3] + dy)
    cx, cy = int((box[0] + box[2]) / 2), int((box[1] + box[3]) / 2)
    inside = box[0] + 6 <= cx <= box[2] - 6 and box[1] + 6 <= cy <= box[3] - 6
    clear = all(not (o[0] <= cx <= o[2] and o[1] <= cy <= o[3]) for o in OVERLAYS)
    log("target box on FRESH frame = %s  centre=(%d,%d)  inside=%s  outside_overlays=%s"
        % (str(box), cx, cy, inside, clear))
    if mae > MAE_ACCEPT or not unique or not inside or not clear:
        log("STOP: target not positively confirmed on this frame -> NO TAP")
        json.dump({"tap": False, "reason": "TARGET_UNCONFIRMED", "mae": mae,
                   "shift": [dx, dy], "unique": unique, "inside": inside, "clear": clear},
                  open(os.path.join(HERE, "step2b-target.json"), "w"))
        return 2

    json.dump({"tap": True, "mae": mae, "shift": [dx, dy], "box": box,
               "x": cx, "y": cy, "before": os.path.basename(path1)},
              open(os.path.join(HERE, "step2b-target.json"), "w"), ensure_ascii=False, indent=1)

    d = ImageDraw.Draw(f1)
    d.rectangle([box[0], box[1], box[2], box[3]], outline=(255, 0, 0), width=3)
    for o in OVERLAYS:
        d.rectangle([o[0], o[1], o[2], o[3]], outline=(255, 0, 255), width=2)
    d.ellipse([cx - 10, cy - 10, cx + 10, cy + 10], outline=(0, 160, 0), width=3)
    f1.save(os.path.join(HERE, "step2b-before-annotated.png"))

    cmd = ["shell", "input", "-d", "4", "tap", str(cx), str(cy)]
    t_in0 = time.time()
    adb(cmd)
    t_in1 = time.time()
    log("TAP cmd=adb -s %s %s" % (DEV, " ".join(cmd)))
    log("source=com.android.shell (NOT APP_AUTOMATION)  display=-d 4")
    log("capture_begin_local=%s input_local=%s capture_to_input_ms=%.0f"
        % (time.strftime("%H:%M:%S", time.localtime(t0)),
           time.strftime("%H:%M:%S", time.localtime(t_in1)), (t_in1 - t0) * 1000))

    # first-observed post-action frames
    shots = []
    for i, delay in enumerate((2, 6, 12, 20, 30)):
        time.sleep(delay - (shots[-1][0] if shots else 0))
        pth, im, a, b, n, ok = capture("step2b-after-%d-%s.png" % (i, STAMP))
        d1 = int(np.abs(np.asarray(im, dtype=np.int16) - np.asarray(f1, dtype=np.int16)).mean() * 100)
        shots.append((delay, os.path.basename(pth), n, ok, im.size, d1))
        log("after[+%2ds] %s bytes=%d ok=%s size=%s mean|diff|vs-before=%d/100"
            % (delay, os.path.basename(pth), n, ok, im.size, d1))

    open(os.path.join(HERE, "step2b-attribution.txt"), "w", encoding="utf-8").write(
        "\n".join(LOG) + "\n")
    log("wrote step2b-attribution.txt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
