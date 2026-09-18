package com.chessboard.block;

import net.minecraft.util.StringRepresentable;

/**
 * 中国象棋棋子上的汉字 —— 做成贴图，就能和棋子一起烘焙进区块几何，
 * 不再需要逐帧的字形渲染（字体图集和地形图集不是一套，塞不进区块顶点）。
 *
 * <p>红黑两方的同一个字是两张不同贴图（红方 {@code 0xFFCC2222}、黑方 {@code 0xFF1A1A1A}），
 * 所以按「字 + 颜色」共 14 个变体。
 */
public enum ChessChar implements StringRepresentable {
    SHUAI("shuai_red", 1, 0),
    JIANG("jiang_black", 1, 1),
    SHI_RED("shi_red", 2, 0),
    SHI_BLACK("shi_black", 2, 1),
    XIANG_RED("xiang_red", 3, 0),
    XIANG_BLACK("xiang_black", 3, 1),
    MA_RED("ma_red", 4, 0),
    MA_BLACK("ma_black", 4, 1),
    JU_RED("ju_red", 5, 0),
    JU_BLACK("ju_black", 5, 1),
    PAO_RED("pao_red", 6, 0),
    PAO_BLACK("pao_black", 6, 1),
    BING("bing_red", 7, 0),
    ZU("zu_black", 7, 1);

    private final String name;
    /** 棋子类型，与 {@code ChineseChessLogic.type} 一致：1帅/将 2仕/士 3相/象 4马 5车 6炮 7兵/卒 */
    private final int type;
    /** 阵营：0=红 1=黑 */
    private final int side;

    ChessChar(String name, int type, int side) {
        this.name = name;
        this.type = type;
        this.side = side;
    }

    @Override
    public String getSerializedName() { return name; }

    /** 按棋子类型与阵营取对应汉字，无匹配返回 null */
    public static ChessChar of(int type, int side) {
        for (ChessChar c : values()) {
            if (c.type == type && c.side == side) return c;
        }
        return null;
    }
}
