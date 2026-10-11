import io, sys, os, re, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
FILES = [
    r"C:\Users\lu158\.workbuddy\traces\32820\trace_e991107c749546179ced139a32082f64.json",
]
# find real paths for the other hits
for dp, dn, fn in os.walk(r"C:\Users\lu158\.workbuddy\traces"):
    for f in fn:
        if f.startswith(("trace_a6bd1c10", "trace_c6fc3282", "trace_c181aacc", "trace_c0a05107", "trace_4a15c693")):
            FILES.append(os.path.join(dp, f))
for fp in FILES:
    if not os.path.exists(fp):
        print("absent", fp); continue
    txt = open(fp, encoding="utf-8", errors="replace").read()
    pet = len(re.findall(r"养宠|宠物|香皂|饼干|virtualdisplay|display 4|QQ", txt))
    print("=" * 12, os.path.basename(fp), f"({len(txt)}c, pet-hits={pet})")
    shown = 0
    for m in re.finditer(r"兑换|香皂|饼干", txt):
        s = txt[max(0, m.start() - 140):m.start() + 220].replace("\n", " ").replace("\\n", " ")
        print("   >", s[:330]); shown += 1
        if shown >= 4: break
    adb = re.findall(r"adb[^\"]{0,90}", txt)[:4]
    for a in adb: print("   ADB:", a[:130])
    print()
