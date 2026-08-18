package com.chessboard;

import com.chessboard.block.ChessMaterial;

/**
 * 棋子材质槽位定义（配置保存在棋盘方块实体上）。
 * 槽位：0=中国象棋, 1=国际象棋白, 2=国际象棋黑, 3=五子棋黑, 4=五子棋白, 5=五子棋灰
 * 值为 ChessMaterial 枚举名，null 表示默认。
 */
public final class MaterialData {

    private MaterialData() {}

    public static final int SLOT_CHINESE = 0;
    public static final int SLOT_CHESS_WHITE = 1;
    public static final int SLOT_CHESS_BLACK = 2;
    public static final int SLOT_GOMOKU_BLACK = 3;
    public static final int SLOT_GOMOKU_WHITE = 4;
    public static final int SLOT_GOMOKU_GRAY = 5;
    public static final int SLOT_COUNT = 6;

    /** 各槽位默认材质（未配置时使用，对应各游戏的原始外观） */
    public static ChessMaterial defaultMaterial(int slot) {
        return switch (slot) {
            case SLOT_CHESS_WHITE -> ChessMaterial.BIRCH;
            case SLOT_CHESS_BLACK -> ChessMaterial.DARK_OAK;
            case SLOT_GOMOKU_BLACK -> ChessMaterial.DARK_OAK;
            case SLOT_GOMOKU_WHITE -> ChessMaterial.PALE_OAK;
            case SLOT_GOMOKU_GRAY -> ChessMaterial.ACACIA;
            default -> ChessMaterial.OAK;
        };
    }
}
