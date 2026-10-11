"""Re-locate the first QQ conversation row on display 4 for PRECONDITION_RESTORE.

Semantics (must be confirmed visually by the operator on the SAME frame):
the row is the first conversation in the QQ message list, directly below the
search field / promotion banner, whose preview text is the pet-game entry
("宠物名画之旅").  This script only measures geometry; it does not decide
semantics.  It is deliberately fast so the capture->input latency stays small.

Output: one JSON object on stdout.
"""
import json
import os
import sys

from PIL import Image


def strong(p):
    r, g, b = p
    mx, mn = max(r, g, b), min(r, g, b)
    return (mx - mn) > 40 or mx < 200


def main(path):
    im = Image.open(path).convert("RGB")
    px = im.load()
    W, H = im.size

    # avatar column band of the QQ message list
    x0, x1 = 20, 131
    runs, cur, gap = [], None, 0
    for y in range(300, min(H, 1150)):
        n = sum(1 for x in range(x0, x1) if strong(px[x, y]))
        if n >= 45:
            cur = [y, y] if cur is None else [cur[0], y]
            gap = 0
        elif cur is not None:
            gap += 1
            if gap > 4:
                runs.append(tuple(cur))
                cur = None
    if cur:
        runs.append(tuple(cur))

    # keep avatar-sized discs, topmost first
    discs = [(a, b) for (a, b) in runs if (b - a + 1) >= 60]
    if not discs:
        print(json.dumps({"ok": False, "reason": "NO_AVATAR_DISC", "runs": runs}))
        return

    a, b = discs[0]
    minx = maxx = None
    for y in range(a, b + 1):
        for x in range(0, 200):
            if strong(px[x, y]):
                minx = x if minx is None else min(minx, x)
                maxx = x if maxx is None else max(maxx, x)
    cy = (a + b) / 2.0

    # tap inside the row body (whole row is clickable), away from the timestamp
    tapx, tapy = 360, int(round(cy))

    out = {
        "ok": True,
        "frame": os.path.basename(path),
        "frame_size": [W, H],
        "avatar_disc_bbox": [minx, a, maxx, b],
        "avatar_disc_size": [maxx - minx + 1, b - a + 1],
        "avatar_disc_centre": [(minx + maxx) / 2.0, cy],
        "row_band_y": [a, b],
        "tap": [tapx, tapy],
        "all_avatar_discs": [[x, y0, y1] for (y0, y1) in discs[:6]],
    }
    print(json.dumps(out))


if __name__ == "__main__":
    main(sys.argv[1])
