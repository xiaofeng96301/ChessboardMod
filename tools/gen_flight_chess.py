"""生成飞行棋的**纹理**、棋子/骰子的 blockstate 与模型，并打印要同步到 Java 的常量。

用法:  python tools/gen_flight_chess.py

**棋盘形状不在这里写死** —— 布局在 tools/flight_layout.txt，改那个文件即可。

.. warning::

   **棋盘（{BOARD}）的 blockstate / 模型 / 物品模型 / 配方 / 语言条目都不归本脚本管了**，
   统一由 ``tools/gen_chessboard_resources.py`` 生成。原因是棋盘外观已经改成由方块实体上的
   动态皮肤决定，方块状态里不再有木种变体；如果在这里重新生成那 96 条 ``wood=`` 变体，
   会撞上一个已经不存在的属性，方块直接渲染成紫黑格。

   本脚本只负责：解析布局 → 校验 → 画纹理 → 棋子/图标/骰子的 blockstate 与模型
   → 打印要同步到 FlightChessLogic.java 的常量。

纹理画法要点（错了就会和棋子位置对不上）：
  模型 16px ↔ 纹理 TEX。棋盘顶面 up 面的 uv 是 [0,0,16,16]，MC 规定 up 面的
  u 轴对应 +X、v 轴对应 +Z，所以**行号在纹理上是倒着**的（行 0 在图像下方）。
  格点在模型空间是 (1 + step*c, 15 - step*r)，step = 14/(SIZE-1)。

  无框棋盘（board_frameless.json）把 uv 缩到 0.375..15.625、模型缩到 15×15，
  两者正好抵消 —— 所以同一张纹理在两种框型下格点位置完全一致，不用画两份。
"""
import hashlib
import json
import math
import os
import random
import shutil
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/chessboard"
MODID = "chessboard"
BOARD = "flight_chess_board"

# ── 棋盘几何 ──

TEX = 256            # 棋盘纹理边长
SS = 4               # 超采样倍数（PIL 的矩形不抗锯齿，先画大再缩）

# 四队颜色。这几个值是**从已发布的棋盘贴图里量出来的**（采样风车叶片内部，
# 四色各约 2800 像素、是图里第 3~6 多的纯色，可以确认就是填充色本身）——
# 这个常量一度从脚本里丢了，脚本因此跑不起来（NameError: TEAM_RGB）。
# 要改配色请连着贴图一起重新生成，别只改这里。
TEAM_RGB = {
    "red": (142, 33, 33),
    "yellow": (240, 175, 21),
    "blue": (44, 46, 143),
    "green": (94, 124, 22),
}

# 环路上的循环色序：**一格一色**，红→蓝→黄→绿 沿环路（顺时针）循环。
# 顺序不能随便定：四条跑道把环路分成 4 段各 13 格，13 % 4 == 1，所以色序必须跟着
# 四臂的顺时针顺序走（上红 → 右蓝 → 下黄 → 左绿），四个接口才都能接上本臂的颜色。
# 起点由 ring_start_index 锚在红色跑道外侧那一格上（见那里的说明）。
RING_COLORS = ["red", "blue", "yellow", "green"]

# ── 布局来自 tools/flight_layout.txt，改那个文件即可 ──

LAYOUT_FILE = Path(__file__).resolve().parent / "flight_layout.txt"

CHAR_RING = "#"
CHAR_CENTER = "o"
CHAR_DICE = "*"
CHAR_RUNWAY = {"^": "top", "v": "bottom", "<": "left", ">": "right"}
CHAR_HANGAR = {"G": "green", "R": "red", "B": "blue", "Y": "yellow"}

# 跑道颜色 —— 接到中央同色叶片上（和机库颜色对应）
RUNWAY_TEAM = {"top": "red", "right": "blue", "bottom": "yellow", "left": "green"}


# 环路的邻接是**八邻接**：拐角是斜角路，两格只对角相接也算连通。
RING_STEPS = [(dr, dc) for dr in (-1, 0, 1) for dc in (-1, 0, 1) if (dr, dc) != (0, 0)]


