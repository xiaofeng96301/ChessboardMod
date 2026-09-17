package com.chessboard.client.renderer;

import com.chessboard.block.ChessboardBlock;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.game.BoardGameLogic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把静止的棋子烘焙进区块几何（性能优化核心）。
 *
 * <p>方块实体渲染器每帧要为每颗棋子提交一次模型（五子棋满盘 225 次），
 * 这里改为在区块网格构建时一次性写入顶点，之后每帧零开销。
 *
 * <p>几何是<b>静态</b>的：只有区块重建时才刷新。因此
 * <ul>
 *   <li>正在动画的棋子由 {@link ChessboardAnimTracker} 标记后从烘焙中排除，交给渲染器逐帧画；</li>
 *   <li>中国象棋的文字走字体图集，无法烘焙，始终由渲染器绘制；</li>
 *   <li>动画结束、数据变化时由 tracker 触发区块重建。</li>
 * </ul>
 *
 * <p>线程模型：事件处理器在<b>主线程</b>运行，注册的 renderer 在<b>网格 worker 线程</b>运行。
 * 世界数据只能在处理器里读取并拷成快照，renderer 里不得触碰 Level / 方块实体。
 */
public final class ChessboardSectionGeometry {

    private ChessboardSectionGeometry() {}

    /** 棋盘客户端数据变化 → 更新动画状态并重建所在区块（由 ChessboardBlockEntity 的钩子调用） */
    public static void onBoardDataChanged(ChessboardBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || !level.isClientSide()) return;
        ChessboardAnimTracker t = ChessboardAnimTracker.INSTANCE;
        t.update(be);
        t.markDirty(be.getBlockPos());
    }

    /** 每 tick 驱动动画到期扫描（动画结束后把棋子烘回几何） */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ChessboardAnimTracker.INSTANCE.tick();
    }

    @SubscribeEvent
    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        Level level = event.getLevel();
        BlockPos origin = event.getSectionOrigin();
        int cy = SectionPos.blockToSectionCoord(origin.getY());
        int cx = SectionPos.blockToSectionCoord(origin.getX());
        int cz = SectionPos.blockToSectionCoord(origin.getZ());

        // 不强制加载区块；棋盘只占 1 格，必然只属于一个区块
        if (!(level.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) return;

        List<BoardGeometrySnapshot> snaps = null;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof ChessboardBlockEntity board)) continue;
            if (SectionPos.blockToSectionCoord(board.getBlockPos().getY()) != cy) continue;
            if (snaps == null) snaps = new ArrayList<>();
            snaps.add(snapshot(board, origin));
        }
        // 本区块没有棋盘（绝大多数情况）：不注册 renderer，保留空区块优化
        if (snaps == null) return;

        BlockStateModelSet models;
        try {
            models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        } catch (Exception e) {
            return; // 模型尚未初始化完成
        }
        final List<BoardGeometrySnapshot> list = snaps;
        event.addRenderer(ctx -> emit(ctx, list, models));
    }

    /** 主线程：把棋盘状态拷贝成 worker 线程可安全只读的快照 */
    private static BoardGeometrySnapshot snapshot(ChessboardBlockEntity board, BlockPos origin) {
        BoardGameLogic g = board.gameLogic();
        int total = g.rows() * g.cols();
        int[] pieces = board.pieces().clone();
        boolean[] excluded = ChessboardAnimTracker.INSTANCE.exclusionSnapshot(board.getBlockPos(), total);
        String[] materials = board.materials();

        // 主线程预构建方块状态：worker 线程不碰注册表
        Map<Integer, BlockState> states = new HashMap<>();
        for (int p : pieces) {
            if (p == 0) continue;
            states.computeIfAbsent(p, k -> ChessboardPieceGeometry.stateFor(g, k, materials));
        }
        BlockPos bp = board.getBlockPos();
        return new BoardGeometrySnapshot(bp, bp.subtract(origin),
                board.getBlockState().getValue(ChessboardBlock.FACING),
                g, pieces, excluded, states);
    }

    /** worker 线程：把静止棋子发射进区块顶点缓冲 */
    private static void emit(AddSectionGeometryEvent.SectionRenderingContext ctx,
                             List<BoardGeometrySnapshot> snaps, BlockStateModelSet models) {
        PoseStack ps = new PoseStack();
        QuadInstance qi = new QuadInstance();
        List<BlockStateModelPart> parts = new ObjectArrayList<>();
        float[] pos = new float[2];

        for (BoardGeometrySnapshot s : snaps) {
            // 与 BlockEntityRenderState.extractBase 同函数同位置，保证烘焙版与动态版光照一致
            int light = LevelRenderer.getLightCoords(ctx.getRegion(), s.boardPos());
            BoardGameLogic g = s.logic();
            int cols = g.cols();
            for (int row = 0; row < g.rows(); row++) {
                for (int col = 0; col < cols; col++) {
                    int cell = row * cols + col;
                    int piece = s.pieces()[cell];
                    if (piece == 0 || s.excluded()[cell]) continue;
                    BlockState state = s.states().get(piece);
                    if (state == null) continue;
                    ChessboardPieceGeometry.gridPos(g, s.facing(), row, col, pos);
                    ChessboardPieceGeometry.emitPiece(ctx, ps, qi, parts, models, state,
                            s.offset().getX(), s.offset().getY(), s.offset().getZ(),
                            pos[0], pos[1], g, s.facing(), piece, light);
                }
            }
        }
    }
}
