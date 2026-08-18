package com.chessboard.game;

import java.util.Arrays;

/**
 * 五子棋规则：15×15 棋盘，黑先白后，点击空格落子。
 */
public class GomokuLogic implements PlaceGameLogic {

    public static final GomokuLogic INSTANCE = new GomokuLogic(1f, 14f);

    private static final int ROWS = 15, COLS = 15;

    private final float offset;
    private final float span;

    public GomokuLogic(float offset, float span) {
        this.offset = offset;
        this.span = span;
    }

    /** 黑子 */
    public static final int BLACK = 1;
    /** 白子 */
    public static final int WHITE = 2;
    /** 灰子（随机开局障碍） */
    public static final int GRAY = 3;

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
    @Override public float gridSpan() { return span; }
    @Override public float gridOffsetX() { return offset; }
    @Override public float gridOffsetZ() { return offset; }

    public static boolean isGray(int piece) { return piece == GRAY; }

    @Override public int nextStone() { return nextSide == 0 ? BLACK : WHITE; }

    @Override public void toggleSide() { nextSide ^= 1; }

    @Override public void onUndo() { toggleSide(); }
}
