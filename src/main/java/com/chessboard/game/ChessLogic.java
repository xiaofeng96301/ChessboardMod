package com.chessboard.game;

import com.chessboard.SkinData;
import com.chessboard.api.BoardGameLogic;

import java.util.Arrays;
import java.util.List;

/**
 * 国际象棋规则：8×8 棋盘，白/黑双方各 16 枚棋子。
 */
public class ChessLogic implements BoardGameLogic {

    public static final ChessLogic INSTANCE = new ChessLogic(1.9f, 12.2f);

    public static final int KING = 1, QUEEN = 2, BISHOP = 3, KNIGHT = 4, ROOK = 5, PAWN = 6;
    private static final int ROWS = 8, COLS = 8;

    private final float offset;
    private final float span;

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

    // 模型（六种棋型 × 黑白两色）在 client.renderer.PieceModels 里登记 ——
    // 不放这里是为了让规则类保持纯 Java，能脱离 Minecraft 跑 jshell 自测。

    @Override public int skinSlot(int piece) {
        return side(piece) == 0 ? SkinData.SLOT_CHESS_WHITE : SkinData.SLOT_CHESS_BLACK;
    }

    @Override public int[] skinSlots() {
        return new int[]{SkinData.SLOT_BOARD, SkinData.SLOT_CHESS_WHITE, SkinData.SLOT_CHESS_BLACK};
    }

    @Override public float pieceScale() { return 0.6f; }

    /** 只有默认开局 */
    @Override public List<StartAction> startActions() {
        return List.of(new StartAction("默认开局", "reset"));
    }

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
    public ClickResult onClick(int[] pieces, int selRow, int selCol, int clickRow, int clickCol) {
        return onClickMove(pieces, selRow, selCol, clickRow, clickCol);
    }

    /** 一方的王不在了 → 对面赢，报赢家全体棋子（表演是原地自转一圈，见 {@link #winStyle}） */
    @Override
    public int[] winCells(int[] pieces) {
        boolean white = hasKing(pieces, 0), black = hasKing(pieces, 1);
        if (white == black) return null;               // 都在或都没了（空盘）→ 不判
        int[] cells = cellsOfSide(pieces, white ? 0 : 1);
        return cells.length == 0 ? null : cells;
    }

    @Override public int winStyle() { return WIN_SPIN; }

    private boolean hasKing(int[] p, int s) {
        for (int v : p) if (v != 0 && side(v) == s && type(v) == KING) return true;
        return false;
    }

    private int[] cellsOfSide(int[] p, int s) {
        int n = 0;
        for (int v : p) if (v != 0 && side(v) == s) n++;
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < p.length; i++) if (p[i] != 0 && side(p[i]) == s) out[k++] = i;
        return out;
    }

    /** 有胜利效果 → 界面上给一个开关（默认开，可关掉） */
    @Override public boolean winToggleable() { return true; }

    public static int pack(int side, int type) { return (side << 3) | type; }
    public static int type(int piece) { return piece & 7; }
}