def _ring_neighbors(cell, cs):
    return [(cell[0] + dr, cell[1] + dc) for dr, dc in RING_STEPS
            if (cell[0] + dr, cell[1] + dc) in cs]


def _order_ring(cells):
    """把环路格子排成沿路走一圈的闭合顺序（决定色块从哪开始循环）。

    八邻接会带来「直边贴着拐角」的假邻居（比如 (0,5) 会斜看到 (1,4)），
    所以不能按度数判断，而是真走一圈：**优先走正交通路，没有正交才走斜角**。
    走完再检查是否恰好走遍所有格子 —— 这才真正证明它是单层闭合回路。
    """
    cs = set(cells)
    start = min(cs)
    order, prev, cur = [start], None, start
    while True:
        cands = [n for n in _ring_neighbors(cur, cs) if n != prev]
        if not cands:
            break
        orth = [n for n in cands if n[0] == cur[0] or n[1] == cur[1]]
        prev, cur = cur, (orth or cands)[0]
        if cur == start:
            break
        order.append(cur)
    return order


def load_layout():
    """读 tools/flight_layout.txt，解析出环路 / 跑道 / 机库 / 中央区，并做约束检查。"""
    rows = []
    with open(LAYOUT_FILE, encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\r\n")
            if not line.strip() or line.lstrip().startswith(";"):
                continue
            rows.append(line)
    if len(rows) != len(rows[0]) if rows else True:
        pass
    if not rows:
        raise SystemExit("布局文件是空的")
    size = len(rows)
    if size != len(rows[0]):
        raise SystemExit(f"布局必须是正方形：{size} 行 × {len(rows[0])} 列")
    for i, r in enumerate(rows):
        if len(r) != size:
            raise SystemExit(f"第 {i} 行有 {len(r)} 个字符，应为 {size} 个")

    ring, center, dice = [], [], []
    runways = {arm: [] for arm in RUNWAY_TEAM}
    hangars = {t: [] for t in CHAR_HANGAR.values()}
    for r, line in enumerate(rows):
        for c, ch in enumerate(line):
            if ch == CHAR_RING:
                ring.append((r, c))
            elif ch == CHAR_CENTER:
                center.append((r, c))
            elif ch == CHAR_DICE:
                dice.append((r, c))
            elif ch in CHAR_RUNWAY:
                runways[CHAR_RUNWAY[ch]].append((r, c))
            elif ch in CHAR_HANGAR:
                hangars[CHAR_HANGAR[ch]].append((r, c))
            elif ch != ".":
                raise SystemExit(f"({r},{c}) 有无法识别的字符 {ch!r}")

    # ── 约束检查 ──
    if len(dice) != 1:
        raise SystemExit(f"骰子格必须有且只有 1 个，现在有 {len(dice)} 个")
    if not center:
        raise SystemExit("没有中央终点区（o）")
    # 骰子格用的是 * 而不是 o，所以它不在 center 列表里 —— 检查它落在中央区正中即可
    rs_ = [r for r, _ in center]
    cs_ = [c for _, c in center]
    dr, dc = dice[0]
    if abs(dr - (min(rs_) + max(rs_)) / 2) > 0.5 or abs(dc - (min(cs_) + max(cs_)) / 2) > 0.5:
        raise SystemExit(f"骰子格 {dice[0]} 不在中央区正中"
                         f"（中央区行列范围 {min(rs_)}..{max(rs_)} / {min(cs_)}..{max(cs_)}）")
    center = center + dice        # 中央区含骰子格，画风车时要盖住它

    rs = set(ring)
    order = _order_ring(ring)
    if len(order) != len(ring) or len(set(order)) != len(ring):
        raise SystemExit(
            f"环路不是一条单层闭合回路：实际有 {len(ring)} 格，"
            f"但沿路只走了 {len(order)} 格（去重 {len(set(order))} 格）。"
            f"检查是不是有断开、分叉、或某处没接上")

    for team, cells in hangars.items():
        if len(cells) != 4:
            raise SystemExit(f"{team} 机库有 {len(cells)} 个停机位，应为 4 个")
        if rs & set(cells):
            raise SystemExit(f"{team} 机库和环路重叠：{sorted(rs & set(cells))}")
    for arm, cells in runways.items():
        if not cells:
            raise SystemExit(f"{arm} 跑道是空的")
        if rs & set(cells):
            raise SystemExit(f"{arm} 跑道和环路重叠：{sorted(rs & set(cells))}")
    for t, cells in hangars.items():
        for arm, rc in runways.items():
            if set(cells) & set(rc):
                raise SystemExit(f"{t} 机库和 {arm} 跑道重叠")
    return size, order, center, dice[0], runways, hangars


SIZE, RING, CENTER_CELLS, DICE_CELL, RUNWAYS, HANGAR_CELLS = load_layout()

# 每格 texel 数：格距 = gridSpan/(SIZE-1) 模型像素，再乘 TEX/16
CELL = (TEX / 16.0) * (14.0 / (SIZE - 1))


def ring_start_index(order):
    """环路色序从哪一格开始 —— 红色跑道最外侧那格的**正外侧**。

    这样红色车道一路延伸到环路上的那一格，和十字上方的红接得上。不能像原来那样
    直接拿 `_order_ring` 的第一格（它是行列扫描顺序里最小的格子，固定在左上角），
    否则色序的相位就跟着扫描顺序漂，看着像随便挑了个地方起头。
    """
    red_arm = next(arm for arm, team in RUNWAY_TEAM.items() if team == "red")
    cells = RUNWAYS[red_arm]
    cy = sum(r for r, _ in CENTER_CELLS) / len(CENTER_CELLS)
    cx = sum(c for _, c in CENTER_CELLS) / len(CENTER_CELLS)
    far = lambda rc: (rc[0] - cy) ** 2 + (rc[1] - cx) ** 2      # noqa: E731
    outer, inner = max(cells, key=far), min(cells, key=far)
    # 沿跑道轴向朝外走：这条臂只在一个方向上延伸，那一位就是轴向
    dr = (outer[0] > inner[0]) - (outer[0] < inner[0])
    dc = (outer[1] > inner[1]) - (outer[1] < inner[1])
    ring = set(RING)
    cur = outer
    for _ in range(len(RING)):
        cur = (cur[0] + dr, cur[1] + dc)
        if cur in ring:
            return order.index(cur)
    raise SystemExit(f"红色跑道最外侧 {outer} 再往外没走到环路格，色序起点定不下来")


# 外圈：一格一色，从红跑道外侧那格起沿环路（顺时针）红→蓝→黄→绿 循环
RING_PHASE = ring_start_index(RING)
RING_COLOR = {cell: RING_COLORS[(i - RING_PHASE) % len(RING_COLORS)]
              for i, cell in enumerate(RING)}


def runway_cells(arm):
    """某条臂的跑道格（来自布局文件里的 ^ v < >）"""
    return RUNWAYS[arm]


def cells_bbox(cells):
    """一组格子的包围盒（纹理 texel 坐标）"""
    boxes = [cell_box(r, c) for (r, c) in cells]
    return (min(b[0] for b in boxes), min(b[1] for b in boxes),
            max(b[2] for b in boxes), max(b[3] for b in boxes))


def span_pad(box, pad):
    """包围盒向外扩 pad"""
    return (box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad)


# ── 队伍 ──

TEAMS = ["red", "yellow", "blue", "green"]


# ── 纹理 ──

def texel(r, c):
    """格点 (row, col) → 纹理像素坐标（浮点）。

    模型 16px ↔ 纹理 TEX。格点在模型空间是 (1 + step*c, 15 - step*r)，
    其中 step = 14/(SIZE-1) 是格距 —— 跨度 14 像素要铺满 (SIZE-1) 个间隔。
    最后乘 TEX/16 换成纹理坐标。
    """
    k = TEX / 16.0
    step = 14.0 / (SIZE - 1)
    return (k * (1.0 + step * c), k * (15.0 - step * r))


def new_noise_base(size, rgb, seed, amp=7):
    """带轻微噪点的纯色底 —— 避免大片死平，接近 MC 材质的观感"""
    rnd = random.Random(seed)
    img = Image.new("RGB", (size, size), rgb)
    px = img.load()
    for y in range(size):
        for x in range(size):
            d = rnd.randint(-amp, amp)
            r, g, b = rgb
            px[x, y] = (max(0, min(255, r + d)),
                        max(0, min(255, g + d)),
                        max(0, min(255, b + d)))
    return img


def cell_box(r, c, shrink=0.0):
    """格子的像素包围盒（左上, 右下），shrink 为向内收缩量"""
    cx, cy = texel(r, c)
    h = CELL / 2 - shrink
    return (cx - h, cy - h, cx + h, cy + h)


# ── 棋盘贴图的保护闸门 ──
#
# 这张贴图已经被作者**手工重画**过，而且没有别的副本 —— 本脚本默认拒绝覆盖它。
# 判据：磁盘上的文件和 tools/flight_chess_board.sha256（脚本上次写出的指纹）一致，
# 才算「脚本自己的产物」，可以放心重写；不一致就跳过，只提醒。
# 确实要重新生成：加 --force-board，脚本会先把当前文件备份到 tools/backup/ 再写。
BOARD_TEX = ASSETS / "textures/block" / f"{BOARD}.png"
BOARD_TEX_SHA = ROOT / "tools" / f"{BOARD}.sha256"
BACKUP_DIR = ROOT / "tools" / "backup"


def save_board_texture(img):
    """写棋盘贴图；手工改过的版本默认不覆盖（理由见上）"""
    force = "--force-board" in sys.argv
    known = BOARD_TEX_SHA.read_text(encoding="utf-8").strip() if BOARD_TEX_SHA.exists() else ""
    cur = hashlib.sha256(BOARD_TEX.read_bytes()).hexdigest() if BOARD_TEX.exists() else ""
    if cur and cur != known:
        if not force:
            print(f"  !! 跳过 {BOARD_TEX.relative_to(ROOT)}：它的内容和本脚本上次写出的不一致，"
                  f"多半是手工画的。要覆盖请加 --force-board（会先备份到 tools/backup/）。")
            return
        BACKUP_DIR.mkdir(parents=True, exist_ok=True)
        bak = BACKUP_DIR / f"{BOARD}.{time.strftime('%Y%m%d-%H%M%S')}.png"
        shutil.copy2(BOARD_TEX, bak)
        print(f"  （覆盖前已把原文件备份到 {bak.relative_to(ROOT)}）")
    BOARD_TEX.parent.mkdir(parents=True, exist_ok=True)
    img.save(BOARD_TEX)
    BOARD_TEX_SHA.write_text(hashlib.sha256(BOARD_TEX.read_bytes()).hexdigest() + "\n", encoding="utf-8")
    print(f"  {BOARD_TEX.relative_to(ROOT)}")


def draw_board():
    """TEX×TEX 的棋盘纹理"""
    S = TEX * SS
    k = SS

    def box(r, c, shrink=0.0):
        x0, y0, x1, y1 = cell_box(r, c, shrink)
        return (x0 * k, y0 * k, x1 * k, y1 * k)

    def span_box(r0, c0, r1, c1):
        """覆盖 [r0..r1]×[c0..c1] 的包围盒。

        坑：行号增大时 texel_y 反而减小，所以 cell(r1,c1) 的「左上角」在数值上
        可能比 cell(r0,c0) 的左上角更小。必须把两个格子的**完整**范围并起来
        （四个边各自取 min/max），只取一个角会得到只有一格高的错箱子。"""
        ax0, ay0, ax1, ay1 = box(r0, c0)
        bx0, by0, bx1, by1 = box(r1, c1)
        return (min(ax0, bx0), min(ay0, by0), max(ax1, bx1), max(ay1, by1))

    def circle(r, c, rad, fill, outline, ow):
        cx, cy = texel(r, c)
        d.ellipse((cx * k - rad * k, cy * k - rad * k, cx * k + rad * k, cy * k + rad * k),
                  fill=fill, outline=outline, width=int(ow * k))

    # 底：白（参考图里十字外面是白的）
    img = Image.new("RGB", (S, S), (250, 249, 246))
    d = ImageDraw.Draw(img)

    def square(r, c, team):
        """整格铺色 —— 不留白缝，相邻同色自然连成一片"""
        d.rectangle(box(r, c), fill=TEAM_RGB[team])

    # ① 环路：沿布局文件标 # 的格子铺色（不勾边）
    for (r, c), team in RING_COLOR.items():
        square(r, c, team)

    # ② 四条跑道：从回路内侧往中央走，画成独立的队色车道
    runway = {}
    LANE_INSET = 2.2
    for arm, team in RUNWAY_TEAM.items():
        cells = runway_cells(arm)
        runway[arm] = cells
        bx0, by0, bx1, by1 = cells_bbox(cells)
        # 白色隔离带把车道和环路隔开
        d.rounded_rectangle([v * k for v in span_pad((bx0, by0, bx1, by1), LANE_INSET + 1.6)],
                            radius=4.5 * k, fill=(252, 251, 248))
        # 车道沿臂的方向收窄，两侧留出白缝
        if cells[0][1] == cells[-1][1]:      # 竖臂：左右各收 LANE_INSET
            lane = (bx0 + LANE_INSET, by0, bx1 - LANE_INSET, by1)
        else:                                 # 横臂：上下各收 LANE_INSET
            lane = (bx0, by0 + LANE_INSET, bx1, by1 - LANE_INSET)
        d.rounded_rectangle([v * k for v in lane], radius=3.0 * k,
                            fill=TEAM_RGB[team])

    # ③ 白圆点：棋子正好摆在圆点上
    for (r, c) in RING_COLOR:
        circle(r, c, CELL * 0.30, (252, 251, 248), (122, 114, 102), 0.9)
    for cs in runway.values():
        for (r, c) in cs:
            circle(r, c, CELL * 0.26, (252, 251, 248), (122, 114, 102), 0.7)

    # 每条臂的跑道中段画一个指向中央的箭头（dy 为正 = 朝图像下方）
    mid = {arm: cells[len(cells) // 2] for arm, cells in runway.items()}
    t, b = CELL * 0.30, CELL * 0.24      # 箭尖长度 / 尾半宽
    arrows = {
        "top": [(0, -t), (b, b), (-b, b)],       # 朝下
        "bottom": [(0, t), (b, -b), (-b, -b)],   # 朝上
        "left": [(t, 0), (-b, b), (-b, -b)],     # 朝右
        "right": [(-t, 0), (b, b), (b, -b)],     # 朝左
    }
    for arm, pts in arrows.items():
        cx, cy = texel(*mid[arm])
        # 深色箭头 + 白描边：车道上铺满了白圆点，纯白的箭头会糊成一片
        d.polygon([(cx * k + dx * k, cy * k + dy * k) for dx, dy in pts],
                  fill=(74, 66, 54), outline=(252, 251, 248), width=int(1.0 * k))

    # 中央 3×3：四片以中心为顶点的三角形拼成风车（X 形），叶片颜色各自接住对应的跑道
    c0x, c0y, c1x, c1y = [v * k for v in cells_bbox(CENTER_CELLS)]
    mx, my = (c0x + c1x) / 2, (c0y + c1y) / 2
    blades = [
        (RUNWAY_TEAM["top"], [(c0x, c0y), (c1x, c0y), (mx, my)]),      # 上
        (RUNWAY_TEAM["right"], [(c1x, c0y), (c1x, c1y), (mx, my)]),    # 右
        (RUNWAY_TEAM["bottom"], [(c1x, c1y), (c0x, c1y), (mx, my)]),   # 下
        (RUNWAY_TEAM["left"], [(c0x, c1y), (c0x, c0y), (mx, my)]),     # 左
    ]
    for team, pts in blades:
        d.polygon(pts, fill=TEAM_RGB[team])
    d.rectangle((c0x, c0y, c1x, c1y), outline=(228, 196, 104), width=int(2.2 * k))

    # 机库：队色停机坪（把 4 个停机位包进去）+ 4 个白圈，飞机棋子就摆在这 4 个点上
    for team, slots in HANGAR_CELLS.items():
        d.rounded_rectangle([v * k for v in span_pad(cells_bbox(slots), CELL * 0.9)],
                            radius=4.0 * k, fill=TEAM_RGB[team])
        for (r, c) in slots:
            circle(r, c, CELL * 0.40, (252, 251, 248), (168, 162, 152), 0.8)

    # 骰子底盘（骰子模型盖在上面，转起来时有层次）
    dr, dc = DICE_CELL
    circle(dr, dc, CELL * 0.46, (250, 249, 245), (168, 162, 152), 0.8)

    img = img.resize((TEX, TEX), Image.LANCZOS)
    save_board_texture(img)


def draw_icon():
    """32×32 飞机图标（透明底，白色机身 + 深色描边，压在任意混凝土色上都看得清）"""
    N = 32
    S = N * 8
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    def poly(pts, fill, outline=None, w=0):
        d.polygon([(x * S / N, y * S / N) for (x, y) in pts], fill=fill, outline=outline)

    BODY = (247, 247, 252, 255)
    EDGE = (38, 38, 48, 255)

    # 俯视飞机，机头朝上（-Z 方向）。绘制顺序：尾翼 → 主翼 → 机身，机身压在最上层
    # 水平尾翼
    poly([(16, 25.5), (10.5, 21.5), (16, 18.5), (21.5, 21.5)], BODY)
    # 主翼（后掠）
    poly([(16, 18.5), (1.5, 14.0), (1.5, 11.0), (16, 13.5),
          (30.5, 11.0), (30.5, 14.0)], BODY)
    # 机身：机头收尖，尾部略窄
    poly([(16, 1.5), (19.2, 9.0), (19.2, 26.0), (16, 30.5),
          (12.8, 26.0), (12.8, 9.0)], BODY)
    # 垂直尾翼（俯视是一条窄条，画在机身之上更清楚）
    poly([(16, 23.0), (14.4, 27.5), (17.6, 27.5)], BODY)

    # 描边：把 alpha 外扩一圈作轮廓。MaxFilter 必须是奇数，
    # 7 在 8 倍超采样下约等于最终 0.9 像素，再大就会把机身吃细。
    alpha = img.split()[3]
    grown = alpha.filter(ImageFilter.MaxFilter(7))
    edge_layer = Image.new("RGBA", (S, S), EDGE)
    edge_layer.putalpha(grown)
    img = Image.alpha_composite(edge_layer, img)

    img = img.resize((N, N), Image.LANCZOS)
    out = ASSETS / "textures/block/flight_icon.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print(f"  {out.relative_to(ROOT)}")


# 骰子图集：6 个 16×16 的 tile，tile(i) = 点数 i+1
#
# 图集必须是 64×64 而不是 96×16！MC 方块模型的 uv 是 **0..16 归一化空间**，
# 不是纹理像素空间 —— 直接写像素坐标会被再乘一次 (纹理宽/16)，越界后
# 「Cannot compute translucency out of bounds」导致整个模型烘焙失败，
# 渲染成 missing model 的紫黑整方块（而且它是 0..16 的方块，会被 pivot 推出中心）。
# 用 64×64、每格 16×16 时归一化 uv 正好是 4 的整数倍，没有换算误差。
DICE_TILE = 16
DICE_COLS = 3                     # 6 个面按 3×2 摆放
DICE_ROWS = 2
# 画布必须是 4 格宽（64）而不是 3 格宽（48）：这样每格占归一化 u 的 16/64*16 = 4，
# 是整数。若用 48 宽，每格是 16/3 ≈ 5.333，UV 会带上除不尽的误差。
DICE_TEX = DICE_TILE * 4          # 64


def dice_tile_uv(face):
    """点数 face(1..6) → 归一化 UV（0..16 空间）"""
    i = face - 1
    col, row = i % DICE_COLS, i // DICE_COLS
    return [4.0 * col, 4.0 * row, 4.0 * col + 4, 4.0 * row + 4]

# 标准骰子朝向：up 面为 N 时，其余四面的点数（对面点数之和为 7）
DICE_SIDES = {
    1: (2, 3, 5, 4),   # north, east, south, west
    2: (6, 3, 1, 4),
    3: (2, 6, 5, 1),
    4: (2, 1, 5, 6),
    5: (1, 3, 6, 4),
    6: (5, 3, 2, 4),
}
# pips 在 16×16 tile 里的相对位置（0..1）
PIP_LAYOUT = {
    1: [(0.5, 0.5)],
    2: [(0.28, 0.28), (0.72, 0.72)],
    3: [(0.24, 0.24), (0.5, 0.5), (0.76, 0.76)],
    4: [(0.28, 0.28), (0.72, 0.28), (0.28, 0.72), (0.72, 0.72)],
    5: [(0.26, 0.26), (0.74, 0.26), (0.5, 0.5), (0.26, 0.74), (0.74, 0.74)],
    6: [(0.28, 0.22), (0.72, 0.22), (0.28, 0.5), (0.72, 0.5), (0.28, 0.78), (0.72, 0.78)],
}


def draw_dice():
    """64×64 骰子图集：3×2 排布的 6 个 16×16 面，tile(i) 是点数 i+1"""
    N = DICE_TILE
    S = N * 8  # 每个 tile 的绘制分辨率
    img = Image.new("RGBA", (DICE_TEX * 8, DICE_TEX * 8), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    BODY = (243, 240, 231, 255)
    EDGE = (44, 40, 36, 255)
    PIP = (40, 36, 34, 255)

    for face in range(1, 7):
        i = face - 1
        col, row = i % DICE_COLS, i // DICE_COLS
        ox, oy = col * S, row * S
        # 面底 + 圆角描边
        d.rounded_rectangle((ox + 1, oy + 1, ox + S - 1, oy + S - 1), radius=S * 0.14,
                            fill=BODY, outline=EDGE, width=int(S * 0.045))
        # 点数
        pr = S * 0.075
        for (fx, fy) in PIP_LAYOUT[face]:
            cx, cy = ox + fx * S, oy + fy * S
            d.ellipse((cx - pr, cy - pr, cx + pr, cy + pr), fill=PIP)

    img = img.resize((DICE_TEX, DICE_TEX), Image.LANCZOS)
    out = ASSETS / "textures/block/flight_dice.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print(f"  {out.relative_to(ROOT)}")


# ── JSON 资源 ──

def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def gen_piece_and_dice_assets():
    """棋子（4 队）、飞机图标、骰子（6 面）的 blockstate 与模型"""
    # 棋子
    piece_variants = {}
    for team in TEAMS:
        write_json(ASSETS / f"models/block/flight_piece_{team}.json", {
            "parent": f"{MODID}:block/piece_flat",
            "textures": {"1": f"minecraft:block/{team}_concrete"},
        })
        piece_variants[f"team={team}"] = {"model": f"{MODID}:block/flight_piece_{team}"}
    write_json(ASSETS / "blockstates/flight_piece.json", {"variants": piece_variants})

    # 飞机图标层（照抄 piece_char_template 的几何）
    write_json(ASSETS / "models/block/flight_icon.json", {
        "format_version": "1.21.11",
        "textures": {
            "0": f"{MODID}:block/flight_icon",
            "particle": f"{MODID}:block/flight_icon",
        },
        "elements": [{
            "from": [0.5, 1, 0.5],
            "to": [4.5, 1.05, 4.5],
            "faces": {"up": {"uv": [0, 0, 16, 16], "texture": "#0"}},
        }],
    })
    write_json(ASSETS / "blockstates/flight_icon.json",
               {"variants": {"": {"model": f"{MODID}:block/flight_icon"}}})

    # 骰子：故意做成**满方块**（0..16），几何中心就是方块正中 (0.5, 0.5, 0.5)。
    # 原来画的是角落里 4 单位见方的小立方体（中心 (2.5, 2, 2.5)），旋转轴心得靠好几个
    # 换算过的常数拼出来，错一个就变成绕骰子外面的点公转；满方块让轴心三个轴都是 0.5。
    # 尺寸靠 ChessboardPieceGeometry 里的 DICE_MODEL_SCALE（4/16）缩回去，视觉大小不变；
    # 面 UV 不用动 —— 4×4 的贴图块贴到 16 单位的大面上，再整体缩到 1/4，像素密度一样。
    dice_variants = {}
    for face in range(1, 7):
        north, east, south, west = DICE_SIDES[face]
        down = 7 - face

        uv = dice_tile_uv  # 归一化 0..16 空间，切勿写像素坐标

        write_json(ASSETS / f"models/block/flight_dice_{face}.json", {
            "textures": {
                "dice": f"{MODID}:block/flight_dice",
                "particle": f"{MODID}:block/flight_dice",
            },
            "elements": [{
                "from": [0, 0, 0],
                "to": [16, 16, 16],
                "faces": {
                    "up":    {"uv": uv(face),  "texture": "#dice"},
                    "down":  {"uv": uv(down),  "texture": "#dice"},
                    "north": {"uv": uv(north), "texture": "#dice"},
                    "south": {"uv": uv(south), "texture": "#dice"},
                    "east":  {"uv": uv(east),  "texture": "#dice"},
                    "west":  {"uv": uv(west),  "texture": "#dice"},
                },
            }],
        })
        dice_variants[f"face={face}"] = {"model": f"{MODID}:block/flight_dice_{face}"}
    write_json(ASSETS / "blockstates/flight_dice.json", {"variants": dice_variants})

    print("  blockstates/flight_piece.json (4) + models/block/flight_piece_*.json (4)")
    print("  blockstates/flight_icon.json + models/block/flight_icon.json")
    print("  blockstates/flight_dice.json (6) + models/block/flight_dice_*.json (6)")


def gen_lang():
    """往现有语言文件里补飞行棋的方块名（变体名归 gen_chessboard_resources.py 管）"""
    for lang, name in (("en_us", "Aeroplane Chess"), ("zh_cn", "飞行棋")):
        path = ASSETS / f"lang/{lang}.json"
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        data[f"block.{MODID}.{BOARD}"] = name
        write_json(path, data)
        print(f"  lang/{lang}.json (block name)")


def print_java_sync():
    """打印需要同步到 FlightChessLogic.java 的常量。改了布局就照这个改 Java。"""
    dr, dc = DICE_CELL
    print()
    print("=" * 62)
    print("请把下面这些同步到 FlightChessLogic.java：")
    print("=" * 62)
    print(f"  private static final int SIZE = {SIZE};")
    print(f"  DICE_CELL = {dr * SIZE + dc}   // 骰子格 ({dr}, {dc})")
    print()
    print("  initBoard 里的机库（参数：row0, col0, 队号）：")
    order = ["red", "yellow", "blue", "green"]
    for team in order:
        rr = min(r for r, _ in HANGAR_CELLS[team])
        cc = min(c for _, c in HANGAR_CELLS[team])
        print(f"      hangar(p, {rr}, {cc}, {order.index(team)});   // {team}")
    print()
    cap = 14.0 / (SIZE - 1) / 5
    print(f"  pieceScale 必须 ≤ {cap:.3f}（格距只有 {14.0 / (SIZE - 1):.2f} 像素，"
          f"棋子模型宽 5 单位）")
    print("=" * 62)


if __name__ == "__main__":
    print("纹理:")
    draw_board()
    draw_icon()
    draw_dice()
    print("资源（棋子 / 图标 / 骰子；棋盘 JSON 归 gen_chessboard_resources.py）:")
    gen_piece_and_dice_assets()
    gen_lang()
    print_java_sync()
    print("完成（记得也跑一次 tools/gen_chessboard_resources.py）")
