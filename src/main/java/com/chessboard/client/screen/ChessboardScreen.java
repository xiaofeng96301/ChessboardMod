package com.chessboard.client.screen;

import com.chessboard.Config;
import com.chessboard.blockentity.ChessboardBlockEntity;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 棋盘管理界面 —— 悔棋、重置、导入/导出棋局码。
 */
public class ChessboardScreen extends Screen {

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
        codeField = new EditBox(font, cx - 90, cy - 50, 180, 20, Component.literal(""));
        codeField.setMaxLength(2000);
        String hint = currentCode.length() > 20 ? currentCode.substring(0, 20) + "..." : currentCode;
        codeField.setHint(Component.literal(hint));
        addRenderableWidget(codeField);

        // 导入 / 复制按钮
        addRenderableWidget(Button.builder(Component.literal("导入"), btn -> {
                    if (minecraft.player != null) {
                        String paste = codeField.getValue().isEmpty() ? currentCode : codeField.getValue();
                        minecraft.player.connection.sendCommand(
                            "chessboard import " + boardPos.getX() + " " + boardPos.getY() + " " +
                            boardPos.getZ() + " " + paste);
                    }
                    onClose();
                })
                .bounds(cx - 92, cy - 25, 60, 20).build());

        addRenderableWidget(Button.builder(Component.literal("复制"), btn -> {
                    minecraft.keyboardHandler.setClipboard(currentCode);
                    onClose();
                })
                .bounds(cx - 30, cy - 25, 60, 20).build());

        addRenderableWidget(Button.builder(Component.literal("重置"), btn -> {
                    sendCmd("reset");
                    onClose();
                })
                .bounds(cx + 32, cy - 25, 60, 20).build());

        addRenderableWidget(Button.builder(Component.literal("悔棋"), btn -> {
                    sendCmd("undo");
                    onClose();
                })
                .bounds(cx - 50, cy + 5, 100, 20).build());

        addRenderableWidget(Button.builder(rightClickMenuLabel(), btn -> {
                    Config.RIGHT_CLICK_OPENS_MENU.set(!Config.RIGHT_CLICK_OPENS_MENU.get());
                    Config.CLIENT_SPEC.save();
                    btn.setMessage(rightClickMenuLabel());
                })
                .bounds(cx - 100, cy + 32, 200, 20).build());
    }

    private static Component rightClickMenuLabel() {
        return Component.literal("右键侧面打开菜单：" + (Config.RIGHT_CLICK_OPENS_MENU.get() ? "开" : "关"));
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
