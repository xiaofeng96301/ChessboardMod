package com.chessboard.client.renderer;

import com.chessboard.ChessboardMod;
import com.chessboard.api.BoardGameLogic;
import com.chessboard.api.DiceBoard;
import com.chessboard.block.ChessChar;
import com.chessboard.block.ChessCharBlock;
import com.chessboard.block.FlightDiceBlock;
import com.chessboard.block.FlightPieceBlock;
import com.chessboard.block.FlightTeam;
import com.chessboard.game.ChessLogic;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.FlightChessLogic;
import com.chessboard.game.GomokuLogic;
import com.chessboard.game.TicTacToeLogic;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「这颗棋子用哪个模型」的登记表 —— 从规则层搬出来的那一层壳。
 *
 * <p><b>为什么不放在 {@link BoardGameLogic} 上</b>：那个接口（含五个规则类）是<b>纯 Java</b> 的，
 * 规则改动可以脱离 Minecraft 直接用 jshell 跑自测（{@code tools/check_flight_rules.jsh}，抓过真 bug）。
 * 一旦它的方法签名里出现 {@code BlockState}，加载规则类就得解析 Minecraft 类型，那条自测线当场就断
 * —— 2026-10-01 这次重构就撞上了。所以：
 * <ul>
 *   <li>规则类只报<b>数值</b>（{@code modelScale} / {@code modelCenterX-Y-Z}，都是 float）；</li>
 *   <li>「用哪个方块」放在这里按棋类登记，框架不再 switch。</li>
 * </ul>
 *
 * <p>新增棋类 = 在下面加一条 {@link #register}，不用改任何 switch、也不用碰规则接口。
 * 对外注册入口（另一个模组加棋盘）走同一条路：见计划里的 {@code BoardGames}。
 */
public interface PieceModels {

    /** 这颗棋子用哪个方块状态（只决定几何与属性）；{@code null} = 用框架默认棋子模型 */
    BlockState model(BoardGameLogic g, int piece);

    /** 叠在棋子上的第二层模型（汉字 / 飞机图标）；{@code null} = 不叠 */
    default BlockState overlay(BoardGameLogic g, int piece) { return null; }

    // ── 登记表 ──

    /**
     * 按棋类登记的表。首次访问本接口时由 {@link #builtins()} 填好（<b>接口里不能写
     * {@code static {}} 初始化块</b>，所以用静态字段的初始化表达式兜）。
     */
    Map<Class<?>, PieceModels> REGISTRY = builtins();

    /** 框架自带的五种棋。对外的模组在自己的初始化里调 {@link #register} 追加 */
    static Map<Class<?>, PieceModels> builtins() {
        Map<Class<?>, PieceModels> m = new ConcurrentHashMap<>();
        register(m,  ChineseChessLogic.class, new PieceModels() {
            @Override
            public BlockState model(BoardGameLogic g, int piece) {
                return (ChineseChessLogic.isHidden(piece)
                        ? ChessboardMod.CHINESE_PIECE_HIDDEN : ChessboardMod.CHESS_PIECE_MODEL)
                        .get().defaultBlockState();
            }

            @Override
            public BlockState overlay(BoardGameLogic g, int piece) {
                if (ChineseChessLogic.isHidden(piece)) return null;
                ChessChar c = ChessChar.of(ChineseChessLogic.type(piece), g.side(piece));
                return c == null ? null
                        : ChessboardMod.CHINESE_PIECE_CHAR.get().defaultBlockState().setValue(ChessCharBlock.CHAR, c);
            }
        });

        // 国际象棋：六种棋型 × 黑白两色
        register(m, ChessLogic.class, (g, piece) -> {
            boolean white = g.side(piece) == 0;
            return switch (ChessLogic.type(piece)) {
                case ChessLogic.KING -> (white ? ChessboardMod.CHESS_PIECE_KING_WHITE : ChessboardMod.CHESS_PIECE_KING).get().defaultBlockState();
                case ChessLogic.QUEEN -> (white ? ChessboardMod.CHESS_PIECE_QUEEN_WHITE : ChessboardMod.CHESS_PIECE_QUEEN).get().defaultBlockState();
                case ChessLogic.BISHOP -> (white ? ChessboardMod.CHESS_PIECE_BISHOP_WHITE : ChessboardMod.CHESS_PIECE_BISHOP).get().defaultBlockState();
                case ChessLogic.KNIGHT -> (white ? ChessboardMod.CHESS_PIECE_KNIGHT_WHITE : ChessboardMod.CHESS_PIECE_KNIGHT).get().defaultBlockState();
                case ChessLogic.ROOK -> (white ? ChessboardMod.CHESS_PIECE_ROOK_WHITE : ChessboardMod.CHESS_PIECE_ROOK).get().defaultBlockState();
                default -> (white ? ChessboardMod.CHESS_PIECE_PAWN_WHITE : ChessboardMod.CHESS_PIECE_PAWN).get().defaultBlockState();
            };
        });

        // 五子棋：黑白双方 + 随机开局的灰色障碍子
        register(m, GomokuLogic.class, (g, piece) -> (GomokuLogic.isGray(piece)
                ? ChessboardMod.GOMOKU_PIECE_GRAY
                : g.side(piece) == 0 ? ChessboardMod.GOMOKU_PIECE_BLACK : ChessboardMod.GOMOKU_PIECE_WHITE)
                .get().defaultBlockState());

        // 井字棋：两方共用同一个模型，X 方靠 pieceFlipX 翻面
        register(m, TicTacToeLogic.class, (g, piece) -> ChessboardMod.TICTACTOE_PIECE_MODEL.get().defaultBlockState());

        // 飞行棋：中央格是骰子（FACE 属性决定点数），其余格是四色飞机；飞机再叠一层图标
        register(m, FlightChessLogic.class, new PieceModels() {
            @Override
            public BlockState model(BoardGameLogic g, int piece) {
                if (g instanceof DiceBoard db && db.isDice(piece)) {
                    return ChessboardMod.FLIGHT_DICE.get().defaultBlockState()
                            .setValue(FlightDiceBlock.FACE, db.faceOf(piece));
                }
                return ChessboardMod.FLIGHT_PIECE.get().defaultBlockState()
                        .setValue(FlightPieceBlock.TEAM, FlightTeam.of(g.side(piece)));
            }

            @Override
            public BlockState overlay(BoardGameLogic g, int piece) {
                return FlightChessLogic.isPlane(piece)
                        ? ChessboardMod.FLIGHT_ICON.get().defaultBlockState() : null;
            }
        });
        return m;
    }

    /** 按棋类登记模型提供者；同一个类后登记的覆盖先前的 */
    static void register(Class<? extends BoardGameLogic> logicClass, PieceModels models) {
        register(REGISTRY, logicClass, models);
    }

    private static void register(Map<Class<?>, PieceModels> map,
                                 Class<? extends BoardGameLogic> logicClass, PieceModels models) {
        map.put(logicClass, models);
    }

    /** 查这个棋类登记的提供者；没登记返回 {@code null}（= 用框架默认模型） */
    static PieceModels of(BoardGameLogic g) {
        return REGISTRY.get(g.getClass());
    }
}
