package com.chessboard;

import com.chessboard.block.ChessCharBlock;
import com.chessboard.block.ChessboardBlock;
import com.chessboard.block.FlightDiceBlock;
import com.chessboard.block.FlightPieceBlock;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.api.BoardGameLogic;
import com.chessboard.game.ChessLogic;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.FlightChessLogic;
import com.chessboard.game.GomokuLogic;
import com.chessboard.game.TicTacToeLogic;
import com.chessboard.menu.BoardSkinMenu;
import com.chessboard.network.OpenBoardScreenPayload;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;


@Mod(ChessboardMod.MODID)
public class ChessboardMod {

    public static final String MODID = "chessboard";

    /** 模块日志。渲染路径上的「皮肤没生效」这类问题只能靠它定位，别再静默吞异常 */
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MODID);

    /**
     * 棋盘皮肤界面。用 NeoForge 的 {@code IMenuTypeExtension} 是为了能带额外数据 ——
     * 服务端把棋盘坐标和槽位布局塞进去，客户端照着重建成一样的槽位列表。
     */
    public static final DeferredHolder<MenuType<?>, MenuType<BoardSkinMenu>> BOARD_SKIN_MENU =
            MENUS.register("board_skin", () -> IMenuTypeExtension.create(BoardSkinMenu::new));

    // ── 注册模板 ──

    /** 棋盘方块：木材质薄板，硬度 2，无碰撞遮挡 */
    private static DeferredBlock<ChessboardBlock> registerBoard(String name, BoardGameLogic logic, BoardGameLogic framelessLogic) {
        return BLOCKS.registerBlock(name,
                p -> new ChessboardBlock(p, logic, framelessLogic),
                p -> p.mapColor(MapColor.WOOD).strength(2f, 3f).sound(SoundType.WOOD).noOcclusion());
    }

    /** 棋子模型方块：纯渲染用，无碰撞遮挡。外观由动态皮肤决定，所以就是个普通 Block */
    private static DeferredBlock<Block> registerPiece(String name) {
        return BLOCKS.registerBlock(name, Block::new, p -> p.mapColor(MapColor.WOOD).noOcclusion());
    }

    // ── 棋盘注册 ──

    static final DeferredBlock<ChessboardBlock> CHINESE_CHESSBOARD = registerBoard(
            "chinese_chessboard", ChineseChessLogic.INSTANCE, new ChineseChessLogic(1.115f, 13.77f));
    static final DeferredBlock<ChessboardBlock> GOMOKU_BOARD = registerBoard(
            "gomoku_board", GomokuLogic.INSTANCE, new GomokuLogic(1.115f, 13.77f));
    static final DeferredBlock<ChessboardBlock> TICTACTOE_BOARD = registerBoard(
            "tictactoe_board", TicTacToeLogic.INSTANCE, new TicTacToeLogic(3.28f, 9.34f));
    static final DeferredBlock<ChessboardBlock> CHESS_BOARD = registerBoard(
            "chess_board", ChessLogic.INSTANCE, new ChessLogic(2.0f, 12.0f));
    static final DeferredBlock<ChessboardBlock> FLIGHT_CHESS_BOARD = registerBoard(
            "flight_chess_board", FlightChessLogic.INSTANCE, new FlightChessLogic(1.115f, 13.77f));

    /** 棋子汉字模型方块（纯渲染用，带汉字属性） */
    public static final DeferredBlock<ChessCharBlock> CHINESE_PIECE_CHAR = BLOCKS.registerBlock(
            "chinese_piece_char", ChessCharBlock::new, p -> p.mapColor(MapColor.WOOD).noOcclusion());

    // 下面三个都纯粹是渲染用、从不放置（和 registerPiece 一样），MapColor 无实际意义
    /** 飞行棋棋子圆片（按队换混凝土贴图） */
    public static final DeferredBlock<FlightPieceBlock> FLIGHT_PIECE = BLOCKS.registerBlock(
            "flight_piece", FlightPieceBlock::new, p -> p.mapColor(MapColor.WOOD).noOcclusion());
    /** 飞行棋圆片上的飞机图标层（四队共用，纯渲染用） */
    public static final DeferredBlock<Block> FLIGHT_ICON = BLOCKS.registerBlock(
            "flight_icon", Block::new, p -> p.mapColor(MapColor.WOOD).noOcclusion());
    /** 飞行棋中央的骰子（按 FACE 换朝上的点数） */
    public static final DeferredBlock<FlightDiceBlock> FLIGHT_DICE = BLOCKS.registerBlock(
            "flight_dice", FlightDiceBlock::new, p -> p.mapColor(MapColor.WOOD).noOcclusion());

    // 棋子模型方块（带材质属性）
    public static final DeferredBlock<Block> CHESS_PIECE_MODEL = registerPiece("chess_piece");
    public static final DeferredBlock<Block> CHINESE_PIECE_HIDDEN = registerPiece("chinese_piece_hidden");
    public static final DeferredBlock<Block> GOMOKU_PIECE_BLACK = registerPiece("gomoku_piece_black");
    public static final DeferredBlock<Block> GOMOKU_PIECE_WHITE = registerPiece("gomoku_piece_white");
    public static final DeferredBlock<Block> GOMOKU_PIECE_GRAY = registerPiece("gomoku_piece_gray");
    public static final DeferredBlock<Block> TICTACTOE_PIECE_MODEL = registerPiece("tictactoe_piece");

    // 国际象棋棋子模型方块
    public static final DeferredBlock<Block> CHESS_PIECE_KING = registerPiece("chess_piece_king");
    public static final DeferredBlock<Block> CHESS_PIECE_QUEEN = registerPiece("chess_piece_queen");
    public static final DeferredBlock<Block> CHESS_PIECE_BISHOP = registerPiece("chess_piece_bishop");
    public static final DeferredBlock<Block> CHESS_PIECE_KNIGHT = registerPiece("chess_piece_knight");
    public static final DeferredBlock<Block> CHESS_PIECE_ROOK = registerPiece("chess_piece_rook");
    public static final DeferredBlock<Block> CHESS_PIECE_PAWN = registerPiece("chess_piece_pawn");

    public static final DeferredBlock<Block> CHESS_PIECE_KING_WHITE = registerPiece("chess_piece_king_white");
    public static final DeferredBlock<Block> CHESS_PIECE_QUEEN_WHITE = registerPiece("chess_piece_queen_white");
    public static final DeferredBlock<Block> CHESS_PIECE_BISHOP_WHITE = registerPiece("chess_piece_bishop_white");
    public static final DeferredBlock<Block> CHESS_PIECE_KNIGHT_WHITE = registerPiece("chess_piece_knight_white");
    public static final DeferredBlock<Block> CHESS_PIECE_ROOK_WHITE = registerPiece("chess_piece_rook_white");
    public static final DeferredBlock<Block> CHESS_PIECE_PAWN_WHITE = registerPiece("chess_piece_pawn_white");

    // 方块实体（所有棋盘共用一种类型）
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChessboardBlockEntity>> CHESSBOARD_BE =
            BLOCK_ENTITIES.register("board_game", () -> {
                var t = new BlockEntityType<>(ChessboardBlockEntity::new,
                        Set.of(CHINESE_CHESSBOARD.get(), GOMOKU_BOARD.get(), TICTACTOE_BOARD.get(),
                                CHESS_BOARD.get(), FLIGHT_CHESS_BOARD.get()), true);
                ChessboardBlockEntity.TYPE = t;
                return t;
            });

    // 创造标签页
    static final DeferredItem<BlockItem> CHINESE_CHESSBOARD_ITEM = ITEMS.registerSimpleBlockItem(CHINESE_CHESSBOARD);
    static final DeferredItem<BlockItem> GOMOKU_BOARD_ITEM = ITEMS.registerSimpleBlockItem(GOMOKU_BOARD);
    static final DeferredItem<BlockItem> TICTACTOE_BOARD_ITEM = ITEMS.registerSimpleBlockItem(TICTACTOE_BOARD);
    static final DeferredItem<BlockItem> CHESS_BOARD_ITEM = ITEMS.registerSimpleBlockItem(CHESS_BOARD);
    static final DeferredItem<BlockItem> FLIGHT_CHESS_BOARD_ITEM = ITEMS.registerSimpleBlockItem(FLIGHT_CHESS_BOARD);

    /**
     * 构造棋盘物品（放置/掉落/创造标签页共用）。
     *
     * <p>外观不再靠方块状态的木种变体，而是靠方块实体上的动态皮肤（见 {@link SkinData}）。
     * 物品模型是 {@code minecraft:select} + {@code block_state_property: "frameless"}，
     * 所以这里只需要把 {@code FRAMELESS} 写进 {@code BLOCK_STATE} 组件就够了，
     * <b>不需要</b>再写 {@code CUSTOM_MODEL_DATA}。
     */
    public static ItemStack boardStack(ItemLike item, boolean frameless) {
        Item itemObj = item.asItem();
        ItemStack stack = new ItemStack(itemObj);
        stack.set(DataComponents.BLOCK_STATE,
                BlockItemStateProperties.EMPTY.with(ChessboardBlock.FRAMELESS, frameless));
        if (frameless) {
            stack.set(DataComponents.CUSTOM_NAME, Component.translatable(
                    "item.chessboard.variant."
                            + itemObj.builtInRegistryHolder().key().identifier().getPath() + "_frameless"));
        }
        return stack;
    }

    /** 向标签页输出该棋盘的两个物品：带框 + 无框 */
    private static void addBoardVariants(CreativeModeTab.Output output, DeferredItem<BlockItem> item) {
        output.accept(item);
        output.accept(boardStack(item.get(), true));
    }

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CHESSBOARD_TAB =
            TABS.register("chessboard_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.chessboard"))
                    .icon(() -> CHINESE_CHESSBOARD_ITEM.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        addBoardVariants(output, CHINESE_CHESSBOARD_ITEM);
                        addBoardVariants(output, CHESS_BOARD_ITEM);
                        addBoardVariants(output, GOMOKU_BOARD_ITEM);
                        addBoardVariants(output, TICTACTOE_BOARD_ITEM);
                        addBoardVariants(output, FLIGHT_CHESS_BOARD_ITEM);
                    })
                    .build());

    public ChessboardMod(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        TABS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        modEventBus.addListener(ChessboardMod::registerPayloads);
    }

    /** 网络载荷注册：客户端请求打开棋盘皮肤界面 */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToServer(OpenBoardScreenPayload.TYPE, OpenBoardScreenPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> openBoardScreen(context.player(), payload.pos())));
    }

    /**
     * 校验并打开皮肤界面。客户端发来的坐标不可信，所以这里把该查的都查一遍。
     *
     * <p>槽位布局（这个棋类要显示哪几个皮肤槽）在这里算好，随开界面一起发给客户端 ——
     * {@code AbstractContainerMenu.slots} 的下标就是协议里的槽位号，两边必须构造出
     * 完全一致的列表，不能让客户端自己猜。
     */
    private static void openBoardScreen(Player player, BlockPos pos) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (sp.distanceToSqr(Vec3.atCenterOf(pos)) > 64.0) return;
        if (!(sp.level().getBlockEntity(pos) instanceof ChessboardBlockEntity be)) return;
        int[] shown = SkinData.slotsFor(be.gameLogic());
        sp.openMenu(new SimpleMenuProvider(
                        (id, inv, p) -> new BoardSkinMenu(id, inv, pos, shown),
                        Component.literal("棋盘样式")),
                buf -> {
                    buf.writeBlockPos(pos);
                    buf.writeVarIntArray(shown);
                });
    }

    /** 定位棋盘方块实体、执行操作并反馈的命令模板；failMsg 为 null 时失败不提示 */
    private static ArgumentBuilder<CommandSourceStack, ?> boardCommand(String name, String successMsg, String failMsg,
            Function<ChessboardBlockEntity, Boolean> action) {
        return Commands.literal(name)
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            BlockPos pos = new BlockPos(
                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                    IntegerArgumentType.getInteger(ctx, "z"));
                                            var be = ctx.getSource().getLevel().getBlockEntity(pos);
                                            if (be instanceof ChessboardBlockEntity board && action.apply(board)) {
                                                ctx.getSource().sendSuccess(() -> Component.literal(successMsg), true);
                                            } else if (failMsg != null) {
                                                ctx.getSource().sendFailure(Component.literal(failMsg));
                                            }
                                            return 1;
                                        }))));
    }

    /** 无返回值的命令模板 */
    private static ArgumentBuilder<CommandSourceStack, ?> boardCommand(String name, String successMsg,
            Consumer<ChessboardBlockEntity> action) {
        return boardCommand(name, successMsg, null, board -> {
            action.accept(board);
            return true;
        });
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal(MODID)
                        .then(Commands.literal("click")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("row", IntegerArgumentType.integer())
                                                                .then(Commands.argument("col", IntegerArgumentType.integer())
                                                                        .executes(ctx -> {
                                                                            BlockPos pos = new BlockPos(
                                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                                    IntegerArgumentType.getInteger(ctx, "z"));
                                                                            int row = IntegerArgumentType.getInteger(ctx, "row");
                                                                            int col = IntegerArgumentType.getInteger(ctx, "col");
                                                                            var be = ctx.getSource().getLevel().getBlockEntity(pos);
                                                                            if (be instanceof ChessboardBlockEntity board) {
                                                                                board.handleClick(row, col);
                                                                            }
                                                                            return 1;
                                                                        })))))))
                        .then(boardCommand("undo", "已悔棋", "没有可以悔棋的步骤", ChessboardBlockEntity::undoMove))
                        .then(boardCommand("darkstart", "已暗棋开局", ChessboardBlockEntity::darkStart))
                        .then(boardCommand("fulldarkstart", "已全暗棋开局", ChessboardBlockEntity::fullDarkStart))
                        .then(boardCommand("randomstart", "已随机开局", ChessboardBlockEntity::randomStart))
                        .then(boardCommand("peacefulstart", "已和平开局", ChessboardBlockEntity::peacefulStart))
                        .then(boardCommand("reset", "棋盘已重置", ChessboardBlockEntity::resetBoard))
                        .then(Commands.literal("import")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("code", StringArgumentType.greedyString())
                                                                .executes(ctx -> {
                                                                    BlockPos pos = new BlockPos(
                                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                                            IntegerArgumentType.getInteger(ctx, "y"),
                                                                            IntegerArgumentType.getInteger(ctx, "z"));
                                                                    String code = StringArgumentType.getString(ctx, "code");
                                                                    var be = ctx.getSource().getLevel().getBlockEntity(pos);
                                                                    if (be instanceof ChessboardBlockEntity board) {
                                                                        if (board.importCode(code)) {
                                                                            ctx.getSource().sendSuccess(() -> Component.literal("已导入"), true);
                                                                        } else {
                                                                            // 解析失败不算导入成功：棋盘保持原样，别再报「已导入」骗人
                                                                            ctx.getSource().sendFailure(Component.literal("代码格式不对，棋盘未改动"));
                                                                        }
                                                                    }
                                                                    return 1;
                                                                }))))))
        );
    }
}
