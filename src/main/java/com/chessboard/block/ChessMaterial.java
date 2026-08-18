package com.chessboard.block;

import net.minecraft.util.StringRepresentable;

/**
 * 棋子可配置材质（剥皮木头 + 磨制石）。
 */
public enum ChessMaterial implements StringRepresentable {
    OAK("oak", "橡木"),
    SPRUCE("spruce", "云杉木"),
    BIRCH("birch", "白桦木"),
    ACACIA("acacia", "金合欢木"),
    DARK_OAK("dark_oak", "深色橡木"),
    CHERRY("cherry", "樱花木"),
    PALE_OAK("pale_oak", "苍白橡木"),
    POLISHED_GRANITE("polished_granite", "磨制花岗岩"),
    POLISHED_DIORITE("polished_diorite", "磨制闪长岩"),
    POLISHED_ANDESITE("polished_andesite", "磨制安山岩"),
    POLISHED_DEEPSLATE("polished_deepslate", "磨制深板岩"),
    POLISHED_BLACKSTONE("polished_blackstone", "磨制黑石");

    private final String name;
    private final String zhName;

    ChessMaterial(String name, String zhName) {
        this.name = name;
        this.zhName = zhName;
    }

    /** 中文显示名（UI 用） */
    public String zhName() { return zhName; }

    @Override
    public String getSerializedName() { return name; }

    /** 按序列名查找，找不到返回 null */
    public static ChessMaterial find(String name) {
        for (ChessMaterial m : values()) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }

    /** 按序列名查找，找不到回退 OAK */
    public static ChessMaterial byName(String name) {
        ChessMaterial m = find(name);
        return m != null ? m : OAK;
    }
}
