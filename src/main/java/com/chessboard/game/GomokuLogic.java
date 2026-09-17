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

    /** 连五检测：横向/纵向/两条对角线，返回连成 5 子的格子下标，无则 null（灰子不算） */
    @Override
    public int[] winLine(int[] pieces) {
        int[][] dirs = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int p = pieces[idx(r, c)];
                if (p == 0 || p == GRAY) continue;
                for (int[] d : dirs) {
                    int[] line = new int[5];
                    line[0] = idx(r, c);
                    boolean ok = true;
                    for (int k = 1; k < 5; k++) {
                        int rr = r + d[0] * k, cc = c + d[1] * k;
                        if (rr < 0 || rr >= ROWS || cc < 0 || cc >= COLS || pieces[idx(rr, cc)] != p) {
                            ok = false;
                            break;
                        }
                        line[k] = idx(rr, cc);
                    }
                    if (ok) return line;
                }
            }
        }
        return null;
    }

    @Override public int nextStone() { return nextSide == 0 ? BLACK : WHITE; }

    @Override public void toggleSide() { nextSide ^= 1; }

    @Override public void onUndo() { toggleSide(); }
}
