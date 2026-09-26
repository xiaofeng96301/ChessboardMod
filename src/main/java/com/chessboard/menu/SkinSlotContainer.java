package com.chessboard.menu;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 皮肤槽位的物品容器。
 *
 * <p>里面<b>只放玩家自己拖进去的方块</b>，关闭界面时全部还给他 —— 这就是「放入不消耗」。
 * 当前皮肤长什么样不出现在槽位里，而是在界面上的文字标签里显示（见 {@link BoardSkinMenu#currentSkin}）。
 *
 * <p>早先的版本会把「当前皮肤」也当成一个物品塞进槽位里显示，结果那个物品玩家并不拥有，
 * 只能做成取不出来的展示品 —— 体验上就是「我东西卡在里面了」。所以改成纯文字显示。
 *
 * <p>这里<b>不需要</b>记录「哪个槽被玩家动过」：{@link BoardSkinMenu#applyFromSlots()} 是按
 * 「槽里有没有方块」判断的，空槽一律跳过（这正是「拿出来不回归默认」的实现），
 * 所以没有「区分从没动过的空格子和刚被清空的格子」这种需求。
 */
public class SkinSlotContainer extends SimpleContainer {

    public SkinSlotContainer(int size) {
        super(size);
    }

    /**
     * 把槽位里的方块全还给玩家，并清空槽位。
     *
     * <p>用在关界面和「重置样式」两处。不还就是把玩家的东西吞了。
     */
    public void returnToPlayer(Player player) {
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack stack = getItem(i);
            if (!stack.isEmpty()) {
                if (!player.getInventory().add(stack)) player.drop(stack, false);
                setItem(i, ItemStack.EMPTY);
            }
        }
    }
}
