package com.chessboard.block;

import com.chessboard.ChessboardMod;
import com.chessboard.SkinData;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.ChineseChessLogic;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collections;
import java.util.List;

/**
 * 通用棋盘方块 —— 框架层。
 *
 * <p>棋盘为 1/16 格厚的薄板，方块状态只有水平朝向（{@link #FACING}）与无框（{@link #FRAMELESS}）。
 * 外观（边框材质）不再走方块状态的木种变体，而是由方块实体上的<b>动态皮肤</b>决定，
 * 见 {@link com.chessboard.SkinData}。构造时传入带框/无框两套游戏逻辑（格子参数不同）。
 */
public class ChessboardBlock extends BaseEntityBlock {

    /**
     * 方块 codec。
     *
     * <p><b>不能用 {@code simpleCodec} / {@code propertiesCodec}</b>：26.3 把整套方块 codec 体系删了
     * （{@code BlockBehaviour.codec()}、{@code simpleCodec}、{@code propertiesCodec}、
     * {@code Properties.CODEC} 全没了），而 26.1.2 的 {@code codec()} 又是 abstract、必须实现。
     *
     * <p>改用 DFU 的 {@link MapCodec#unit}，两个版本都能编过，且不会丢语义 —— 方块状态的序列化走
     * {@code BlockState.CODEC}，它只依赖 {@code StateDefinition}（26.1.2 里是
     * {@code BlockState.codec(blockCodec, Block::defaultBlockState, Block::getStateDefinition)}），
     * 根本不经过这里。所以本方法只是个「必须存在」的占位，正常游戏流程不会调用到它。
     */
    public static final MapCodec<ChessboardBlock> CODEC = MapCodec.unit(
            () -> new ChessboardBlock(BlockBehaviour.Properties.of(), null, null));

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty FRAMELESS = BooleanProperty.create("frameless");

    private static final VoxelShape SHAPE_FRAMED = Shapes.box(0, 0, 0, 1, 1.0 / 16.0, 1);
    private static final VoxelShape SHAPE_FRAMELESS = Shapes.box(0.5 / 16.0, 0, 0.5 / 16.0, 15.5 / 16.0, 1.0 / 16.0, 15.5 / 16.0);

    private final BoardGameLogic gameLogic;
    private final BoardGameLogic framelessLogic;

    public ChessboardBlock(Properties props, BoardGameLogic gameLogic, BoardGameLogic framelessLogic) {
        super(props);
        this.gameLogic = gameLogic;
        this.framelessLogic = framelessLogic;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.SOUTH)
                .setValue(FRAMELESS, false));
    }

    /** 按方块状态（无框/带框）返回对应游戏逻辑 */
    public BoardGameLogic getGameLogic(BlockState state) {
        BoardGameLogic g = state.getValue(FRAMELESS) ? framelessLogic : gameLogic;
        return g != null ? g : ChineseChessLogic.INSTANCE;
    }

    // 故意不加 @Override：26.1.2 的父类是 abstract codec()，26.3 的父类则完全没有这个方法
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, FRAMELESS); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
    }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return s.getValue(FRAMELESS) ? SHAPE_FRAMELESS : SHAPE_FRAMED;
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChessboardBlockEntity(pos, state);
    }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return Collections.emptyList(); // 由 playerWillDestroy 手动掉落（带 NBT）
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // 潜影盒同款：collectComponents + applyComponents
        if (!level.isClientSide() && !player.isCreative()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ChessboardBlockEntity board) {
                // 保留无框变体（放置状态、模型、名字）；皮肤在 collectComponents 里
                ItemStack stack = ChessboardMod.boardStack(this, state.getValue(FRAMELESS));
                stack.applyComponents(board.collectComponents());
                popResource(level, pos, stack);
                level.removeBlockEntity(pos);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (!player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ChessboardBlockEntity board)) return InteractionResult.FAIL;

        int[] rc = worldToModel(hit.getLocation().x - pos.getX(),
                hit.getLocation().z - pos.getZ(), state.getValue(FACING), board.gameLogic());
        board.handleClick(rc[0], rc[1]); // row, col
        return InteractionResult.SUCCESS;
    }

    /** 世界坐标 → 棋盘行列（返回 {row, col}，匹配 rowPixel/colPixel） */
    private static int[] worldToModel(double wx, double wz, Direction facing, BoardGameLogic g) {
        wx *= 16.0; wz *= 16.0;
        double mx, mz;
        switch (facing) {
            case WEST  -> { mx = 16 - wz; mz = wx; }
            case NORTH -> { mx = 16 - wx; mz = 16 - wz; }
            case EAST  -> { mx = wz; mz = 16 - wx; }
            default    -> { mx = wx; mz = wz; }
        }
        // 最近邻匹配 rowPixel/colPixel
        int bestRow = 0, bestCol = 0;
        double bestDist = Double.MAX_VALUE;
        for (int r = 0; r < g.rows(); r++) {
            for (int c = 0; c < g.cols(); c++) {
                double dx = mx - g.colPixel(c), dz = mz - g.rowPixel(r);
                double d = dx * dx + dz * dz;
                if (d < bestDist) { bestDist = d; bestRow = r; bestCol = c; }
            }
        }
        return new int[]{bestRow, bestCol};
    }
}
