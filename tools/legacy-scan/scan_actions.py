# Search decoded transcripts for ACTUAL executed tap/inject actions near purchase UI,
# not prose mentions. JSONL lines are tool-call records; we extract command inputs.
import os, sys, re, json, glob

DEC = sys.argv[1]

# Coordinates around the exchange dialog (720x1600 frame):
#   pay button ~ (360,1424); qty stepper +/- ~ (510..660,1298); biscuit ~ (103,1072)
PAY_RE = re.compile(r"(3[0-9][0-9]\s*,\s*14[0-2][0-9])|(1424)|(360\s*14[0-3][0-9])")
TAP_RE = re.compile(r"(injectInputEvent|dispatchTouch|input tap|GestureDescription|tap\s*\(|injectTap|swipe)", re.I)
COIN_OK_RE = re.compile(r"(coins.{0,20}(minus|deduct|-5|-20|changed)|购买成功|paySuccess|purchaseSuccess|PAY_SUCCESS|兑换成功)", re.I)
STOCK_RE = re.compile(r"(stock|库存).{0,30}(528|437|100|increase|\+1|增长)", re.I)

# Extract candidate "command-like" strings from a JSON record.
def strings(obj):
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from strings(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from strings(v)

stats = {}
for fp in glob.glob(os.path.join(DEC, "*")):
    key = os.path.basename(fp)
    m_tap = m_pay = m_ok = m_stock = 0
    samples = []
    with open(fp, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or not line.startswith("{"):
                continue
            try:
                rec = json.loads(line)
            except Exception:
                # lines may be concatenated; salvage by regex on raw
                rec = None
            texts = []
            if rec is not None:
                texts = list(strings(rec))
            else:
                texts = [line]
            for t in texts:
                if len(t) < 8:
                    continue
                if TAP_RE.search(t) and re.search(r"\b(510|660|103|313|360)\b", t) and re.search(r"\b(1072|1298|1424|1297)\b", t):
                    m_tap += 1
                    if len(samples) < 3:
                        samples.append(t[:220])
                if PAY_RE.search(t):
                    m_pay += 1
                if COIN_OK_RE.search(t):
                    m_ok += 1
                    if len(samples) < 6:
                        samples.append("OK? " + t[:220])
                if STOCK_RE.search(t):
                    m_stock += 1
    stats[key] = (m_tap, m_pay, m_ok, m_stock, samples)

print(f"{'file':70s} tap-coord pay-geo payOK stock")
for key, (a, b, c, d, s) in sorted(stats.items(), key=lambda kv: -(kv[1][0]+kv[1][2])):
    if a + c + d == 0 and b < 50:
        continue
    print(f"{key[:70]:70s} {a:8d} {b:6d} {c:5d} {d:5d}")
    for x in s:
        print(f"      | {x}")
