package com.chessboard.client.screen;

import com.chessboard.Config;
import com.chessboard.MaterialData;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 棋盘管理界面 —— 左列操作区（导入/复制/悔棋/开关），右列下拉区（开局/棋盘材质/棋子材质）。
 * 背景图 gui/configui.png（276×166）居中绘制。
 */
public class ChessboardScreen extends Screen {

    private static final Identifier BACKGROUND = Identifier.fromNamespaceAndPath("chessboard", "gui/configui.png");
    private static final int BG_W = 276, BG_H = 166;

    private final BlockPos boardPos;
    private EditBox codeField;

    // ── 右列下拉状态 ──
    private String activeDropdown = null; // null / "open" / "wood" / "mat"
    private int expandedSlot = -1;
    private int materialOffset = 0;
    private int woodOffset = 0;

    private final List<Button> rightColumnToggles = new ArrayList<>();
    private final List<Button> openOptions = new ArrayList<>();
    private final List<Button> woodOptions = new ArrayList<>();
    private final Map<Integer, Button> toggleButtons = new HashMap<>();
    private final Map<Integer, List<Button>> materialOptions = new HashMap<>();

    public ChessboardScreen(BlockPos pos) {
        super(Component.literal("棋盘管理"));
        this.boardPos = pos;
    }

    @Override
    protected void init() {
        int cx = width / 2, cy = height / 2;

        // ── 左列 ──
        String currentCode = getCurrentCode();
        codeField = new EditBox(font, cx - 124, cy - 74, 144, 18, Component.literal(""));
        codeField.setMaxLength(2000);
        String hint = currentCode.length() > 20 ? currentCode.substring(0, 20) + "..." : currentCode;
        codeField.setHint(Component.literal(hint));
        addRenderableWidget(codeField);

        addRenderableWidget(Button.builder(Component.literal("导入"), btn -> {
                    if (minecraft.player != null) {
                        String paste = codeField.getValue().isEmpty() ? currentCode : codeField.getValue();
                        minecraft.player.connection.sendCommand(
                            "chessboard import " + boardPos.getX() + " " + boardPos.getY() + " " +
                            boardPos.getZ() + " " + paste);
                    }
                    onClose();
                })
                .bounds(cx - 124, cy - 50, 68, 16).build());

        addRenderableWidget(Button.builder(Component.literal("复制"), btn -> {
                    minecraft.keyboardHandler.setClipboard(currentCode);
                    onClose();
                })
                .bounds(cx - 48, cy - 50, 68, 16).build());

        addRenderableWidget(Button.builder(Component.literal("悔棋"), btn -> {
                    sendCmd("undo");
                    onClose();
                })
                .bounds(cx - 114, cy - 28, 100, 16).build());

        addRenderableWidget(Button.builder(rightClickMenuLabel(), btn -> {
                    Config.RIGHT_CLICK_OPENS_MENU.set(!Config.RIGHT_CLICK_OPENS_MENU.get());
                    Config.CLIENT_SPEC.save();
                    btn.setMessage(rightClickMenuLabel());
                })
                .bounds(cx - 124, cy + 44, 144, 16).build());

        // ── 右列：开局方式下拉 ──
        int rx = cx + 26; // 右列 x
        Button openToggle = Button.builder(Component.literal("开局方式▾"), btn -> {
                    if (activeDropdown != null && activeDropdown.equals("open")) {
                        closeDropdowns();
                    } else {
                        showDropdown(0);
                        activeDropdown = "open";
                        openOptions.forEach(o -> o.visible = true);
                    }
                })
                .bounds(rx, cy - 74, 100, 16).build();
        rightColumnToggles.add(openToggle);
        addRenderableWidget(openToggle);

        List<String[]> openActions = new ArrayList<>();
        openActions.add(new String[]{"默认开局", "reset"});
        if (isChineseChessBoard()) {
            openActions.add(new String[]{"暗棋开局", "darkstart"});
            openActions.add(new String[]{"全暗棋开局", "fulldarkstart"});
        }
        if (isGomokuBoard()) {
            openActions.add(new String[]{"随机开局", "randomstart"});
        }
        for (int i = 0; i < openActions.size(); i++) {
            String[] act = openActions.get(i);
            Button opt = Button.builder(Component.literal(act[0]), b -> {
                        sendCmd(act[1]);
                        onClose();
                    })
                    .bounds(rx, cy - 56 + i * 12, 100, 12).build();
            opt.visible = false;
            openOptions.add(opt);
            addRenderableWidget(opt);
        }

        // ── 右列：棋盘材质下拉 ──
        Button woodToggle = Button.builder(boardWoodLabel(), btn -> {
                    if (activeDropdown != null && activeDropdown.equals("wood")) {
                        closeDropdowns();
                    } else {
                        woodOffset = 0;
                        showDropdown(1);
                        activeDropdown = "wood";
                        updateWoodOptions(cy);
                    }
                })
                .bounds(rx, cy - 50, 100, 16).build();
        rightColumnToggles.add(woodToggle);
        addRenderableWidget(woodToggle);

        com.chessboard.block.ChessMaterial[] woods = com.chessboard.block.ChessMaterial.values();
        for (int i = 0; i < woods.length; i++) {
            com.chessboard.block.ChessMaterial w = woods[i];
            Button opt = Button.builder(Component.literal(w.zhName()), b -> {
                        if (minecraft.player != null) {
                            minecraft.player.connection.sendCommand(
                                    "chessboard wood " + boardPos.getX() + " " + boardPos.getY() + " " +
                                            boardPos.getZ() + " " + w.getSerializedName());
                        }
                        rightColumnToggles.get(1).setMessage(boardWoodLabel());
                        closeDropdowns();
                    })
                    .bounds(rx, cy - 32 + i * 12, 100, 12).build();
            opt.visible = false;
            woodOptions.add(opt);
            addRenderableWidget(opt);
        }

        // ── 右列：棋子材质下拉（1~3 个槽位）──
        addMaterialButton(cy, rx, MaterialData.SLOT_CHINESE, "中国象棋棋子", isChineseChessBoard(), 0);
        addMaterialButton(cy, rx, MaterialData.SLOT_CHESS_WHITE, "国际象棋白子", isChessBoard(), 0);
        addMaterialButton(cy, rx, MaterialData.SLOT_CHESS_BLACK, "国际象棋黑子", isChessBoard(), 1);
        addMaterialButton(cy, rx, MaterialData.SLOT_GOMOKU_BLACK, "五子棋黑子", isGomokuBoard(), 0);
        addMaterialButton(cy, rx, MaterialData.SLOT_GOMOKU_WHITE, "五子棋白子", isGomokuBoard(), 1);
        addMaterialButton(cy, rx, MaterialData.SLOT_GOMOKU_GRAY, "五子棋灰子", isGomokuBoard(), 2);
    }

