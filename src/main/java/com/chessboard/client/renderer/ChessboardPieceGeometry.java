package com.chessboard.client.renderer;

import com.chessboard.ChessboardMod;
import com.chessboard.MaterialData;
import com.chessboard.block.ChessMaterial;
import com.chessboard.block.ChessPieceBlock;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.ChessLogic;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.GomokuLogic;
import com.chessboard.game.TicTacToeLogic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent.SectionRenderingContext;

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

    /** 按棋盘上保存的材质配置选择棋子方块状态 */
    private static BlockState withMaterial(ChessPieceBlock block, String[] materials, int slot) {
        String mat = materials != null && slot < materials.length ? materials[slot] : null;
        ChessMaterial m = mat != null ? ChessMaterial.byName(mat) : MaterialData.defaultMaterial(slot);
        return block.defaultBlockState().setValue(ChessPieceBlock.MATERIAL, m);
    }

    /**
     * 把一颗静止棋子发射进区块几何（worker 线程）。
     *
     * <p>变换链必须与 {@code ChessboardRenderer.renderPiece} 保持一致：
     * 平移 → 朝向 Y 旋转 → 翻转 X → 棋子自身 Y 旋转 → 缩放 → 移到模型中心。
     * 法线变换与 UV 解包由 {@code VertexConsumer.putBakedQuad} 内部处理。
     *
     * @param ox,oy,oz 棋盘相对区块原点的偏移
     * @param wx,wz    棋子在方块内的局部坐标
     * @param light    打包光照（由调用方按棋盘位置算一次）
     */
    public static void emitPiece(SectionRenderingContext ctx, PoseStack ps, QuadInstance qi,
                                 List<BlockStateModelPart> parts, BlockStateModelSet models,
                                 BlockState state, float ox, float oy, float oz,
                                 float wx, float wz, BoardGameLogic g,
                                 Direction facing, int piece, int light) {
        float cx = g.pieceCenterX() / 16f, cz = g.pieceCenterZ() / 16f;
        float sc = g.pieceScale();

        ps.pushPose();
        ps.translate(ox + wx, oy + g.pieceHeight(), oz + wz);
        ps.mulPose(Axis.YP.rotationDegrees(facingDegrees(facing, false)));
        if (g.pieceFlipX(piece)) ps.mulPose(Axis.XP.rotationDegrees(180));
        float ry = g.pieceYRotation(piece);
        if (ry != 0) ps.mulPose(Axis.YP.rotationDegrees(ry));
        ps.scale(sc, sc, sc);
        ps.translate(-cx, 0, -cz);

        qi.setLightCoords(light);
        qi.setOverlayCoords(OverlayTexture.NO_OVERLAY);

        parts.clear();
        BlockStateModel model = models.get(state);
        model.collectParts(ctx.getRegion(), BlockPos.ZERO, state, RandomSource.create(MODEL_SEED), parts);

        PoseStack.Pose pose = ps.last();
        for (BlockStateModelPart part : parts) {
            for (Direction d : DIRECTIONS) {
                for (var q : part.getQuads(d)) {
                    ctx.getOrCreateChunkBuffer(q.materialInfo().layer()).putBakedQuad(pose, q, qi);
                }
            }
            // 无方向（不参与面剔除）的四边形
            for (var q : part.getQuads(null)) {
                ctx.getOrCreateChunkBuffer(q.materialInfo().layer()).putBakedQuad(pose, q, qi);
            }
        }
        ps.popPose();
    }
}
