package com.chessboard;

import com.chessboard.block.ChessboardBlock;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.client.renderer.ChessboardRenderer;
import com.chessboard.client.renderer.ChessboardSectionGeometry;
import com.chessboard.client.screen.BoardSkinScreen;
import com.chessboard.network.OpenBoardScreenPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import static com.chessboard.ChessboardMod.CHESSBOARD_BE;

@Mod(value = ChessboardMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = ChessboardMod.MODID, value = Dist.CLIENT)
public class ChessboardClient {

    @SuppressWarnings("deprecation")
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(ChessboardMod.MODID, "chessboard"));

    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.chessboard.open_menu",
            InputConstants.KEY_LSHIFT,
            CATEGORY);

    /**
     * 请求服务端打开皮肤界面。
     *
     * <p>容器菜单是服务端权威的，客户端不能自己 {@code setScreen} —— 只能发请求，由服务端校验后
     * 把界面推回来（见 {@code ChessboardMod.openBoardScreen}）。以前那套「反射调 setScreen /
     * setScreenAndShow 兼容两个版本」的写法因此整个删掉了。
     */
    public static void requestBoardScreen(BlockPos pos) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) connection.send(new OpenBoardScreenPayload(pos));
    }

    public ChessboardClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(final FMLClientSetupEvent event) {
        BlockEntityRenderers.register(CHESSBOARD_BE.get(), ChessboardRenderer::new);
        // 静止棋子烘焙进区块几何：数据变化时更新动画状态并重建所在区块
        ChessboardBlockEntity.clientDataHook = ChessboardSectionGeometry::onBoardDataChanged;
        NeoForge.EVENT_BUS.register(ChessboardSectionGeometry.class);
    }

    @SubscribeEvent
    static void registerKeys(final RegisterKeyMappingsEvent event) {
        event.register(OPEN_MENU);
    }

    @SubscribeEvent
    static void registerScreens(final RegisterMenuScreensEvent event) {
        event.register(ChessboardMod.BOARD_SKIN_MENU.get(), BoardSkinScreen::new);
    }
}
