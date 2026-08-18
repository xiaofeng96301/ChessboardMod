package com.chessboard.game;

import java.util.Arrays;

/**
 * 井字棋规则：3×3 棋盘，O 先 X 后，轮流落子。
 * 棋子模型正面圆圈反面 X，X 方渲染时翻转模型。
 */
public class TicTacToeLogic implements PlaceGameLogic {

    public static final TicTacToeLogic INSTANCE = new TicTacToeLogic(3.2f, 9.5f);

    private static final int ROWS = 3, COLS = 3;
    static final int O = 1, X = 2;

    private final float offset;
    private final float span;
    private int nextSide = 0;

    public TicTacToeLogic(float offset, float span) {
        this.offset = offset;
        this.span = span;
    }

    @Override public int rows() { return ROWS; }
    @Override public int cols() { return COLS; }

    @Override
    public void initBoard(int[] p) {
        Arrays.fill(p, 0);
        nextSide = 0;
    }

    @Override public String pieceName(int piece) { return ""; }
    @Override public int textColor(int piece) { return 0; }
    @Override public int side(int piece) { return piece == O ? 0 : 1; }
    @Override public String codePrefix() { return "jz"; }
    @Override public float pieceScale() { return 0.55f; }
    @Override public float pieceCenterX() { return 3.5f; }
    @Override public float pieceCenterZ() { return 3.5f; }
    @Override public float gridSpan() { return span; }
    @Override public float gridOffsetX() { return offset; }
    @Override public float gridOffsetZ() { return offset; }

    @Override public boolean pieceFlipX(int piece) { return piece == X; }
    @Override public float pieceHeight() { return 1.002f / 16f; }

    @Override public int nextStone() { return nextSide == 0 ? O : X; }

    @Override public void toggleSide() { nextSide ^= 1; }

    @Override public void onUndo() { toggleSide(); }
}
