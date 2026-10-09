package com.chessboard.menu;

import com.chessboard.ChessboardMod;
import com.chessboard.SkinData;
import com.chessboard.block.ChessboardBlock;
import com.chessboard.blockentity.ChessboardBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Objects;

/**
 * 棋盘皮肤界面 —— 真物品槽。
 *
 * <p>每次打开的槽位数量是<b>跟着棋类变</b>的（井字棋 1 个、飞行棋 5 个……见
 * {@link SkinData#slotsFor}），所以布局不是常量。而 {@code AbstractContainerMenu.slots}
 * 的<b>下标就是协议里的槽位号</b>，客户端和服务端必须构造出完全一样的槽位列表，否则物品会串位。
 * 因此服务端在开界面时把布局（{@code shown}）一起写进额外数据发给客户端，两边都按它构造。
 *
 * <p>热键栏只放 9 格（需求明确说不用渲染背包）。要动主背包里的方块，先把它挪到热键栏。
 *
 * <p>槽位里<b>只有玩家自己放的方块</b>，当前皮肤改成界面上的文字显示（{@link #currentSkin}）——
 * 早期版本把当前皮肤也塞进槽位当展示品，结果做出一个「取不出来的物品」，体验很糟。
 *
 * <p><b>皮肤只增不减</b>：槽位里有方块 → 下一 tick 就按它上色；槽位空着 → 这个槽<b>原样不动</b>。
 * 所以「放方块进去」立刻生效，而「拿出来」和「关界面」都不会把皮肤退回默认 —— 想退回默认
 * 只有点「重置样式」（{@link #BUTTON_RESET}）一条路。这样玩家可以放心地拿方块进去试色。
 */
public class BoardSkinMenu extends AbstractContainerMenu {

    /**
     * 菜单按钮 id：「重置样式」——把全部槽位的皮肤退回默认。
     *
     * <p>这是<b>唯一</b>会让皮肤退回默认的入口。方块拿出来、直接关界面都不会退（见
     * {@link #applyFromSlots()}）。
     */
    public static final int BUTTON_RESET = 0;
    /** 菜单按钮 id：切换「胜利判定」开关（棋盘自己的属性，见 {@code ChessboardBlockEntity#winFx}） */
    public static final int BUTTON_WIN = 1;

    // 布局常量（相对界面左上角）。客户端和服务端用同一份，所以必须是常量。
    // 行首 y 必须排在「开局方式」下拉的选项下面（选项到 y≈60 为止），否则物品会压在选项上
    // —— 界面是「先画控件、后画槽位」的顺序，槽位会盖住下拉。
    /** 皮肤槽行首的 y，每行 20（单行标签：「短名：当前材质」） */
    public static final int ROW_Y = 74, ROW_H = 20;
    /** 皮肤槽的 x（右列右端对齐） */
    public static final int SKIN_SLOT_X = 246;
    /**
     * 热键栏 9 格：排在面板底部。y 要大于最后一行槽位的底边（飞行棋 5 行时到 172），
     * 否则格子会和标签叠在一起。x 上它在左半边，和右列的皮肤槽不冲突。
     */
    public static final int HOTBAR_X = 57, HOTBAR_Y = 174;

    private final BlockPos boardPos;
    /** 这个棋盘要显示哪些皮肤槽（容器下标，顺序即显示顺序） */
    private final int[] shown;
    private final SkinSlotContainer skins;
    /** 菜单的主人。开菜单和读写皮肤都要用他 */
    private final Player owner;

    /** 服务端构造：由 MenuProvider 直接调，shown 由服务端按棋类算好 */
    public BoardSkinMenu(int id, Inventory playerInv, BlockPos pos, int[] shown) {
        super(ChessboardMod.BOARD_SKIN_MENU.get(), id);
        this.boardPos = pos;
        this.shown = shown;
        this.owner = playerInv.player;
        this.skins = new SkinSlotContainer(SkinData.SLOT_COUNT);

        // 皮肤槽：第 r 个显示槽绑到容器的 shown[r] 号槽
        for (int r = 0; r < shown.length; r++) {
            addSlot(new SkinSlot(skins, shown[r], SKIN_SLOT_X, ROW_Y + r * ROW_H));
        }
        // 玩家热键栏 9 格（容器下标 0..8）
        for (int x = 0; x < 9; x++) {
            addSlot(new Slot(playerInv, x, HOTBAR_X + x * 18, HOTBAR_Y));
        }
    }

    /** 客户端构造：布局跟着服务端的额外数据走，保证两边槽位列表一致 */
    public BoardSkinMenu(int id, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        this(id, playerInv, buf.readBlockPos(), buf.readVarIntArray());
    }

    /**
     * 某个皮肤槽当前是什么（方块 ID，null = 默认）。
     * 界面用它显示「当前材质」文字 —— 客户端读的是已同步的方块实体，和服务端一致。
     */
    public String currentSkin(int slot) {
        String[] all = currentSkins();
        return all != null && slot >= 0 && slot < all.length ? all[slot] : null;
    }

    /** 棋盘方块实体当前存的皮肤；没有（客户端还没同步 / 方块没了）返回 null */
    /**
     * 这块棋盘的胜利判定开关；读不到（客户端还没同步 / 方块没了）时按棋类默认值算。
     * 界面用它显示按钮文字。
     */
    public boolean winFx() {
        if (owner != null && owner.level().getBlockEntity(boardPos) instanceof ChessboardBlockEntity be) {
            return be.winFx();
        }
        return true;
    }

    private String[] currentSkins() {
        if (owner == null) return null;
        return owner.level().getBlockEntity(boardPos) instanceof ChessboardBlockEntity be
                ? be.skins() : null;
    }

