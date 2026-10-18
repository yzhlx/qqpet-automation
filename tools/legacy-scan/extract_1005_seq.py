import io, sys, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
FP = r"C:\Users\lu158\.codex\sessions\2026\10\05\rollout-2026-10-05T13-18-24-01a10a7f-b72e-7a42-985c-d48aba0be48a.jsonl"
txt = open(FP, encoding="utf-8", errors="replace").read()
# Extract every executed adb command string (dedup), in order.
seq = []
seen = set()
for m in re.finditer(r'adb\.exe[\\ ]+shell[\\ ]+input[^\"]{0,120}', txt):
    s = m.group(0).replace("\\\\", "\\").replace('\\"', '"')
    s = re.sub(r"\s+", " ", s)[:110]
    if s not in seen:
        seen.add(s)
        seq.append((m.start(), s))
print(f"unique input cmds: {len(seq)}")
for pos, s in seq[:60]:
    print(f"  @{pos}: {s}")
# screencap / template hits
for kw in ("screencap", "soap", "Soap", "支付", "兑换", "数量", "库存"):
    print(kw, txt.count(kw))
# find a narrative passage around 兑换/支付
for m in list(re.finditer(r"支付|兑换", txt))[:6]:
    s = txt[max(0, m.start() - 200):m.start() + 260].replace("\\n", " ").replace("\n", " ")
    print("CTX>", s[:420]); print()
