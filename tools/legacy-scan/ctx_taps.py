import io, sys, os, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
fp = r"C:\Users\lu158\.dsh\cache\dsh-legacy-scan\all\decoded\a201e82f-13d8-4210-90e9-edb8f6256c7e__session.v4.jsonl"
txt = open(fp, encoding="utf-8", errors="replace").read()
for pos in (420752, 991102, 1004867, 1066973, 1663722, 1835213, 2105042, 2224974, 3101331):
    s = txt[max(0, pos - 700):pos + 400]
    s = re.sub(r"\\n", " ", s)
    s = re.sub(r"[^ ]{80,}", lambda m: m.group(0)[:80] + "…", s)
    print("=" * 18, pos)
    print(s[:1000])
    print()
