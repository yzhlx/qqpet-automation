"""Crop only observed fixed ROIs. No OCR, detector or synthetic templates."""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parent
out = root / "app/src/debug/assets/pet_experimental"
out.mkdir(parents=True, exist_ok=True)
samples = {
    "home": ("experimental/restored.png", (600, 130, 670, 190)),
    "room": ("experimental/restored.png", (320, 1340, 405, 1465)),
    "health": ("experimental/restored-health.png", (169, 228, 250, 415)),
    "cookie": ("experimental/food-nav.png", (280, 1255, 440, 1360)),
    "soap": ("gate-c/soap-finger-after.png", (280, 1280, 440, 1400)),
    "outdoor": ("gate-c/outdoor.png", (126, 610, 275, 663)),
    "academy": ("experimental/1791196488723/round-1-11-academy-after.png", (330, 158, 346, 177)),
    "high_five": ("gate-c/learning-start.png", (284, 344, 355, 400)),
    "grade_a": ("gate-c/result-before-continue.png", (545, 385, 695, 525)),
}
for name, (source, rect) in samples.items():
    Image.open(root / "deliverables" / source).crop(rect).save(out / f"{name}.png")
    print(name, source, rect)
