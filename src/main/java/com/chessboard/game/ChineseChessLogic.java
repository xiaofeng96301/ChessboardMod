package com.chessboard.game;

import java.util.Arrays;

/**
 * 中国象棋规则：10×9 棋盘，红/黑双方各 16 枚棋子。
 */
public class ChineseChessLogic implements BoardGameLogic {

    public static final ChineseChessLogic INSTANCE = new ChineseChessLogic();

    private static final int ROWS = 10, COLS = 9;

    @Override public int rows() { return ROWS; }
    @Override public int cols() { return COLS; }

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

    @Override
    public String pieceName(int piece) {
        if (piece == 0) return "";
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

    @Override public int textColor(int piece) { return side(piece) == 0 ? 0xFFCC2222 : 0xFF1A1A1A; }
    @Override public int side(int piece) { return (piece >> 3) & 1; }
    @Override public String codePrefix() { return "xq"; }
    @Override public String pieceModelPath(int piece) { return "chessboard:block/chinese_chesspiece"; }

    @Override
    public ClickResult onClick(int[] pieces, int selRow, int selCol, int clickRow, int clickCol) {
        int cp = pieces[idx(clickRow, clickCol)];
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

    // ── 工具 ──

    public static int pack(int side, int type) { return (side << 3) | type; }
    public static int type(int piece) { return piece & 7; }
    public static int idx(int row, int col) { return row * COLS + col; }
}
