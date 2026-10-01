package com.chessboard.game;

import com.chessboard.SkinData;
import com.chessboard.api.BoardGameLogic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * 板棋家族（Tafl）：萨米 / 爱尔兰 / 苏格兰 / 挪威板棋。
 *
 * <p>这几种子玩法<b>共用同一套外形</b> —— 王在正中、护王方若干兵围着王、捉王方四边各有营地 ——
 * 差别只在<b>棋盘尺寸</b>与<b>开局摆法</b>。所以这里是一个类、三块棋盘（7×7 / 9×9 / 11×11），
 * 尺寸内的不同摆法做成「开局方式」下拉里的选项（见 {@link Variant}）。
 *
 * <p><b>和其它棋类一样，只做棋盘与开局摆放，不判定走法</b>：棋子可以自由走到任意格。
 * 真实规则（车走法、夹吃、四面围王、王到边缘/角落即胜）以后要做再说，位置信息都在开局里备好了。
 *
 * <p>坐标用记谱表示：列 a–i(–k) 从左到右，行 1–n 从下到上；棋盘第 0 行在<b>下方</b>，
 * 所以记谱行 {@code rank} 对应数组行 {@code size - rank}。
 *
 * <p>真实规则里王座与四角是特殊格；这里只把王座画在贴图上，不参与判定。
 */
public class TaflLogic implements BoardGameLogic {

    /** 开局变体：{@code label} 是下拉里的显示名，{@code key} 同时是命令字面量与 startBoard 的 mode */
    public enum Variant {
        /** 萨米板棋：9×9，护王方 8 兵十字，捉王方四边各 4 兵（横臂贴边 + 竖臂向内） */
        TABLUT("萨米板棋", "tablut"),
        /** 爱尔兰板棋：7×7，王只有 4 名卫士，捉王方 8 兵贴着四个角；节奏最快 */
        BRANDUBH("爱尔兰板棋", "brandubh"),
        /** 苏格兰板棋：7×7，8 名卫士 + 16 名捉王兵，捉王方每边 3+1 */
        ARD_RI("苏格兰板棋", "ardri"),
        /** 挪威板棋：11×11，12 名卫士 + 24 名捉王兵，捉王方每边 4+2 */
        HNEFATAFL("挪威板棋", "hnefatafl");

        public final String label;
        public final String key;

        Variant(String label, String key) {
            this.label = label;
            this.key = key;
        }
    }

    /** 7×7：爱尔兰 / 苏格兰板棋 */
    public static final TaflLogic SMALL = new TaflLogic(7, new Variant[]{Variant.BRANDUBH, Variant.ARD_RI}, 1f, 14f);
    /** 9×9：萨米板棋 */
    public static final TaflLogic NINE = new TaflLogic(9, new Variant[]{Variant.TABLUT}, 1f, 14f);
    /** 11×11：挪威板棋（威尔士板棋同尺寸同布局，差别只在胜利条件，等做了胜利条件再加） */
    public static final TaflLogic LARGE = new TaflLogic(11, new Variant[]{Variant.HNEFATAFL}, 1f, 14f);

    /** 护王方（瑞典/浅色，side 0）的兵 */
    public static final int SWEDE = 1;
    /** 国王（属护王方） */
    public static final int KING = 2;
    /** 捉王方（莫斯科/深色，side 1）的兵 */
    public static final int MUSCOVITE = 3;

    private final int size;
    private final Variant[] variants;
    private final float offset;
    private final float span;

    public TaflLogic(int size, Variant[] variants, float offset, float span) {
        this.size = size;
        this.variants = variants;
        this.offset = offset;
        this.span = span;
    }

    /** 记谱坐标（列 a…、行 1…）→ 数组下标。行 1 在棋盘下方 */
    private int sq(String coord) {
        int col = coord.charAt(0) - 'a';
        int rank = coord.charAt(1) - '0';
        return idx(size - rank, col);
    }

    /** 同一个尺寸与变体、只换格子参数的副本（带框 / 无框各一份） */
    public TaflLogic with(float offset, float span) {
        return new TaflLogic(size, variants, offset, span);
    }

    @Override public int rows() { return size; }
    @Override public int cols() { return size; }

    /** 默认开局 = 这块棋盘列出的第一个变体 */
    @Override
    public void initBoard(int[] p) { initBoard(p, variants[0]); }

