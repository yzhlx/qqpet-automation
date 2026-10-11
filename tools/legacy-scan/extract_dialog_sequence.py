import io, sys, os, re, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
DEC = r"C:\Users\lu158\.dsh\cache\dsh-legacy-scan\all\decoded"
# sessions with purchase evidence: a201e82f, b797ec57, cb61db57
targets = [f for f in os.listdir(DEC) if any(s in f for s in ("a201e82f", "b797ec57", "cb61db57"))]
print("files:", targets)
TAP = re.compile(r"input(?:\s+-d\s+4)?\s+tap\s+(\d+)\s+(\d+)|input\s+(-d\s+4\s+)?tap\s+(\d+)\s+(\d+)")
for t in targets:
    fp = os.path.join(DEC, t)
    txt = open(fp, encoding="utf-8", errors="replace").read()
    print("=" * 25, t, len(txt))
    # find passages around 兑换 dialog opening: look for keywords 兑换/商店/购买/支付 with nearby taps
    # collect ordered (pos, tapcmd) list
    ev = []
    for m in re.finditer(r"adb[^\"]{0,10}?shell[^\"]{0,10}?input\s+(?:-d\s+4\s+)?(tap|swipe)\s+(\d+)\s+(\d+)(?:\s+(\d+)\s+(\d+))?(?:\s+(\d+))?", txt):
        kind = m.group(1)
        if kind == "tap":
            ev.append((m.start(), f"tap {m.group(2)} {m.group(3)}"))
        else:
            ev.append((m.start(), f"swipe {m.group(2)} {m.group(3)} {m.group(4)} {m.group(5)} {m.group(6)}"))
    # dedup consecutive
    out = []
    last = None
    for pos, s in ev:
        if s != last:
            out.append((pos, s)); last = s
    # print windows around 兑换/支付 mentions
    marks = [m.start() for m in re.finditer("兑换|支付|购买|商店|入口", txt)]
    print("keyword marks:", len(marks), "tap events:", len(out))
    # find taps that occur within +-3000 chars of a keyword
    near = []
    for pos, s in out:
        if any(abs(pos - mk) < 3000 for mk in marks):
            near.append((pos, s))
    # print sequence compressed
    prev = None; shown = 0
    for pos, s in near:
        if s != prev:
            print(f"  @{pos}: {s}")
            prev = s; shown += 1
        if shown > 45: break
