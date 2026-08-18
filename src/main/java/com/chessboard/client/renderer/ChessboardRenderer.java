package com.chessboard.client.renderer;

import com.chessboard.ChessboardMod;
import com.chessboard.MaterialData;
import com.chessboard.block.ChessMaterial;
import com.chessboard.block.ChessPieceBlock;
import com.chessboard.block.ChessboardBlock;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.ChessLogic;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.GomokuLogic;
import com.chessboard.game.TicTacToeLogic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 通用棋盘棋子渲染器 —— 框架层。
 * 通过 {@link BoardGameLogic} 适配不同棋类，负责模型加载、动画和文字渲染。
 */
public class ChessboardRenderer implements BlockEntityRenderer<ChessboardBlockEntity, ChessboardRenderer.ChessboardRenderState> {

    private final BlockModelResolver modelResolver;
    private final Font font;
    private final Map<BlockPos, AnimData> animMap = new HashMap<>();
    /** 每个棋盘缓存的已解析棋子模型（key: piece * 31 + materials 哈希），避免每帧每子重复解析 */
    private final Map<BlockPos, Map<Integer, BlockModelRenderState>> modelCache = new HashMap<>();

    public ChessboardRenderer(BlockEntityRendererProvider.Context ctx) {
        this.modelResolver = ctx.blockModelResolver();
        this.font = ctx.font();
    }

    private static class AnimData {
        long selMs, unselMs, moveMs, flipMs;
        int[] prevPieces;
        int prevSelRow = -1, prevSelCol = -1;
        int unselRow = -1, unselCol = -1;
        int fromRow = -1, fromCol = -1, toRow = -1, toCol = -1;
        int flipRow = -1, flipCol = -1;
    }

    private BlockModelRenderState loadModel(BoardGameLogic g, int piece, String[] materials) {
        var ms = new BlockModelRenderState();
        var state = switch (g) {
            case ChineseChessLogic ccl -> withMaterial(
                    ChineseChessLogic.isHidden(piece)
                            ? ChessboardMod.CHINESE_PIECE_HIDDEN.get()
                            : ChessboardMod.CHESS_PIECE_MODEL.get(),
                    materials, MaterialData.SLOT_CHINESE);
            case GomokuLogic gml -> {
                int slot = GomokuLogic.isGray(piece)
                        ? MaterialData.SLOT_GOMOKU_GRAY
                        : (gml.side(piece) == 0
                                ? MaterialData.SLOT_GOMOKU_BLACK
                                : MaterialData.SLOT_GOMOKU_WHITE);
                var block = GomokuLogic.isGray(piece)
                        ? ChessboardMod.GOMOKU_PIECE_GRAY.get()
                        : (gml.side(piece) == 0
                                ? ChessboardMod.GOMOKU_PIECE_BLACK.get()
                                : ChessboardMod.GOMOKU_PIECE_WHITE.get());
                yield withMaterial(block, materials, slot);
            }
            case TicTacToeLogic ttt -> ChessboardMod.TICTACTOE_PIECE_MODEL.get().defaultBlockState();
            case ChessLogic cl -> {
                boolean isWhite = cl.side(piece) == 0;
                int slot = isWhite ? MaterialData.SLOT_CHESS_WHITE : MaterialData.SLOT_CHESS_BLACK;
                var block = switch (ChessLogic.type(piece)) {
                    case ChessLogic.KING -> isWhite ? ChessboardMod.CHESS_PIECE_KING_WHITE.get() : ChessboardMod.CHESS_PIECE_KING.get();
                    case ChessLogic.QUEEN -> isWhite ? ChessboardMod.CHESS_PIECE_QUEEN_WHITE.get() : ChessboardMod.CHESS_PIECE_QUEEN.get();
                    case ChessLogic.BISHOP -> isWhite ? ChessboardMod.CHESS_PIECE_BISHOP_WHITE.get() : ChessboardMod.CHESS_PIECE_BISHOP.get();
                    case ChessLogic.KNIGHT -> isWhite ? ChessboardMod.CHESS_PIECE_KNIGHT_WHITE.get() : ChessboardMod.CHESS_PIECE_KNIGHT.get();
                    case ChessLogic.ROOK -> isWhite ? ChessboardMod.CHESS_PIECE_ROOK_WHITE.get() : ChessboardMod.CHESS_PIECE_ROOK.get();
                    default -> isWhite ? ChessboardMod.CHESS_PIECE_PAWN_WHITE.get() : ChessboardMod.CHESS_PIECE_PAWN.get();
                };
                yield withMaterial(block, materials, slot);
            }
            default -> ChessboardMod.CHESS_PIECE_MODEL.get().defaultBlockState();
        };
        modelResolver.update(ms, state, BlockDisplayContext.create());
        return ms;
    }

