package com.chessboard.block;

import net.minecraft.util.StringRepresentable;

/**
 * 棋盘木种变体。
 */
public enum ChessWood implements StringRepresentable {
    OAK("oak", "minecraft:block/stripped_oak_log"),
    SPRUCE("spruce", "minecraft:block/stripped_spruce_log"),
    BIRCH("birch", "minecraft:block/stripped_birch_log"),
    ACACIA("acacia", "minecraft:block/stripped_acacia_log"),
    DARK_OAK("dark_oak", "minecraft:block/stripped_dark_oak_log"),
    CHERRY("cherry", "minecraft:block/stripped_cherry_log"),
    PALE_OAK("pale_oak", "minecraft:block/stripped_pale_oak_log");

    private final String name;
    private final String texture;

    ChessWood(String name, String texture) {
        this.name = name;
        this.texture = texture;
    }

    @Override
    public String getSerializedName() { return name; }

    /** 侧面木纹贴图（模型 texture "2"） */
    public String texture() { return texture; }
}
