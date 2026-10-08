package com.chessboard.block;

import com.chessboard.ChessboardMod;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.api.BoardGameLogic;
import com.chessboard.api.DiceBoard;
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

    /*
     * 骰子格上的小突起。
     *
     * 棋盘本体是 1/16 厚的薄板，而骰子是<b>画出来的模型</b>（FlightDiceBlock 从不真正放置），
     * 立在板面上：整方块模型 × (pieceScale × diceModelScale) = 16 × 0.18 × 0.25 ≈ 0.045 方块
     * （≈ 0.72/16，比一格还小），顶面在 1/16 + 0.045 ≈ 1.7/16 那儿。
     *
     * 于是原先只有薄板能被打到：瞄准骰子时射线要么从它头顶掠过去，要么穿到它<b>后面那一格</b>
     * —— 骰子那一格反倒最难点。所以在骰子格上给交互形状补一块小突起，射线就能打在骰子身上；
     * 再用 {@link #hitsDiceBump} 那条「命中点高过薄板 ⇒ 就是骰子格」把行列判回来。
     *
     * 只改 getShape（选择框 + 射线），不动 getCollisionShape —— 否则玩家会撞在这块突起上、
     * 甚至站上去（vanilla 的默认碰撞形状就是 state.getShape()，26.1.2 的 BlockBehaviour 里写着）。
     *
     * 突起是竖着的方块，<b>侧面也是竖直的</b>，而右键侧面是「开皮肤菜单」的手势
     * （MultiPlayerGameModeMixin），所以那个 mixin 也要拿 {@link #hitsDiceBump} 排掉它，
     * 否则右键骰子会弹 GUI 而不是掷骰子。
     */

    /** 突起半宽：骰子本体 0.72/16 见方，两边各放宽到半格（= 一格宽），刚好盖住还不抢旁边格子 */
    private static final float DICE_BUMP_HALF = 0.5f / 16.0f;
    /** 突起高度：骰子顶面约 1.7/16，这里到 1.5/16（最顶上那一线可能漏过去，可接受） */
    private static final float DICE_BUMP_TOP = 1.5f / 16.0f;

    /**
     * 命中点是不是落在骰子那块小突起上 —— 判据是「高过薄板顶面」，因为板面只有 1/16 厚，
     * 除了突起没有任何地方够得到这个高度（没有骰子的棋盘也就永远不会命中）。
     *
     * <p>方块自己（{@code useItemOn}）和右键菜单的 mixin 共用这一条，改这里两边一起变。
     */
    public static boolean hitsDiceBump(BlockPos pos, BlockHitResult hit) {
        return hit.getLocation().y - pos.getY() > 1.05 / 16.0;
    }

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

    /**
     * 按方块状态（无框/带框）返回对应游戏逻辑。
     *
     * <p>{@code logic} 为 null 只可能是 {@link #CODEC} 那个占位实例（它必须存在，但从不放置、
     * 也从不渲染），兜一个默认规则只是为了不 NPE。真实的棋盘方块注册时都带着两套逻辑。
     */
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
        VoxelShape base = s.getValue(FRAMELESS) ? SHAPE_FRAMELESS : SHAPE_FRAMED;
        VoxelShape bump = diceBump(s);
        return bump == null ? base : Shapes.or(base, bump);
    }

    /**
     * 碰撞形状永远是那块薄板 —— 骰子的小突起只是给射线和选择框用的。
     *
     * <p>必须显式覆写：vanilla 的默认实现是 {@code hasCollision ? state.getShape(...) : empty()}，
     * 不覆写就会连带把突起变成实体障碍（玩家撞上去、还能站上去）。
     */
    @Override protected VoxelShape getCollisionShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return s.getValue(FRAMELESS) ? SHAPE_FRAMELESS : SHAPE_FRAMED;
    }

    /** 骰子格上的小突起（方块自己的坐标轴）；这块棋盘没有骰子就返回 null */
    private VoxelShape diceBump(BlockState state) {
        float[] center = diceBumpCenter(state);
        if (center == null) return null;
        return Shapes.box(center[0] - DICE_BUMP_HALF, 0, center[1] - DICE_BUMP_HALF,
                center[0] + DICE_BUMP_HALF, DICE_BUMP_TOP, center[1] + DICE_BUMP_HALF);
    }

    /** 骰子格格心在方块坐标里的 (x, z)；这块棋盘没有骰子就返回 null */
    private float[] diceBumpCenter(BlockState state) {
        BoardGameLogic g = getGameLogic(state);
        if (!(g instanceof DiceBoard dice)) return null;
        int cell = dice.diceCell();
        if (cell < 0 || cell >= g.rows() * g.cols()) return null;
        // 格心是模型坐标（colPixel/rowPixel 是 0..16 的像素坐标，除 16 得方块坐标），
        // 再按朝向转到方块自己的坐标轴 —— 骰子摆在正中时四个朝向其实一样，不特殊对待。
        return modelToWorld(g.colPixel(cell % g.cols()) / 16f,
                g.rowPixel(cell / g.cols()) / 16f, state.getValue(FACING));
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

        int[] rc = hitCell(state.getValue(FACING), hit, pos, board.gameLogic());
        board.handleClick(rc[0], rc[1]); // row, col
        return InteractionResult.SUCCESS;
    }

    /**
     * 命中点 → 棋盘行列（{@code {row, col}}）。骰子那块凸起的兜底在这里：打在高过薄板的突起上
     * 就直接算骰子格，否则瞄立在板上的骰子时，按「最近邻格」判出来的行列会落到它<b>后面那一格</b>。
     */
    private static int[] hitCell(Direction facing, BlockHitResult hit, BlockPos pos, BoardGameLogic g) {
        int[] rc = worldToModel(hit.getLocation().x - pos.getX(),
                hit.getLocation().z - pos.getZ(), facing, g);
        if (hitsDiceBump(pos, hit)) {
            int cell = g instanceof DiceBoard dice ? dice.diceCell() : -1;
            if (cell >= 0 && cell < g.rows() * g.cols()) rc = new int[]{cell / g.cols(), cell % g.cols()};
        }
        return rc;
    }

    /**
     * 棋盘模型坐标（{@code mx}/{@code mz} 是 0..1 的方块坐标，和 {@link #worldToModel} 同一套轴）
     * → 方块自己的坐标。就是客户端 ChessboardPieceGeometry#gridPos 的同一条映射
     * （{@link #worldToModel} 是它的逆）—— 那边有 vanilla 字节码的依据，改这里必须同时改那边。
     */
    private static float[] modelToWorld(float mx, float mz, Direction facing) {
        return switch (facing) {
            case WEST -> new float[]{1 - mz, mx};
            case NORTH -> new float[]{1 - mx, 1 - mz};
            case EAST -> new float[]{mz, 1 - mx};
            default -> new float[]{mx, mz};
        };
    }

    /** 世界坐标 → 棋盘行列（返回 {row, col}，匹配 rowPixel/colPixel） */
    private static int[] worldToModel(double wx, double wz, Direction facing, BoardGameLogic g) {
        wx *= 16.0; wz *= 16.0;
        double mx, mz;
        // 必须是 ChessboardPieceGeometry#gridPos 的逆（那边有 vanilla 字节码的依据）。
        // 两处同时改，否则棋子画在一处、点击判定在另一处 —— 朝西/东的棋盘会错开 90°。
        switch (facing) {
            case WEST  -> { mx = wz; mz = 16 - wx; }
            case NORTH -> { mx = 16 - wx; mz = 16 - wz; }
            case EAST  -> { mx = 16 - wz; mz = wx; }
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
