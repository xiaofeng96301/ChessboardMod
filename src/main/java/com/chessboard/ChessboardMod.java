package com.chessboard;

import com.chessboard.block.ChessCharBlock;
import com.chessboard.block.ChessMaterial;
import com.chessboard.block.ChessPieceBlock;
import com.chessboard.block.ChessboardBlock;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.ChessLogic;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.GomokuLogic;
import com.chessboard.game.TicTacToeLogic;
import com.chessboard.network.SetMaterialPayload;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;


@Mod(ChessboardMod.MODID)
public class ChessboardMod {

    public static final String MODID = "chessboard";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    // ── 注册模板 ──

    /** 棋盘方块：木材质薄板，硬度 2，无碰撞遮挡 */
    private static DeferredBlock<ChessboardBlock> registerBoard(String name, BoardGameLogic logic, BoardGameLogic framelessLogic) {
        return BLOCKS.registerBlock(name,
                p -> new ChessboardBlock(p, logic, framelessLogic),
                p -> p.mapColor(MapColor.WOOD).strength(2f, 3f).sound(SoundType.WOOD).noOcclusion());
    }

    /** 棋子模型方块：纯渲染用，无碰撞遮挡 */
    private static DeferredBlock<ChessPieceBlock> registerPiece(String name) {
        return BLOCKS.registerBlock(name, ChessPieceBlock::new, p -> p.mapColor(MapColor.WOOD).noOcclusion());
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

    /** 棋子汉字模型方块（纯渲染用，带汉字属性） */
    public static final DeferredBlock<ChessCharBlock> CHINESE_PIECE_CHAR = BLOCKS.registerBlock(
            "chinese_piece_char", ChessCharBlock::new, p -> p.mapColor(MapColor.WOOD).noOcclusion());

    // 棋子模型方块（带材质属性）
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_MODEL = registerPiece("chess_piece");
    public static final DeferredBlock<ChessPieceBlock> CHINESE_PIECE_HIDDEN = registerPiece("chinese_piece_hidden");
    public static final DeferredBlock<ChessPieceBlock> GOMOKU_PIECE_BLACK = registerPiece("gomoku_piece_black");
    public static final DeferredBlock<ChessPieceBlock> GOMOKU_PIECE_WHITE = registerPiece("gomoku_piece_white");
    public static final DeferredBlock<ChessPieceBlock> GOMOKU_PIECE_GRAY = registerPiece("gomoku_piece_gray");
    public static final DeferredBlock<ChessPieceBlock> TICTACTOE_PIECE_MODEL = registerPiece("tictactoe_piece");

    // 国际象棋棋子模型方块
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_KING = registerPiece("chess_piece_king");
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_QUEEN = registerPiece("chess_piece_queen");
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_BISHOP = registerPiece("chess_piece_bishop");
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_KNIGHT = registerPiece("chess_piece_knight");
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_ROOK = registerPiece("chess_piece_rook");
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_PAWN = registerPiece("chess_piece_pawn");

    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_KING_WHITE = registerPiece("chess_piece_king_white");
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_QUEEN_WHITE = registerPiece("chess_piece_queen_white");
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_BISHOP_WHITE = registerPiece("chess_piece_bishop_white");
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_KNIGHT_WHITE = registerPiece("chess_piece_knight_white");
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_ROOK_WHITE = registerPiece("chess_piece_rook_white");
    public static final DeferredBlock<ChessPieceBlock> CHESS_PIECE_PAWN_WHITE = registerPiece("chess_piece_pawn_white");

    // 方块实体（所有棋盘共用一种类型）
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChessboardBlockEntity>> CHESSBOARD_BE =
            BLOCK_ENTITIES.register("board_game", () -> {
                var t = new BlockEntityType<>(ChessboardBlockEntity::new,
                        Set.of(CHINESE_CHESSBOARD.get(), GOMOKU_BOARD.get(), TICTACTOE_BOARD.get(), CHESS_BOARD.get()), true);
                ChessboardBlockEntity.TYPE = t;
                return t;
            });

    // 创造标签页
    static final DeferredItem<BlockItem> CHINESE_CHESSBOARD_ITEM = ITEMS.registerSimpleBlockItem(CHINESE_CHESSBOARD);
    static final DeferredItem<BlockItem> GOMOKU_BOARD_ITEM = ITEMS.registerSimpleBlockItem(GOMOKU_BOARD);
    static final DeferredItem<BlockItem> TICTACTOE_BOARD_ITEM = ITEMS.registerSimpleBlockItem(TICTACTOE_BOARD);
    static final DeferredItem<BlockItem> CHESS_BOARD_ITEM = ITEMS.registerSimpleBlockItem(CHESS_BOARD);

    /** 变体索引：wood*2 + frameless，供物品模型 custom_model_data 切换 */
    public static int variantIndex(ChessMaterial wood, boolean frameless) {
        return wood.ordinal() * 2 + (frameless ? 1 : 0);
    }

    /** 变体物品翻译 key（custom_name 用） */
    public static String variantLangKey(String idPath, ChessMaterial wood, boolean frameless) {
        return "item.chessboard.variant." + idPath
                + "_" + wood.getSerializedName() + (frameless ? "_frameless" : "");
    }

    /** 构造棋盘变体物品（block_state + custom_model_data + custom_name 组件），放置/掉落/创造标签页共用 */
    public static ItemStack variantStack(ItemLike item, ChessMaterial wood, boolean frameless) {
        Item itemObj = item.asItem();
        BlockItemStateProperties props = BlockItemStateProperties.EMPTY
                .with(ChessboardBlock.WOOD, wood)
                .with(ChessboardBlock.FRAMELESS, frameless);
        CustomModelData cmd = new CustomModelData(
                List.of((float) variantIndex(wood, frameless)), List.of(), List.of(), List.of());
        ItemStack stack = new ItemStack(itemObj);
        stack.set(DataComponents.BLOCK_STATE, props);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable(
                variantLangKey(itemObj.builtInRegistryHolder().key().identifier().getPath(), wood, frameless)));
        return stack;
    }

