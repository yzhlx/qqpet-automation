# Print full context of every executed-action candidate so we can classify
# Agent-operated GUI taps vs app-internal automation vs mere prose.
import os, sys, re, json, glob, datetime

DEC = sys.argv[1]
TARGET_COORDS = [(1072, 1298, 1424, 1297)]

pat_tap = re.compile(r"(injectInputEvent|dispatchTouch|input tap|GestureDescription|addStroke|injectTap)", re.I)
pat_geo = re.compile(r"(107[0-9]|129[0-9]|14[12][0-9])")

def strings(obj, path=""):
    if isinstance(obj, str):
        yield path, obj
    elif isinstance(obj, dict):
        for k, v in obj.items():
            yield from strings(v, path + "/" + str(k))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from strings(v, f"{path}[{i}]")

for fp in sorted(glob.glob(os.path.join(DEC, "*"))):
    key = os.path.basename(fp)
    with open(fp, "r", encoding="utf-8", errors="replace") as f:
        data = f.read()
    lines = [l for l in data.split("\n") if l.strip().startswith("{")]
    n_shown = 0
    for ln, line in enumerate(lines):
        try:
            rec = json.loads(line)
        except Exception:
            continue
        # Identify record type quickly
        rtype = rec.get("type") or rec.get("kind") or "?"
        for path, t in strings(rec):
            if len(t) < 12:
                continue
            if pat_tap.search(t) and pat_geo.search(t):
                mt = datetime.datetime.fromtimestamp(os.path.getmtime(fp)).strftime("%m-%d")
                print(f"### {key} ({mt}) line {ln} type={rtype}")
                print(t[:1500].encode("utf-8", "replace").decode("utf-8", "replace"))
                print("---")
                n_shown += 1
                if n_shown >= 6:
                    break
        if n_shown >= 6:
            break
