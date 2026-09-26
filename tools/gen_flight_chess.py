"""生成飞行棋的全部资源：纹理 + blockstate + 模型 + 物品模型 + 配方 + 语言条目。

用法:  python tools/gen_flight_chess.py

**棋盘形状不在这里写死** —— 布局在 tools/flight_layout.txt，改那个文件即可。
本脚本负责：解析布局 → 校验 → 画纹理 → 生成全部 json → 打印要同步到
FlightChessLogic.java 的常量。

纹理画法要点（错了就会和棋子位置对不上）：
  模型 16px ↔ 纹理 TEX。棋盘顶面 up 面的 uv 是 [0,0,16,16]，MC 规定 up 面的
  u 轴对应 +X、v 轴对应 +Z，所以**行号在纹理上是倒着**的（行 0 在图像下方）。
  格点在模型空间是 (1 + step*c, 15 - step*r)，step = 14/(SIZE-1)。

  无框棋盘（board_frameless.json）把 uv 缩到 0.375..15.625、模型缩到 15×15，
  两者正好抵消 —— 所以同一张纹理在两种框型下格点位置完全一致，不用画两份。
"""
import json
import math
import os
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/chessboard"
DATA = ROOT / "src/main/resources/data/chessboard"
MODID = "chessboard"
BOARD = "flight_chess_board"

# ── 棋盘几何 ──

TEX = 256            # 棋盘纹理边长
SS = 4               # 超采样倍数（PIL 的矩形不抗锯齿，先画大再缩）

# 环路上的循环色序（每 2 格一换，铺出成块的彩色跑道）
RING_COLORS = ["green", "red", "blue", "yellow"]

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