    /** 生成棋盘变体物品（创造标签页用） */
    private static ItemStack boardVariant(DeferredItem<BlockItem> item, ChessMaterial wood, boolean frameless) {
        return variantStack(item.get(), wood, frameless);
    }

    /** 向标签页输出基础物品（橡木带框）+ 其余变体（跳橡木带框，避免重复） */
    private static void addBoardVariants(CreativeModeTab.Output output, DeferredItem<BlockItem> item) {
        output.accept(item);
        for (ChessMaterial w : ChessMaterial.values()) {
            if (w == ChessMaterial.OAK) continue;
            output.accept(boardVariant(item, w, false));
        }
        for (ChessMaterial w : ChessMaterial.values()) {
            output.accept(boardVariant(item, w, true));
        }
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
                    })
                    .build());

    public ChessboardMod(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        TABS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        modEventBus.addListener(ChessboardMod::registerPayloads);
    }

    /** 网络载荷注册：设置棋盘上的棋子材质（写方块实体，自动同步） */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToServer(SetMaterialPayload.TYPE, SetMaterialPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    var player = context.player();
                    if (player == null) return;
                    var level = player.level();
                    if (level.getBlockEntity(payload.pos()) instanceof ChessboardBlockEntity be) {
                        be.setMaterial(payload.slot(), payload.material());
                    }
                }));
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
                                                                        board.importCode(code);
                                                                        ctx.getSource().sendSuccess(() -> Component.literal("已导入"), true);
                                                                    }
                                                                    return 1;
                                                                }))))))
                        .then(Commands.literal("wood")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("wood", StringArgumentType.word())
                                                                .executes(ctx -> {
                                                                    BlockPos pos = new BlockPos(
                                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                                            IntegerArgumentType.getInteger(ctx, "y"),
                                                                            IntegerArgumentType.getInteger(ctx, "z"));
                                                                    String woodName = StringArgumentType.getString(ctx, "wood");
                                                                    var level = ctx.getSource().getLevel();
                                                                    BlockState cur = level.getBlockState(pos);
                                                                    if (cur.getBlock() instanceof ChessboardBlock
                                                                            && cur.hasProperty(ChessboardBlock.WOOD)) {
                                                                        ChessMaterial wood = ChessMaterial.find(woodName);
                                                                        if (wood != null) {
                                                                            level.setBlock(pos, cur.setValue(ChessboardBlock.WOOD, wood), 3);
                                                                            ctx.getSource().sendSuccess(() -> Component.literal("棋盘木种已改为 " + woodName), true);
                                                                            return 1;
                                                                        }
                                                                    }
                                                                    ctx.getSource().sendFailure(Component.literal("无法修改该方块的木种"));
                                                                    return 0;
                                                                }))))))
        );
    }
}
