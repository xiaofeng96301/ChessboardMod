package com.chessboard.block;

import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
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

import java.util.Collections;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Consumer;

/**
 * 通用棋盘方块 —— 框架层。
 * 棋盘为 1/16 格厚的薄板，支持水平朝向、木种（WOOD）与无框（FRAMELESS）变体。
 * 构造时传入带框/无框两套游戏逻辑（格子参数不同）。
 */
public class ChessboardBlock extends BaseEntityBlock {

    public static final MapCodec<ChessboardBlock> CODEC = simpleCodec(p -> new ChessboardBlock(p, null, null));
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** 棋盘材质（剥皮木头 + 磨制石），属性名沿用 "wood" */
    public static final EnumProperty<ChessMaterial> WOOD = EnumProperty.create("wood", ChessMaterial.class);
    public static final BooleanProperty FRAMELESS = BooleanProperty.create("frameless");

    private static final VoxelShape SHAPE_FRAMED = Shapes.box(0, 0, 0, 1, 1.0 / 16.0, 1);
    private static final VoxelShape SHAPE_FRAMELESS = Shapes.box(0.5 / 16.0, 0, 0.5 / 16.0, 15.5 / 16.0, 1.0 / 16.0, 15.5 / 16.0);

    private final com.chessboard.game.BoardGameLogic gameLogic;
    private final com.chessboard.game.BoardGameLogic framelessLogic;

    /** 客户端注入：Shift+右键打开管理界面的动作 */
    public static Consumer<BlockPos> openScreenAction = pos -> {};

    public ChessboardBlock(Properties props, com.chessboard.game.BoardGameLogic gameLogic,
                           com.chessboard.game.BoardGameLogic framelessLogic) {
        super(props);
        this.gameLogic = gameLogic;
        this.framelessLogic = framelessLogic;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.SOUTH)
                .setValue(WOOD, ChessMaterial.OAK)
                .setValue(FRAMELESS, false));
    }

    /** 按方块状态（无框/带框）返回对应游戏逻辑 */
    public com.chessboard.game.BoardGameLogic getGameLogic(BlockState state) {
        com.chessboard.game.BoardGameLogic g = state.getValue(FRAMELESS) ? framelessLogic : gameLogic;
        return g != null ? g : com.chessboard.game.ChineseChessLogic.INSTANCE;
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, WOOD, FRAMELESS); }
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
                ItemStack stack = new ItemStack(this);
                // 保留木种/无框变体（放置状态、模型、名字）
                ChessMaterial wood = state.getValue(WOOD);
                boolean frameless = state.getValue(FRAMELESS);
                net.minecraft.world.item.component.BlockItemStateProperties props =
                        net.minecraft.world.item.component.BlockItemStateProperties.EMPTY
                                .with(WOOD, wood)
                                .with(FRAMELESS, frameless);
                stack.set(net.minecraft.core.component.DataComponents.BLOCK_STATE, props);
                net.minecraft.world.item.component.CustomModelData cmd = new net.minecraft.world.item.component.CustomModelData(
                        java.util.List.of((float) com.chessboard.ChessboardMod.variantIndex(wood, frameless)),
                        java.util.List.of(), java.util.List.of(), java.util.List.of());
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA, cmd);
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                        net.minecraft.network.chat.Component.translatable(
                                com.chessboard.ChessboardMod.variantLangKey(
                                        asItem().builtInRegistryHolder().key().identifier().getPath(),
                                        wood, frameless)));
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