    // ── 皮肤应用 ──

    /**
     * 每 tick 一次（{@code broadcastChanges} 由服务端驱动）：把槽位里的方块涂到棋盘上。
     *
     * <p><b>只增不减</b>是实现的关键：槽位里有方块就按它上色，<b>槽位空着就直接跳过、这个槽原样不动</b>。
     * 于是「把方块拿出来」和「关闭界面（方块被还回来）」都不会让皮肤退回默认 —— 想退回默认只有
     * 「重置样式」那一个入口。早先的版本把「空格子」当成「清除」，玩家换个方块试试、手一抖拿回来，
     * 皮肤就跳回默认，很困惑。
     *
     * <p>所以这里也不需要「哪个槽被玩家动过」这种状态：方块在不在槽里本身就把话说清楚了。
     */
    private void applyFromSlots() {
        // 只在服务端写。broadcastChanges 由 ServerPlayer 驱动，但万一哪天客户端也调，
        // 往客户端方块实体 setSkins 会白白触发一次 sendBlockUpdated，必须挡住
        if (owner == null || owner.level().isClientSide()) return;
        String[] current = currentSkins();
        if (current == null) return; // 方块没了
        String[] next = current.clone();
        boolean changed = false;
        for (int idx : shown) {
            if (idx < 0 || idx >= next.length) continue;
            ItemStack stack = skins.getItem(idx);
            if (stack.isEmpty()) continue; // ← 「拿出来不回归默认」就靠这一行
            String id = SkinData.blockIdOf(stack);
            // 不能当皮肤用的方块（带方块实体的那种）正常进不来（SkinSlot.mayPlace 挡了），
            // 真漏进来也只是不动它，绝不当成「清除」
            if (SkinData.stateOf(id) == null) continue;
            if (!Objects.equals(id, next[idx])) {
                next[idx] = id;
                changed = true;
            }
        }
        if (!changed) return;
        writeSkins(next);
    }

    /**
     * 「重置样式」：把这个棋盘显示的全部槽位写回默认。
     *
     * <p>调用前必须先把槽位清空（方块还给玩家）—— 否则下一 tick 的 {@link #applyFromSlots()}
     * 会照着槽里剩下的方块立刻把它们重新涂回去，重置等于没点。
     */
    private void resetAllSkins() {
        if (owner == null || owner.level().isClientSide()) return;
        String[] current = currentSkins();
        if (current == null) return;
        String[] next = current.clone();
        boolean changed = false;
        for (int idx : shown) {
            if (idx < 0 || idx >= next.length) continue;
            if (next[idx] != null) {
                next[idx] = null;
                changed = true;
            }
        }
        if (!changed) return;
        writeSkins(next);
    }

    /** 写回方块实体：一次写完，避免逐槽触发 N 次区块重发 */
    private void writeSkins(String[] next) {
        if (owner.level().getBlockEntity(boardPos) instanceof ChessboardBlockEntity be) {
            be.setSkins(Arrays.asList(next));
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        applyFromSlots();
    }

    // ── 菜单行为 ──

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == BUTTON_WIN) {
            if (owner.level().getBlockEntity(boardPos) instanceof ChessboardBlockEntity be) be.toggleWinFx();
            return true;
        }
        if (id != BUTTON_RESET) return false;
        // 顺序不能反：先把槽位清空（方块还给玩家），再把皮肤写回默认。
        // 槽里留着方块的话，下一 tick 的 applyFromSlots 会立刻把它们重新涂上去。
        skins.returnToPlayer(player);
        resetAllSkins();
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // 关门前再刷一次：玩家可能在同一个 tick 里放下方块又立刻关界面，那次「放下」还没轮到
        // broadcastChanges，槽位就会被下面这句清空 —— 补刷一下，「放进去就上色」就不挑时序了。
        // 它只会往槽位里涂色、不会退回默认，所以和「关界面不回归默认」不冲突
        applyFromSlots();
        // 只把玩家自己的方块还给他（「放入不消耗」）
        skins.returnToPlayer(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack inSlot = slot.getItem();
        ItemStack copy = inSlot.copy();
        int skinCount = shown.length;
        boolean moved = index < skinCount
                ? moveItemStackTo(inSlot, skinCount, slots.size(), true)   // 皮肤槽 → 热键栏
                : moveItemStackTo(inSlot, 0, skinCount, false);            // 热键栏 → 皮肤槽
        if (!moved) return ItemStack.EMPTY;
        if (inSlot.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockState(boardPos).getBlock() instanceof ChessboardBlock
                && player.distanceToSqr(Vec3.atCenterOf(boardPos)) <= 64.0;
    }

    /** 界面要显示哪些皮肤槽（容器下标，顺序即显示顺序），供界面画标签用 */
    public int[] shownSlots() {
        return shown;
    }

    /** 界面要用的棋盘坐标（读代码、发命令、查方块状态都要它） */
    public BlockPos boardPos() {
        return boardPos;
    }

    // ── 槽位 ──

    /**
     * 皮肤槽：只收「真能当皮肤用」的方块。
     *
     * <p>带方块实体的方块（箱子、熔炉……）会被 {@link SkinData#stateOf} 拒掉 ——
     * 它们的模型不能静态烘焙，套上去渲染不出来，放进去了也是白放，所以直接不收，
     * 玩家能立刻看到物品被弹回来。
     */
    public static class SkinSlot extends Slot {
        public SkinSlot(SkinSlotContainer container, int containerSlot, int x, int y) {
            super(container, containerSlot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return SkinData.isUsableSkin(stack);
        }
    }
}