    /** 取缓存棋子模型：仅当 piece/材质变化时才重新解析（submit 只读复用，见 BlockModelRenderState#submit） */
    private BlockModelRenderState cachedModel(BlockPos pos, BoardGameLogic g, int piece, String[] materials) {
        Map<Integer, BlockModelRenderState> byPiece = modelCache.computeIfAbsent(pos, k -> new HashMap<>());
        int key = piece * 31 + Arrays.hashCode(materials);
        BlockModelRenderState rs = byPiece.get(key);
        if (rs == null) {
            rs = loadModel(g, piece, materials);
            byPiece.put(key, rs);
        }
        return rs;
    }

    /** 按棋盘上保存的材质配置选择棋子方块状态 */
    private static BlockState withMaterial(ChessPieceBlock block, String[] materials, int slot) {
        String mat = materials != null && slot < materials.length ? materials[slot] : null;
        ChessMaterial m = mat != null
                ? ChessMaterial.byName(mat)
                : MaterialData.defaultMaterial(slot);
        return block.defaultBlockState().setValue(ChessPieceBlock.MATERIAL, m);
    }

    @Override
    public ChessboardRenderState createRenderState() { return new ChessboardRenderState(); }
    // 棋子/文字都在方块包围盒内（浮动高度很小），可交给视锥剔除；棋盘不在画面里就不渲染
    @Override
    public boolean shouldRenderOffScreen() { return false; }

    @Override
    public void extractRenderState(ChessboardBlockEntity entity, ChessboardRenderState s,
                                    float partialTick, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(entity, s, crumbling);
        BoardGameLogic g = entity.gameLogic();
        int total = g.rows() * g.cols();
        if (s.pieces == null || s.pieces.length != total) s.pieces = new int[total];
        System.arraycopy(entity.pieces(), 0, s.pieces, 0, total);
        s.selRow = entity.selRow();
        s.selCol = entity.selCol();
        s.rows = g.rows();
        s.cols = g.cols();
        s.facing = entity.getBlockState().getValue(ChessboardBlock.FACING);
        s.logic = g;
        if (s.materials == null || s.materials.length != MaterialData.SLOT_COUNT)
            s.materials = new String[MaterialData.SLOT_COUNT];
        System.arraycopy(entity.materials(), 0, s.materials, 0, MaterialData.SLOT_COUNT);

        AnimData a = animMap.computeIfAbsent(entity.getBlockPos(), k -> new AnimData());
        if (a.prevPieces == null || a.prevPieces.length != total) a.prevPieces = new int[total];
        long now = System.currentTimeMillis();

        boolean changed = !Arrays.equals(s.pieces, a.prevPieces);
        if (changed) {
            a.fromRow = a.fromCol = a.toRow = a.toCol = -1;
            int moved = 0;
            for (int i = 0; i < total; i++) {
                if (a.prevPieces[i] != 0 && s.pieces[i] == 0) {
                    a.fromRow = i / g.cols(); a.fromCol = i % g.cols();
                    moved = a.prevPieces[i]; break;
                }
            }
            for (int i = 0; i < total; i++) {
                if (s.pieces[i] == moved && a.prevPieces[i] != moved) {
                    a.toRow = i / g.cols(); a.toCol = i % g.cols(); break;
                }
            }
            // 暗棋翻面动画：prev 为暗棋、now 为明棋
            a.flipRow = a.flipCol = -1;
            if (s.logic instanceof ChineseChessLogic) {
                for (int i = 0; i < total; i++) {
                    if (ChineseChessLogic.isHidden(a.prevPieces[i])
                            && !ChineseChessLogic.isHidden(s.pieces[i])
                            && a.prevPieces[i] != s.pieces[i]) {
                        a.flipRow = i / g.cols(); a.flipCol = i % g.cols(); a.flipMs = now;
                        break;
                    }
                }
            }
            a.moveMs = now;
            System.arraycopy(s.pieces, 0, a.prevPieces, 0, total);
        }

        if (a.prevSelRow != s.selRow || a.prevSelCol != s.selCol) {
            if (!changed && a.prevSelRow >= 0 && (s.selRow < 0 || s.selRow != a.prevSelRow || s.selCol != a.prevSelCol)) {
                a.unselRow = a.prevSelRow; a.unselCol = a.prevSelCol; a.unselMs = now;
            }
            a.selMs = now;
            a.prevSelRow = s.selRow; a.prevSelCol = s.selCol;
        }
        float selT = Math.clamp((now - a.selMs) / (float) g.pieceLiftMs(), 0f, 1f);
        s.lift = (s.selRow >= 0) ? g.pieceLift() * selT : 0;
        s.unlift = g.pieceLift() * (1f - Math.clamp((now - a.unselMs) / (float) g.pieceLiftMs(), 0f, 1f));
        s.moveT = Math.clamp((now - a.moveMs) / (float) g.pieceMoveMs(), 0f, 1f);
        s.unselRow = a.unselRow; s.unselCol = a.unselCol;
        s.fromRow = a.fromRow; s.fromCol = a.fromCol;
        s.toRow = a.toRow; s.toCol = a.toCol;
        s.flipRow = a.flipRow; s.flipCol = a.flipCol;
        s.flipT = Math.clamp((now - a.flipMs) / (float) g.pieceFlipMs(), 0f, 1f);
    }