    // ── 下拉管理 ──

    /** 材质槽位在右列中的编号（open=0, wood=1, 材质槽位从 2 起） */
    private final Map<Integer, Integer> slotIndexMap = new HashMap<>();
    private int materialButtonCount = 0;

    /** 第 N 个按钮展开时最多可见选项行数：1→7, 2→6, 3→5, 4→4, 5→4 */
    private static int maxVisibleFor(int index) {
        return Math.max(8 - (index + 1), 4);
    }

    /** 收起全部下拉，恢复右列按钮 */
    private void closeDropdowns() {
        activeDropdown = null;
        expandedSlot = -1;
        openOptions.forEach(o -> o.visible = false);
        woodOptions.forEach(o -> o.visible = false);
        materialOptions.forEach((s, opts) -> opts.forEach(o -> o.visible = false));
        rightColumnToggles.forEach(b -> b.visible = true);
    }

    /** 展开第 index 个下拉：隐藏其下方按钮（编号更大），设置选项可见 */
    private void showDropdown(int index) {
        closeDropdowns();
        // 隐藏下方按钮（编号 > index）
        for (int i = index + 1; i < rightColumnToggles.size(); i++) {
            rightColumnToggles.get(i).visible = false;
        }
    }

    /** 材质槽位按钮：纵向排列在右列，点击展开选项（滚动显示） */
    private void addMaterialButton(int cy, int rx, int slot, String label, boolean visible, int index) {
        if (!visible) return;
        int y = cy - 28 + index * 20;
        int globalIndex = 2 + materialButtonCount++;
        slotIndexMap.put(slot, globalIndex);
        Button toggle = Button.builder(materialLabel(slot, label), btn -> {
                    if (expandedSlot == slot && activeDropdown != null && activeDropdown.equals("mat")) {
                        closeDropdowns();
                    } else {
                        materialOffset = 0;
                        showDropdown(globalIndex);
                        activeDropdown = "mat";
                        expandedSlot = slot;
                        int maxVisible = maxVisibleFor(globalIndex);
                        List<Button> opts = materialOptions.get(slot);
                        for (int i = 0; i < opts.size(); i++) {
                            Button o = opts.get(i);
                            o.visible = i >= materialOffset && i < materialOffset + maxVisible;
                            o.setY(y + 18 + (i - materialOffset) * 12);
                        }
                    }
                })
                .bounds(rx, y, 100, 16).build();
        toggleButtons.put(slot, toggle);
        rightColumnToggles.add(toggle);
        addRenderableWidget(toggle);

        List<Button> options = new ArrayList<>();
        com.chessboard.block.ChessMaterial[] all = com.chessboard.block.ChessMaterial.values();
        for (int i = 0; i < all.length; i++) {
            com.chessboard.block.ChessMaterial mat = all[i];
            Button opt = Button.builder(Component.literal(mat.zhName()), b -> {
                        selectMaterial(slot, mat, label);
                    })
                    .bounds(rx, y, 100, 12).build();
            opt.visible = false;
            options.add(opt);
            addRenderableWidget(opt);
        }
        materialOptions.put(slot, options);
    }

