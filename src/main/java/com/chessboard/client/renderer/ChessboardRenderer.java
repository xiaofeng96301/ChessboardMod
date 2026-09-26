package com.chessboard.client.renderer;

import com.chessboard.Config;
import com.chessboard.SkinData;
import com.chessboard.block.ChessboardBlock;
import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.FlightChessLogic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.math.Axis;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.ao.EnhancedBlockModelLighter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 棋盘棋子渲染器 —— 只负责<b>动画中</b>的棋子。
 *
 * <p>静止棋子在区块网格构建时已由 {@link ChessboardSectionGeometry} 烘焙进区块顶点，
 * 本渲染器不再重复绘制。动画状态来自 {@link ChessboardAnimTracker}（与区块几何共用同一份），
 * 因此两条路径绘制的格子天然互斥。
 *
 * <p><b>光照必须和区块几何路径完全一致</b>：两条路径的着色器都只做
 * {@code Color * lightmap}（地形管线没有法线属性，面朝向明暗存在顶点色里），
 * 所以这里也走同一个 {@link BlockModelLighter}，否则动画结束、棋子由渲染器交还给区块几何时
 * 会出现亮度跳变。
 */
public class ChessboardRenderer implements BlockEntityRenderer<ChessboardBlockEntity, ChessboardRenderer.ChessboardRenderState> {

    /** 骰子立方体模型里几何中心的 y（模型单位，立方体占 y 0..4） */
    private static final float DICE_CENTER_Y = 2f;

    /** 与区块几何路径同一个光照器；懒创建 —— 它内部取线程本地的 AO 缓存，必须在实际使用线程上构造 */
    private BlockModelLighter lighter;
    private final QuadInstance quadInstance = new QuadInstance();
    private final List<BlockStateModelPart> parts = new ObjectArrayList<>();
    /** 资源重载会重建渲染器实例，所以这里的引用不会过期 */
    private BlockStateModelSet models;
    /** 方块 ID → 皮肤贴图（null 也缓存）。同上，实例重建即缓存作废 */
    private final Map<String, TextureAtlasSprite> skinCache = new HashMap<>();

    public ChessboardRenderer(BlockEntityRendererProvider.Context ctx) {}

    private BlockModelLighter lighter() {
        if (lighter == null) lighter = EnhancedBlockModelLighter.newInstance();
        return lighter;
    }

    private BlockStateModelSet models() {
        if (models == null) {
            try {
                models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
            } catch (Exception e) {
                return null; // 模型尚未初始化
            }
        }
        return models;
    }

    /**
     * 方块 ID → 皮肤贴图。
     *
     * <p>按 ID 缓存是必需的：渲染器每帧要为每颗棋子取一次贴图，五子棋满盘 225 颗，
     * 每次现场查注册表 + 解析 Identifier 太贵。渲染器实例在资源重载时会重建，
     * 所以缓存的贴图不会变成过期对象。解析不出来也缓存（null），避免反复查未知 ID。
     */
    private TextureAtlasSprite skinSprite(String id) {
        if (id == null) return null;
        if (skinCache.containsKey(id)) return skinCache.get(id);
        TextureAtlasSprite sprite = ChessboardPieceGeometry.skinSprite(SkinData.stateOf(id), models());
        skinCache.put(id, sprite);
        return sprite;
    }

    /** 该棋子吃哪个槽位的皮肤（没设 / 不吃皮肤 → null） */
    private static TextureAtlasSprite skinFor(ChessboardRenderState s, int piece) {
        int slot = SkinData.slotFor(s.logic, piece);
        return slot >= 0 && s.skinSprites != null ? s.skinSprites[slot] : null;
    }

    @Override
    public ChessboardRenderState createRenderState() { return new ChessboardRenderState(); }

    // 必须为 false：SectionCompiler 只在这个值为 false 时才把方块实体加入**本区块**的渲染列表，
    // 从而和烘焙棋子走同一套区块级视锥剔除。改成 true 会转入 ClientLevel 的全局列表，
    // 而那个列表只在方块实体「被添加」时填充，已加载的棋盘不会补进去。
    @Override
    public boolean shouldRenderOffScreen() { return false; }

