#!/usr/bin/env python3
"""把棋子模型的「碎块雕塑」重做成同精度的体素网格 —— 砍面数，救 26.3 的区块顶点上限。

背景（数字都是实测的，见 ChessboardSectionGeometry.PIECE_QUAD_BUDGET 的注释）：
  MC 26.3 把区块顶点上传换成了 UberGpuBuffer + StagingBuffer，单次追加超过 98 MiB 直接崩游戏。
  一块满员国际象棋原本 ~4124 个四边形，一个区块里放 150 块棋盘就爆了。

为什么必须「重做」而不是「优化」：试过两条零外观代价的路，都没用 ——
  * 隐藏面剔除（被别的元素挡住的面）：只能砍 14%（马 240->203）。
    因为棋子是雕刻式的独立凸起，不是实心堆叠。
  * 同平面相邻面合并：只砍 1%（798->796）。这些面根本不相邻。
剩下唯一的路就是降低表面细节 —— 也就是这里做的：把形状按固定格子量化，
再把同方向的相邻面贪心合并成矩形。格子取 0.25 模型单位（= 1 个贴图像素，
因为棋子渲染时会整体缩放），所以丢掉的基本是贴图分辨率以下的细节。

精度怎么选：**每种棋子单独挑，目标是「谁也不超过约 2x」**。
一开始统一用 0.25，结果马被削掉 4.9 倍，看上去很抽象（马本来就最重、最吃细节）。
所以按下面的 PRESET 一件一件挑 —— 面数随精度不是单调的（体素格和形状特征对不齐），
这几个数是 --dry-run 量出来再挑的，别凭直觉改。

用法：
  python tools/simplify_piece_models.py --dry-run chess_horse            # 只报数，不写文件
  python tools/simplify_piece_models.py --preset                         # 按 PRESET 做全套（推荐）
  python tools/simplify_piece_models.py --res 0.25 chess_horse chess_horse_white
  python tools/simplify_piece_models.py --all                            # 全部含 elements 的模型

改坏了用 git 还原：git checkout -- src/main/resources/assets/chessboard/models/block
"""

import argparse
import collections
import json
import math
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODELS = ROOT / "src/main/resources/assets/chessboard/models/block"

EPS = 1e-9

# 每个体素在 UV 上占的跨度。0.05 ≈ 原模型的密度：原模型一个 1.6 宽的面的 UV 跨度约 0.5，
# 折成体素是 0.06 左右。按面的大小直接算跨度会把整张木纹拉过整个面（看着像糊了）。
UV_SPAN_PER_UNIT = 0.05

# 每种棋子的量化精度（模型单位）。右侧注释是实测的 原始面数 -> 新面数。
# 统一 0.25 会把马削成 4.9x（抽象），所以这里按「不超过约 2x」逐个挑。
PRESET = {
    "chess_horse": 0.20,     # 240 -> 121  (2.0x)
    "chess_wang": 0.15,      # 228 -> 145  (1.6x)
    "chess_queen": 0.20,     # 138 ->  98  (1.4x)
    "chess_elephant": 0.20,  # 138 ->  74  (1.9x)
    "chess_car": 0.25,       # 102 ->  76  (1.3x)
    "chess_soldier": 0.25,   #  90 ->  73  (1.2x)
}

# 六个面：名字 -> (法线轴, 步进方向)
FACE_DIRS = {
    "north": (2, -1), "south": (2, +1),
    "west": (0, -1), "east": (0, +1),
    "down": (1, -1), "up": (1, +1),
}


def axis_aligned(element):
    """元素是否轴对齐。0 度旋转是恒等变换，算轴对齐（这批模型 330 个元素里 316 个是 y/0）。"""
    rot = element.get("rotation")
    if rot is None:
        return True
    axis, angle = rot.get("axis"), rot.get("angle")
    if axis is None or angle is None:
        return True
    return abs(float(angle)) < EPS


