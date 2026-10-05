"""Extract measured pixel ROIs from real headless captures; no OCR or detection."""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parent
frames = root / "deliverables/gate-c"
assets = root / "app/src/main/assets/pet_mvp"
measurements = [
    ("result", "result-before-continue.png", (225, 234, 500, 284)),
    ("course_list", "continue-after.png", (118, 681, 290, 727)),
    ("course_detail", "course-detail-confirmed.png", (80, 762, 328, 1029)),
    ("learning_initial", "learning-start.png", (95, 1132, 260, 1174)),
    ("learning_expanded", "observed-03.png", (95, 946, 260, 988)),
    ("soap", "soap-panel-closed.png", (280, 1280, 440, 1400)),
    ("soap_idle", "soap-match-before.png", (280, 1280, 440, 1400)),
]
for name, source, rect in measurements:
    with Image.open(frames / source) as image:
        assert image.size == (720, 1600)
        image.crop(rect).save(assets / (name + ".png"))
    print(name, source, rect)
