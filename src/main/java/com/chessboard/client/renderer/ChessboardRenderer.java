package com.chessboard.client.renderer;

import com.chessboard.Config;
import com.chessboard.MaterialData;
import com.chessboard.block.ChessboardBlock;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.ChineseChessLogic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
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
 * 棋盘棋子渲染器 —— 只负责<b>动画中</b>的棋子和文字。
 *
 * <p>静止棋子在区块网格构建时已由 {@link ChessboardSectionGeometry} 烘焙进区块顶点，
 * 本渲染器不再重复绘制。动画状态来自 {@link ChessboardAnimTracker}（与区块几何共用同一份），
 * 因此两条路径绘制的格子天然互斥。
 *
 * <p>文字无法烘焙（字体图集 ≠ 地形图集，且需逐帧朝向相机 + POLYGON_OFFSET），
 * 所以始终由本渲染器绘制。
 */
public class ChessboardRenderer implements BlockEntityRenderer<ChessboardBlockEntity, ChessboardRenderer.ChessboardRenderState> {

    private final BlockModelResolver modelResolver;
    private final Font font;
    /** 动态棋子的模型缓存（key: piece * 31 + materials 哈希）；同时最多只会有几颗 */
    private final Map<BlockPos, Map<Integer, BlockModelRenderState>> modelCache = new HashMap<>();

    public ChessboardRenderer(BlockEntityRendererProvider.Context ctx) {
        this.modelResolver = ctx.blockModelResolver();
        this.font = ctx.font();
    }

