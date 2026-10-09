package com.chessboard.game;

import com.chessboard.SkinData;
import com.chessboard.api.BoardGameLogic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * 中国象棋规则：10×9 棋盘，红/黑双方各 16 枚棋子。
 * 支持暗棋模式：棋子带隐藏位，翻开后恢复正常交互。
 */
public class ChineseChessLogic implements BoardGameLogic {

    public static final ChineseChessLogic INSTANCE = new ChineseChessLogic(1f, 14f);

    private static final int ROWS = 10, COLS = 9;
    /** 暗棋隐藏位（bit4），与 side/type 不冲突 */
    public static final int HIDDEN_BIT = 0x10;

    private final float offset;
    private final float span;

    public ChineseChessLogic(float offset, float span) {
        this.offset = offset;
        this.span = span;
    }

    @Override public int rows() { return ROWS; }
    @Override public int cols() { return COLS; }

    @Override public float gridSpan() { return span; }
    @Override public float gridOffsetX() { return offset; }
    @Override public float gridOffsetZ() { return offset; }

    @Override
    public void initBoard(int[] p) {
        Arrays.fill(p, 0);
        int[] back = {5,4,3,2,1,2,3,4,5};
        for (int c = 0; c < COLS; c++) p[idx(0,c)] = pack(0, back[c]);
        p[idx(2,1)] = pack(0,6); p[idx(2,7)] = pack(0,6);
        for (int c = 0; c < COLS; c+=2) p[idx(3,c)] = pack(0,7);
        for (int c = 0; c < COLS; c+=2) p[idx(6,c)] = pack(1,7);
        p[idx(7,1)] = pack(1,6); p[idx(7,7)] = pack(1,6);
        for (int c = 0; c < COLS; c++) p[idx(9,c)] = pack(1, back[c]);
    }

    /** 暗棋开局：重置布局后，双方各自随机交换棋子类型位置，全部盖上背面 */
    public void darkStart(int[] p) {
        initBoard(p);
        Random rnd = new Random();
        for (int side = 0; side <= 1; side++) {
            List<Integer> types = new ArrayList<>();
            for (int i = 0; i < p.length; i++) if (p[i] != 0 && side(p[i]) == side) types.add(type(p[i]));
            Collections.shuffle(types, rnd);
            int k = 0;
            for (int i = 0; i < p.length; i++) if (p[i] != 0 && side(p[i]) == side) p[i] = pack(side, types.get(k++));
        }
        for (int i = 0; i < p.length; i++) if (p[i] != 0) p[i] |= HIDDEN_BIT;
    }

