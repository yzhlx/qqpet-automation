import io, sys, re, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
FILES = [
    r"C:\Users\lu158\.codex\sessions\2026\10\05\rollout-2026-10-05T00-05-47-01a107a1-fd15-71e0-84cf-14085b14e319_01a107aa-0dae-78e2-95df-6be36aea96ef.jsonl",
    r"C:\Users\lu158\.codex\sessions\2026\10\05\rollout-2026-10-05T00-11-10-01a107ae-fa8a-7662-8879-68a69be43562.jsonl",
    r"C:\Users\lu158\.codex\sessions\2026\10\05\rollout-2026-10-05T13-18-24-01a10a7f-b72e-7a42-985c-d48aba0be48a.jsonl",
    r"C:\Users\lu158\.codex\sessions\2026\10\05\rollout-2026-10-05T13-23-11-01a10a84-16c0-78a3-9d6f-50454346463c.jsonl",
]
PAT = re.compile(r"input -d 4 tap|input -d 4 swipe|兑换|支付|1390|1408|1424|soap_gesture|app_process|饼干|香皂")
for fp in FILES:
    try:
        txt = open(fp, encoding="utf-8", errors="replace").read()
    except Exception as e:
        print("SKIP", fp, e); continue
    print("=" * 20, fp.split(chr(92))[-1][:60])
    print("size", len(txt))
    hits = list(PAT.finditer(txt))
    print("hits", len(hits))
    seen = set()
    n = 0
    for m in hits:
        s = txt[max(0, m.start() - 120):m.start() + 220].replace("\\n", " ").replace("\n", " ")
        key = s[:80]
        if key in seen:
            continue
        seen.add(key)
        n += 1
        print(f"  [{m.group(0)}] ...{s[:300]}...")
        if n >= 12:
            break
    # session cwd / first user msg
    m2 = re.search(r'"cwd":"([^"]+)"', txt)
    if m2: print("  cwd:", m2.group(1))
    m3 = re.search(r'"role":"user".{0,600}?"text":"(.{0,200})', txt)
    if m3: print("  first-user:", m3.group(1)[:200].replace("\\n", " "))
    print()
