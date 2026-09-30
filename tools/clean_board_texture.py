"""清理手工抠图留下的白边 —— 棋盘贴图专用。

从白色背景上抠出来的贴图，轮廓上会剩一圈「不透明的近白像素」和半透明的白色混合，
游戏里看就是棋盘外面挂着一圈发白的颗粒。而且这些颗粒常常是**断续**的：它们把透明区
割成一个个互不连通的小口袋，所以不能只看「某个透明像素」，得从图像四边做一次洪泛
—— 洪泛时允许**穿过**近背景色的不透明像素（白边本身就是通道）。

判定为「外面」的像素按下面处理：

  1. 不透明、颜色离背景色不远 → 按「离背景色多远」折算 alpha：完全等于背景色的变全透明，
     掺了一半的留半透明。白边于是变成柔和的透明过渡；
  2. 半透明 → 按 alpha 反解掉白底混色（un-premultiply），去掉那层白雾。

图形**内部**的白圆点、星位不会被碰：它们要么不与外界连通，要么中途会撞上饱和的图案色
（调色板里的红黄蓝绿离背景色都很远，洪泛过不去）。脚本每次都会打印「内部近白像素」
的数量做对照 —— 处理前后必须**完全一致**，否则说明误伤了，脚本会拒绝保存。

用法（默认只试算，不改文件）：

    python tools/clean_board_texture.py <png>              # 试算
    python tools/clean_board_texture.py <png> --force      # 就地改写（先自动备份到 tools/backup/）
"""

import pathlib
import shutil
import sys
import time
from collections import Counter

from PIL import Image

# 与背景色的最大切比雪夫距离：超过它就不当白边（调色板里的饱和色都远大于它）
TOL = 64
# 比这更接近背景色的直接清成全透明（实测残留颗粒都在 24 以内），
# 只有 TOL 那一段过渡带才做羽化 —— 否则留一层很淡的白雾
CLEAR_TOL = 24
# 洪泛时允许穿过的「近背景」阈值，比 TOL 宽松：白边颗粒本身要能当通道
TRAVERSE_TOL = 96


def detect_background(img):
    """背景色 = 不透明的近白像素里出现最多的那种颜色"""
    c = Counter()
    for p in img.getdata():
        if p[3] > 0 and min(p[:3]) >= 230 and max(p[:3]) - min(p[:3]) <= 16:
            c[p[:3]] += 1
    return c.most_common(1)[0][0] if c else (255, 255, 255)


def clean(path, force=False):
    src = pathlib.Path(path)
    img = Image.open(src).convert("RGBA")
    w, h = img.size
    px = img.load()
    bg = detect_background(img)
    print("背景色判定为 %s" % (bg,))

    def dist(r, g, b):
        return max(abs(r - bg[0]), abs(g - bg[1]), abs(b - bg[2]))

    # 从四边洪泛出「外面」：全透明像素可以走，近背景色的不透明像素也可以走（白边是通道），
    # 撞上真正的图案色就停下 —— 内部的白点因此进不来。
    outside = [[False] * h for _ in range(w)]
    stack = []
    for x in range(w):
        for y in (0, h - 1):
            outside[x][y] = True
            stack.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if not outside[x][y]:
                outside[x][y] = True
                stack.append((x, y))
    while stack:
        x, y = stack.pop()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if not (0 <= nx < w and 0 <= ny < h) or outside[nx][ny]:
                continue
            r, g, b, a = px[nx, ny]
            if a == 0 or dist(r, g, b) < TRAVERSE_TOL:
                outside[nx][ny] = True
                stack.append((nx, ny))

    def inner_bright():
        # 只数「外界够不到」的近白像素 —— 那才是图形内部的白圆点/星位。
        # 挨着外边的近白像素本来就是白边颗粒（实测有几个只有 3 个透明邻居），该清。
        return sum(1 for x in range(w) for y in range(h)
                   if px[x, y][3] > 0 and min(px[x, y][:3]) >= 230 and not outside[x][y])

    before = inner_bright()
    cleared = faded = unmatte = 0
    for x in range(w):
        for y in range(h):
            r, g, b, a = px[x, y]
            if a == 0 or not outside[x][y]:
                continue
            if a < 255:  # 半透明：反解白底
                nr = max(0, min(255, round((r - (255 - a) / 255 * bg[0]) * 255 / a)))
                ng = max(0, min(255, round((g - (255 - a) / 255 * bg[1]) * 255 / a)))
                nb = max(0, min(255, round((b - (255 - a) / 255 * bg[2]) * 255 / a)))
                px[x, y] = (nr, ng, nb, a)
                unmatte += 1
                continue
            d = dist(r, g, b)
            if d < CLEAR_TOL:
                na = 0
                cleared += 1
            else:
                na = min(255, round(255 * (d - CLEAR_TOL) / (TOL - CLEAR_TOL)))
                faded += 1
            px[x, y] = (r, g, b, na)

    after = inner_bright()
    print("  全清成透明的白边像素: %d" % cleared)
    print("  降成半透明的白边像素: %d" % faded)
    print("  反解掉白底的半透明像素: %d" % unmatte)
    print("  图形内部近白像素: 前 %d → 后 %d（必须一样）" % (before, after))
    if before != after:
        raise SystemExit("外部处理波及到了被保护的白点 —— 不要保存，检查 TOL/TRAVERSE_TOL")

    if not force:
        print("（试算，未写入。加 --force 才就地改写）")
        return
    backup_dir = pathlib.Path(__file__).resolve().parent / "backup"
    backup_dir.mkdir(exist_ok=True)
    bak = backup_dir / (src.stem + ".preclean-" + time.strftime("%Y%m%d-%H%M%S") + src.suffix)
    shutil.copy2(src, bak)
    img.save(src)
    print("  已写入 %s（原文件备份到 %s）" % (src, bak))


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        raise SystemExit(__doc__)
    clean(args[0], force="--force" in sys.argv)
