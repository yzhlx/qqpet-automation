import sys
from collections import deque
from PIL import Image

path = sys.argv[1]
region_top = int(sys.argv[2]) if len(sys.argv) > 2 else 1150
region_bottom = int(sys.argv[3]) if len(sys.argv) > 3 else 1512

im = Image.open(path).convert("RGB")
px = im.load()
w, h = im.size
print("frame", w, "x", h)

bg = px[5, region_top + 5]


def warm(x, y):
    r, g, b = px[x, y]
    l1 = abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2])
    # warm/fur: red-dominant, clearly saturated warm tone, well away from grey bg
    return l1 > 100 and (r > g >= b) and (r - b) > 25


# connected components (4-neighbour) over the floating zone only
seen = [[False] * (region_bottom - region_top) for _ in range(w)]
comps = []
for y0 in range(region_top, region_bottom):
    row = y0 - region_top
    for x0 in range(w):
        if seen[x0][row] or not warm(x0, y0):
            continue
        q = deque([(x0, y0)])
        seen[x0][row] = True
        n = 0
        minx = maxx = x0
        miny = maxy = y0
        while q:
            x, y = q.popleft()
            n += 1
            minx = min(minx, x); maxx = max(maxx, x)
            miny = min(miny, y); maxy = max(maxy, y)
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and region_top <= ny < region_bottom:
                    rr = ny - region_top
                    if not seen[nx][rr] and warm(nx, ny):
                        seen[nx][rr] = True
                        q.append((nx, ny))
        comps.append((n, minx, miny, maxx, maxy))

comps.sort(reverse=True)
print("components found:", len(comps))
for n, minx, miny, maxx, maxy in comps[:8]:
    cx = (minx + maxx) / 2
    cy = (miny + maxy) / 2
    print("  px=%d bbox=(%d,%d)-(%d,%d) size=%dx%d centre=(%.1f,%.1f)"
          % (n, minx, miny, maxx, maxy, maxx - minx + 1, maxy - miny + 1, cx, cy))

big = [c for c in comps if c[0] >= 250]
print("components with >=250 px:", len(big))
