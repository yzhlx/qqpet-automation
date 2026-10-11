import io, sys, os, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

FILES = [
    r"C:\Users\lu158\.workbuddy\projects\e26d7fec-19f8-4dbd-9553-1b97f26464f2.jsonl",
    r"C:\Users\lu158\.workbuddy\traces\trace_e991107c749546179ced139a32082f64.json",
    r"C:\Users\lu158\.workbuddy\traces\trace_a6bd1c108fff4abda5d01445d3703713.json",
    r"C:\Users\lu158\.workbuddy\traces\trace_c6fc3282a09f40bda2e67ad03cadb6d2.json",
    r"C:\Users\lu158\.workbuddy\traces\trace_c181aacbcfa847ff86a5e6788a641f62.json",
]
PAT = re.compile(r"兑换|支付|购买|香皂|饼干|adb|input|tap|screencap|养宠|virtualdisplay|宠物")
for fp in FILES:
    if not os.path.exists(fp):
        print("absent", fp); continue
    txt = open(fp, encoding="utf-8", errors="replace").read()
    print("=" * 15, os.path.basename(fp), f"({len(txt)} chars)")
    # is it pet-related at all?
    pet = len(re.findall(r"养宠|宠物|香皂|饼干|virtualdisplay|display 4|adelie", txt))
    print(f"  pet-related hits: {pet}")
    shown = 0
    for m in re.finditer(r"兑换|香皂|饼干", txt):
        s = txt[max(0, m.start() - 130):m.start() + 200].replace("\n", " ").replace("\\n", " ")
        print("   >", s[:300])
        shown += 1
        if shown >= 5: break
    # any executed adb?
    adb = re.findall(r"adb[^\"]{0,80}", txt)[:5]
    for a in adb:
        print("   ADB:", a[:120])
    print()
