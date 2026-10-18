import io, sys, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
fp = r"C:\Users\lu158\.dsh\cache\dsh-legacy-scan\all\decoded\session-db2f8e1d-c00a-4918-9c01-37e0c02c0b09__session.v4.jsonl"
txt = open(fp, encoding="utf-8", errors="replace").read()
print("size", len(txt))
for kw in ("兑换", "支付", "购买", "金币", "宠物", "input -d", "tap", "mobileqq", "adelie"):
    print(kw, txt.count(kw))
i = txt.find("user/message")
print("FIRST-USER:", txt[i:i + 400].replace("\n", " "))