    @Override
    public void extractRenderState(ChessboardBlockEntity entity, ChessboardRenderState s,
                                    float partialTick, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(entity, s, crumbling);
        // 远处不渲染：省掉每帧的模型提交
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
        if (s.skins == null || s.skins.length != SkinData.SLOT_COUNT)
            s.skins = new String[SkinData.SLOT_COUNT];
        System.arraycopy(entity.skins(), 0, s.skins, 0, SkinData.SLOT_COUNT);
        // 各槽位的皮肤贴图（null = 没设）。每帧解析一次；按方块 ID 缓存，避免逐棋子重查
        if (s.skinSprites == null || s.skinSprites.length != SkinData.SLOT_COUNT)
            s.skinSprites = new TextureAtlasSprite[SkinData.SLOT_COUNT];
        for (int i = 0; i < s.skins.length; i++) s.skinSprites[i] = skinSprite(s.skins[i]);

        long now = System.currentTimeMillis();
        ChessboardAnimTracker.INSTANCE.ensureBaseline(entity); // 兜底：数据钩子漏掉时也能建立状态
        var a = ChessboardAnimTracker.INSTANCE.get(entity.getBlockPos());
        if (a == null) {
            // 状态尚未建立：全部按静止处理
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
        // 飞行棋骰子翻滚进度（未掷过时 rollMs=0，这里自然收敛到 1 = 静止）
        s.diceRollT = Math.clamp((now - a.rollMs) / (float) ChessboardAnimTracker.DICE_ROLL_MS, 0f, 1f);
    }

    @Override
    public void submit(ChessboardRenderState s, PoseStack ps,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        if (s.tooFar) return;
        BlockAndTintGetter region = Minecraft.getInstance().level;
        if (region == null || models() == null) return;
        float[] scratch = new float[2];

        for (int row = 0; row < s.rows; row++) {
            for (int col = 0; col < s.cols; col++) {
                int cell = row * s.cols + col;
                int piece = s.pieces[cell];
                if (piece == 0) continue;
                // 飞行中的棋子由下方单独绘制
                if (s.moveT < 1f && s.toRow == row && s.toCol == col) continue;
                // 静止棋子在区块几何里，这里只画动画中的那几颗
                if (!ChessboardAnimTracker.INSTANCE.isDynamic(s.blockPos, cell)) continue;

                boolean flipping = (s.flipRow == row && s.flipCol == col && s.flipT < 1f);
                // 翻面前半程显示背面（暗棋）模型
                int modelPiece = (flipping && s.flipT < 0.5f)
                        ? ChineseChessLogic.hide(piece)
                        : piece;
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
                renderPiece(ps, collector, s, region, scratch[0], scratch[1], lift + winJump,
                        modelPiece, flipDeg, winTilt);
            }
        }

        // 飞行中的棋子（起点已空、终点已从几何排除）
        if (s.moveT < 1f && s.fromRow >= 0 && s.toRow >= 0) {
            int p = s.pieces[s.toRow * s.cols + s.toCol];
            if (p != 0) {
                float[] from = new float[2];
                float[] to = new float[2];
                ChessboardPieceGeometry.gridPos(s.logic, s.facing, s.fromRow, s.fromCol, from);
                ChessboardPieceGeometry.gridPos(s.logic, s.facing, s.toRow, s.toCol, to);
                float wx = lerp(from[0], to[0], s.moveT);
                float wz = lerp(from[1], to[1], s.moveT);
                renderPiece(ps, collector, s, region, wx, wz, s.logic.pieceLift() * (1f - s.moveT),
                        p, 0, 0);
            }
        }
    }

    /** 绘制一颗棋子：圆片模型 + 其上的汉字/图标贴图（与棋子共用变换链，只是朝向按文字规则） */
    private void renderPiece(PoseStack ps, SubmitNodeCollector cc, ChessboardRenderState s,
                             BlockAndTintGetter region, float wx, float wz, float lift,
                             int piece, float flipDeg, float winTilt) {
        // 飞行棋骰子：翻滚 + 小跳。两整圈起步、缓出，结束时正好转回正立姿态，
        // 所以静止时（diceRollT=1）这里的 720° 等价于不转。
        float spinDeg = 0, hop = 0;
        if (s.logic instanceof FlightChessLogic && FlightChessLogic.isDice(piece)) {
            float t = s.diceRollT;
            float ease = 1f - (1f - t) * (1f - t) * (1f - t);
            spinDeg = 720f * ease;
            hop = 0.02f * (float) Math.sin(t * Math.PI);
        }
        float y = lift + hop;

        TextureAtlasSprite skin = skinFor(s, piece);
        submitModel(ps, cc, s, region, ChessboardPieceGeometry.stateFor(s.logic, piece),
                wx, wz, y, piece, flipDeg, winTilt, spinDeg, false, skin);

        BlockState charState = ChessboardPieceGeometry.charStateFor(s.logic, piece);
        if (charState != null) {
            // 汉字是本模组自己的贴图，皮肤不会覆盖它（skinQuad 会原样放过）
            submitModel(ps, cc, s, region, charState, wx, wz, y, piece, flipDeg, winTilt, spinDeg, true, skin);
        }
    }

    /**
     * 按与区块几何路径<b>完全相同</b>的变换与光照发射模型。
     * 用 {@code submitCustomGeometry} 自己写顶点，而不是 {@code BlockModelRenderState.submit}，
     * 因为后者不会加面朝向明暗 —— 那样两条路径亮度不一致，交接时就会闪。
     */
    private void submitModel(PoseStack ps, SubmitNodeCollector cc, ChessboardRenderState s,
                             BlockAndTintGetter region, BlockState state,
                             float wx, float wz, float lift,
                             int piece, float flipDeg, float winTilt, float spinDeg, boolean textRotation,
                             TextureAtlasSprite skin) {
        float y = s.logic.pieceHeight() + lift;
        float cx = s.logic.pieceCenterX() / 16f, cz = s.logic.pieceCenterZ() / 16f;
        float sc = s.logic.pieceScale();
        // 圆片类棋子的朝向跟随文字（含阵营翻转），保证棋子和自己的汉字完全对齐
        boolean textLike = textRotation || s.logic.pieceFollowsTextRotation();
        ps.pushPose();
        ps.translate(wx, y, wz);
        ps.mulPose(Axis.YP.rotationDegrees(ChessboardPieceGeometry.facingDegrees(s.facing, textLike)));
        if (textLike && s.logic.side(piece) != 0) ps.mulPose(Axis.YP.rotationDegrees(180));
        if (winTilt != 0) ps.mulPose(Axis.ZP.rotationDegrees(winTilt)); // 胜利左右歪动，绕底部中心
        if (flipDeg != 0) ps.mulPose(Axis.XP.rotationDegrees(flipDeg));
        if (s.logic.pieceFlipX(piece)) ps.mulPose(Axis.XP.rotationDegrees(180));
        float ry = s.logic.pieceYRotation(piece);
        if (ry != 0) ps.mulPose(Axis.YP.rotationDegrees(ry));
        ps.scale(sc, sc, sc);
        ps.translate(-cx, 0, -cz);
        if (spinDeg != 0) {
            // 骰子立方体在模型里占 y 0..4，此时它的几何中心落在 (0, 2, 0)，
            // 绕这一点转才是原地翻滚（绕原点会甩出去）
            ps.translate(0, DICE_CENTER_Y, 0);
            ps.mulPose(Axis.XP.rotationDegrees(spinDeg));
            ps.translate(0, -DICE_CENTER_Y, 0);
        }

        BlockStateModelSet set = models();
        if (set == null) {
            ps.popPose();
            return;
        }
        // 必须走 BLOCK 管线（core/block）：它和地形着色器同一条公式 Color * lightmap。
        // Sheets.cutoutBlockSheet() 那套走 core/entity，会再按法线算一次光照，
        // 叠加到我们已经烘好的面朝向明暗上就会把侧边压暗两次。
        // 棋子与汉字贴图都是全不透明/二值 alpha，走 cutout 即可（与区块里的 CUTOUT 图层对应）。
        RenderType sheet = RenderTypes.cutoutMovingBlock();
        cc.submitCustomGeometry(ps, sheet, (pose, vc) ->
                ChessboardPieceGeometry.emitQuads(vc, pose, region, s.blockPos, state,
                        lighter(), set, parts, quadInstance, skin));
        ps.popPose();
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    public static class ChessboardRenderState extends BlockEntityRenderState {
        public int[] pieces;
        public int selRow = -1, selCol = -1, rows, cols;
        public Direction facing = Direction.SOUTH;
        public BoardGameLogic logic;
        /** 各槽位皮肤（方块 ID，null = 未设） */
        public String[] skins;
        public float lift, unlift, moveT = 1f;
        public int unselRow = -1, unselCol = -1;
        public int fromRow = -1, fromCol = -1, toRow = -1, toCol = -1;
        public int flipRow = -1, flipCol = -1;
        public float flipT = 1f;
        public int[] winCells;
        public float winT = 1f;
        /** 飞行棋骰子翻滚进度 0..1（1 = 静止） */
        public float diceRollT = 1f;
        /** 各槽位解析好的皮肤贴图（null = 没设皮肤，用模型自带贴图） */
        public TextureAtlasSprite[] skinSprites;
        /** 超出渲染距离，本帧不画（见 Config#BOARD_RENDER_DISTANCE） */
        public boolean tooFar;
    }
}
