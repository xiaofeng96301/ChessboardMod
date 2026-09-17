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

    /**
     * 超过该距离（方块）就不再渲染棋盘上的动画棋子与文字，省掉每帧的模型提交和字形批处理。
     * 注意：烘焙进区块的静止棋子不受此项控制，由客户端「渲染距离」决定（超出即不渲染）。
     */
    public static final ModConfigSpec.IntValue BOARD_RENDER_DISTANCE = BUILDER
            .comment("Distance in blocks beyond which animated pieces and text on chessboards are not rendered.",
                     "Static pieces are baked into chunk geometry and follow the client render distance instead.")
            .defineInRange("boardRenderDistance", 32, 8, 512);

    public static final ModConfigSpec CLIENT_SPEC = BUILDER.build();
}
