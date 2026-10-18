import io, sys, os, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

PAT = re.compile(r"兑换|支付|购买|input -d 4 tap|soap_gesture|香皂|饼干")

def scan(fp, label):
    try:
        sz = os.path.getsize(fp)
        if sz > 300_000_000:
            print(f"{label}: SKIP huge {sz}")
            return
        txt = open(fp, encoding="utf-8", errors="replace").read()
    except Exception as e:
        print(f"{label}: ERR {e}"); return
    hits = list(PAT.finditer(txt))
    real = [h for h in hits if h.group(0) in ("兑换", "支付", "input -d 4 tap", "soap_gesture", "香皂", "饼干")]
    if not real:
        return
    ctx_pay = [h for h in real if h.group(0) == "兑换"]
    ctx_tap = [h for h in real if h.group(0) == "input -d 4 tap"]
    print(f"{label}: {len(real)} hits (兑换={len(ctx_pay)}, tap-d4={len(ctx_tap)}, 香皂={sum(1 for h in real if h.group(0)=='香皂')}, 饼干={sum(1 for h in real if h.group(0)=='饼干')})")
    shown = 0
    for h in real:
        s = txt[max(0, h.start() - 100):h.start() + 160].replace("\n", " ")
        if any(k in s for k in ("token", "credits", "quota", "banked")):
            continue
        print(f"   [{h.group(0)}] {s[:230]}")
        shown += 1
        if shown >= 6:
            break

roots = [
    (r"C:\Users\lu158\.codex\sessions\2026\10\04", "codex-1004"),
    (r"C:\Users\lu158\.codex\sessions\2026\10\05", "codex-1005"),
    (r"C:\Users\lu158\.workbuddy\sessions", "wb-sessions"),
    (r"C:\Users\lu158\.workbuddy\tasks", "wb-tasks"),
    (r"C:\Users\lu158\.workbuddy\traces", "wb-traces"),
    (r"C:\Users\lu158\.workbuddy\projects", "wb-projects"),
]
for root, label in roots:
    if not os.path.isdir(root):
        print(f"{label}: absent"); continue
    for dp, dn, fn in os.walk(root):
        for f in fn:
            p = os.path.join(dp, f)
            if f.endswith((".jsonl", ".md", ".txt", ".json")):
                scan(p, f"{label}/{f[:48]}")
