package com.chessboard.game;

import com.chessboard.SkinData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

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

    // 模型（黑白双方 + 随机开局的灰色障碍子）在 client.renderer.PieceModels 里登记 ——
    // 不放这里是为了让规则类保持纯 Java，能脱离 Minecraft 跑 jshell 自测。

    @Override public int skinSlot(int piece) {
        if (isGray(piece)) return SkinData.SLOT_GOMOKU_GRAY;
        return side(piece) == 0 ? SkinData.SLOT_GOMOKU_BLACK : SkinData.SLOT_GOMOKU_WHITE;
    }

    @Override public int[] skinSlots() {
        return new int[]{SkinData.SLOT_BOARD, SkinData.SLOT_GOMOKU_BLACK,
                SkinData.SLOT_GOMOKU_WHITE, SkinData.SLOT_GOMOKU_GRAY};
    }

    @Override
    public List<StartAction> startActions() {
        return List.of(new StartAction("随机开局", "randomstart"));
    }

    /** 随机开局：摆 3~10 颗灰色障碍子；<b>逐颗</b>推历史，悔棋时也逐颗回退（与改前一致） */
    @Override
    public Start startBoard(int[] pieces, String mode, Consumer<int[]> historyPush) {
        if (!"randomstart".equals(mode)) {
            if (!"reset".equals(mode)) return Start.UNSUPPORTED;
            initBoard(pieces);
            return Start.PLAIN;
        }
        initBoard(pieces);
        List<Integer> empty = new ArrayList<>();
        for (int i = 0; i < pieces.length; i++) if (pieces[i] == 0) empty.add(i);
        if (!empty.isEmpty()) {
            Collections.shuffle(empty);
            int count = Math.min(3 + ThreadLocalRandom.current().nextInt(8), empty.size()); // 3~10
            for (int i = 0; i < count; i++) {
                int idx = empty.get(i);
                pieces[idx] = GRAY;
                // 一条单格增量：这个格子放了灰子、旧值是空
                historyPush.accept(new int[]{1, idx, 0});
            }
        }
        return Start.PLAIN;
    }
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
