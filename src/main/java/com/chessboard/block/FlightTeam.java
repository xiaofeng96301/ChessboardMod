package com.chessboard.block;

import net.minecraft.util.StringRepresentable;

/**
 * 飞行棋的四支队伍。
 *
 * <p>它决定棋子圆片的默认队色 —— 也就是 {@code flight_piece_<name>.json} 里引用的那张
 * 原版混凝土贴图（贴图路径写在资源那侧，这里只提供队号与名字）。想换掉某个队的贴图
 * 走动态皮肤：{@code SkinData} 的 7..10 号槽位。
 *
 * <p>它同时也是 {@link FlightPieceBlock#TEAM} 的属性类型。
 * <b>顺序必须和 {@code FlightChessLogic.side()} 的返回值、以及皮肤槽位一一对应</b>。
 */
public enum FlightTeam implements StringRepresentable {

    RED("red", "红队"),
    YELLOW("yellow", "黄队"),
    BLUE("blue", "蓝队"),
    GREEN("green", "绿队");

    private final String name;
    private final String zhName;

    FlightTeam(String name, String zhName) {
        this.name = name;
        this.zhName = zhName;
    }

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
