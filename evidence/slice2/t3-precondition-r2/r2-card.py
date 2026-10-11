"""Locate the topmost white structured-message CARD in the QQ pet conversation.

Semantics are decided separately by the operator; this only measures geometry on
a given frame so the tap point can be re-derived quickly on a fresh frame.
Cards are near-pure-white rounded rects on a light-grey conversation background.
"""
import json
import os
import sys

from PIL import Image


def main(path):
    im = Image.open(path).convert("RGB")
    px = im.load()
    W, H = im.size

    def is_white(p):
        r, g, b = p
        return r >= 250 and g >= 250 and b >= 250

    # count near-white pixels per row inside the card x-band
    x0, x1 = 120, 590
    rows = []
    for y in range(180, 1200):
        n = sum(1 for x in range(x0, x1, 2) if is_white(px[x, y]))
        rows.append((y, n))

    span = x1 - x0
    thresh = span * 0.55
    runs, cur, gap = [], None, 0
    for y, n in rows:
        if n >= thresh:
            cur = [y, y] if cur is None else [cur[0], y]
            gap = 0
        elif cur is not None:
            gap += 1
            if gap > 8:
                runs.append(tuple(cur))
                cur = None
    if cur:
        runs.append(tuple(cur))

    runs = [(a, b) for (a, b) in runs if (b - a + 1) >= 100]
    if not runs:
        print(json.dumps({"ok": False, "reason": "NO_CARD_ROWS", "raw": runs}))
        return

    a, b = runs[0]  # topmost card
    # horizontal extent of the card at its vertical middle
    ymid = (a + b) // 2
    minx = None
    maxx = None
    for x in range(0, W):
        if is_white(px[x, ymid]):
            minx = x if minx is None else minx
            maxx = x
    if minx is None:
        print(json.dumps({"ok": False, "reason": "NO_CARD_EXTENT"}))
        return

    # tap inside the card body, above its footer tag row
    body_y = a + int((b - a) * 0.45)
    tapx = (minx + maxx) // 2

    out = {
        "ok": True,
        "frame": os.path.basename(path),
        "frame_size": [W, H],
        "card_bbox": [minx, a, maxx, b],
        "card_size": [maxx - minx + 1, b - a + 1],
        "card_centre": [(minx + maxx) / 2.0, (a + b) / 2.0],
        "tap": [tapx, body_y],
        "card_count": len(runs),
        "all_cards": [[x, y0, y1] for (y0, y1) in runs[:5]],
    }
    print(json.dumps(out))


if __name__ == "__main__":
    main(sys.argv[1])