def voxelize(elements, res):
    """把元素的并集量化到边长 res 的格子上，返回 (grid, origin, dims)。"""
    xs = [e["from"][0] for e in elements] + [e["to"][0] for e in elements]
    ys = [e["from"][1] for e in elements] + [e["to"][1] for e in elements]
    zs = [e["from"][2] for e in elements] + [e["to"][2] for e in elements]
    ox, oy, oz = min(xs), min(ys), min(zs)
    dims = (math.ceil((max(xs) - ox) / res), math.ceil((max(ys) - oy) / res), math.ceil((max(zs) - oz) / res))
    grid = [[[False] * dims[2] for _ in range(dims[1])] for _ in range(dims[0])]
    for e in elements:
        if not axis_aligned(e):
            raise SystemExit(f"元素带非 0 度旋转，这套量化不适用：{e.get('rotation')}")
        f, t = e["from"], e["to"]
        for i in range(dims[0]):
            cx = ox + (i + 0.5) * res
            if not f[0] - EPS <= cx <= t[0] + EPS:
                continue
            for j in range(dims[1]):
                cy = oy + (j + 0.5) * res
                if not f[1] - EPS <= cy <= t[1] + EPS:
                    continue
                for k in range(dims[2]):
                    cz = oz + (k + 0.5) * res
                    if f[2] - EPS <= cz <= t[2] + EPS:
                        grid[i][j][k] = True
    return grid, (ox, oy, oz), dims


def surface_rects(grid, dims):
    """抽出表面，按 (方向, 平面) 分组后贪心合并成矩形。

    返回 {方向: [(plane_index, u0, v0, u_count, v_count), ...]}
    """
    out = {}
    for name, (axis, step) in FACE_DIRS.items():
        ua, ub = (axis + 1) % 3, (axis + 2) % 3
        rects = []
        for s in range(dims[axis]):
            occupied = set()
            for i in range(dims[ua]):
                for j in range(dims[ub]):
                    c = [0, 0, 0]
                    c[axis], c[ua], c[ub] = s, i, j
                    if not grid[c[0]][c[1]][c[2]]:
                        continue
                    n = list(c)
                    n[axis] = s + step
                    if 0 <= n[axis] < dims[axis] and grid[n[0]][n[1]][n[2]]:
                        continue  # 邻居也是实心 -> 这个面在里面，看不见
                    occupied.add((i, j))
            # 贪心：先横向拉满，再整行向下拉
            free = set(occupied)
            for (i, j) in sorted(occupied):
                if (i, j) not in free:
                    continue
                w = 1
                while (i + w, j) in free:
                    w += 1
                h = 1
                while all((i + dx, j + h) in free for dx in range(w)):
                    h += 1
                for dx in range(w):
                    for dy in range(h):
                        free.discard((i + dx, j + dy))
                rects.append((s, i, j, w, h))
        out[name] = rects
    return out


def texture_key(model):
    """模型所有面引用的贴图变量名。

    <b>不能写死 "#0"</b>：这批模型里 `chess_elephant` / `chess_queen` 的 `textures` 用的是
    `"1"`、面引用 `#1`。写死 `#0` 的话这两个模型解析不到贴图，游戏里渲染成缺贴图的紫黑
    （2026-09-26 实际踩到过）。混用多个变量名的模型本脚本处理不了，直接报错而不是悄悄画错。
    """
    keys = collections.Counter()
    for e in model.get("elements") or []:
        for fv in (e.get("faces") or {}).values():
            keys[fv.get("texture")] += 1
    if not keys:
        return None
    if len(keys) > 1:
        raise SystemExit(f"模型混用了多个贴图变量 {dict(keys)}，体素化会丢掉区分，本脚本不适用")
    return next(iter(keys))


