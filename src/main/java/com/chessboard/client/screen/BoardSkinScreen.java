package com.chessboard.client.screen;

import com.chessboard.Config;
import com.chessboard.SkinData;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.ChessLogic;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.GomokuLogic;
import com.chessboard.menu.BoardSkinMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 棋盘皮肤界面 —— 真容器界面，皮肤槽是能拖拽的真物品槽。
 *
 * <p>布局说明（坐标都是相对界面左上角 {@code leftPos/topPos} 的）：
 * <ul>
 *   <li>左列：代码框 / 导入 / 复制 / 悔棋 / 右键开关 / 重置样式；</li>
 *   <li>右列：开局方式下拉 + 每个皮肤槽一行（左边文字标签，右边真槽位）。
 *       方块<b>放进去就立刻上色</b>；拿出来、关界面都不会退回默认，只有左列的「重置样式」会；</li>
 *   <li>面板下方：玩家热键栏 9 格。需求明确不要渲染背包，所以主背包 27 格没放进来 ——
 *       要用主背包里的方块，先把它挪到热键栏。</li>
 * </ul>
 *
 * <p><b>槽位位置必须由菜单决定</b>，界面只负责画标签和底框 —— 否则改了菜单忘了改界面，
 * 标签就会和槽位错位。
 */
public class BoardSkinScreen extends AbstractContainerScreen<BoardSkinMenu> {

    private static final Identifier BACKGROUND =
            Identifier.fromNamespaceAndPath("chessboard", "gui/configui.png");

    /** 背景图原始尺寸 */
    private static final int PANEL_W = 276, PANEL_H = 166;
    /** 界面总高：比原图高，多出来的一截留给热键栏（见 drawPanel 的接长做法） */
    private static final int IMAGE_W = PANEL_W, IMAGE_H = 204;
    /** 背景图里底边框的第一行。接长时从这里往下裁一条带补足高度 */
    private static final int PANEL_BOTTOM = 158;

    private static final int LEFT_X = 14, LEFT_W = 144;
    private static final int RIGHT_X = 164, RIGHT_W = 100;
    /**
     * 行内文字标签的 x（贴着槽位左边）。
     * 槽位本身的坐标<b>一律用 {@link BoardSkinMenu} 里的常量</b>，界面不留副本 ——
     * 两份常量一旦不同步，标签就会和槽位错位，而这种错位只有进游戏才看得出来。
     */
    private static final int ROW_LABEL_X = RIGHT_X;
    /** 控件区的起始 y：让开背景图顶部的标题栏（它占 0..8 行） */
    private static final int CONTENT_Y = 10;
    /** 标签最大像素宽度，超出截断 */
    private static final int LABEL_W = 76;

    private EditBox codeField;
    private boolean openActive;
    private final List<Button> openOptions = new ArrayList<>();

