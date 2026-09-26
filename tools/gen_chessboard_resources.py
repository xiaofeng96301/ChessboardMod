"""棋盘与棋子的 JSON 资源生成器 —— 这几类资源的**唯一真相**。

用法:
    python tools/gen_chessboard_resources.py            # 写入
    python tools/gen_chessboard_resources.py --check    # 只校验，不改文件（CI / 提交前用）

背景
----
老的「木种」外观系统给每个棋盘生成了 12 木种 × 有框/无框 = 24 个模型，5 个棋盘共 120 个，
棋子再按木种各来一套，加起来 318 个人造模型文件。外观现在改由方块实体上的**动态皮肤**
决定（见 `SkinData`），方块状态里不再有 `wood` / `material` 属性，所以这些变体全部删除：

  * 每个棋盘只剩 2 个模型：`<board>.json` 和 `<board>_frameless.json`
  * 棋盘 blockstate 只剩 `facing × frameless` = 8 条
  * 棋子 blockstate 变成无属性的单变体
  * 物品模型改用 `minecraft:select` + `block_state_property: "frameless"`（按语义选，不看 CMD 数值）
  * 每个棋盘只剩 2 个切石配方（带框 / 无框），ingredient 一次列全所有可用的原木与木头

.. warning::

   棋盘 blockstate 里**绝对不能**再出现 `wood=`。已删掉该属性后，
   `VariantSelector.predicate` 会对未知属性名直接抛 `Unknown blockstate property`，
   该变体会被静默跳过，方块渲染成紫黑格。`--check` 会断言这一点。
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/chessboard"
DATA = ROOT / "src/main/resources/data/chessboard"
MODID = "chessboard"

# ── 棋盘：注册名 → 棋盘图案贴图 ──
BOARDS = {
    "chinese_chessboard": "chinese_chessboard",
    "chess_board": "chess_board",
    "gomoku_board": "gomoku_board",
    "tictactoe_board": "tictactoe_board",
    "flight_chess_board": "flight_chess_board",
}

# ── 棋子：blockstate 名 → 模型名（不含命名空间）──
# 全部是「无属性单变体」，模型就是原来 material=oak 那一档
PIECES = {
    "chess_piece": "chinese_chesspiece",
    "chinese_piece_hidden": "chinese_chesspiece",
    "gomoku_piece_black": "gomoku_pieces_black",
    "gomoku_piece_white": "gomoku_pieces_white",
    "gomoku_piece_gray": "gomoku_pieces_gray",
    "tictactoe_piece": "tictactoe_pieces",
    "chess_piece_king": "chess_wang",
    "chess_piece_king_white": "chess_wang_white",
    "chess_piece_queen": "chess_queen",
    "chess_piece_queen_white": "chess_queen_white",
    "chess_piece_bishop": "chess_elephant",
    "chess_piece_bishop_white": "chess_elephant_white",
    "chess_piece_knight": "chess_horse",
    "chess_piece_knight_white": "chess_horse_white",
    "chess_piece_rook": "chess_car",
    "chess_piece_rook_white": "chess_car_white",
    "chess_piece_pawn": "chess_soldier",
    "chess_piece_pawn_white": "chess_soldier_white",
}

# 老系统的 12 种材质名。只用来**识别并删除**遗留的变体模型文件，新资源不再引用它们。
LEGACY_MATERIALS = [
    "oak", "spruce", "birch", "acacia", "dark_oak", "cherry", "pale_oak",
    "polished_granite", "polished_diorite", "polished_andesite",
    "polished_deepslate", "polished_blackstone",
]

# 配方接受的材料：7 种木头 × (剥皮原木 + 剥皮木头) + 5 种磨制石
RECIPE_WOODS = ["oak", "spruce", "birch", "acacia", "dark_oak", "cherry", "pale_oak"]
RECIPE_STONES = ["polished_granite", "polished_diorite", "polished_andesite",
                 "polished_deepslate", "polished_blackstone"]

# 棋盘边框的默认贴图。想换边框走动态皮肤（SkinData 的 0 号槽位），不改这里。
FRAME_TEXTURE = "minecraft:block/stripped_oak_log"

FACINGS = (("south", 0), ("west", 90), ("north", 180), ("east", 270))

# 变体模型文件名：<任意前缀>_<材质> 或 <任意前缀>_<材质>_frameless
VARIANT_RE = re.compile(
    r"_(?:" + "|".join(LEGACY_MATERIALS) + r")(?:_frameless)?\.json$")

# 已删除的属性：棋盘 blockstate 里出现就会渲染成紫黑格
FORBIDDEN_PROPERTIES = ("wood", "material")


def write_json(path, obj, check):
    text = json.dumps(obj, indent=2, ensure_ascii=False) + "\n"
    if check:
        old = path.read_text(encoding="utf-8") if path.exists() else None
        if old != text:
            fail(f"内容与预期不符：{path.relative_to(ROOT)}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def fail(msg):
    print(f"  ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


# ── 各步 ──

def delete_variants(check):
    """删掉全部木种变体模型"""
    victims = sorted(p for p in (ASSETS / "models/block").glob("*.json")
                     if VARIANT_RE.search(p.name))
    if check:
        if victims:
            fail(f"还有 {len(victims)} 个木种变体模型没删：{victims[0].name} …")
        return
    for p in victims:
        p.unlink()
    print(f"  删除木种变体模型 {len(victims)} 个")


def gen_board_models(check):
    """每个棋盘 2 个模型（带框 / 无框）"""
    n = 0
    for board, pattern in BOARDS.items():
        for frameless in (False, True):
            name = f"{board}_frameless" if frameless else board
            write_json(ASSETS / f"models/block/{name}.json", {
                "parent": f"{MODID}:block/{'board_frameless' if frameless else 'board'}",
                "textures": {
                    "0": f"{MODID}:block/{pattern}",
                    "2": FRAME_TEXTURE,
                },
            }, check)
            n += 1
    print(f"  棋盘模型 {n} 个")


def gen_board_blockstates(check):
    """棋盘 blockstate：facing × frameless = 8 条，没有 wood"""
    for board in BOARDS:
        variants = {}
        for frameless in (False, True):
            model = f"{MODID}:block/{board}{'_frameless' if frameless else ''}"
            for facing, y in FACINGS:
                variants[f"facing={facing},frameless={'true' if frameless else 'false'}"] = {
                    "model": model,
                    "y": y,
                }
        write_json(ASSETS / f"blockstates/{board}.json", {"variants": variants}, check)
    print(f"  棋盘 blockstate {len(BOARDS)} 个（每个 8 条）")


def gen_piece_blockstates(check):
    """棋子 blockstate：无属性的单变体"""
    for piece, model in PIECES.items():
        write_json(ASSETS / f"blockstates/{piece}.json",
                   {"variants": {"": {"model": f"{MODID}:block/{model}"}}}, check)
    print(f"  棋子 blockstate {len(PIECES)} 个（单变体）")


def gen_item_models(check):
    """物品模型：按 block_state 的 frameless 选模型，不再用 custom_model_data"""
    for board in BOARDS:
        write_json(ASSETS / f"items/{board}.json", {
            "model": {
                "type": "minecraft:select",
                "property": "minecraft:block_state",
                "block_state_property": "frameless",
                "cases": [{
                    "when": "true",
                    "model": {"type": "minecraft:model",
                              "model": f"{MODID}:block/{board}_frameless"},
                }],
                "fallback": {"type": "minecraft:model", "model": f"{MODID}:block/{board}"},
            },
        }, check)
    print(f"  物品模型 {len(BOARDS)} 个（select + frameless）")


def recipe_ingredients():
    items = [f"minecraft:stripped_{w}_log" for w in RECIPE_WOODS]
    items += [f"minecraft:stripped_{w}_wood" for w in RECIPE_WOODS]
    items += [f"minecraft:polished_{s.split('_', 1)[1]}" for s in RECIPE_STONES]
    return items


def delete_legacy_recipes(check):
    """删掉老的 120 个按材质命名的配方（保留 <board>.json / <board>_frameless.json）"""
    keep = {f"{b}.json" for b in BOARDS} | {f"{b}_frameless.json" for b in BOARDS}
    victims = sorted(p for p in (DATA / "recipe").glob("*.json") if p.name not in keep)
    if check:
        return
    for p in victims:
        p.unlink()
    print(f"  删除老配方 {len(victims)} 个")


def gen_recipes(check):
    """每个棋盘 2 条：带框 / 无框；ingredient 一次列全所有木头与石头"""
    items = recipe_ingredients()
    for board in BOARDS:
        write_json(DATA / f"recipe/{board}.json", {
            "type": "minecraft:stonecutting",
            "ingredient": items,
            "result": {"id": f"{MODID}:{board}"},
        }, check)
        write_json(DATA / f"recipe/{board}_frameless.json", {
            "type": "minecraft:stonecutting",
            "ingredient": items,
            "result": {
                "id": f"{MODID}:{board}",
                "components": {
                    "minecraft:block_state": {"frameless": "true"},
                    "minecraft:custom_name": {
                        "translate": f"item.{MODID}.variant.{board}_frameless"},
                },
            },
        }, check)
    print(f"  切石配方 {len(BOARDS) * 2} 条（各 {len(items)} 项 ingredient）")


def gen_lang(check):
    """删掉 115 条木种变体名，只留每棋盘一条「无框」名"""
    for lang in ("en_us", "zh_cn"):
        path = ASSETS / f"lang/{lang}.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        for key in [k for k in data if k.startswith(f"item.{MODID}.variant.")]:
            del data[key]
        for board in BOARDS:
            base = data.get(f"block.{MODID}.{board}", board)
            data[f"item.{MODID}.variant.{board}_frameless"] = (
                f"Frameless {base}" if lang == "en_us" else f"无框{base}")
        write_json(path, data, check)
    print(f"  语言文件 2 个（每条只剩 1 个无框变体名）")


# ── 校验 ──

def check_blockstate_properties():
    """棋盘 blockstate 只能用现存属性；棋子 blockstate 不能带属性"""
    for board in BOARDS:
        data = json.loads((ASSETS / f"blockstates/{board}.json").read_text(encoding="utf-8"))
        for key in data["variants"]:
            for prop in FORBIDDEN_PROPERTIES:
                if f"{prop}=" in key:
                    fail(f"{board}.json 的 variant {key!r} 引用了已删除的属性 {prop!r}")
            names = {kv.split("=")[0] for kv in key.split(",") if kv}
            if names - {"facing", "frameless"}:
                fail(f"{board}.json 的 variant {key!r} 含未知属性 {sorted(names)}")
    for piece in PIECES:
        data = json.loads((ASSETS / f"blockstates/{piece}.json").read_text(encoding="utf-8"))
        keys = list(data["variants"])
        if keys != [""]:
            fail(f"{piece}.json 应是无属性单变体，实际 keys={keys}")


def check_models_exist():
    """所有被 blockstate / 物品模型引用的模型文件都必须存在"""
    referenced = set()
    for path in list((ASSETS / "blockstates").glob("*.json")) + list((ASSETS / "items").glob("*.json")):
        for ref in re.findall(rf'"{MODID}:block/([a-z0-9_/]+)"', path.read_text(encoding="utf-8")):
            referenced.add(ref)
    missing = sorted(r for r in referenced
                     if not (ASSETS / f"models/block/{r}.json").exists())
    if missing:
        fail(f"这些模型被引用但不存在：{missing}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只校验不改文件")
    args = ap.parse_args()
    check = args.check

    if check:
        print("校验棋盘 / 棋子资源:")
        check_blockstate_properties()
        check_models_exist()
        print("  OK: blockstate 属性合法、被引用的模型都存在")
        return

    print("清理与生成:")
    delete_legacy_recipes(check)
    delete_variants(check)
    gen_board_models(check)
    gen_board_blockstates(check)
    gen_piece_blockstates(check)
    gen_item_models(check)
    gen_recipes(check)
    gen_lang(check)
    print("自校验:")
    check_blockstate_properties()
    check_models_exist()
    print("  OK: 完成")


if __name__ == "__main__":
    main()
