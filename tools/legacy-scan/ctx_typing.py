import io, sys, os, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
fp = r"C:\Users\lu158\.dsh\cache\dsh-legacy-scan\all\decoded\a201e82f-13d8-4210-90e9-edb8f6256c7e__session.v4.jsonl"
txt = open(fp, encoding="utf-8", errors="replace").read()
# window before the keyevent-66 pay commit @2105042
seg = txt[1950000:2106000]
for m in re.finditer(r"input[^\"]{0,120}", seg):
    s = m.group(0)
    if any(k in s for k in ("tap", "text", "keyevent", "swipe")):
        print(f"@{1950000 + m.start()}: {s[:130]}")
