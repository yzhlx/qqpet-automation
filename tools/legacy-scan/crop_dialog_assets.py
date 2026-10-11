import io, sys, os
from PIL import Image
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
BASE = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化"
SC = BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7"
ASSETS = BASE + r"\VirtualDisplay\app\src\main\assets\pet_exchange"
os.makedirs(ASSETS, exist_ok=True)

# (asset name, source frame, crop box (l,t,r,b), purpose)
CROPS = [
    # dialog identity: title text row, stable across qty states; food vs soap differ
    ("dialog_food_title.png", SC + r"\cookie-buy-one-confirm.png", (240, 878, 480, 922)),
    ("dialog_soap_title.png", SC + r"\soap-price.png", (240, 878, 480, 922)),
    # selected product frame (blue border) - proves WHICH product is selected
    ("food_cookie_selected.png", SC + r"\cookie-buy-one-confirm.png", (16, 916, 108, 1204)),
    ("soap_soap_selected.png", SC + r"\soap-price.png", (16, 916, 108, 1204)),
    # confirm "bought" state: after successful pay the dialog closes -> verify by title absence + scene restored.
    # No extra asset needed; but keep a "bought" stock indicator: cookie-use frames show plate full state.
]
for name, src, box in CROPS:
    Image.open(src).convert("RGB").crop(box).save(os.path.join(ASSETS, name))
    print(name, "from", os.path.basename(src), box)

# ALSO extract same title crops from a second frame each (validate stability)
for tag, src in [("chk_food", SC + r"\cookie-price.png"), ("chk_soap", SC + r"\resume9-soap-price-current.png")]:
    im = Image.open(src).convert("RGB").crop((240, 878, 480, 922))
    ref = Image.open(os.path.join(ASSETS, "dialog_food_title.png" if "food" in tag else "dialog_soap_title.png")).convert("RGB")
    a, b = im.resize((24, 5)), ref.resize((24, 5))
    pa, pb = a.load(), b.load()
    diff = sum(abs(pa[x, y][c] - pb[x, y][c]) for x in range(24) for y in range(5) for c in range(3)) / (24 * 5 * 3 * 255)
    print(f"{tag} title stability vs ref: sim={1 - diff:.4f}")