def rects_to_elements(rects, origin, dims, res, tex_key):
    """矩形 -> 方块元素（沿法线方向厚 res）。

    UV 分两部分，别搞混：
      * <b>位置</b>用 {@code 16/max(dims)} 把整块模型摊进 0..16 —— 让不同高度的面取到木纹的
        不同段落，和原模型一样有深浅变化；跨度必须留在 0..16 内，超了会绕到图集别的地方。
      * <b>跨度</b>用一个固定的小系数（见 {@link UV_SPAN_PER_UNIT}）。这里<b>不能</b>按面的大小算：
        一个 14 体素宽的面会把整张木纹横着拉过去，看上去就是「糊了/条纹全被拉直」——原模型的
        跨度只有 0.25~0.5（几乎是个均匀色块），按它的密度折过来就是这个数量级。
    """
    elements = []
    uv_scale = 16.0 / max(dims)
    for name, (axis, step) in FACE_DIRS.items():
        ua, ub = (axis + 1) % 3, (axis + 2) % 3
        for (s, i, j, w, h) in rects[name]:
            lo = [0.0, 0.0, 0.0]
            hi = [0.0, 0.0, 0.0]
            lo[axis] = origin[axis] + s * res
            hi[axis] = lo[axis] + res
            lo[ua] = origin[ua] + i * res
            hi[ua] = lo[ua] + w * res
            lo[ub] = origin[ub] + j * res
            hi[ub] = lo[ub] + h * res
            # 只保留朝外那一个面：另外五个面要么被邻居盖住、要么是 1 体素宽的边缘，
            # 留白反而会在轮廓上透光。原版模型也是这么省的。
            u0, v0 = i * uv_scale, j * uv_scale
            u1, v1 = u0 + w * UV_SPAN_PER_UNIT, v0 + h * UV_SPAN_PER_UNIT
            elements.append({
                "from": [round(v, 4) for v in lo],
                "to": [round(v, 4) for v in hi],
                "faces": {name: {"uv": [round(u0, 4), round(v0, 4), round(u1, 4), round(v1, 4)],
                                 "texture": tex_key}},
            })
    return elements


def simplify(path, res, dry_run):
    model = json.loads(path.read_text(encoding="utf-8"))
    elements = model.get("elements") or []
    if not elements:
        return None
    before = sum(len(e.get("faces") or {}) for e in elements)
    grid, origin, dims = voxelize(elements, res)
    rects = surface_rects(grid, dims)
    key = texture_key(model)
    if key is None:
        return None
    new_elements = rects_to_elements(rects, origin, dims, res, key)
    after = sum(len(e.get("faces") or {}) for e in new_elements)

    if not dry_run:
        model["elements"] = new_elements
        path.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return before, after, len(elements), len(new_elements), dims


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="*", help="模型名（不含 .json），如 chess_horse")
    ap.add_argument("--all", action="store_true", help="处理全部含 elements 的模型")
    ap.add_argument("--preset", action="store_true", help="按 PRESET 逐件挑精度做全套棋子（黑白两版）")
    ap.add_argument("--res", type=float, default=0.25, help="量化精度，模型单位（默认 0.25 = 1 贴图像素）")
    ap.add_argument("--dry-run", action="store_true", help="只报数，不写文件")
    args = ap.parse_args()

    jobs = []  # (路径, 精度)
    if args.preset:
        for name, res in PRESET.items():
            jobs.append((MODELS / f"{name}.json", res))
            jobs.append((MODELS / f"{name}_white.json", res))
    elif args.all:
        jobs = [(p, args.res) for p in sorted(MODELS.glob("*.json"))
                if json.loads(p.read_text(encoding="utf-8")).get("elements")]
    else:
        if not args.models:
            ap.error("要么给模型名，要么用 --preset / --all")
        jobs = [(MODELS / f"{n}.json", args.res) for n in args.models]

    tb = ta = 0
    for p, res in jobs:
        r = simplify(p, res, args.dry_run)
        if r is None:
            print(f"  {p.name:32s} 没有 elements，跳过")
            continue
        before, after, nel, nnew, dims = r
        tb += before
        ta += after
        tag = "（未写入）" if args.dry_run else ""
        print(f"  {p.name:28s} res={res:<5} {before:5d} -> {after:5d} 面  ({before/after:4.2f}x, "
              f"元素 {nel} -> {nnew}, 格 {dims[0]}x{dims[1]}x{dims[2]}) {tag}")
    if tb:
        print(f"\n合计 {tb} -> {ta} 面 ({tb/ta:.2f}x), {len(jobs)} 个文件")


if __name__ == "__main__":
    main()
