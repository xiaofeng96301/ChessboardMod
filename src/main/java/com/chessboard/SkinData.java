package com.chessboard;

import com.chessboard.api.BoardGameLogic;
import com.chessboard.game.ChessLogic;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.FlightChessLogic;
import com.chessboard.game.GomokuLogic;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 棋盘与棋子的动态皮肤 —— <b>外观的唯一真相</b>。
 *
 * <p>皮肤值是一个方块 ID 字符串，{@code null} = 未设（用模型自带的贴图）。
 * 每个「槽位」对应棋盘或某一方的棋子，所以国际象棋黑白、五子棋黑白灰、飞行棋四队
 * 都可以各设各的，不会互相串色。
 *
 * <p>皮肤的渲染语义：把模型上<b>不属于本模组</b>的贴图换成皮肤方块的贴图。
 * 棋盘换的是边框与底板，棋子换的是本体；棋盘图案、汉字、飞机图标、骰子点数
 * 这些本模组自己的贴图原样保留。
 */
public final class SkinData {

    // ── 槽位表 ──
    // 1..6 对应旧 MaterialData 的 0..5 顺移一位，这个顺序让老 matN 的迁移就是 +1

    /** 棋盘本体（边框 / 底板）—— 与框架接口同源，防止两边漂移 */
    public static final int SLOT_BOARD = BoardGameLogic.BOARD_SLOT;
    /** 中国象棋（红黑双方共用一个槽，区分靠汉字颜色） */
    public static final int SLOT_CHINESE = 1;
    public static final int SLOT_CHESS_WHITE = 2;
    public static final int SLOT_CHESS_BLACK = 3;
    public static final int SLOT_GOMOKU_BLACK = 4;
    public static final int SLOT_GOMOKU_WHITE = 5;
    /** 五子棋随机开局的灰色障碍子 */
    public static final int SLOT_GOMOKU_GRAY = 6;
    public static final int SLOT_FLIGHT_RED = 7;
    public static final int SLOT_FLIGHT_YELLOW = 8;
    public static final int SLOT_FLIGHT_BLUE = 9;
    public static final int SLOT_FLIGHT_GREEN = 10;
    public static final int SLOT_COUNT = 11;

    /** 该棋子不吃皮肤（井字棋走自己的贴图、骰子走本模组贴图）—— 与框架接口同源 */
    public static final int NO_SLOT = BoardGameLogic.NO_SKIN_SLOT;

    private SkinData() {}

    // ── 槽位查询 ──

    /**
     * 棋子值 → 该棋子吃哪个皮肤槽。{@link #NO_SLOT} = 不吃。
     *
     * <p>渲染两条路径（静态烘焙 + 动画渲染器）都靠它决定贴图，必须唯一。
     */
    public static int slotFor(BoardGameLogic g, int piece) {
        return g.skinSlot(piece); // 各棋类自己报，见 BoardGameLogic#skinSlot
    }

    /** 该棋类要在管理界面里显示哪些样式格（顺序即显示顺序，第一项固定是棋盘） */
    public static int[] slotsFor(BoardGameLogic g) {
        return g.skinSlots();
    }

    /**
     * 槽位的短名（界面用）。要短 —— 界面上这一行还要接上「：当前材质」，
     * 太长了会被截断，玩家就看不到当前材质了。
     */
    public static String slotLabel(int slot) {
        return switch (slot) {
            case SLOT_BOARD -> "棋盘";
            case SLOT_CHINESE -> "象棋";
            case SLOT_CHESS_WHITE -> "白子";
            case SLOT_CHESS_BLACK -> "黑子";
            case SLOT_GOMOKU_BLACK -> "黑棋";
            case SLOT_GOMOKU_WHITE -> "白棋";
            case SLOT_GOMOKU_GRAY -> "灰棋";
            case SLOT_FLIGHT_RED -> "红队";
            case SLOT_FLIGHT_YELLOW -> "黄队";
            case SLOT_FLIGHT_BLUE -> "蓝队";
            case SLOT_FLIGHT_GREEN -> "绿队";
            default -> "样式";
        };
    }

    // ── 解析与校验 ──

    /** 空串 / 空白 → null。网络上用空串表示「未设」（ByteBuf 编不了 null） */
    public static String blankToNull(String id) {
        return id == null || id.isBlank() ? null : id;
    }

    /** 物品堆 → 方块 ID；不是方块物品返回 null。GUI 的真槽位用它把物品翻译成皮肤。 */
    public static String blockIdOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (!(stack.getItem() instanceof BlockItem bi)) return null;
        Identifier key = BuiltInRegistries.BLOCK.getKey(bi.getBlock());
        return key == null ? null : key.toString();
    }

    /** 这个物品能不能当皮肤用（顺带挡掉空气、带方块实体的方块等） */
    public static boolean isUsableSkin(ItemStack stack) {
        return stateOf(blockIdOf(stack)) != null;
    }

    /**
     * 方块 ID → 该方块的默认方块状态；解析不出来时返回 null（= 不覆盖）。
     *
     * <p>服务端写 NBT 前也走这里做校验 —— 客户端发上来的 ID 不可信，
     * 未知 ID、空气、以及带方块实体的方块（没有可静态烘焙的模型，套上去会渲染不出来）
     * 一律退回「不覆盖」。
     */
    public static BlockState stateOf(String id) {
        String norm = blankToNull(id);
        if (norm == null) return null;
        Identifier key = Identifier.tryParse(norm);
        if (key == null) return null;
        Block block = BuiltInRegistries.BLOCK.getValue(key); // 未知 ID → 默认值（空气）
        if (block == null || block == Blocks.AIR) return null;
        BlockState state = block.defaultBlockState();
        // 带方块实体的方块只能由自己的渲染器画，静态烘焙不出模型 → 不接受
        return state.hasBlockEntity() ? null : state;
    }

    // ── 老数据迁移 ──

    /**
     * 旧 {@code mat<i>} 的值（{@code ChessMaterial} 枚举序列名）→ 皮肤方块 ID。
     *
     * <p>只用于老存档的一次性迁移：材质槽在方块实体 NBT 里，读一次就能免费保住老棋子的外观
     * （棋盘的木种存在方块状态里，已经随 {@code WOOD} 属性一起删掉了，保不住）。
     *
     * <p>{@code oak} 返回 null —— {@code stripped_oak_log} 就是模型自带的贴图，
     * 填进去只会多画一层皮肤四边形，并让「设过皮肤」误报成真。
     */
    public static String legacyMaterialSkin(String legacyName) {
        if (legacyName == null) return null;
        return switch (legacyName) {
            case "oak" -> null;
            case "polished_granite", "polished_diorite", "polished_andesite",
                 "polished_deepslate", "polished_blackstone" -> "minecraft:block/" + legacyName;
            case "spruce", "birch", "acacia", "dark_oak", "cherry", "pale_oak" ->
                    "minecraft:block/stripped_" + legacyName + "_log";
            default -> null;
        };
    }
}
