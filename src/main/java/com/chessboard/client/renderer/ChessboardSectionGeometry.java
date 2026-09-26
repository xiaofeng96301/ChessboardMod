package com.chessboard.client.renderer;

import com.chessboard.ChessboardMod;
import com.chessboard.SkinData;
import com.chessboard.block.ChessboardBlock;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.game.BoardGameLogic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
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
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * 一个区块里最多为<b>棋子</b>发多少个四边形，超了就只画棋盘、不画棋子。
     *
     * <p>这是<b>防崩闸门</b>，不是性能优化。26.3 把区块顶点上传换成了
     * {@code UberGpuBuffer} + {@code StagingBuffer}，而 {@code StagingBuffer.tryAppend} 里是
     * {@code if (size > capacity) throw new IllegalArgumentException(...)} —— <b>硬崩，不降级</b>。
     * 那个 staging buffer 固定 98 MiB（102,760,448），而且卡的是
     * <b>「一个区块 × 一个渲染层」的单次追加</b>，不是整场累计。
     *
     * <p>数字是实地标定的：铺 2500 块满员国际象棋（50×50）时，被填满的区块里约 222 块棋盘
     * × 4124 面 = 915k 四边形，发出了 112 MiB（117,497,856）→ <b>128 字节/四边形</b>
     * （正好 32 字节/顶点，自洽）。98 MiB 上限 ≈ <b>803k 四边形</b>。
     *
     * <p>棋子模型已经整体降过面（见 tools/simplify_piece_models.py）：一块满员国际象棋
     * 从 4124 面降到 <b>2738 面</b>，所以上面那种区块现在是 222 × 2738 = 608k 四边形（78 MB，
     * 占上限的 76%）。这里给 650k 的额度、约 150k 留给原版地形和其它 mod。
     *
     * <p>只统计棋子：棋盘本体的模型才几十个面，永远画得起；爆量的从来是棋子
     * （一块满员国际象棋 = 32 颗 × 上百个面）。
     */
    private static final int PIECE_QUAD_BUDGET = 650_000;

    /** 降级告警只发一次，别每个区块刷屏 */
    private static final AtomicBoolean BUDGET_WARNED = new AtomicBoolean();

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

        // 先扫有没有棋盘 —— 绝大多数区块没有，这样能避免每次网格重建都去取模型集合
        List<ChessboardBlockEntity> boards = null;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof ChessboardBlockEntity board)) continue;
            if (SectionPos.blockToSectionCoord(board.getBlockPos().getY()) != cy) continue;
            if (boards == null) boards = new ArrayList<>();
            boards.add(board);
        }
        if (boards == null) return; // 不注册 renderer，保留空区块优化

        BlockStateModelSet models;
        try {
            models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        } catch (Exception e) {
            return; // 模型尚未初始化完成
        }

        List<BoardGeometrySnapshot> snaps = new ArrayList<>(boards.size());
        for (ChessboardBlockEntity board : boards) snaps.add(snapshot(board, origin, models));

        final List<BoardGeometrySnapshot> list = snaps;
        final BlockPos sectionOrigin = origin;
        event.addRenderer(ctx -> emit(ctx, sectionOrigin, list, models));
    }

    /** 主线程：把棋盘状态拷贝成 worker 线程可安全只读的快照 */
    private static BoardGeometrySnapshot snapshot(ChessboardBlockEntity board, BlockPos origin,
                                                  BlockStateModelSet models) {
        BoardGameLogic g = board.gameLogic();
        int total = g.rows() * g.cols();
        int[] pieces = board.pieces().clone();
        boolean[] excluded = ChessboardAnimTracker.INSTANCE.exclusionSnapshot(board.getBlockPos(), total);

        // 主线程预构建方块状态：worker 线程不碰注册表
        Map<Integer, BlockState> states = new HashMap<>();
        Map<Integer, BlockState> charStates = new HashMap<>();
        for (int p : pieces) {
            if (p == 0 || states.containsKey(p)) continue;
            states.put(p, ChessboardPieceGeometry.stateFor(g, p));
            BlockState cs = ChessboardPieceGeometry.charStateFor(g, p);
            if (cs != null) charStates.put(p, cs);
        }
        BlockPos bp = board.getBlockPos();
        BlockState boardState = board.getBlockState();
        return new BoardGeometrySnapshot(bp, bp.subtract(origin),
                boardState.getValue(ChessboardBlock.FACING),
                g, pieces, excluded, states, charStates, boardState,
                resolveSkinned(board, models));
    }

    /** 主线程：把各槽位的皮肤方块 ID 解析成贴图（没设 / 解析不出来的槽留 null） */
    private static TextureAtlasSprite[] resolveSkinned(ChessboardBlockEntity board, BlockStateModelSet models) {
        String[] ids = board.skins();
        TextureAtlasSprite[] sprites = new TextureAtlasSprite[SkinData.SLOT_COUNT];
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = ChessboardPieceGeometry.skinSprite(SkinData.stateOf(ids[i]), models);
        }
        return sprites;
    }

    /** worker 线程：把静止棋子发射进区块顶点缓冲 */
    private static void emit(AddSectionGeometryEvent.SectionRenderingContext ctx, BlockPos sectionOrigin,
                             List<BoardGeometrySnapshot> snaps, BlockStateModelSet models) {
        float[] pos = new float[2];
        // 预算按整个区块算（不是按棋盘）：上限卡的就是「一个区块」的顶点数据量
        int pieceQuads = 0;
        int degraded = 0;

        for (BoardGeometrySnapshot s : snaps) {
            BoardGameLogic g = s.logic();
            int cols = g.cols();
            TextureAtlasSprite[] skins = s.skinSprites();
            // 每块棋盘一个上下文：内含原版光照器（逐顶点光照 + 面朝向明暗）
            var ec = new ChessboardPieceGeometry.EmitContext(ctx, models, s.boardPos(),
                    s.offset().getX(), s.offset().getY(), s.offset().getZ());
            // 棋盘本体先画，且不计入预算 —— 一个棋盘模型才几十个面，爆量的从来是棋子。
            // 先画它还有个好处：预算用完时棋盘是完整的，缺的只是棋子。
            ChessboardPieceGeometry.emitBoardSkin(ec, s.boardState(), skins[SkinData.SLOT_BOARD]);
            if (pieceQuads >= PIECE_QUAD_BUDGET) {
                degraded++;
                continue; // 见 PIECE_QUAD_BUDGET：宁可这块棋盘空着，也不能让区块顶点超上限崩游戏
            }
            for (int row = 0; row < g.rows(); row++) {
                for (int col = 0; col < cols; col++) {
                    int cell = row * cols + col;
                    int piece = s.pieces()[cell];
                    if (piece == 0 || s.excluded()[cell]) continue;
                    BlockState state = s.states().get(piece);
                    if (state == null) continue;
                    // 每方各自一个皮肤槽，所以同一块棋盘上不同阵营的棋子贴图可以不一样
                    int slot = SkinData.slotFor(g, piece);
                    TextureAtlasSprite skin = slot >= 0 ? skins[slot] : null;
                    ChessboardPieceGeometry.gridPos(g, s.facing(), row, col, pos);
                    ChessboardPieceGeometry.emitPiece(ec, state, pos[0], pos[1], g, s.facing(), piece, false, skin);
                    // 汉字：贴在棋子圆片上的第二层（本模组贴图，皮肤不会覆盖它）
                    BlockState charState = s.charStates().get(piece);
                    if (charState != null) {
                        ChessboardPieceGeometry.emitPiece(ec, charState, pos[0], pos[1], g, s.facing(), piece, true, skin);
                    }
                }
            }
            pieceQuads += ec.pieceQuads;
        }

        if (degraded > 0 && BUDGET_WARNED.compareAndSet(false, true)) {
            // 只报一次：这是「玩家造得太大」的提示，不是每帧错误，刷屏没意义
            ChessboardMod.LOGGER.warn(
                    "区块 {} 里的棋盘太多，几何量会超过原版顶点缓冲上限；已降级为只画棋盘、不画棋子（本次跳过 {} 块）。"
                            + "要恢复显示请把棋盘铺稀一些。",
                    sectionOrigin, degraded);
        }
    }
}
