package com.chessboard.client.renderer;

import com.chessboard.ChessboardMod;
import com.chessboard.MaterialData;
import com.chessboard.block.ChessChar;
import com.chessboard.block.ChessCharBlock;
import com.chessboard.block.ChessMaterial;
import com.chessboard.block.ChessPieceBlock;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.ChessLogic;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.GomokuLogic;
import com.chessboard.game.TicTacToeLogic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent.SectionRenderingContext;
import net.neoforged.neoforge.client.model.ao.EnhancedBlockModelLighter;

import java.util.List;

/**
 * 棋子几何的共用工具 —— 方块实体渲染器与区块几何两条路径必须用同一份实现，
 * 否则烘焙版与动态版的位姿/材质会对不上。
 */
public final class ChessboardPieceGeometry {

    private static final Direction[] DIRECTIONS = Direction.values();
    /** 模型变体选择用的固定种子：棋子模型都是单变体，这样取值即可 */
    private static final long MODEL_SEED = 42L;

    private ChessboardPieceGeometry() {}

    /** 朝向对应的棋子 Y 旋转；text=true 时反向，用于文字始终面向玩家 */
    public static float facingDegrees(Direction facing, boolean text) {
        return switch (facing) {
            case WEST -> text ? 90 : -90;
            case NORTH -> 180;
            case EAST -> text ? -90 : 90;
            default -> 0;
        };
    }

    /** 棋盘行列 → 方块内局部坐标（写入 out，避免每格分配数组） */
    public static void gridPos(BoardGameLogic g, Direction facing, int row, int col, float[] out) {
        float gx = g.colPixel(col) / 16f;
        float gz = g.rowPixel(row) / 16f;
        switch (facing) {
            case WEST -> { out[0] = gz; out[1] = 1 - gx; }
            case NORTH -> { out[0] = 1 - gx; out[1] = 1 - gz; }
            case EAST -> { out[0] = 1 - gz; out[1] = gx; }
            default -> { out[0] = gx; out[1] = gz; }
        }
    }

