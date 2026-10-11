# Second-order test: place the 52x31 window so firstcol lands in 4..8, then check
# whether the EXISTING templates match the dialog font at >=0.98 confidence.
import sys
from PIL import Image
sys.path.insert(0, r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\tools\digit-recon")
from reader_port import load_templates, read_digits

TPL = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\app\src\main\assets\pet_health\digits.txt"
templates = load_templates(TPL)

FRAMES = {
    "p1-feed-after (437/5/25)": r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\screenshots\v01\p1-feed-after.png",
    "soap-price (638/10/20)": r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\soap-price.png",
    "cookie-price (663/1/5)": r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\cookie-price.png",
}

def find_strokes(img, x0, x1, y0, y1, t=100):
    px = img.load()
    cols = [x for x in range(x0, x1) if any(max(px[x, y]) < t for y in range(y0, y1))]
    return cols

for label, path in FRAMES.items():
    img = Image.open(path).convert("RGB")
    print(f"=== {label} ===")
    regions = {
        "balance": (585, 665, 845, 895),
        "quantity": (545, 615, 1270, 1310),
        "paytotal": (360, 440, 1372, 1412),
    }
    for rname, (rx0, rx1, ry0, ry1) in regions.items():
        cols = find_strokes(img, rx0, rx1, ry0, ry1)
        rows = [y for y in range(ry0, ry1) if any(max(img.load()[x, y]) < 100 for x in range(rx0, rx1))]
        if not cols or not rows:
            print(f"  {rname}: no dark strokes")
            continue
        first, last = cols[0], cols[-1]
        top, bot = rows[0], rows[-1]
        print(f"  {rname}: strokes x {first}..{last} (w={last-first+1}) y {top}..{bot} (h={bot-top+1})")
        # place window: left = first-6 (offset 6 in 4..8), top = bot-24 (b in 22..24)
        found = []
        for off in (4, 5, 6, 7, 8):
            for bb in (22, 23, 24):
                x0, y0 = first - off, bot - bb
                st, det = read_digits(img, x0, y0, 52, 31, dark_thresh=100,
                                      templates=templates, max_digits=4, value_range=(0, 9999))
                if st == "KNOWN":
                    v, conf, text, detail = det
                    found.append((x0, y0, v, conf, detail))
        if found:
            for x0, y0, v, conf, detail in found[:5]:
                print(f"      KNOWN ({x0},{y0}) -> {v} conf={conf:.4f} {detail}")
        else:
            # show why: try one placement and print status
            x0, y0 = first - 6, bot - 23
            st, det = read_digits(img, x0, y0, 52, 31, dark_thresh=100,
                                  templates=templates, max_digits=4, value_range=(0, 9999))
            print(f"      best-effort ({x0},{y0}) -> {st} {det}")
    print()
