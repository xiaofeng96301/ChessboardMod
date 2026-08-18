package com.chessboard;

import com.chessboard.block.ChessWood;
import com.chessboard.block.ChessboardBlock;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.game.ChessLogic;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.GomokuLogic;
import com.chessboard.game.TicTacToeLogic;
import java.util.Set;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;


@Mod(ChessboardMod.MODID)
public class ChessboardMod {

    public static final String MODID = "chessboard";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    // ── 棋盘注册（去皮橡木）──

    static final DeferredBlock<ChessboardBlock> CHINESE_CHESSBOARD = BLOCKS.registerBlock(
            "chinese_chessboard",
            p -> new ChessboardBlock(p, ChineseChessLogic.INSTANCE, new ChineseChessLogic(1.115f, 13.77f)),
            p -> p.mapColor(MapColor.WOOD).strength(2f, 3f).sound(SoundType.WOOD).noOcclusion());
    static final DeferredBlock<ChessboardBlock> GOMOKU_BOARD = BLOCKS.registerBlock(
            "gomoku_board",
            p -> new ChessboardBlock(p, GomokuLogic.INSTANCE, new GomokuLogic(1.115f, 13.77f)),
            p -> p.mapColor(MapColor.WOOD).strength(2f, 3f).sound(SoundType.WOOD).noOcclusion());
    static final DeferredBlock<ChessboardBlock> TICTACTOE_BOARD = BLOCKS.registerBlock(
            "tictactoe_board",
            p -> new ChessboardBlock(p, TicTacToeLogic.INSTANCE, new TicTacToeLogic(3.28f, 9.34f)),
            p -> p.mapColor(MapColor.WOOD).strength(2f, 3f).sound(SoundType.WOOD).noOcclusion());
    static final DeferredBlock<ChessboardBlock> CHESS_BOARD = BLOCKS.registerBlock(
            "chess_board",
            p -> new ChessboardBlock(p, ChessLogic.INSTANCE, new ChessLogic(2.0f, 12.0f)),
            p -> p.mapColor(MapColor.WOOD).strength(2f, 3f).sound(SoundType.WOOD).noOcclusion());

