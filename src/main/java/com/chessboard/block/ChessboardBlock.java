package com.chessboard.block;

import com.chessboard.blockentity.ChessboardBlockEntity;
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
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.Collections;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Consumer;

/**
 * 通用棋盘方块 —— 框架层。
 * 棋盘为 1/16 格厚的薄板，支持水平朝向。
 * 子类构造时传入游戏逻辑。
 */
public class ChessboardBlock extends BaseEntityBlock {

    public static final MapCodec<ChessboardBlock> CODEC = simpleCodec(p -> new ChessboardBlock(p, null));
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Shapes.box(0, 0, 0, 1, 1.0 / 16.0, 1);

    private final com.chessboard.game.BoardGameLogic gameLogic;

    /** 客户端注入：Shift+右键打开管理界面的动作 */
    public static Consumer<BlockPos> openScreenAction = pos -> {};

    public ChessboardBlock(Properties props, com.chessboard.game.BoardGameLogic gameLogic) {
        super(props);
        this.gameLogic = gameLogic;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH));
    }

    public com.chessboard.game.BoardGameLogic getGameLogic() {
        return gameLogic != null ? gameLogic : com.chessboard.game.ChineseChessLogic.INSTANCE;
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) { return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection()); }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
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
                ItemStack stack = new ItemStack(this);
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
        // 手持物品时不拦截交互
        if (!player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;

        if (level.isClientSide()) {
            if (com.chessboard.ChessboardClient.OPEN_MENU.isDown()) {
                openScreenAction.accept(pos);
                return InteractionResult.CONSUME;
            }
            return InteractionResult.SUCCESS;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ChessboardBlockEntity board)) return InteractionResult.FAIL;

        int[] mc = worldToModel(hit.getLocation().x - pos.getX(),
                hit.getLocation().z - pos.getZ(), state.getValue(FACING), board.gameLogic());
        board.handleClick(mc[1], mc[0]); // row, col
        return InteractionResult.SUCCESS;
    }

    /** 世界坐标 → 棋盘行列（匹配 rowPixel/colPixel） */
    private static int[] worldToModel(double wx, double wz, Direction facing, com.chessboard.game.BoardGameLogic g) {
        wx *= 16.0; wz *= 16.0;
        double mx, mz;
        switch (facing) {
            case WEST  -> { mx = 16 - wz; mz = wx; }
            case NORTH -> { mx = 16 - wx; mz = 16 - wz; }
            case EAST  -> { mx = wz; mz = 16 - wx; }
            default    -> { mx = wx; mz = wz; }
        }
        // 最近邻匹配 rowPixel/colPixel
        int bestCol = 0, bestRow = 0;
        double bestDist = Double.MAX_VALUE;
        for (int r = 0; r < g.rows(); r++) {
            for (int c = 0; c < g.cols(); c++) {
                double dx = mx - g.colPixel(c), dz = mz - g.rowPixel(r);
                double d = dx * dx + dz * dz;
                if (d < bestDist) { bestDist = d; bestRow = r; bestCol = c; }
            }
        }
        return new int[]{bestCol, bestRow};
    }
}
