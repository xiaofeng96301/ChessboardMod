package com.chessboard.game;

import java.util.Arrays;

/**
 * 五子棋规则：15×15 棋盘，黑先白后，点击空格落子。
 */
public class GomokuLogic implements BoardGameLogic {

    public static final GomokuLogic INSTANCE = new GomokuLogic();

    private static final int ROWS = 15, COLS = 15;

    /** 黑子 */
    public static final int BLACK = 1;
    /** 白子 */
    public static final int WHITE = 2;

    private int nextSide = 0; // 0=黑先, 1=白

    @Override public int rows() { return ROWS; }
    @Override public int cols() { return COLS; }

    @Override
    public void initBoard(int[] p) {
        Arrays.fill(p, 0);
        nextSide = 0;
    }

    @Override public String pieceName(int piece) { return ""; } // 五子棋不渲染文字

    @Override public int textColor(int piece) { return 0; }

    @Override public int side(int piece) {
        return piece == BLACK ? 0 : 1;
    }

    @Override public String codePrefix() { return "wz"; }
    @Override public float pieceScale() { return 0.25f / 1.5f; }

    @Override public String pieceModelPath(int piece) {
        return piece == BLACK ? "chessboard:block/gomoku_pieces_black" : "chessboard:block/gomoku_pieces_white";
    }

    @Override
    public ClickResult onClick(int[] pieces, int selRow, int selCol, int clickRow, int clickCol) {
        if (pieces[idx(clickRow, clickCol)] != 0) return new ClickResult.None();
        int stone = (nextSide == 0) ? BLACK : WHITE;
        pieces[idx(clickRow, clickCol)] = stone;
        nextSide ^= 1;
        return new ClickResult.Place(clickRow, clickCol);
    }

    public void toggleSide() { nextSide ^= 1; }

    public static int idx(int row, int col) { return row * COLS + col; }
}