    @Override
    public void submit(ChessboardRenderState s, PoseStack ps,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        int light = s.lightCoords, overlay = OverlayTexture.NO_OVERLAY;
        float[] scratch = new float[2];

        for (int row = 0; row < s.rows; row++) {
            for (int col = 0; col < s.cols; col++) {
                int piece = s.pieces[row * s.cols + col];
                if (piece == 0) continue;
                if (s.moveT < 1f && s.toRow == row && s.toCol == col) continue;

                boolean flipping = (s.flipRow == row && s.flipCol == col && s.flipT < 1f);
                // 翻面前半程显示背面（暗棋）模型
                int modelPiece = (flipping && s.flipT < 0.5f)
                        ? ChineseChessLogic.hide(piece)
                        : piece;
                BlockModelRenderState model = cachedModel(s.blockPos, s.logic, modelPiece, s.materials);
                boolean sel = (s.selRow == row && s.selCol == col);
                float lift = sel ? s.lift : 0;
                if (s.unselRow == row && s.unselCol == col && s.unlift > 0 && lift == 0) lift = s.unlift;

                gridPos(s, row, col, scratch);
                float flipDeg = flipping ? 180f * (1f - s.flipT) : 0;
                renderPiece(ps, collector, model, scratch[0], scratch[1], s, lift, light, overlay, modelPiece, flipDeg);
                renderText(ps, collector, scratch[0], scratch[1], s, lift, light, modelPiece);
            }
        }

        if (s.moveT < 1f && s.fromRow >= 0 && s.toRow >= 0) {
            int p = s.pieces[s.toRow * s.cols + s.toCol];
            if (p != 0) {
                BlockModelRenderState mm = cachedModel(s.blockPos, s.logic, p, s.materials);
                float[] from = new float[2];
                float[] to = new float[2];
                gridPos(s, s.fromRow, s.fromCol, from);
                gridPos(s, s.toRow, s.toCol, to);
                float wx = lerp(from[0], to[0], s.moveT);
                float wz = lerp(from[1], to[1], s.moveT);
                renderPiece(ps, collector, mm, wx, wz, s, s.logic.pieceLift() * (1f - s.moveT), light, overlay, p, 0);
                renderText(ps, collector, wx, wz, s, s.logic.pieceLift() * (1f - s.moveT), light, p);
            }
        }
    }

