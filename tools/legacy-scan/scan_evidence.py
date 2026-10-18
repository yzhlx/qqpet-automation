# Extract and classify purchase-chain EVIDENCE from decoded session JSONLs.
# Writes a UTF-8 report file; no console CJK needed.
import os, sys, re, json, glob, datetime

DEC = sys.argv[1]
REPORT = sys.argv[2]

def walk(obj, path=""):
    if isinstance(obj, str):
        yield path, obj
    elif isinstance(obj, dict):
        for k, v in obj.items():
            yield from walk(v, path + "/" + str(k))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from walk(v, f"{path}[{i}]")

# What a REAL executed purchase looks like in transcripts:
# 1. A tool call whose input contains adb/app tap coordinates over dialog
#    (y=1072 goods, y=1298 stepper, y=1424 pay)  OR app log events INPUT_SENT w/ those coords
# 2. FOLLOWED by success text like 购买成功/兑换成功/余额减少/coins 528->523 or stock +1
EXEC_MARK = re.compile(r"(input tap|injectInputEvent|dispatchTouch|GestureDescription|addStroke|PetInputController|INPUT_SENT)")
COORD_MARK = re.compile(r"(142[0-9]|143[0-9])")   # pay button y
GOODS = re.compile(r"(107[0-9]|106[0-9])")
STEPPER = re.compile(r"(129[0-9]|130[0-9])")
SUCCESS = re.compile(r"(购买成功|兑换成功|道具增加|库存增加|余额减少|coins? -\d|-\s*(5|20|25|100)\s*(金币|coins)|paid|payment succeeded|PAY_CONFIRMED)", re.I)
FAILSTOP = re.compile(r"(PAGE_MISMATCH|CLEAN_NO_GAIN|FEED_NO_GAIN|STOPPED)", re.I)

out = []
def log(s):
    out.append(s)

log(f"# legacy purchase-chain scan {datetime.datetime.now()}")
log("")

for fp in sorted(glob.glob(os.path.join(DEC, "*")), key=os.path.getmtime):
    key = os.path.basename(fp)
    mt = datetime.datetime.fromtimestamp(os.path.getmtime(fp)).strftime("%Y-%m-%d %H:%M")
    n_exec_coord = 0
    n_success = 0
    samples = []
    with open(fp, "r", encoding="utf-8", errors="replace") as f:
        for ln, line in enumerate(f):
            if not line.strip().startswith("{"):
                continue
            try:
                rec = json.loads(line)
            except Exception:
                continue
            for path, t in walk(rec):
                if len(t) < 10:
                    continue
                if EXEC_MARK.search(t) and (COORD_MARK.search(t) or (GOODS.search(t) and STEPPER.search(t))):
                    n_exec_coord += 1
                    if len(samples) < 8:
                        samples.append((ln, path[:60], t[:300]))
                if SUCCESS.search(t):
                    n_success += 1
                    if len(samples) < 12:
                        samples.append((ln, "SUCCESS-MATCH " + path[:40], t[:300]))
    if n_exec_coord or n_success:
        log(f"## {key}  ({mt})  execCoordHits={n_exec_coord} successHits={n_success}")
        for ln, p, s in samples:
            log(f"  line {ln} [{p}]")
            log(f"    {s}")
        log("")

if len(out) == 1:
    log("NO session contains executed purchase coords or purchase-success text.")

with open(REPORT, "w", encoding="utf-8") as f:
    f.write("\n".join(out))
print(f"report: {REPORT}, {len(out)} lines")
