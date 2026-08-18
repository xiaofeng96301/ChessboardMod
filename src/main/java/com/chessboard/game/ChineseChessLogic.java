package com.chessboard.game;

import java.util.Arrays;

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
        java.util.Random rnd = new java.util.Random();
        for (int side = 0; side <= 1; side++) {
            int[] types = new int[16];
            int n = 0;
            for (int i = 0; i < p.length; i++) if (p[i] != 0 && side(p[i]) == side) types[n++] = type(p[i]);
            for (int i = n - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = types[i]; types[i] = types[j]; types[j] = t;
            }
            int k = 0;
            for (int i = 0; i < p.length; i++) if (p[i] != 0 && side(p[i]) == side) p[i] = pack(side, types[k++]);
        }
        for (int i = 0; i < p.length; i++) if (p[i] != 0) p[i] |= HIDDEN_BIT;
    }

    /** 全暗棋开局：重置布局后，红黑双方的棋子值全部随机打乱位置（阵营也随机），全部盖上背面 */
    public void fullDarkStart(int[] p) {
        initBoard(p);
        java.util.ArrayList<Integer> positions = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> values = new java.util.ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            if (p[i] != 0) { positions.add(i); values.add(p[i]); }
        }
        java.util.Collections.shuffle(positions);
        java.util.Collections.shuffle(values);
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
        int cp = pieces[idx(clickRow, clickCol)];
        if (isHidden(cp)) return new ClickResult.Flip(clickRow, clickCol);
        if (selRow < 0) {
            return cp != 0 ? new ClickResult.Select(clickRow, clickCol) : new ClickResult.None();
        }
        int sp = pieces[idx(selRow, selCol)];
        if (cp != 0) {
            if (side(cp) == side(sp))
                return new ClickResult.Select(clickRow, clickCol);
            pieces[idx(selRow, selCol)] = 0;
            pieces[idx(clickRow, clickCol)] = sp;
            return new ClickResult.Move(selRow, selCol, clickRow, clickCol);
        }
        pieces[idx(selRow, selCol)] = 0;
        pieces[idx(clickRow, clickCol)] = sp;
        return new ClickResult.Move(selRow, selCol, clickRow, clickCol);
    }

    public static boolean isHidden(int piece) { return (piece & HIDDEN_BIT) != 0; }
    public static int reveal(int piece) { return piece & ~HIDDEN_BIT; }
    public static int hide(int piece) { return piece | HIDDEN_BIT; }

    public static int pack(int side, int type) { return (side << 3) | type; }
    public static int type(int piece) { return piece & 7; }
    public static int idx(int row, int col) { return row * COLS + col; }
}