    /** 按变体摆放棋子 */
    public void initBoard(int[] p, Variant v) {
        Arrays.fill(p, 0);
        int mid = size / 2;
        p[idx(mid, mid)] = KING;
        switch (v) {
            case TABLUT -> {
                // 护王方：紧贴国王的十字（左右各 2、上下各 2）
                for (String c : new String[]{"c5", "d5", "f5", "g5", "e3", "e4", "e6", "e7"}) {
                    p[sq(c)] = SWEDE;
                }
                // 捉王方：四边各一组 T 形营地 —— 横臂贴着边缘，竖臂向内一格
                for (String c : new String[]{
                        "d1", "e1", "f1", "e2",     // 南
                        "d9", "e9", "f9", "e8",     // 北
                        "a4", "a5", "a6", "b5",     // 西
                        "i4", "i5", "i6", "h5"}) {  // 东
                    p[sq(c)] = MUSCOVITE;
                }
            }
            case BRANDUBH -> {
                // 护王方：只有王的四个正交邻格
                p[idx(mid - 1, mid)] = SWEDE;
                p[idx(mid + 1, mid)] = SWEDE;
                p[idx(mid, mid - 1)] = SWEDE;
                p[idx(mid, mid + 1)] = SWEDE;
                // 捉王方：8 名，两两贴着四个角（每个角在两条边上的邻格）
                for (int[] corner : CORNERS) {
                    int r = corner[0] * (size - 1);
                    int c = corner[1] * (size - 1);
                    p[idx(r, c == 0 ? 1 : size - 2)] = MUSCOVITE;
                    p[idx(r == 0 ? 1 : size - 2, c)] = MUSCOVITE;
                }
            }
            case ARD_RI -> {
                // 护王方：王的八邻格全占
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        if (dr != 0 || dc != 0) p[idx(mid + dr, mid + dc)] = SWEDE;
                    }
                }
                // 捉王方：四边每边 3（居中贴边）+ 1（向内），共 16
                camp(p, new int[]{mid - 1, mid, mid + 1}, new int[]{mid});
            }
            case HNEFATAFL -> {
                // 护王方 12：王的 4 正交邻格 + 4 斜邻格 + 正交方向外一格
                for (int d = -1; d <= 1; d += 2) {
                    p[idx(mid + d, mid)] = SWEDE;
                    p[idx(mid, mid + d)] = SWEDE;
                    p[idx(mid + d, mid + d)] = SWEDE;
                    p[idx(mid + d, mid - d)] = SWEDE;
                    p[idx(mid + 2 * d, mid)] = SWEDE;
                    p[idx(mid, mid + 2 * d)] = SWEDE;
                }
                // 捉王方 24：四边每边 4（对称贴边）+ 2（向内对称），共 24
                camp(p, new int[]{mid - 2, mid - 1, mid + 1, mid + 2}, new int[]{mid - 1, mid + 1});
            }
        }
    }

    private static final int[][] CORNERS = {{0, 0}, {0, 1}, {1, 0}, {1, 1}};

    /**
     * 四边的营地（捉王方）。四条边形状相同、只是朝向不同：
     * {@code edge} 是贴着边缘那一排要占的行/列号，{@code inner} 是向棋盘内再占一格的。
     * 每边棋子数 = {@code edge.length + inner.length}，四边乘以 4 就是总数。
     */
    private void camp(int[] p, int[] edge, int[] inner) {
        for (int c : edge) {                       // 北 / 南
            p[idx(0, c)] = MUSCOVITE;
            p[idx(size - 1, c)] = MUSCOVITE;
        }
        for (int r : edge) {                       // 西 / 东
            p[idx(r, 0)] = MUSCOVITE;
            p[idx(r, size - 1)] = MUSCOVITE;
        }
        for (int c : inner) {
            p[idx(1, c)] = MUSCOVITE;
            p[idx(size - 2, c)] = MUSCOVITE;
        }
        for (int r : inner) {
            p[idx(r, 1)] = MUSCOVITE;
            p[idx(r, size - 2)] = MUSCOVITE;
        }
    }

    @Override public String pieceName(int piece) { return ""; }
    @Override public int textColor(int piece) { return 0; }

    /** 不限定规则：空点选子、点空格/敌方走过去（框架的默认走子），不做任何合法性判定 */
    @Override
    public ClickResult onClick(int[] pieces, int selRow, int selCol, int clickRow, int clickCol) {
        return onClickMove(pieces, selRow, selCol, clickRow, clickCol);
    }

    /** 捉王方是 side 1，护王方（含国王）是 side 0 */
    @Override public int side(int piece) { return piece == MUSCOVITE ? 1 : 0; }

    @Override public String codePrefix() { return "tb"; }

    /** 棋子模型宽 5 单位，缩放取格距的 0.9 倍留一点间隙（7×7 → 0.42，9×9 → 0.32，11×11 → 0.25） */
    @Override public float pieceScale() { return span / (size - 1) / 5f * 0.9f; }

    @Override public float gridSpan() { return span; }
    @Override public float gridOffsetX() { return offset; }
    @Override public float gridOffsetZ() { return offset; }

    // 模型（三种棋子）在 client.renderer.PieceModels 里登记 ——
    // 不放这里是为了让规则类保持纯 Java，能脱离 Minecraft 跑 jshell 自测。

    /** 护王方（含国王）一个槽，捉王方一个槽 —— 三种尺寸共用 */
    @Override public int skinSlot(int piece) {
        return piece == MUSCOVITE ? SkinData.SLOT_TABLUT_MUSCOVITE : SkinData.SLOT_TABLUT_SWEDE;
    }

    @Override public int[] skinSlots() {
        return new int[]{SkinData.SLOT_BOARD, SkinData.SLOT_TABLUT_SWEDE, SkinData.SLOT_TABLUT_MUSCOVITE};
    }

    // ── 开局方式（下拉）──

    /**
     * 列出这块尺寸上的所有摆法。
     *
     * <p><b>不列「默认开局」</b>：它的含义就是「按第一套摆法重开」，和第一个变体完全是同一件事；
     * 界面只渲染这里给出的动作，所以板棋的下拉里就是纯粹的变体列表。
     */
    @Override
    public List<StartAction> startActions() {
        List<StartAction> list = new ArrayList<>();
        for (Variant v : variants) list.add(new StartAction(v.label, v.key));
        return list;
    }

    @Override
    public Start startBoard(int[] pieces, String mode, Consumer<int[]> historyPush) {
        for (Variant v : variants) {
            if (v.key.equals(mode)) {
                initBoard(pieces, v);
                return Start.PLAIN;
            }
        }
        if (!"reset".equals(mode)) return Start.UNSUPPORTED;
        initBoard(pieces);
        return Start.PLAIN;
    }
}
