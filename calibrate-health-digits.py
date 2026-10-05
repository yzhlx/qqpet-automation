"""Reproduce only the frozen, manually labelled numeric bank. Never observes a phone."""
from pathlib import Path
import hashlib
import json
from PIL import Image


def main():
    root = Path(__file__).resolve().parent
    manifest = json.loads((root / "evidence/slice1/health-ground-truth.json").read_text())
    bank = set()
    for sample in manifest["training"]:
        path = root / sample["file"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != sample["sha256"]:
            raise RuntimeError("Training evidence changed: " + str(path))
        image = Image.open(path).convert("RGB")
        if image.size != (720, 1600):
            raise RuntimeError("Unsupported display geometry")
        for y, value in zip((228, 304, 380), sample["visually_checked"]):
            roi = image.crop((256, y, 308, y + 31))
            mask = [[max(roi.getpixel((x, j))) < 100 for x in range(52)] for j in range(31)]
            columns = [x for x in range(52) if any(row[x] for row in mask)]
            groups = []
            for x in columns:
                if not groups or x > groups[-1][-1] + 1:
                    groups.append([])
                groups[-1].append(x)
            if len(groups) != len(str(value)):
                raise RuntimeError("Label/glyph count mismatch")
            for digit, xs in zip(str(value), groups):
                ys = [j for j in range(31) if any(mask[j][x] for x in xs)]
                left, right, top, bottom = xs[0], xs[-1], min(ys), max(ys)
                normalized = "".join("1" if mask[top + j * (bottom-top+1)//32][left + x * (right-left+1)//24]
                                     else "0" for j in range(32) for x in range(24))
                bank.add((int(digit), normalized))
    if {digit for digit, _ in bank} != set(range(10)):
        raise RuntimeError("Incomplete fixed-digit bank")
    data = ("\n".join(f"{digit} {mask}" for digit, mask in sorted(bank)) + "\n").encode()
    if hashlib.sha256(data).hexdigest() != manifest["bank_sha256"]:
        raise RuntimeError("Calibration is not reproducible; bank left unchanged")
    (root / "app/src/main/assets/pet_health/digits.txt").write_bytes(data)
    print(f"HEALTH_BANK_REPRODUCED templates={len(bank)}")


if __name__ == "__main__":
    main()