    /** 选择材质：本地生效 + 发服务端 + 收起 */
    private void selectMaterial(int slot, com.chessboard.block.ChessMaterial mat, String label) {
        if (minecraft.level != null
                && minecraft.level.getBlockEntity(boardPos) instanceof ChessboardBlockEntity be) {
            be.setMaterial(slot, mat.getSerializedName());
        }
        if (minecraft.player != null) {
            minecraft.player.connection.send(
                    new com.chessboard.network.SetMaterialPayload(boardPos, slot, mat.getSerializedName()));
        }
        Button toggle = toggleButtons.get(slot);
        if (toggle != null) toggle.setMessage(materialLabel(slot, label));
        closeDropdowns();
    }

    /** 棋盘材质按钮文字（显示当前材质） */
    private Component boardWoodLabel() {
        if (minecraft.level != null) {
            var st = minecraft.level.getBlockState(boardPos);
            if (st.hasProperty(com.chessboard.block.ChessboardBlock.WOOD)) {
                return Component.literal("棋盘材质：" + st.getValue(com.chessboard.block.ChessboardBlock.WOOD).zhName());
            }
        }
        return Component.literal("棋盘材质");
    }

    /** 更新棋盘材质选项的可见性与位置（滚动） */
    private void updateWoodOptions(int cy) {
        int maxVisible = maxVisibleFor(1);
        for (int i = 0; i < woodOptions.size(); i++) {
            Button o = woodOptions.get(i);
            o.visible = i >= woodOffset && i < woodOffset + maxVisible;
            o.setY(cy - 32 + (i - woodOffset) * 12);
        }
    }

    /** 鼠标滚轮滚动下拉选项 */
    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (activeDropdown == null) return super.mouseScrolled(x, y, scrollX, scrollY);
        int dir = scrollY > 0 ? -1 : 1;
        if (activeDropdown.equals("mat") && expandedSlot >= 0) {
            int maxOffset = com.chessboard.block.ChessMaterial.values().length - maxVisibleFor(slotIndexMap.getOrDefault(expandedSlot, 2));
            int next = materialOffset + dir;
            if (next >= 0 && next <= maxOffset) {
                materialOffset = next;
                int globalIndex = slotIndexMap.getOrDefault(expandedSlot, 2);
                int maxVisible = maxVisibleFor(globalIndex);
                List<Button> opts = materialOptions.get(expandedSlot);
                if (opts != null) {
                    for (int i = 0; i < opts.size(); i++) {
                        Button o = opts.get(i);
                        o.visible = i >= materialOffset && i < materialOffset + maxVisible;
                        o.setY(toggleButtons.get(expandedSlot).getY() + 18 + (i - materialOffset) * 12);
                    }
                }
                return true;
            }
        } else if (activeDropdown.equals("wood")) {
            int maxOffset = com.chessboard.block.ChessMaterial.values().length - maxVisibleFor(1);
            int next = woodOffset + dir;
            if (next >= 0 && next <= maxOffset) {
                woodOffset = next;
                updateWoodOptions(height / 2);
                return true;
            }
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    private Component materialLabel(int slot, String label) {
        return Component.literal(label + "：" + currentMaterial(slot).zhName());
    }

    /** 棋盘上某槽位的材质（未设置用该槽位默认材质） */
    private com.chessboard.block.ChessMaterial currentMaterial(int slot) {
        if (minecraft.level != null && minecraft.level.getBlockEntity(boardPos) instanceof ChessboardBlockEntity be) {
            String mat = be.material(slot);
            return mat != null
                    ? com.chessboard.block.ChessMaterial.byName(mat)
                    : MaterialData.defaultMaterial(slot);
        }
        return MaterialData.defaultMaterial(slot);
    }

    // ── 其他 ──

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int cx = width / 2, cy = height / 2;
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

    private boolean isChessBoard() {
        if (minecraft.level == null) return false;
        var be = minecraft.level.getBlockEntity(boardPos);
        return be instanceof ChessboardBlockEntity board && board.gameLogic() instanceof com.chessboard.game.ChessLogic;
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
