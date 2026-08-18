package com.chessboard.client.screen;

import com.chessboard.Config;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.GomokuLogic;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 棋盘管理界面 —— 悔棋、重置、导入/导出棋局码、特殊模式开局。
 * 背景图 gui/configui.png（276×166）居中绘制。
 */
public class ChessboardScreen extends Screen {

    private static final Identifier BACKGROUND = Identifier.fromNamespaceAndPath("chessboard", "gui/configui.png");
    private static final int BG_W = 276, BG_H = 166;

    private final BlockPos boardPos;
    private EditBox codeField;

    public ChessboardScreen(BlockPos pos) {
        super(Component.literal("棋盘管理"));
        this.boardPos = pos;
    }

    @Override
    protected void init() {
        int cx = width / 2, cy = height / 2;

        // 棋局码输入框
        String currentCode = getCurrentCode();
        codeField = new EditBox(font, cx - 118, cy - 74, 236, 18, Component.literal(""));
        codeField.setMaxLength(2000);
        String hint = currentCode.length() > 20 ? currentCode.substring(0, 20) + "..." : currentCode;
        codeField.setHint(Component.literal(hint));
        addRenderableWidget(codeField);

        // 导入 / 复制 / 重置
        addRenderableWidget(Button.builder(Component.literal("导入"), btn -> {
                    if (minecraft.player != null) {
                        String paste = codeField.getValue().isEmpty() ? currentCode : codeField.getValue();
                        minecraft.player.connection.sendCommand(
                            "chessboard import " + boardPos.getX() + " " + boardPos.getY() + " " +
                            boardPos.getZ() + " " + paste);
                    }
                    onClose();
                })
                .bounds(cx - 113, cy - 46, 70, 20).build());

        addRenderableWidget(Button.builder(Component.literal("复制"), btn -> {
                    minecraft.keyboardHandler.setClipboard(currentCode);
                    onClose();
                })
                .bounds(cx - 35, cy - 46, 70, 20).build());

        addRenderableWidget(Button.builder(Component.literal("重置"), btn -> {
                    sendCmd("reset");
                    onClose();
                })
                .bounds(cx + 43, cy - 46, 70, 20).build());

        // 悔棋
        addRenderableWidget(Button.builder(Component.literal("悔棋"), btn -> {
                    sendCmd("undo");
                    onClose();
                })
                .bounds(cx - 50, cy - 18, 100, 20).build());

        // ── 特殊模式区 ──
        if (isChineseChessBoard()) {
            addRenderableWidget(Button.builder(Component.literal("暗棋开局"), btn -> {
                        sendCmd("darkstart");
                        onClose();
                    })
                    .bounds(cx - 109, cy + 10, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("全暗棋开局"), btn -> {
                        sendCmd("fulldarkstart");
                        onClose();
                    })
                    .bounds(cx - 1, cy + 10, 110, 20).build());
        }
        if (isGomokuBoard()) {
            addRenderableWidget(Button.builder(Component.literal("随机开局"), btn -> {
                        sendCmd("randomstart");
                        onClose();
                    })
                    .bounds(cx - 70, cy + 10, 140, 20).build());
        }

        // 右键侧面开关
        addRenderableWidget(Button.builder(rightClickMenuLabel(), btn -> {
                    Config.RIGHT_CLICK_OPENS_MENU.set(!Config.RIGHT_CLICK_OPENS_MENU.get());
                    Config.CLIENT_SPEC.save();
                    btn.setMessage(rightClickMenuLabel());
                })
                .bounds(cx - 110, cy + 38, 220, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int cx = width / 2, cy = height / 2;
        // 背景垫底
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND,
                cx - BG_W / 2, cy - BG_H / 2, 0, 0, BG_W, BG_H, BG_W, BG_H);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private static Component rightClickMenuLabel() {
        return Component.literal("右键侧面打开菜单：" + (Config.RIGHT_CLICK_OPENS_MENU.get() ? "开" : "关"));
    }

    private boolean isChineseChessBoard() {
        if (minecraft.level == null) return false;
        var be = minecraft.level.getBlockEntity(boardPos);
        return be instanceof ChessboardBlockEntity board && board.gameLogic() instanceof ChineseChessLogic;
    }

    private boolean isGomokuBoard() {
        if (minecraft.level == null) return false;
        var be = minecraft.level.getBlockEntity(boardPos);
        return be instanceof ChessboardBlockEntity board && board.gameLogic() instanceof GomokuLogic;
    }

    private String getCurrentCode() {
        if (minecraft.level == null) return "";
        var be = minecraft.level.getBlockEntity(boardPos);
        if (be instanceof ChessboardBlockEntity board)
            return board.gameLogic().encodePieces(board.pieces());
        return "";
    }

    private void sendCmd(String action) {
        if (minecraft.player != null) {
            minecraft.player.connection.sendCommand(
                    "chessboard " + action + " " + boardPos.getX() + " " + boardPos.getY() + " " + boardPos.getZ());
        }
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (minecraft.options.keyInventory.matches(keyEvent)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyEvent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