    /** 全暗棋开局：重置布局后，红黑双方的棋子值全部随机打乱位置（阵营也随机），全部盖上背面 */
    public void fullDarkStart(int[] p) {
        initBoard(p);
        List<Integer> positions = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            if (p[i] != 0) { positions.add(i); values.add(p[i]); }
        }
        Collections.shuffle(positions);
        Collections.shuffle(values);
        for (int i = 0; i < positions.size(); i++) p[positions.get(i)] = values.get(i) | HIDDEN_BIT;
    }

    @Override
    public String pieceName(int piece) {
        if (piece == 0 || isHidden(piece)) return "";
        int t = type(piece), s = side(piece);
        return switch (t) {
            case 1 -> s == 0 ? "帅" : "将";
            case 2 -> s == 0 ? "仕" : "士";
            case 3 -> s == 0 ? "相" : "象";
            case 4 -> "马"; case 5 -> "车"; case 6 -> "炮";
            case 7 -> s == 0 ? "兵" : "卒";
            default -> "";
        };
    }

    @Override public int textColor(int piece) { return isHidden(piece) ? 0 : (side(piece) == 0 ? 0xFFCC2222 : 0xFF1A1A1A); }
    @Override public int side(int piece) { return (piece >> 3) & 1; }
    @Override public String codePrefix() { return "xq"; }

    // 模型（暗棋背面 / 圆片 + 汉字）在 client.renderer.PieceModels 里登记 ——
    // 不放这里是为了让规则类保持纯 Java，能脱离 Minecraft 跑 jshell 自测。

    /** 红黑双方共用「象棋」一个槽，区分靠汉字颜色 */
    @Override public int skinSlot(int piece) { return SkinData.SLOT_CHINESE; }

    @Override public int[] skinSlots() { return new int[]{SkinData.SLOT_BOARD, SkinData.SLOT_CHINESE}; }

    @Override
    public List<StartAction> startActions() {
        return List.of(new StartAction("默认开局", "reset"),
                new StartAction("暗棋开局", "darkstart"),
                new StartAction("全暗棋开局", "fulldarkstart"));
    }

    @Override
    public Start startBoard(int[] pieces, String mode, Consumer<int[]> historyPush) {
        return switch (mode) {
            case "darkstart" -> { darkStart(pieces); yield Start.PLAIN; }
            case "fulldarkstart" -> { fullDarkStart(pieces); yield Start.PLAIN; }
            case "reset" -> { initBoard(pieces); yield Start.PLAIN; }
            default -> Start.UNSUPPORTED;
        };
    }

    /** 暗棋翻成明棋：上一代带隐藏位、这一代没有，且值确实变了 */
    @Override
    public boolean isFlipTransition(int prev, int now) {
        return isHidden(prev) && !isHidden(now) && prev != now;
    }

    @Override public int onFlip(int piece) { return reveal(piece); }

    /** 翻面动画前半程显示背面（暗棋模型） */
    @Override public int flippedModelPiece(int piece) { return hide(piece); }

    /** 圆片没有正反面，朝向跟随汉字（并按阵营翻转），否则黑方棋子和汉字会差 180° */
    @Override public boolean pieceFollowsTextRotation() { return true; }

    // ── 文字位置（象棋默认值，按需调整）──
    @Override public float pieceTextHeight() { return 0.0161f; }
    @Override public float pieceTextOffsetX() { return 0.7f; }

    /** 导出时揭开暗棋，避免隐藏位破坏编码 */
    @Override
    public String encodePieces(int[] pieces) {
        int[] copy = pieces.clone();
        for (int i = 0; i < copy.length; i++) if (isHidden(copy[i])) copy[i] = reveal(copy[i]);
        return BoardGameLogic.super.encodePieces(copy);
    }

    @Override
    public ClickResult onClick(int[] pieces, int selRow, int selCol, int clickRow, int clickCol) {
        if (isHidden(pieces[idx(clickRow, clickCol)])) return new ClickResult.Flip(clickRow, clickCol);
        return onClickMove(pieces, selRow, selCol, clickRow, clickCol);
    }

    public static boolean isHidden(int piece) { return (piece & HIDDEN_BIT) != 0; }
    public static int reveal(int piece) { return piece & ~HIDDEN_BIT; }
    public static int hide(int piece) { return piece | HIDDEN_BIT; }

    // ── 胜利 / 将军提示 ──

    /**
     * 要表演的格子：
     * <ul>
     *   <li>一方的帅（将）不在了 → 对面赢，报赢家<b>全体棋子</b>（全体跳起晃动）；</li>
     *   <li>否则只要有帅（将）正被攻击 → 只报那一格（帅自己跳一下，提示「将军」）。</li>
     * </ul>
     *
     * <p>「被攻击」按象棋的攻击路线算（车直行、炮翻山、马别腿、兵过河能横走、帅对脸），
     * <b>只用来提示，不参与走子合法性</b> —— 这个模组本来就不判走法。
     * 暗棋（没翻开的）身份未知，不参与攻击判定。
     */
    @Override
    public int[] winCells(int[] pieces) {
        int redKing = findKing(pieces, 0), blackKing = findKing(pieces, 1);
        if (redKing >= 0 && blackKing < 0) return cellsOf(pieces, 0);
        if (blackKing >= 0 && redKing < 0) return cellsOf(pieces, 1);
        if (redKing < 0 || blackKing < 0) return null;      // 双方都没王（空盘）→ 不判
        if (isAttacked(pieces, redKing / COLS, redKing % COLS, 0)) return new int[]{redKing};
        if (isAttacked(pieces, blackKing / COLS, blackKing % COLS, 1)) return new int[]{blackKing};
        return null;
    }

    /** 某一方帅/将所在格；不在返回 -1（盖着的暗棋也算在，隐藏位不影响棋子类型） */
    private int findKing(int[] p, int s) {
        for (int i = 0; i < p.length; i++) {
            if (p[i] != 0 && side(p[i]) == s && type(p[i]) == 1) return i;
        }
        return -1;
    }

    /** 某一方所有棋子所在格 */
    private int[] cellsOf(int[] p, int s) {
        int n = 0;
        for (int i = 0; i < p.length; i++) if (p[i] != 0 && side(p[i]) == s) n++;
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < p.length; i++) if (p[i] != 0 && side(p[i]) == s) out[k++] = i;
        return out;
    }

    /** (row, col) 是否正被 oppSide 的攻击覆盖 */
    private boolean isAttacked(int[] p, int row, int col, int oppSide) {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int v = p[idx(r, c)];
                if (v == 0 || isHidden(v) || side(v) == oppSide) continue;
                if (attacks(p, r, c, row, col)) return true;
            }
        }
        return false;
    }

    /** (r,c) 上的棋子按象棋走法能不能吃到 (row,col)：只看攻击路线，忽略河界/九宫这类实际限制 */
    private boolean attacks(int[] p, int r, int c, int row, int col) {
        int piece = p[idx(r, c)];
        if (piece == 0 || isHidden(piece)) return false;
        int dr = row - r, dc = col - c;
        switch (type(piece)) {
            case 5 -> {                                            // 车：直线，中间无子
                if (dr != 0 && dc != 0) return false;
                return countBetween(p, r, c, row, col) == 0;
            }
            case 6 -> {                                            // 炮：直线，中间恰好一个炮架
                if (dr != 0 && dc != 0) return false;
                return countBetween(p, r, c, row, col) == 1;
            }
            case 4 -> {                                            // 马：日字，别腿
                if (Math.abs(dr) == 2 && Math.abs(dc) == 1) return p[idx(r + dr / 2, c)] == 0;
                if (Math.abs(dr) == 1 && Math.abs(dc) == 2) return p[idx(r, c + dc / 2)] == 0;
                return false;
            }
            case 1 -> {                                            // 帅/将：一步正交，或同列对脸
                if (Math.abs(dr) + Math.abs(dc) == 1) return true;
                return dc == 0 && dr != 0 && countBetween(p, r, c, row, col) == 0;
            }
            case 2 -> {                                            // 仕/士：斜一步
                return Math.abs(dr) == 1 && Math.abs(dc) == 1;
            }
            case 3 -> {                                            // 相/象：田字，塞象眼
                if (Math.abs(dr) != 2 || Math.abs(dc) != 2) return false;
                return p[idx(r + dr / 2, c + dc / 2)] == 0;
            }
            case 7 -> {                                            // 兵/卒：向前一步，过河后还能横走
                int forward = side(piece) == 0 ? 1 : -1;           // 红在下方，往上走
                if (dr == forward && dc == 0) return true;
                if (dc == 0) return false;
                boolean crossed = side(piece) == 0 ? r >= 5 : r <= 4;
                return crossed && dr == 0 && Math.abs(dc) == 1;
            }
            default -> {
                return false;
            }
        }
    }

    /** 两点之间（不含端点）有几颗棋子 —— 车 / 炮 / 对脸判定用 */
    private int countBetween(int[] p, int r1, int c1, int r2, int c2) {
        int dr = Integer.signum(r2 - r1), dc = Integer.signum(c2 - c1);
        int n = 0;
        for (int r = r1 + dr, c = c1 + dc; r != r2 || c != c2; r += dr, c += dc) {
            if (p[idx(r, c)] != 0) n++;
        }
        return n;
    }

    /** 有胜利效果 → 界面上给一个开关（默认开，可关掉） */
    @Override public boolean winToggleable() { return true; }

    public static int pack(int side, int type) { return (side << 3) | type; }
    public static int type(int piece) { return piece & 7; }
}
