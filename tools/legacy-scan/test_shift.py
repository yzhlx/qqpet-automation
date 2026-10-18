import sys
from PIL import Image
sys.path.insert(0, r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\tools\digit-recon")
from reader_port import load_templates, read_digits

TPL = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\app\src\main\assets\pet_health\digits.txt"
templates = load_templates(TPL)
FRAMES = {
    "437": (r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\screenshots\v01\p1-feed-after.png", 624, 871),
    "638": (r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\soap-price.png", 625, 872),
    "663": (r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\cookie-price.png", 625, 871),
}
for want, (path, sx, sy) in FRAMES.items():
    img = Image.open(path).convert("RGB")
    print(f"=== balance {want} ===")
    for dy in range(-6, 3):
        for dx in range(-10, 3):
            x0, y0 = sx + dx, sy + dy
            st, det = read_digits(img, x0, y0, 52, 31, dark_thresh=100,
                                  templates=templates, max_digits=4, value_range=(0, 9999))
            if st == "KNOWN":
                v, conf, text, detail = det
                print(f"  KNOWN ({x0},{y0}) -> {v} conf={conf:.4f} {detail}")
        else:
            pass
    # report the best near-miss reason
    st, det = read_digits(img, sx - 6, sy - 5, 52, 31, dark_thresh=100,
                          templates=templates, max_digits=4, value_range=(0, 9999))
    print(f"  probe({sx-6},{sy-5}): {st} {det}")
