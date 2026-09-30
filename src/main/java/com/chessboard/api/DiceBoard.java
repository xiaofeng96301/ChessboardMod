package com.chessboard.api;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 「这个棋盘里有一颗会掷的骰子」—— 想让任何玩法都能加一格骰子就实现它。
 *
 * <p>框架（两条渲染路径、动画追踪）只认这个接口，不再 {@code instanceof} 某个具体棋类：
 * 骰子格在哪、哪颗是骰子、点数怎么编码，全由实现方回答；翻滚动画的形状则由下面这些
 * 默认方法给出（想换手感就覆写）。
 *
 * <p><b>接口里不许出现 Minecraft 类型</b>（同 {@link BoardGameLogic} 的理由）：实现方是
 * 纯 Java 的规则类，要能脱离游戏跑 jshell 自测。「骰子用哪个方块模型」在
 * {@code client.renderer.PieceModels} 里按棋类登记。
 *
 * <p>飞行棋的实现见 {@code FlightChessLogic}（基准值 10，即 11..16 是骰子）。
 */
public interface DiceBoard {

    /** 骰子所在格的下标；{@code -1} = 这个棋盘没有骰子格 */
    int diceCell();

    /** 这颗棋子是不是骰子 */
    boolean isDice(int piece);

    /** 骰子点数（1..6） */
    int faceOf(int piece);

    /** 点数（1..6）对应的棋子值 */
    int pieceForFace(int face);

    // ── 模型度量（骰子常是满方块模型，要缩回棋子大小、绕方块正中自转）──

    /** 骰子模型的缩放倍率：满方块模型（0..16）缩到 4/16 */
    default float diceModelScale() { return 4f / 16f; }

    /** 骰子模型的几何中心（方块空间）—— 满方块就是 0.5，三个轴同一个数 */
    default float dicePivot() { return 0.5f; }

    // ── 翻滚动画的形状（时长 / 圈数 / 斜角 / 抛起）──

    default int diceRollMs() { return 900; }
    default float diceRollTurns() { return 2f; }
    default float diceLeanDeg() { return 30f; }
    default float diceLeanIn() { return 0.25f; }
    default float diceLeanOut() { return 0.65f; }
    default float diceHop() { return 0.04f; }
    default float diceHopLand() { return 0.7f; }

    /**
     * 掷一次：把新点数写进 {@code pieces} 的骰子格。
     *
     * <p>点数的随机源在这里，规则只负责「什么时候允许掷、掷完产生什么结果」。
     *
     * @return {@code false} = 这一格不是骰子格（没掷）
     */
    default boolean roll(int[] pieces, int cell) {
        if (cell != diceCell()) return false;
        pieces[cell] = pieceForFace(ThreadLocalRandom.current().nextInt(1, 7));
        return true;
    }
}
