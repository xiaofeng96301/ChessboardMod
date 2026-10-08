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
 * <p><b>做棋盘、开局摆放、夹击吃子</b>；走法仍是「任意格 → 任意空格」，没有车行/跳子的限制，
 * 也没有胜负判定。真实规则里剩下的那些（车走法、王要四面围死、王座与四角当「墙」参与夹击、
 * 王逃到边角即胜）以后要做再说，位置信息都在开局里备好了。
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

    /*
     * 板棋是「棋子摆在格子里」，所以走的是国际象棋那套格子参数，不是五子棋/象棋那套
     * 「棋子踩在线交叉点上」的 1 / 14 —— 板棋若用它，格子会撑到贴图外沿、外圈边框整条消失。
     * 换算：8 像素 = 1 单位，格心落在 offset + 格宽·k（k = 0 … n−1）。
     *
     * 下面三组 offset/span 是照着三张**手绘贴图**量出来的格心位置，不是一条统一公式：
     * 作者按整数格画的（7×7 每格 16 像素、9×9 12 像素、11×11 10 像素），只有 7×7 的
     * 16×7 正好铺满中间那 112 像素（8..120），另两张分别只有 108 / 110 像素宽。
     * 所以别拿「1 + 7/n」去套；贴图重画过就要重新量，并同步改 check_tafl_start.jsh 里钉住的数。
     */

    /** 7×7：爱尔兰 / 苏格兰板棋（格心 16..112 像素，格子正好铺满 8..120） */
    public static final TaflLogic SMALL = new TaflLogic(7, new Variant[]{Variant.BRANDUBH, Variant.ARD_RI},
            2.0f, 12.0f);
    /** 9×9：萨米板棋（12 像素一格，格心 15.5..111.5 像素） */
    public static final TaflLogic NINE = new TaflLogic(9, new Variant[]{Variant.TABLUT},
            15.5f / 8f, (111.5f - 15.5f) / 8f);
    /** 11×11：挪威板棋（10 像素一格，格心 13.5..113.5 像素；威尔士板棋同尺寸同布局，等做了胜利条件再加） */
    public static final TaflLogic LARGE = new TaflLogic(11, new Variant[]{Variant.HNEFATAFL},
            13.5f / 8f, (113.5f - 13.5f) / 8f);

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

    /**
     * 无框变体：同一个棋盘、摆到没有木框的那块薄板上。
     *
     * <p>无框模型把贴图的 3..125 像素铺在 0.5..15.5 的面上（带框模型铺的是 0..16 / 0..16），
     * 所以同一个<b>像素</b>位置要换算一次坐标，棋子和贴图格子才对得上。
     * 这条换算反推其它棋盘也对得上：国际象棋 1.9/12.2 → 2.0/12.0，五子棋 1/14 → 1.115/13.77。
     */
    public TaflLogic frameless() {
        float first = pixelToFrameless(offset);
        return with(first, pixelToFrameless(offset + span) - first);
    }

    /** 带框坐标 g（16 单位制）→ 无框坐标：先换像素再按无框模型的面重新铺 */
    private static float pixelToFrameless(float g) { return 0.5f + (8f * g - 3f) * 15f / 122f; }

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

    /**
     * 板棋走子：空点选子、点自己人换选/放下、点空格走子 —— 这些沿用框架那套。
     *
     * <p>只有吃子换成了板棋的规则：<b>不能走到有棋子的格子上</b>（没有象棋/国际象棋那种
     * 「踩上去吃掉」），吃子只看落子之后的夹击，见 {@link #capture}。
     *
     * <p>走法本身仍是「任意格 → 任意空格」，没限定车行、跳子与距离。
     */
    @Override
    public ClickResult onClick(int[] pieces, int selRow, int selCol, int clickRow, int clickCol) {
        int target = pieces[idx(clickRow, clickCol)];
        if (selRow >= 0 && target != 0 && side(target) != side(pieces[idx(selRow, selCol)])) {
            // 抬着棋子点敌子：板棋不吃这一套。这一下什么都不做，棋子还举着，方便换个落点
            return new ClickResult.None();
        }
        ClickResult r = onClickMove(pieces, selRow, selCol, clickRow, clickCol);
        if (r instanceof ClickResult.Move moved) capture(pieces, moved.toRow(), moved.toCol());
        return r;
    }

    /** 四个正交方向（板棋只按上下左右夹） */
    private static final int[][] ORTHO = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /**
     * 夹击吃子：刚落下的这颗棋子，看它四个正交方向上<b>紧邻</b>的敌子 —— 敌子另一侧又紧邻着
     * 己方棋子，就是被夹死了，直接吃掉（从数组里抹掉）。
     *
     * <p>一次落子可能同时夹掉多颗（四个方向各一颗），所以逐个方向都查一遍。
     * 抹掉的格子由调用方（方块实体）按「前后两代棋盘的差异」记进历史，所以照样能悔棋。
     *
     * <p><b>还没做</b>（真实规则里都有，需要的话再说）：王的四面围死、王座与四角当「墙」参与夹击、
     * 王逃到边角即胜 / 王被吃即负这类胜负判定 —— 现在王跟普通棋子一样，两颗夹住就没了。
     */
    private void capture(int[] pieces, int row, int col) {
        int mine = side(pieces[idx(row, col)]);
        for (int[] d : ORTHO) {
            int r = row + d[0], c = col + d[1];
            if (!inside(r, c)) continue;
            int victim = pieces[idx(r, c)];
            if (victim == 0 || side(victim) == mine) continue;      // 空格或自己人，不是夹击对象
            int r2 = r + d[0], c2 = c + d[1];
            if (inside(r2, c2) && pieces[idx(r2, c2)] != 0 && side(pieces[idx(r2, c2)]) == mine) {
                pieces[idx(r, c)] = 0;                              // 另一侧也是我方 → 夹死
            }
        }
    }

    /** 这一格在棋盘内 */
    private boolean inside(int row, int col) {
        return row >= 0 && row < size && col >= 0 && col < size;
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