    // 棋子模型方块（纯渲染用）
    public static final DeferredBlock<Block> CHESS_PIECE_MODEL = BLOCKS.registerSimpleBlock(
            "chess_piece", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CHINESE_PIECE_HIDDEN = BLOCKS.registerSimpleBlock(
            "chinese_piece_hidden", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> GOMOKU_PIECE_BLACK = BLOCKS.registerSimpleBlock(
            "gomoku_piece_black", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> GOMOKU_PIECE_WHITE = BLOCKS.registerSimpleBlock(
            "gomoku_piece_white", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> GOMOKU_PIECE_GRAY = BLOCKS.registerSimpleBlock(
            "gomoku_piece_gray", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> TICTACTOE_PIECE_MODEL = BLOCKS.registerSimpleBlock(
            "tictactoe_piece", p -> p.mapColor(MapColor.WOOD).noOcclusion());

    // 国际象棋棋子模型方块
    public static final DeferredBlock<Block> CHESS_PIECE_KING = BLOCKS.registerSimpleBlock(
            "chess_piece_king", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CHESS_PIECE_QUEEN = BLOCKS.registerSimpleBlock(
            "chess_piece_queen", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CHESS_PIECE_BISHOP = BLOCKS.registerSimpleBlock(
            "chess_piece_bishop", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CHESS_PIECE_KNIGHT = BLOCKS.registerSimpleBlock(
            "chess_piece_knight", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CHESS_PIECE_ROOK = BLOCKS.registerSimpleBlock(
            "chess_piece_rook", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CHESS_PIECE_PAWN = BLOCKS.registerSimpleBlock(
            "chess_piece_pawn", p -> p.mapColor(MapColor.WOOD).noOcclusion());

    public static final DeferredBlock<Block> CHESS_PIECE_KING_WHITE = BLOCKS.registerSimpleBlock(
            "chess_piece_king_white", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CHESS_PIECE_QUEEN_WHITE = BLOCKS.registerSimpleBlock(
            "chess_piece_queen_white", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CHESS_PIECE_BISHOP_WHITE = BLOCKS.registerSimpleBlock(
            "chess_piece_bishop_white", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CHESS_PIECE_KNIGHT_WHITE = BLOCKS.registerSimpleBlock(
            "chess_piece_knight_white", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CHESS_PIECE_ROOK_WHITE = BLOCKS.registerSimpleBlock(
            "chess_piece_rook_white", p -> p.mapColor(MapColor.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CHESS_PIECE_PAWN_WHITE = BLOCKS.registerSimpleBlock(
            "chess_piece_pawn_white", p -> p.mapColor(MapColor.WOOD).noOcclusion());

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
    static int variantIndex(ChessWood wood, boolean frameless) {
        return wood.ordinal() * 2 + (frameless ? 1 : 0);
    }

    /** 变体物品翻译 key（custom_name 用） */
    static String variantLangKey(DeferredItem<BlockItem> item, ChessWood wood, boolean frameless) {
        return "item.chessboard.variant." + item.getId().getPath()
                + "_" + wood.getSerializedName() + (frameless ? "_frameless" : "");
    }

    /** 生成棋盘变体物品（block_state + custom_model_data + custom_name 组件） */
    private static ItemStack boardVariant(DeferredItem<BlockItem> item, ChessWood wood, boolean frameless) {
        BlockItemStateProperties props = BlockItemStateProperties.EMPTY
                .with(ChessboardBlock.WOOD, wood)
                .with(ChessboardBlock.FRAMELESS, frameless);
        CustomModelData cmd = new CustomModelData(
                java.util.List.of((float) variantIndex(wood, frameless)),
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        ItemStack stack = new ItemStack(item.get());
        stack.set(DataComponents.BLOCK_STATE, props);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable(variantLangKey(item, wood, frameless)));
        return stack;
    }

    /** 向标签页输出基础物品（橡木带框）+ 其余变体（跳橡木带框，避免重复） */
    private static void addBoardVariants(CreativeModeTab.Output output, DeferredItem<BlockItem> item) {
        output.accept(item);
        for (ChessWood w : ChessWood.values()) {
            if (w == ChessWood.OAK) continue;
            output.accept(boardVariant(item, w, false));
        }
        for (ChessWood w : ChessWood.values()) {
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
                        .then(Commands.literal("undo")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            BlockPos pos = new BlockPos(
                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                    IntegerArgumentType.getInteger(ctx, "z"));
                                                            var be = ctx.getSource().getLevel().getBlockEntity(pos);
                                                            if (be instanceof ChessboardBlockEntity board && board.undoMove()) {
                                                                ctx.getSource().sendSuccess(() -> Component.literal("已悔棋"), true);
                                                            } else {
                                                                ctx.getSource().sendFailure(Component.literal("没有可以悔棋的步骤"));
                                                            }
                                                            return 1;
                                                        })))))
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
                        .then(Commands.literal("darkstart")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            BlockPos pos = new BlockPos(
                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                    IntegerArgumentType.getInteger(ctx, "z"));
                                                            var be = ctx.getSource().getLevel().getBlockEntity(pos);
                                                            if (be instanceof ChessboardBlockEntity board) {
                                                                board.darkStart();
                                                                ctx.getSource().sendSuccess(() -> Component.literal("已暗棋开局"), true);
                                                            }
                                                            return 1;
                                                        })))))
                        .then(Commands.literal("fulldarkstart")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            BlockPos pos = new BlockPos(
                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                    IntegerArgumentType.getInteger(ctx, "z"));
                                                            var be = ctx.getSource().getLevel().getBlockEntity(pos);
                                                            if (be instanceof ChessboardBlockEntity board) {
                                                                board.fullDarkStart();
                                                                ctx.getSource().sendSuccess(() -> Component.literal("已全暗棋开局"), true);
                                                            }
                                                            return 1;
                                                        })))))
                        .then(Commands.literal("randomstart")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            BlockPos pos = new BlockPos(
                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                    IntegerArgumentType.getInteger(ctx, "z"));
                                                            var be = ctx.getSource().getLevel().getBlockEntity(pos);
                                                            if (be instanceof ChessboardBlockEntity board) {
                                                                board.randomStart();
                                                                ctx.getSource().sendSuccess(() -> Component.literal("已随机开局"), true);
                                                            }
                                                            return 1;
                                                        })))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            BlockPos pos = new BlockPos(
                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                    IntegerArgumentType.getInteger(ctx, "z"));
                                                            var be = ctx.getSource().getLevel().getBlockEntity(pos);
                                                            if (be instanceof ChessboardBlockEntity board) {
                                                                board.resetBoard();
                                                                ctx.getSource().sendSuccess(() -> Component.literal("棋盘已重置"), true);
                                                            }
                                                            return 1;
                                                        })))))
        );
    }
}