    public BoardSkinScreen(BoardSkinMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title, IMAGE_W, IMAGE_H);
    }

    @Override
    protected void init() {
        // 必须先让父类算好 leftPos/topPos，后面所有控件都相对它摆
        super.init();
        // 面板顶部 0..8 行是标题栏，控件从它下面开始摆
        int lx = leftPos, ty = topPos + CONTENT_Y;

        String currentCode = getCurrentCode();
        codeField = new EditBox(font, lx + LEFT_X, ty + 6, LEFT_W, 18, Component.literal(""));
        codeField.setMaxLength(2000);
        String hint = currentCode.length() > 20 ? currentCode.substring(0, 20) + "..." : currentCode;
        codeField.setHint(Component.literal(hint));
        addRenderableWidget(codeField);

        addRenderableWidget(Button.builder(Component.literal("导入"), btn -> {
                    if (minecraft != null && minecraft.player != null) {
                        String paste = codeField.getValue().isEmpty() ? currentCode : codeField.getValue();
                        sendCommand("import " + pos().getX() + " " + pos().getY() + " " + pos().getZ() + " " + paste);
                    }
                    onClose();
                })
                .bounds(lx + LEFT_X, ty + 30, 68, 16).build());

        addRenderableWidget(Button.builder(Component.literal("复制"), btn -> {
                    if (minecraft != null) minecraft.keyboardHandler.setClipboard(currentCode);
                    onClose();
                })
                .bounds(lx + LEFT_X + 76, ty + 30, 68, 16).build());

        addRenderableWidget(Button.builder(Component.literal("悔棋"), btn -> {
                    sendCmd("undo");
                    onClose();
                })
                .bounds(lx + LEFT_X, ty + 52, LEFT_W, 16).build());

        addRenderableWidget(Button.builder(rightClickMenuLabel(), btn -> {
                    Config.RIGHT_CLICK_OPENS_MENU.set(!Config.RIGHT_CLICK_OPENS_MENU.get());
                    Config.CLIENT_SPEC.save();
                    btn.setMessage(rightClickMenuLabel());
                })
                .bounds(lx + LEFT_X, ty + 74, LEFT_W, 16).build());

        // 重置是菜单按钮（服务端执行），不是网络包 —— 它还要把玩家自己的方块还回去。
        // 这是唯一会让皮肤退回默认的入口，所以不关界面：撤销是再放一遍方块，不是重开界面
        addRenderableWidget(Button.builder(Component.literal("重置样式"), btn -> {
                    if (minecraft != null && minecraft.gameMode != null) {
                        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, BoardSkinMenu.BUTTON_RESET);
                    }
                })
                .bounds(lx + LEFT_X, ty + 96, LEFT_W, 16).build());

        // ── 右列：开局方式下拉 ──
        addRenderableWidget(Button.builder(Component.literal("开局方式▾"), btn -> {
                    openActive = !openActive;
                    openOptions.forEach(o -> o.visible = openActive);
                })
                .bounds(lx + RIGHT_X, ty + 6, RIGHT_W, 16).build());

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
                    .bounds(lx + RIGHT_X, ty + 24 + i * 12, RIGHT_W, 12).build();
            opt.visible = false;
            openOptions.add(opt);
            addRenderableWidget(opt);
        }
    }

    // ── 绘制 ──

    /**
     * 背景：面板贴图 + 每个槽位的凹槽底。
     *
     * <p>原版容器界面的槽位底框是画在容器背景贴图里的，我们用的是自己的面板图，
     * 所以要自己把槽位底框画出来（物品图标由父类在这些底框之上绘制）。
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        drawPanel(graphics);
        for (Slot slot : menu.slots) {
            // 槽位底框画在 slot.x-1, slot.y-1：原版槽位的凹槽是 18×18、物品只占中间 16×16，
            // 按 slot.x/y 画会让物品看起来偏左上 1 像素
            drawSlotFrame(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
        }
    }

    /**
     * 画面板背景。
     *
     * <p>背景图是 276×166（行 0 黑外框、1-2 白高光、3-7 标题栏、8 分隔线、9..157 均匀内部、
     * 158 起底边框），但我们要把热键栏也包进面板，所以整块要比原图高。
     *
     * <p><b>接长不用「取子区域贴图」</b>（那个重载的参数含义容易记反，画出来会莫名其妙多一条
     * 边框、看着像两个框），改成：原图原样贴一次，再用 {@code enableScissor} 裁出底部那条带，
     * 把同一张图<b>整体往下平移</b>再贴一次。带里露出来的正好是「均匀内部 + 底边框」，
     * 接缝落在纯色区域上，完全看不出来。
     */
    private void drawPanel(GuiGraphicsExtractor graphics) {
        // 主体：原图原样（8 参重载，目标尺寸与纹理尺寸相同，没有歧义）
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos,
                0, 0, PANEL_W, PANEL_H, PANEL_W, PANEL_H);
        // 接长带：把同一张图往下平移 IMAGE_H-PANEL_H，只露出底边框所在的那一段
        int shift = IMAGE_H - PANEL_H;
        graphics.enableScissor(leftPos, topPos + PANEL_BOTTOM, leftPos + PANEL_W, topPos + IMAGE_H);
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos + shift,
                0, 0, PANEL_W, PANEL_H, PANEL_W, PANEL_H);
        graphics.disableScissor();
    }

    /** 凹槽底：暗框 + 亮内衬，和原版槽位的观感接近 */
    private static void drawSlotFrame(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
        graphics.fill(x + 1, y + 1, x + 16, y + 2, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + 2, y + 16, 0xFF373737);
    }

    /**
     * 皮肤槽的行标签：「短名：当前材质」，例如「棋盘：竹马赛克」，没设时显示「默认」。
     * 父类已经把坐标原点平移到面板左上角，所以这里用面板相对坐标。
     */
    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int[] shown = menu.shownSlots();
        for (int r = 0; r < shown.length; r++) {
            int y = BoardSkinMenu.ROW_Y + r * BoardSkinMenu.ROW_H;
            String text = SkinData.slotLabel(shown[r]) + "：" + currentSkinName(shown[r]);
            // 白字带阴影：面板底色未知，这样在深浅底上都看得清
            graphics.text(font, font.plainSubstrByWidth(text, LABEL_W), ROW_LABEL_X, y + 6,
                    0xFFFFFFFF, true);
        }
    }

    /** 某槽当前材质的显示名 */
    private String currentSkinName(int slot) {
        String id = menu.currentSkin(slot);
        if (SkinData.blankToNull(id) == null) return "默认";
        Identifier rl = Identifier.tryParse(id);
        Block block = rl == null ? null : BuiltInRegistries.BLOCK.getValue(rl);
        return block == null || block == Blocks.AIR ? id : block.getName().getString();
    }

    // ── 其他 ──

    private BlockPos pos() {
        return menu.boardPos();
    }

    private static Component rightClickMenuLabel() {
        return Component.literal("右键侧面打开菜单：" + (Config.RIGHT_CLICK_OPENS_MENU.get() ? "开" : "关"));
    }

    private BoardGameLogic gameLogicOf() {
        if (minecraft == null || minecraft.level == null) return null;
        BlockState state = minecraft.level.getBlockState(pos());
        return state.getBlock() instanceof com.chessboard.block.ChessboardBlock cb ? cb.getGameLogic(state) : null;
    }

    private boolean isBoard(Class<? extends BoardGameLogic> type) {
        return type.isInstance(gameLogicOf());
    }

    private boolean isChineseChessBoard() { return isBoard(ChineseChessLogic.class); }
    private boolean isGomokuBoard() { return isBoard(GomokuLogic.class); }
    private boolean isChessBoard() { return isBoard(ChessLogic.class); }

    private String getCurrentCode() {
        if (minecraft == null || minecraft.level == null) return "";
        if (minecraft.level.getBlockEntity(pos()) instanceof ChessboardBlockEntity board) {
            return board.gameLogic().encodePieces(board.pieces());
        }
        return "";
    }

    private void sendCmd(String action) {
        sendCommand(action + " " + pos().getX() + " " + pos().getY() + " " + pos().getZ());
    }

    private void sendCommand(String args) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.connection.sendCommand("chessboard " + args);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