    /** 取缓存棋子模型：仅当 piece/材质变化时才重新解析（submit 只读复用，见 BlockModelRenderState#submit） */
    private BlockModelRenderState cachedModel(BlockPos pos, BoardGameLogic g, int piece, String[] materials) {
        Map<Integer, BlockModelRenderState> byPiece = modelCache.computeIfAbsent(pos, k -> new HashMap<>());
        int key = piece * 31 + Arrays.hashCode(materials);
        BlockModelRenderState rs = byPiece.get(key);
        if (rs == null) {
            var ms = new BlockModelRenderState();
            modelResolver.update(ms, ChessboardPieceGeometry.stateFor(g, piece, materials),
                    BlockDisplayContext.create());
            byPiece.put(key, ms);
            rs = ms;
        }
        return rs;
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
        // 远处不渲染：文字和动画棋子都跳过，省掉每帧的模型提交与字形批处理
        double limit = Config.BOARD_RENDER_DISTANCE.get();
        if (camera.distanceToSqr(Vec3.atCenterOf(entity.getBlockPos())) > limit * limit) {
            s.tooFar = true;
            return;
        }
        s.tooFar = false;
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

        long now = System.currentTimeMillis();
        ChessboardAnimTracker.INSTANCE.ensureBaseline(entity); // 兜底：数据钩子漏掉时也能建立状态
        var a = ChessboardAnimTracker.INSTANCE.get(entity.getBlockPos());
        if (a == null) {
            // 状态尚未建立：全部按静止处理（理论上有数据钩子会先跑）
            s.lift = 0; s.unlift = 0;
            s.moveT = 1f; s.flipT = 1f; s.winT = 1f;
            s.unselRow = s.unselCol = -1;
            s.fromRow = s.fromCol = s.toRow = s.toCol = -1;
            s.flipRow = s.flipCol = -1;
            s.winCells = null;
            return;
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
        s.winCells = a.winCells;
        s.winT = a.winCells != null
                ? Math.clamp((now - a.winMs) / (float) ChessboardAnimTracker.WIN_ANIM_MS, 0f, 1f)
                : 1f;
    }

    @Override
    public void submit(ChessboardRenderState s, PoseStack ps,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        if (s.tooFar) return;
        int light = s.lightCoords, overlay = OverlayTexture.NO_OVERLAY;
        float[] scratch = new float[2];
        long now = System.currentTimeMillis();

        for (int row = 0; row < s.rows; row++) {
            for (int col = 0; col < s.cols; col++) {
                int cell = row * s.cols + col;
                int piece = s.pieces[cell];
                if (piece == 0) continue;
                // 飞行中的棋子由下方单独绘制（含文字）
                if (s.moveT < 1f && s.toRow == row && s.toCol == col) continue;

                // 静止棋子在区块几何里，这里只画动画中的那几颗
                if (ChessboardAnimTracker.INSTANCE.isDynamic(s.blockPos, cell, now)) {
                    boolean flipping = (s.flipRow == row && s.flipCol == col && s.flipT < 1f);
                    // 翻面前半程显示背面（暗棋）模型
                    int modelPiece = (flipping && s.flipT < 0.5f)
                            ? ChineseChessLogic.hide(piece)
                            : piece;
                    BlockModelRenderState model = cachedModel(s.blockPos, s.logic, modelPiece, s.materials);
                    boolean sel = (s.selRow == row && s.selCol == col);
                    float lift = sel ? s.lift : 0;
                    if (s.unselRow == row && s.unselCol == col && s.unlift > 0 && lift == 0) lift = s.unlift;

                    // 连五胜利：微微跳起 + 左右倾斜晃动（逐个错峰；0~0.35 起跳 / 0.35~0.65 悬停歪动 / 0.65~1 回落）
                    float winJump = 0, winTilt = 0;
                    if (s.winT < 1f && s.winCells != null) {
                        for (int i = 0; i < s.winCells.length; i++) {
                            if (s.winCells[i] == cell) {
                                float tj = Math.clamp(s.winT * 1.3f - i * 0.07f, 0f, 1f);
                                if (tj < 0.35f) {
                                    float k = tj / 0.35f;
                                    winJump = 0.02f * (1f - (1f - k) * (1f - k)); // easeOut 起跳
                                } else if (tj > 0.65f) {
                                    winJump = 0.02f * (1f - (tj - 0.65f) / 0.35f); // 线性回落
                                } else {
                                    winJump = 0.02f; // 悬停
                                }
                                if (tj >= 0.35f && tj < 0.65f) {
                                    float p = (tj - 0.35f) / 0.3f;
                                    // 绕底部中心左右歪一下：/ 到 \ 一个完整来回，轻微衰减
                                    winTilt = 10f * (float) Math.sin(p * Math.PI * 2f) * (1f - p * 0.5f);
                                }
                                break;
                            }
                        }
                    }

                    ChessboardPieceGeometry.gridPos(s.logic, s.facing, row, col, scratch);
                    float flipDeg = flipping ? 180f * (1f - s.flipT) : 0;
                    renderPiece(ps, collector, model, scratch[0], scratch[1], s, lift + winJump,
                            light, overlay, modelPiece, flipDeg, winTilt);
                    renderText(ps, collector, scratch[0], scratch[1], s, lift + winJump, light, modelPiece);
                } else {
                    // 静止棋子已烘焙：只补文字
                    ChessboardPieceGeometry.gridPos(s.logic, s.facing, row, col, scratch);
                    renderText(ps, collector, scratch[0], scratch[1], s, 0f, light, piece);
                }
            }
        }

        // 飞行中的棋子（起点已空、终点已从几何排除）
        if (s.moveT < 1f && s.fromRow >= 0 && s.toRow >= 0) {
            int p = s.pieces[s.toRow * s.cols + s.toCol];
            if (p != 0) {
                BlockModelRenderState mm = cachedModel(s.blockPos, s.logic, p, s.materials);
                float[] from = new float[2];
                float[] to = new float[2];
                ChessboardPieceGeometry.gridPos(s.logic, s.facing, s.fromRow, s.fromCol, from);
                ChessboardPieceGeometry.gridPos(s.logic, s.facing, s.toRow, s.toCol, to);
                float wx = lerp(from[0], to[0], s.moveT);
                float wz = lerp(from[1], to[1], s.moveT);
                renderPiece(ps, collector, mm, wx, wz, s, s.logic.pieceLift() * (1f - s.moveT),
                        light, overlay, p, 0, 0);
                renderText(ps, collector, wx, wz, s, s.logic.pieceLift() * (1f - s.moveT), light, p);
            }
        }
    }

    private static void renderPiece(PoseStack ps, SubmitNodeCollector cc, BlockModelRenderState m,
                                     float wx, float wz, ChessboardRenderState s, float lift,
                                     int light, int overlay, int piece, float flipDeg, float winTilt) {
        float y = s.logic.pieceHeight() + lift;
        float cx = s.logic.pieceCenterX() / 16f, cz = s.logic.pieceCenterZ() / 16f;
        float sc = s.logic.pieceScale();
        ps.pushPose();
        ps.translate(wx, y, wz);
        ps.mulPose(Axis.YP.rotationDegrees(ChessboardPieceGeometry.facingDegrees(s.facing, false)));
        if (winTilt != 0) ps.mulPose(Axis.ZP.rotationDegrees(winTilt)); // 胜利左右歪动，绕底部中心
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
        ps.mulPose(Axis.YP.rotationDegrees(ChessboardPieceGeometry.facingDegrees(s.facing, true)));
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
        public int[] winCells;
        public float winT = 1f;
        /** 超出渲染距离，本帧不画（见 Config#BOARD_RENDER_DISTANCE） */
        public boolean tooFar;
    }
}
