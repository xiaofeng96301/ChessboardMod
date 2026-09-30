package com.chessboard.game;

/**
 * 骰子点数的编码工具 —— 无状态、纯函数，任何玩法都能拿去用。
 *
 * <p>编码就是「基准值 + 点数」：飞行棋把基准取成 10（于是 11..16 是骰子，1..4 仍是单架飞机，
 * 老存档不用迁移）。基准由使用方自己定，这里不假设任何棋类。
 *
 * <p>放在 {@code game} 包而不是 {@code api}：它是实现细节的复用，不是对外契约。
 */
public final class DiceValues {

    private DiceValues() {}

    /** 点数（1..6）编码成棋子值 */
    public static int encode(int base, int face) {
        return base + Math.clamp(face, 1, 6);
    }

    /** 这个棋子值是不是骰子（落在 {@code base+1 .. base+6}） */
    public static boolean is(int value, int base) {
        return value > base && value <= base + 6;
    }

    /** 取骰子点数；不是骰子时返回 1（调用方应先问 {@link #is}） */
    public static int face(int value, int base) {
        return is(value, base) ? value - base : 1;
    }
}
