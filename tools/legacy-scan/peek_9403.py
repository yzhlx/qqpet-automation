import io, sys, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
fp = r"C:\Users\lu158\.dsh\cache\dsh-legacy-scan\all\decoded\session-94035573-5b2c-4d41-8a31-5eb1dca2afc7__session.v4.jsonl"
txt = open(fp, encoding="utf-8", errors="replace").read()
print("size", len(txt))
for kw in ("兑换", "支付", "金币", "宠物", "mobileqq", "adelie", "input -d", "购买"):
    print(kw, txt.count(kw))
for m in re.finditer(r"兑换", txt):
    s = txt[max(0, m.start() - 150):m.start() + 150].replace("\n", " ")
    print("---", s[:280])
    break
