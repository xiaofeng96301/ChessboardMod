package com.chessboard.client.renderer;

import com.chessboard.block.ChessboardBlock;
import com.chessboard.api.DiceBoard;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent;

/**
 * 不给棋盘画原版的选中描边。
 *
 * <p>起因：骰子格那块「点击用的小突起」（见 {@link ChessboardBlock#hitsDiceBump}）。vanilla 的描边
 * 画的就是<b>用来命中的那个形状</b> —— {@code LevelRenderer.extractBlockOutline} 里拿
 * {@code state.getShape(...)} 直接喂给描边，于是凸起也被框出来，看上去像"点击区域多了个边框"。
 *
 * <p>为什么不干脆不加凸起：这个形状同时决定了射线能不能打到骰子。不给凸起，射线就会从立在
 * 板上的骰子头顶掠过去、或穿到它<b>后面那一格</b>，骰子反而最难命中。所以形状留着、描边去掉。
 *
 * <p>棋盘本来就是 1/16 厚的薄板，描边是一条几乎贴地的细框，去掉它几乎没有观感损失；
 * 方块实体被破坏的裂纹覆盖层不受影响（那是另一条路径）。
 *
 * <p>用 NeoForge 的事件（{@code isCanceled() → RETURN}，发生在算形状之前）而不是 mixin：
 * 26.1.2 与 26.3 都有这个事件、取消语义一致，不依赖任何版本相关的渲染类型；
 * 而 {@code addCustomRenderer} 那条路要自己实现 {@code CustomBlockOutlineRenderer}，
 * 两版的 render 签名不一样，会破坏"一个 jar 跑两版"。
 */
public final class BoardOutlineSuppressor {

    private BoardOutlineSuppressor() {}

    @SubscribeEvent
    public static void onExtractBlockOutline(ExtractBlockOutlineRenderStateEvent event) {
        // 只对「有骰子」的棋盘（形状里才有那块凸起）；别的棋盘描边照旧
        if (event.getBlockState().getBlock() instanceof ChessboardBlock block
                && block.getGameLogic(event.getBlockState()) instanceof DiceBoard) {
            event.setCanceled(true);
        }
    }
}
