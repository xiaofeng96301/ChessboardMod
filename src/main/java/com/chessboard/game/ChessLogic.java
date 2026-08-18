package com.chessboard.game;

import java.util.Arrays;

/**
 * 国际象棋规则：8×8 棋盘，白/黑双方各 16 枚棋子。
 */
public class ChessLogic implements BoardGameLogic {

    public static final ChessLogic INSTANCE = new ChessLogic(1.9f, 12.2f);

    public static final int KING = 1, QUEEN = 2, BISHOP = 3, KNIGHT = 4, ROOK = 5, PAWN = 6;
    private static final int ROWS = 8, COLS = 8;

    private final float offset;
    private final float span;

    public ChessLogic() { this(1.9f, 12.2f); }

    public ChessLogic(float offset, float span) {
        this.offset = offset;
        this.span = span;
    }

    @Override public int rows() { return ROWS; }
    @Override public int cols() { return COLS; }

    @Override
    public void initBoard(int[] p) {
        Arrays.fill(p, 0);
        int[] back = {ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK};
        for (int c = 0; c < COLS; c++) {
            p[idx(0, c)] = pack(1, back[c]);
            p[idx(1, c)] = pack(1, PAWN);
            p[idx(6, c)] = pack(0, PAWN);
            p[idx(7, c)] = pack(0, back[c]);
        }
    }

    @Override
    public String pieceName(int piece) { return ""; }

    @Override public int textColor(int piece) { return 0; }
    @Override public int side(int piece) { return (piece >> 3) & 1; }
    @Override public String codePrefix() { return "ic"; }

    @Override public float pieceScale() { return 0.6f; }

    @Override public float pieceCenterX() { return 1f; }
    @Override public float pieceCenterZ() { return 1f; }

    @Override
    public float pieceYRotation(int piece) {
        return side(piece) == 0 ? 90 : 270;
    }
    @Override public float gridSpan() { return span; }
    @Override public float gridOffsetX() { return offset; }
    @Override public float gridOffsetZ() { return offset; }

    @Override
    public String pieceModelPath(int piece) {
        if (piece == 0) return "";
        int t = type(piece);
        String suffix = side(piece) == 0 ? "_white" : "";
        return switch (t) {
            case KING -> "chessboard:block/chess_wang" + suffix;
            case QUEEN -> "chessboard:block/chess_queen" + suffix;
            case BISHOP -> "chessboard:block/chess_elephant" + suffix;
            case KNIGHT -> "chessboard:block/chess_horse" + suffix;
            case ROOK -> "chessboard:block/chess_car" + suffix;
            case PAWN -> "chessboard:block/chess_soldier" + suffix;
            default -> "";
        };
    }

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

    public static int pack(int side, int type) { return (side << 3) | type; }
    public static int type(int piece) { return piece & 7; }
    public static int idx(int row, int col) { return row * COLS + col; }
}
