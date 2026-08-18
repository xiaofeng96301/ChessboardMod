package com.chessboard;

import com.chessboard.block.ChessboardBlock;
import com.chessboard.client.renderer.ChessboardRenderer;
import com.chessboard.client.screen.ChessboardScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.lang.reflect.Method;

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

    /** 26.1 的 setScreen；26.2 改名 setScreenAndShow，用反射兼容两个版本 */
    private static final Method SET_SCREEN = findSetScreen("setScreen");
    private static final Method SET_SCREEN_AND_SHOW = findSetScreen("setScreenAndShow");

    private static Method findSetScreen(String name) {
        try {
            return Minecraft.class.getMethod(name, Screen.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** 打开界面，自动适配 26.1 / 26.2 的方法名 */
    static void openScreen(Screen screen) {
        Method m = SET_SCREEN != null ? SET_SCREEN : SET_SCREEN_AND_SHOW;
        if (m == null) return;
        try {
            m.invoke(Minecraft.getInstance(), screen);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to open screen", e);
        }
    }

    public ChessboardClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(final FMLClientSetupEvent event) {
        BlockEntityRenderers.register(CHESSBOARD_BE.get(), ChessboardRenderer::new);
        ChessboardBlock.openScreenAction = pos -> openScreen(new ChessboardScreen(pos));
    }

    @SubscribeEvent
    static void registerKeys(final RegisterKeyMappingsEvent event) {
        event.register(OPEN_MENU);
    }
}
