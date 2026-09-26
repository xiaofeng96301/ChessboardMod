package com.chessboard.block;

import net.minecraft.util.StringRepresentable;

/**
 * 飞行棋的四支队伍 —— 用原版混凝土的颜色区分棋子。
 *
 * <p>棋子本体（圆片）直接引用原版混凝土贴图，不再走 {@link ChessMaterial} 那套木种/石材，
 * 所以单独一个枚举；它同时也是 {@link FlightPieceBlock#TEAM} 的属性类型。
 */
public enum FlightTeam implements StringRepresentable {

    RED("red", "红队", "minecraft:block/red_concrete"),
    YELLOW("yellow", "黄队", "minecraft:block/yellow_concrete"),
    BLUE("blue", "蓝队", "minecraft:block/blue_concrete"),
    GREEN("green", "绿队", "minecraft:block/green_concrete");

    private final String name;
    private final String zhName;
    private final String texture;

    FlightTeam(String name, String zhName, String texture) {
        this.name = name;
        this.zhName = zhName;
        this.texture = texture;
    }

    /** 棋子本体贴图（原版混凝土） */
    public String texture() { return texture; }

    /** 中文显示名 */
    public String zhName() { return zhName; }

    @Override
    public String getSerializedName() { return name; }

    /** 队号 0..3；越界回退红队 */
    public static FlightTeam of(int team) {
        FlightTeam[] v = values();
        return team >= 0 && team < v.length ? v[team] : RED;
    }
}