    /** 棋子值 + 棋盘材质配置 → 方块状态 */
    public static BlockState stateFor(BoardGameLogic g, int piece, String[] materials) {
        return switch (g) {
            case ChineseChessLogic ccl -> withMaterial(
                    ChineseChessLogic.isHidden(piece)
                            ? ChessboardMod.CHINESE_PIECE_HIDDEN.get()
                            : ChessboardMod.CHESS_PIECE_MODEL.get(),
                    materials, MaterialData.SLOT_CHINESE);
            case GomokuLogic gml -> {
                int slot = GomokuLogic.isGray(piece)
                        ? MaterialData.SLOT_GOMOKU_GRAY
                        : (gml.side(piece) == 0 ? MaterialData.SLOT_GOMOKU_BLACK : MaterialData.SLOT_GOMOKU_WHITE);
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
    }

    /**
     * 棋子上的汉字方块状态。非中国象棋、或暗棋（背面朝上）返回 null —— 不渲染汉字。
     * 汉字已做成贴图，可与棋子一起烘焙进区块几何。
     */
    public static BlockState charStateFor(BoardGameLogic g, int piece) {
        if (!(g instanceof ChineseChessLogic)) return null;
        if (ChineseChessLogic.isHidden(piece)) return null;
        ChessChar c = ChessChar.of(ChineseChessLogic.type(piece), g.side(piece));
        return c == null ? null
                : ChessboardMod.CHINESE_PIECE_CHAR.get().defaultBlockState().setValue(ChessCharBlock.CHAR, c);
    }

    /** 按棋盘上保存的材质配置选择棋子方块状态 */
    private static BlockState withMaterial(ChessPieceBlock block, String[] materials, int slot) {
        String mat = materials != null && slot < materials.length ? materials[slot] : null;
        ChessMaterial m = mat != null ? ChessMaterial.byName(mat) : MaterialData.defaultMaterial(slot);
        return block.defaultBlockState().setValue(ChessPieceBlock.MATERIAL, m);
    }

    /**
     * 单块棋盘的发射上下文：一次区块重建内复用同一套缓冲，避免反复分配。
     *
     * <p><b>{@code lighter} 不可省略</b> —— 地形管线的顶点着色器没有法线属性，
     * 面朝向明暗（顶面亮、侧面暗）是由原版光照器通过 {@code QuadInstance.setColor} 写进顶点色的，
     * 同时它也负责逐顶点平滑光照（AO）。少了它棋子会一片死白、毫无明暗。
     */
    public static final class EmitContext {
        final SectionRenderingContext ctx;
        final BlockAndTintGetter region;
        final BlockStateModelSet models;
        final BlockModelLighter lighter;
        final PoseStack ps = new PoseStack();
        final QuadInstance qi = new QuadInstance();
        final List<BlockStateModelPart> parts = new ObjectArrayList<>();
        final BlockPos boardPos;
        final float ox, oy, oz;

        public EmitContext(SectionRenderingContext ctx, BlockStateModelSet models,
                           BlockPos boardPos, float ox, float oy, float oz) {
            this.ctx = ctx;
            this.models = models;
            this.region = ctx.getRegion();
            this.lighter = EnhancedBlockModelLighter.newInstance();
            this.boardPos = boardPos;
            this.ox = ox; this.oy = oy; this.oz = oz;
        }
    }

    /**
     * 把一颗棋子（或其汉字）发射进区块几何。
     *
     * <p>光照与明暗完全走原版那一套 —— 逐面取光照、再交给光照器算逐顶点光照与面朝向明暗，
     * 保证和区块里其他方块看起来一致。
     */
    public static void emitPiece(EmitContext ec, BlockState state, float wx, float wz,
                                 BoardGameLogic g, Direction facing, int piece, boolean textRotation) {
        float cx = g.pieceCenterX() / 16f, cz = g.pieceCenterZ() / 16f;
        float sc = g.pieceScale();
        PoseStack ps = ec.ps;

        // 圆片类棋子的朝向跟随文字（含阵营翻转），保证棋子和自己的汉字完全对齐
        boolean textLike = textRotation || g.pieceFollowsTextRotation();
        ps.pushPose();
        ps.translate(ec.ox + wx, ec.oy + g.pieceHeight(), ec.oz + wz);
        ps.mulPose(Axis.YP.rotationDegrees(facingDegrees(facing, textLike)));
        if (textLike && g.side(piece) != 0) ps.mulPose(Axis.YP.rotationDegrees(180));
        if (g.pieceFlipX(piece)) ps.mulPose(Axis.XP.rotationDegrees(180));
        float ry = g.pieceYRotation(piece);
        if (ry != 0) ps.mulPose(Axis.YP.rotationDegrees(ry));
        ps.scale(sc, sc, sc);
        ps.translate(-cx, 0, -cz);

        ec.lighter.reset(); // 必须：把 cache 注入 AO 计算器，否则 prepareQuad* 会 NPE
        ec.parts.clear();
        BlockStateModel model = ec.models.get(state);
        model.collectParts(ec.region, ec.boardPos, state, RandomSource.create(MODEL_SEED), ec.parts);

        PoseStack.Pose pose = ps.last();
        for (BlockStateModelPart part : ec.parts) {
            for (Direction d : DIRECTIONS) {
                List<BakedQuad> quads = part.getQuads(d);
                if (quads.isEmpty()) continue;
                int lightCoords = ec.lighter.getLightCoords(state, ec.region, ec.boardPos.relative(d));
                for (BakedQuad q : quads) {
                    putQuad(ec.ctx.getOrCreateChunkBuffer(q.materialInfo().layer()),
                            ec.region, ec.boardPos, state, ec.lighter, q, lightCoords, ec.qi, pose);
                }
            }
            // 无方向（不参与面剔除）的四边形
            for (BakedQuad q : part.getQuads(null)) {
                putQuad(ec.ctx.getOrCreateChunkBuffer(q.materialInfo().layer()),
                        ec.region, ec.boardPos, state, ec.lighter, q, -1, ec.qi, pose);
            }
        }
        ps.popPose();
    }

    /**
     * 把整个模型的四边形写进一个 VertexConsumer —— 方块实体渲染器路径用。
     *
     * <p>光照与明暗复用 {@link #putQuad}，与区块几何路径完全一致，
     * 这样动画中的棋子（渲染器画）和静态棋子（烘焙进区块）亮度相同，
     * 交接时不会出现亮度跳变。
     */
    public static void emitQuads(VertexConsumer vc, PoseStack.Pose pose, BlockAndTintGetter region,
                                 BlockPos boardPos, BlockState state, BlockModelLighter lighter,
                                 BlockStateModelSet models, List<BlockStateModelPart> parts,
                                 QuadInstance qi) {
        lighter.reset(); // 必须：把 cache 注入 AO 计算器，否则 prepareQuad* 会 NPE
        parts.clear();
        models.get(state).collectParts(region, boardPos, state, RandomSource.create(MODEL_SEED), parts);
        for (BlockStateModelPart part : parts) {
            for (Direction d : DIRECTIONS) {
                List<BakedQuad> quads = part.getQuads(d);
                if (quads.isEmpty()) continue;
                int lightCoords = lighter.getLightCoords(state, region, boardPos.relative(d));
                for (BakedQuad q : quads) {
                    putQuad(vc, region, boardPos, state, lighter, q, lightCoords, qi, pose);
                }
            }
            for (BakedQuad q : part.getQuads(null)) {
                putQuad(vc, region, boardPos, state, lighter, q, -1, qi, pose);
            }
        }
    }

    /** 交给原版光照器算好逐顶点光照与面朝向明暗后写入缓冲 */
    private static void putQuad(VertexConsumer vc, BlockAndTintGetter region, BlockPos boardPos,
                                BlockState state, BlockModelLighter lighter, BakedQuad q,
                                int lightCoords, QuadInstance qi, PoseStack.Pose pose) {
        if (q.materialInfo().ambientOcclusion()) {
            lighter.prepareQuadAmbientOcclusion(region, state, boardPos, q, qi);
        } else {
            lighter.prepareQuadFlat(region, state, boardPos, lightCoords, q, qi);
        }
        qi.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        vc.putBakedQuad(pose, q, qi);
    }
}
