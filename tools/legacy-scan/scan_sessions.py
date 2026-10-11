# ASCII-only script: decompress DSH session transcripts and search for purchase-chain evidence.
import os, sys, glob, json, io
import zstandard as zstd

SESS = sys.argv[1]
OUT = sys.argv[2]

# Patterns: purchase-chain markers in any encoding-safe form
PATS = [
    "支付", "兑换", "购买", "金币", "purchase", "buyItem", "payCoins",
    "exchange", "shop", "余额",
]

hits = {}
files = glob.glob(os.path.join(SESS, "**", "*.zstd"), recursive=True)
decoded = os.path.join(OUT, "decoded")
os.makedirs(decoded, exist_ok=True)

for fp in files:
    try:
        with open(fp, "rb") as fh:
            dctx = zstd.ZstdDecompressor()
            data = dctx.stream_reader(fh).read()
        text = data.decode("utf-8", errors="replace")
    except Exception as e:
        print(f"ERR {fp}: {e}")
        continue
    # keep a copy
    name = os.path.basename(os.path.dirname(fp)) + "__" + os.path.basename(fp).replace(".zstd", "")
    with open(os.path.join(decoded, name), "w", encoding="utf-8") as w:
        w.write(text)
    for p in PATS:
        c = text.count(p)
        if c:
            hits.setdefault(p, []).append((name, c, os.path.getmtime(fp)))

print(f"scanned {len(files)} session transcripts")
print()
for p in sorted(hits, key=lambda k: -sum(x[1] for x in hits[k])):
    total = sum(x[1] for x in hits[p])
    print(f"PATTERN {p!r}: total {total} hits in {len(hits[p])} files")
    for name, c, mt in sorted(hits[p], key=lambda x: -x[1])[:6]:
        import datetime
        t = datetime.datetime.fromtimestamp(mt).strftime("%m-%d %H:%M")
        print(f"   {c:5d}x  {name}  ({t})")
