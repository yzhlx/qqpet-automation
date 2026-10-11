"""Derive the step-2 tap target for the t3 PRECONDITION_RESTORE route.

Reads a FRESH uiautomator dump of display 4 and validates, fail-closed, that the
pet-game message card is present. Prints TAP_X/TAP_Y only when every semantic
precondition holds; exits non-zero otherwise so the caller aborts before input.

Selected target: the LOWEST (most recent) ark card whose title text is exactly
"宠物名画之旅", tapped at the centre of that card's title text node.

Why the title node and not the card centre: on the bottom card the title is clear
of the inner clickable child ([303,1170][543,1329], an artwork/thumbnail target)
and of the non-clickable animated pet sprites. Touch passes through the
non-clickable title TextView to the clickable card LinearLayout.
"""
import os
import sys
import xml.etree.ElementTree as ET

TITLE = "\u5ba0\u7269\u540d\u753b\u4e4b\u65c5"          # 宠物名画之旅
FOOTER = "\u5ba0\u7269"                                   # 宠物
SUBTITLE_MARK = "\u65c5\u884c\u73af\u7ebf"                # 旅行环线...


def bounds(s):
    inner = s.strip("[]")
    l, rest = inner.split(",")
    t, rest = rest.split("][")
    r, b = rest.split(",")
    return int(l), int(t), int(r), int(b)


def centre(b):
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


def die(msg):
    print("FAIL_CLOSED: %s" % msg)
    sys.exit(2)


def main():
    if len(sys.argv) < 2:
        die("usage: step2_target.py <ui.xml>")
    path = sys.argv[1]
    if not os.path.exists(path):
        die("dump missing: %s" % path)
    txt = open(path, "rb").read().decode("utf-8", "replace")
    root = ET.fromstring(txt)
    nodes = list(root.iter("node"))

    pkgs = {n.get("package") for n in nodes}
    if pkgs != {"com.tencent.mobileqq"}:
        die("unexpected packages: %s" % pkgs)

    titles, subs, foots = [], [], []
    for n in nodes:
        t = (n.get("text") or "").strip()
        cd = (n.get("content-desc") or "").strip()
        if t == TITLE:
            titles.append(n)
        if t.startswith(SUBTITLE_MARK):
            subs.append(n)
        if t == FOOTER:
            foots.append(n)
        if cd == "\u53d1\u6765" + TITLE:                  # 发来宠物名画之旅
            print("ARK_MSG_DESC bounds=%s" % n.get("bounds"))

    if not titles:
        die("no node with title %r" % TITLE)
    print("titles=%d subtitles=%d footers=%d" % (len(titles), len(subs), len(foots)))
    for n in titles:
        print("TITLE bounds=%s" % n.get("bounds"))

    # most recent = lowest on screen
    title = max(titles, key=lambda n: bounds(n.get("bounds"))[1])
    tb = bounds(title.get("bounds"))
    tx, ty = centre(tb)

    # the enclosing clickable card
    card = None
    for n in nodes:
        if n.get("clickable") != "true":
            continue
        cb = bounds(n.get("bounds"))
        if cb[0] <= tx <= cb[2] and cb[1] <= ty <= cb[3]:
            if card is None or (cb[2] - cb[0]) * (cb[3] - cb[1]) < \
                    (card[2] - card[0]) * (card[3] - card[1]):
                card = cb
    if card is None:
        die("no clickable ancestor/container covers the title centre")
    print("CARD bounds=%s clickable" % (card,))

    # reject if a DIFFERENT clickable node (e.g. a thumbnail target) covers the point
    inside = []
    for n in nodes:
        if n.get("clickable") != "true":
            continue
        cb = bounds(n.get("bounds"))
        if cb == card:
            continue
        if cb[0] <= tx <= cb[2] and cb[1] <= ty <= cb[3]:
            inside.append((cb, (n.get("class") or "").split(".")[-1],
                           (n.get("resource-id") or "").replace("com.tencent.mobileqq:id/", "")))
    if inside:
        die("tap point covered by other clickable nodes: %s" % inside)

    # the same card must carry the pet subtitle and the 宠物 footer
    same = [n for n in subs if card[1] - 10 <= centre(bounds(n.get("bounds")))[1] <= card[3] + 10]
    if not same:
        die("no subtitle in the selected card")
    samef = [n for n in foots if card[1] - 10 <= centre(bounds(n.get("bounds")))[1] <= card[3] + 20]
    if not samef:
        die("no %r footer in the selected card" % FOOTER)
    sub = same[-1]
    print("SUBTITLE in card: %r" % (sub.get("text") or ""))
    print("FOOTER in card: %r" % FOOTER)

    # the animated pet sprites must be non-clickable (touches pass through)
    sprites = [(n.get("bounds"), n.get("content-desc"))
               for n in nodes if (n.get("content-desc") or "").startswith("Q\u5ba0-")]
    for b, d in sprites:
        print("SPRITE desc=%s bounds=%s" % (d, b))
    for b, d in sprites:
        if bounds(b)[0] <= tx <= bounds(b)[2] and bounds(b)[1] <= ty <= bounds(b)[3]:
            print("NOTE: tap point lies over sprite %s (non-clickable, passes through)" % d)

    print("SEMANTICS_OK title=%r card=%s" % (TITLE, card))
    print("TAP_X=%d" % tx)
    print("TAP_Y=%d" % ty)


main()