RING_COLOR = {cell: RING_COLORS[(i // 2) % 4] for i, cell in enumerate(RING)}


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
TEAM_ZH = {"red": "红队", "yellow": "黄队", "blue": "蓝队", "green": "绿队"}
TEAM_EN = {"red": "Red", "yellow": "Yellow", "blue": "Blue", "green": "Green"}
# 取原版混凝土的近似色
TEAM_RGB = {
    "red": (142, 33, 33),
    "yellow": (240, 175, 21),
    "blue": (44, 46, 143),
    "green": (94, 124, 22),
}

WOODS = [
    ("oak", ["minecraft:stripped_oak_log", "minecraft:stripped_oak_wood"]),
    ("spruce", ["minecraft:stripped_spruce_log", "minecraft:stripped_spruce_wood"]),
    ("birch", ["minecraft:stripped_birch_log", "minecraft:stripped_birch_wood"]),
    ("acacia", ["minecraft:stripped_acacia_log", "minecraft:stripped_acacia_wood"]),
    ("dark_oak", ["minecraft:stripped_dark_oak_log", "minecraft:stripped_dark_oak_wood"]),
    ("cherry", ["minecraft:stripped_cherry_log", "minecraft:stripped_cherry_wood"]),
    ("pale_oak", ["minecraft:stripped_pale_oak_log", "minecraft:stripped_pale_oak_wood"]),
    ("polished_granite", ["minecraft:polished_granite"]),
    ("polished_diorite", ["minecraft:polished_diorite"]),
    ("polished_andesite", ["minecraft:polished_andesite"]),
    ("polished_deepslate", ["minecraft:polished_deepslate"]),
    ("polished_blackstone", ["minecraft:polished_blackstone"]),
]
WOOD_ZH = {
    "oak": "橡木", "spruce": "云杉木", "birch": "白桦木", "acacia": "金合欢木",
    "dark_oak": "深色橡木", "cherry": "樱花木", "pale_oak": "苍白橡木",
    "polished_granite": "磨制花岗岩", "polished_diorite": "磨制闪长岩",
    "polished_andesite": "磨制安山岩", "polished_deepslate": "磨制深板岩",
    "polished_blackstone": "磨制黑石",
}
WOOD_EN = {
    "oak": "Oak", "spruce": "Spruce", "birch": "Birch", "acacia": "Acacia",
    "dark_oak": "Dark Oak", "cherry": "Cherry", "pale_oak": "Pale Oak",
    "polished_granite": "Polished Granite", "polished_diorite": "Polished Diorite",
    "polished_andesite": "Polished Andesite", "polished_deepslate": "Polished Deepslate",
    "polished_blackstone": "Polished Blackstone",
}


def variant_index(wood_i, frameless):
    """与 ChessboardMod.variantIndex 一致：wood.ordinal()*2 + frameless"""
    return wood_i * 2 + (1 if frameless else 0)


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


def draw_board():
    """128×128 棋盘纹理"""
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
        """整格铺色 —— 不留白缝，同色才能连成一片（参考图就是成块的色块）"""
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
    out = ASSETS / "textures/block" / f"{BOARD}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print(f"  {out.relative_to(ROOT)}")


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


def gen_blockstate_and_models():
    """棋盘：96 variants blockstate + 24 个模型"""
    variants = {}
    for wi, (wood, _) in enumerate(WOODS):
        for frameless in (False, True):
            suffix = f"{wood}_frameless" if frameless else wood
            model_name = f"{BOARD}_{suffix}"
            # 非橡木的 base 模型（橡木走 BOARD.json 那个不带后缀的）
            write_json(ASSETS / f"models/block/{model_name}.json", {
                "parent": f"{MODID}:block/{'board_frameless' if frameless else 'board'}",
                "textures": {
                    "0": f"{MODID}:block/{BOARD}",
                    "2": _wood_texture(wood),
                },
            })
            for facing, y in (("south", 0), ("west", 90), ("north", 180), ("east", 270)):
                variants[f"facing={facing},wood={wood},frameless={'true' if frameless else 'false'}"] = {
                    "model": f"{MODID}:block/{model_name}",
                    "y": y,
                }
    write_json(ASSETS / f"blockstates/{BOARD}.json", {"variants": variants})
    print(f"  blockstates/{BOARD}.json ({len(variants)} variants)")
    print(f"  models/block/{BOARD}_*.json (24)")


def _wood_texture(wood):
    if wood.startswith("polished_"):
        return f"minecraft:block/{wood}"
    return f"minecraft:block/stripped_{wood}_log"


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

    # 骰子：立方体占 y 0..4，x/z 0.5..4.5（几何中心 (2.5, 2, 2.5)）
    # 与 ChessboardRenderer.DICE_CENTER_Y / 默认 pieceCenterX,Z 对应
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
                "from": [0.5, 0, 0.5],
                "to": [4.5, 4, 4.5],
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


def gen_item_model():
    """物品模型：按 custom_model_data 0..23 挑棋盘变体模型"""
    entries = []
    for wi, (wood, _) in enumerate(WOODS):
        for frameless in (False, True):
            if wood == "oak" and not frameless:
                continue  # 0 档就是基础物品
            suffix = f"{wood}_frameless" if frameless else wood
            entries.append({
                "threshold": float(variant_index(wi, frameless)),
                "model": {"type": "minecraft:model", "model": f"{MODID}:block/{BOARD}_{suffix}"},
            })
    # range_dispatch 要求按 threshold 升序
    entries.sort(key=lambda e: e["threshold"])
    entries.insert(0, {
        "threshold": 0.0,
        "model": {"type": "minecraft:model", "model": f"{MODID}:block/{BOARD}_oak"},
    })
    write_json(ASSETS / f"items/{BOARD}.json", {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "entries": entries,
            "fallback": {"type": "minecraft:model", "model": f"{MODID}:block/{BOARD}_oak"},
        },
    })
    print(f"  items/{BOARD}.json ({len(entries)} entries)")


def gen_recipes():
    """24 个切石配方：基础（橡木带框）+ 11 非橡木带框 + 12 无框"""
    n = 0
    # 基础：橡木带框，无 components
    write_json(DATA / f"recipe/{BOARD}.json", {
        "type": "minecraft:stonecutting",
        "ingredient": ["minecraft:stripped_oak_log", "minecraft:stripped_oak_wood"],
        "result": {"id": f"{MODID}:{BOARD}"},
    })
    n += 1

    for wi, (wood, ingredients) in enumerate(WOODS):
        for frameless in (False, True):
            if wood == "oak" and not frameless:
                continue
            suffix = f"{wood}_frameless" if frameless else wood
            write_json(DATA / f"recipe/{BOARD}_{suffix}.json", {
                "type": "minecraft:stonecutting",
                "ingredient": ingredients,
                "result": {
                    "id": f"{MODID}:{BOARD}",
                    "components": {
                        "minecraft:block_state": {
                            "wood": wood,
                            "frameless": "true" if frameless else "false",
                        },
                        "minecraft:custom_model_data": {
                            "floats": [float(variant_index(wi, frameless))],
                            "flags": [], "strings": [], "colors": [],
                        },
                        "minecraft:custom_name": {
                            "translate": f"item.{MODID}.variant.{BOARD}_{suffix}"
                        },
                    },
                },
            })
            n += 1
    print(f"  recipe/{BOARD}*.json ({n})")


def gen_lang():
    """往现有语言文件里补飞行棋的方块名与 24 个变体名"""
    for lang, names in (("en_us", WOOD_EN), ("zh_cn", WOOD_ZH)):
        path = ASSETS / f"lang/{lang}.json"
        with open(path, encoding="utf-8") as f:
            data = json.load(f)

        data[f"block.{MODID}.{BOARD}"] = "Aeroplane Chess" if lang == "en_us" else "飞行棋"
        for wood, _ in WOODS:
            for frameless in (False, True):
                if wood == "oak" and not frameless:
                    continue
                suffix = f"{wood}_frameless" if frameless else wood
                key = f"item.{MODID}.variant.{BOARD}_{suffix}"
                if lang == "en_us":
                    data[key] = f"{'Frameless ' if frameless else ''}{names[wood]} Aeroplane Chess"
                else:
                    data[key] = f"{'无框' if frameless else ''}{names[wood]}飞行棋"

        write_json(path, data)
        print(f"  lang/{lang}.json (+{1 + 23} keys)")


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
    print("资源:")
    gen_blockstate_and_models()
    gen_piece_and_dice_assets()
    gen_item_model()
    gen_recipes()
    gen_lang()
    print_java_sync()
    print("完成")
