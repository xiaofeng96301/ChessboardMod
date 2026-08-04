package com.chessboard;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组客户端配置文件。
 */
public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 开启后，右键棋盘侧面直接打开管理界面（无需按住菜单键），上下面仍正常落子 */
    public static final ModConfigSpec.BooleanValue RIGHT_CLICK_OPENS_MENU = BUILDER
            .comment("Right-clicking the side faces of the chessboard opens the management screen without holding the menu key.",
                     "Top/bottom faces still place/move pieces normally.")
            .define("rightClickOpensMenu", false);

    public static final ModConfigSpec CLIENT_SPEC = BUILDER.build();
}
