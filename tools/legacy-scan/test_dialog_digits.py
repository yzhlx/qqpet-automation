# Quick offline test: dialog balance/quantity digits are BLACK text (visible in crops),
# not gold. My phase01-04 ROI was wrong (measured the dimmed room-page pill behind the
# scrim). Test the existing reader algorithm with the dark gate on the CORRECT dialog ROIs.
import sys, os
from PIL import Image
sys.path.insert(0, r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\tools\digit-recon")
from reader_port import load_templates, read_digits

TPL = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\app\src\main\assets\pet_health\digits.txt"
templates = load_templates(TPL)

FRAMES = {
    "p1-feed-after (437, qty5, total25)": r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\screenshots\v01\p1-feed-after.png",
    "soap-price (638, qty10, total20)": r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\soap-price.png",
    "cookie-price (663, qty1, total5)": r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\cookie-price.png",
}

# dark-predicate sweep on 52x31, relaxed layout (max 4 digits, range 0..9999)
def sweep(img, x0r, y0r, label):
    best = []
    for y0 in y0r:
        for x0 in x0r:
            if x0 < 0 or y0 < 0 or x0 + 52 > img.width or y0 + 31 > img.height:
                continue
            st, det = read_digits(img, x0, y0, 52, 31, dark_thresh=100,
                                  templates=templates, max_digits=4, value_range=(0, 9999))
            if st == "KNOWN":
                best.append((x0, y0, det))
    return best

for label, path in FRAMES.items():
    img = Image.open(path).convert("RGB")
    print(f"=== {label} ===")
    regions = {
        "balance pill digits": (range(585, 665), range(845, 895)),
        "quantity digits": (range(545, 615), range(1270, 1310)),
        "pay total digits": (range(360, 440), range(1372, 1412)),
    }
    for rname, (xr, yr) in regions.items():
        hits = sweep(img, xr, yr, rname)
        if hits:
            for x0, y0, det in hits[:4]:
                v, conf, text, detail = det
                print(f"  {rname}: KNOWN ({x0},{y0}) -> {v} conf={conf:.4f} {detail}")
        else:
            # report dominant reject reason at center
            x0, y0 = (xr[0]+xr[-1])//2, (yr[0]+yr[-1])//2
            st, det = read_digits(img, x0, y0, 52, 31, dark_thresh=100,
                                  templates=templates, max_digits=4, value_range=(0, 9999))
            print(f"  {rname}: no KNOWN; center({x0},{y0}) -> {st} {det}")
    print()