    private static void renderPiece(PoseStack ps, SubmitNodeCollector cc, BlockModelRenderState m,
                                     float wx, float wz, ChessboardRenderState s, float lift,
                                     int light, int overlay, int piece, float flipDeg) {
        float y = s.logic.pieceHeight() + lift;
        float cx = s.logic.pieceCenterX() / 16f, cz = s.logic.pieceCenterZ() / 16f;
        float sc = s.logic.pieceScale();
        ps.pushPose();
        ps.translate(wx, y, wz);
        ps.mulPose(Axis.YP.rotationDegrees(facingDegrees(s.facing, false)));
        if (flipDeg != 0) ps.mulPose(Axis.XP.rotationDegrees(flipDeg));
        if (s.logic.pieceFlipX(piece)) ps.mulPose(Axis.XP.rotationDegrees(180));
        float ry = s.logic.pieceYRotation(piece);
        if (ry != 0) ps.mulPose(Axis.YP.rotationDegrees(ry));
        ps.scale(sc, sc, sc);
        ps.translate(-cx, 0, -cz);
        m.submit(ps, cc, light, overlay, 0);
        ps.popPose();
    }

    private void renderText(PoseStack ps, SubmitNodeCollector cc,
                            float wx, float wz, ChessboardRenderState s,
                            float lift, int light, int piece) {
        String name = s.logic.pieceName(piece);
        if (name.isEmpty()) return;
        FormattedCharSequence text = FormattedCharSequence.forward(name, Style.EMPTY);
        float textH = s.logic.pieceHeight() + lift + s.logic.pieceTextHeight();
        ps.pushPose();
        ps.translate(wx, textH, wz);
        ps.mulPose(Axis.YP.rotationDegrees(facingDegrees(s.facing, true)));
        if (s.logic.side(piece) != 0) ps.mulPose(Axis.YP.rotationDegrees(180));
        ps.mulPose(Axis.XP.rotationDegrees(90));
        float ts = s.logic.pieceTextScale();
        ps.scale(ts, ts, ts);
        float tx = -font.width(text) / 2f + s.logic.pieceTextOffsetX();
        float ty = -font.lineHeight / 2f + s.logic.pieceTextOffsetZ();
        cc.submitText(ps, tx, ty, text, false, Font.DisplayMode.POLYGON_OFFSET, light,
                s.logic.textColor(piece), 0, 0xFF888888);
        ps.popPose();
    }

    /** 朝向对应的棋子 Y 旋转；text=true 时反向，用于文字始终面向玩家 */
    private static float facingDegrees(Direction facing, boolean text) {
        return switch (facing) {
            case WEST -> text ? 90 : -90;
            case NORTH -> 180;
            case EAST -> text ? -90 : 90;
            default -> 0;
        };
    }

    /** 世界坐标 → 棋盘行列（写入 out，避免每格分配数组） */
    private static void gridPos(ChessboardRenderState s, int row, int col, float[] out) {
        float gx = s.logic.colPixel(col) / 16f;
        float gz = s.logic.rowPixel(row) / 16f;
        switch (s.facing) {
            case WEST -> { out[0] = gz; out[1] = 1 - gx; }
            case NORTH -> { out[0] = 1 - gx; out[1] = 1 - gz; }
            case EAST -> { out[0] = 1 - gz; out[1] = gx; }
            default -> { out[0] = gx; out[1] = gz; }
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    public static class ChessboardRenderState extends BlockEntityRenderState {
        public int[] pieces;
        public int selRow = -1, selCol = -1, rows, cols;
        public Direction facing = Direction.SOUTH;
        public BoardGameLogic logic;
        public String[] materials;
        public float lift, unlift, moveT = 1f;
        public int unselRow = -1, unselCol = -1;
        public int fromRow = -1, fromCol = -1, toRow = -1, toCol = -1;
        public int flipRow = -1, flipCol = -1;
        public float flipT = 1f;
    }
}
