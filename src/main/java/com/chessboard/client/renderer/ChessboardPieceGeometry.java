package com.chessboard.client.renderer;

import com.chessboard.ChessboardMod;
import com.chessboard.SkinData;
import com.chessboard.block.ChessChar;
import com.chessboard.block.ChessCharBlock;
import com.chessboard.block.FlightDiceBlock;
import com.chessboard.block.FlightPieceBlock;
import com.chessboard.block.FlightTeam;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.ChessLogic;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.FlightChessLogic;
import com.chessboard.game.GomokuLogic;
import com.chessboard.game.TicTacToeLogic;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent.SectionRenderingContext;
import net.neoforged.neoforge.client.model.ao.EnhancedBlockModelLighter;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 按四元数旋转 —— <b>全项目只用这一个入口，不要直接调 {@code PoseStack} 的那几个重载</b>。
     *
     * <p>26.1.2 和 26.3 在这件事上的方法名对不上，直接写哪个都会炸掉一边：
     * <ul>
     *   <li>{@code mulPose(Quaternionfc)} —— 26.1.2 有，<b>26.3 删了</b>。写它会编过但在 26.3 上
     *       运行期 {@code NoSuchMethodError}（2026-09-26 那次区块构建线程崩溃就是这个）；</li>
     *   <li>{@code rotate(Quaternionfc)} —— 26.3 的新名字，26.1.2 <b>根本没有</b>，写它 26.1.2 编不过；</li>
     *   <li>{@code mulPose(Matrix4fc)} / {@code rotateAround(...)} —— 两版都在，所以走这条。</li>
     * </ul>
     *
     * <p>{@code Axis.rotationDegrees(f)} 返回的是 {@code Quaternionf}，这里自己把它转成矩阵再乘。
     * {@code new Matrix4f().rotation(q)} 得到的就是 q 的旋转矩阵，{@code pose.mul(matrix)} 与
     * 直接 {@code pose.rotate(q)} 数学上完全等价，只是多一次矩阵构造（每次区块重建几百次，可忽略）。
     */
    public static void rotateBy(PoseStack ps, Quaternionfc q) {
        // 这里必须是 mulPose：两版都有的那个重载。别再把它「统一」成 rotateBy，那是自我递归
        ps.mulPose(new Matrix4f().rotation(q));
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

    /**
     * 棋子值 → 方块状态。
     *
     * <p>只决定<b>几何与属性</b>（花色、队色、骰子点数、暗棋背面），不决定贴图 ——
     * 贴图由动态皮肤按 {@link SkinData#slotFor} 的槽位单独覆盖。
     */
    public static BlockState stateFor(BoardGameLogic g, int piece) {
        return switch (g) {
            case ChineseChessLogic ccl -> (ChineseChessLogic.isHidden(piece)
                    ? ChessboardMod.CHINESE_PIECE_HIDDEN.get()
                    : ChessboardMod.CHESS_PIECE_MODEL.get()).defaultBlockState();
            case GomokuLogic gml -> (GomokuLogic.isGray(piece)
                    ? ChessboardMod.GOMOKU_PIECE_GRAY.get()
                    : (gml.side(piece) == 0
                            ? ChessboardMod.GOMOKU_PIECE_BLACK.get()
                            : ChessboardMod.GOMOKU_PIECE_WHITE.get())).defaultBlockState();
            case TicTacToeLogic ttt -> ChessboardMod.TICTACTOE_PIECE_MODEL.get().defaultBlockState();
            case ChessLogic cl -> {
                boolean isWhite = cl.side(piece) == 0;
                yield switch (ChessLogic.type(piece)) {
                    case ChessLogic.KING -> (isWhite ? ChessboardMod.CHESS_PIECE_KING_WHITE.get() : ChessboardMod.CHESS_PIECE_KING.get()).defaultBlockState();
                    case ChessLogic.QUEEN -> (isWhite ? ChessboardMod.CHESS_PIECE_QUEEN_WHITE.get() : ChessboardMod.CHESS_PIECE_QUEEN.get()).defaultBlockState();
                    case ChessLogic.BISHOP -> (isWhite ? ChessboardMod.CHESS_PIECE_BISHOP_WHITE.get() : ChessboardMod.CHESS_PIECE_BISHOP.get()).defaultBlockState();
                    case ChessLogic.KNIGHT -> (isWhite ? ChessboardMod.CHESS_PIECE_KNIGHT_WHITE.get() : ChessboardMod.CHESS_PIECE_KNIGHT.get()).defaultBlockState();
                    case ChessLogic.ROOK -> (isWhite ? ChessboardMod.CHESS_PIECE_ROOK_WHITE.get() : ChessboardMod.CHESS_PIECE_ROOK.get()).defaultBlockState();
                    default -> (isWhite ? ChessboardMod.CHESS_PIECE_PAWN_WHITE.get() : ChessboardMod.CHESS_PIECE_PAWN.get()).defaultBlockState();
                };
            }
            case FlightChessLogic fcl -> {
                // 中央格是骰子，其余格是四色飞机（队色由 TEAM 属性决定，皮肤按队单独覆盖）
                if (FlightChessLogic.isDice(piece)) {
                    yield ChessboardMod.FLIGHT_DICE.get().defaultBlockState()
                            .setValue(FlightDiceBlock.FACE, FlightChessLogic.faceOf(piece));
                }
                yield ChessboardMod.FLIGHT_PIECE.get().defaultBlockState()
                        .setValue(FlightPieceBlock.TEAM, FlightTeam.of(fcl.side(piece)));
            }
            default -> ChessboardMod.CHESS_PIECE_MODEL.get().defaultBlockState();
        };
    }

    /**
     * 棋子上的汉字方块状态。非中国象棋、或暗棋（背面朝上）返回 null —— 不渲染汉字。
     * 汉字已做成贴图，可与棋子一起烘焙进区块几何。
     */
    public static BlockState charStateFor(BoardGameLogic g, int piece) {
        // 飞行棋：飞机圆片上叠一层飞机图标（四队共用同一张图），骰子不叠
        if (g instanceof FlightChessLogic) {
            return FlightChessLogic.isPlane(piece)
                    ? ChessboardMod.FLIGHT_ICON.get().defaultBlockState()
                    : null;
        }
        if (!(g instanceof ChineseChessLogic)) return null;
        if (ChineseChessLogic.isHidden(piece)) return null;
        ChessChar c = ChessChar.of(ChineseChessLogic.type(piece), g.side(piece));
        return c == null ? null
                : ChessboardMod.CHINESE_PIECE_CHAR.get().defaultBlockState().setValue(ChessCharBlock.CHAR, c);
    }

    // ── 动态皮肤 ──

    /** 边框皮肤相对方块外表面的外扩量（模型单位，1 单位 = 1/16 格） */
    private static final float SKIN_OFFSET = 0.01f;

    /**
     * 棋盘皮肤处理<b>全部六个面</b>（两个列表都遍历一遍用的方向集）。
     *
     * <p>棋盘模型分两层，两层的处理方式不同：
     * <ul>
     *   <li>底板（含顶面）—— 贴图换成皮肤贴图，六面全换；</li>
     *   <li>图案层（y=1.001 那层，只有 up 面）—— 保留图案，按需染色。</li>
     * </ul>
     *
     * <p><b>底板顶面是必须换的</b>：棋盘图案贴图其实是「黑色格子线 + 透明底」，
     * 顶面真正的底色就是底板贴图，图案只负责画线。层与层之间靠沿法线外扩
     * {@link #SKIN_OFFSET} 分层：底板顶面被推到 1.0099、图案被推到 1.011，
     * 图案用 cutout 画在上面，格子线的透明处直接透出皮肤材质。
     */
    private static final Direction[] BOARD_SKIN_FACES = Direction.values();

    /**
     * 皮肤方块状态 → 代表贴图（该方块的粒子贴图，也就是模型的主贴图）。
     * {@code null}（没设皮肤）或模型还没烘出来时返回 null。
     */
    public static TextureAtlasSprite skinSprite(BlockState skin, BlockStateModelSet models) {
        if (skin == null || models == null) return null;
        try {
            return models.getParticleMaterial(skin).sprite();
        } catch (Exception e) {
            // 以前这里静默吞掉，结果「设了皮肤但一点没变」完全无从查起。模型没烘出来时会走到这
            ChessboardMod.LOGGER.warn("[chessboard] 取不到皮肤贴图：state={} ({})", skin, e.toString());
            return null;
        }
    }

    /**
     * 染色值 —— 让 {@code from} 这张贴图的平均色变成 {@code to} 那张的平均色。
     *
     * <p><b>必须是「逐通道取比值」</b>，不能直接拿 {@code to} 的平均色去乘。
     * 顶点色是乘法：拿目标色直接乘，只能把整块压暗或偏色，<b>改不掉源贴图自己的色相</b> ——
     * 棋盘图案是饱和的金黄色，乘上砖块那种偏灰的暖色，结果还是金的，看起来就是「没染上」。
     * 逐通道取比值之后，图案的平均色被精确搬到了目标色上，而图案自己的明暗对比
     * （黑格子线 vs 亮格面）原样保留。
     *
     * <p>返回 0 表示不用染。通道比值只能 ≤ 1（8 位顶点色没法提亮），所以目标色比源色
     * 更亮的通道提不上去 —— 纯白/纯蓝这类「源贴图里几乎没有的通道」染不出来，这是乘法染色的固有上限。
     */
    public static int tintToMatch(TextureAtlasSprite from, TextureAtlasSprite to) {
        if (from == null || to == null || from == to) return 0;
        int[] a = averageRgb(from);
        int[] b = averageRgb(to);
        if (a == null || b == null) return 0;
        int tr = ratio(b[0], a[0]), tg = ratio(b[1], a[1]), tb = ratio(b[2], a[2]);
        if (tr == 255 && tg == 255 && tb == 255) return 0; // 本来就同色：染了等于没染
        return ARGB.color(255, tr, tg, tb);
    }

    /** 目标通道 / 源通道，钳到 8 位；源通道太接近 0（会除爆）时退化成「不染色」 */
    private static int ratio(int target, int source) {
        if (source < 8) return 255;
        return Math.min(255, target * 255 / source);
    }

    /** 贴图平均色（跳过透明像素）；读不出像素返回 null */
    private static int[] averageRgb(TextureAtlasSprite sprite) {
        try {
            NativeImage img = sprite.contents().getOriginalImage();
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return null;
            long r = 0, g = 0, b = 0, n = 0;
            for (int c : img.getPixels()) { // getPixels() 已经是 ARGB 序
                if (ARGB.alpha(c) < 32) continue;
                r += ARGB.red(c); g += ARGB.green(c); b += ARGB.blue(c); n++;
            }
            if (n == 0) return null;
            return new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
        } catch (Exception e) {
            ChessboardMod.LOGGER.warn("[chessboard] 算不出贴图平均色：{}", e.toString());
            return null;
        }
    }

    /** 是否本模组自己的贴图（棋盘图案、汉字、飞机图标、骰子点数）—— 皮肤不覆盖这些 */
    private static boolean isOwnTexture(BakedQuad q) {
        Identifier id = q.materialInfo().sprite().contents().name();
        return ChessboardMod.MODID.equals(id.getNamespace());
    }

    /**
     * 把四边形上非本模组的贴图换成皮肤贴图。
     *
     * <p><b>只换 sprite 是不够的</b>：UV 在烘模型时就已经按 {@code sprite.getU()/getV()}
     * 算成了<b>图集绝对坐标</b>（见 {@code FaceBakery#bakeVertex}），只改 sprite 的话
     * 采样点还停在原来那张贴图上，表现就是「设了皮肤但一点没变」。所以这里要同时把 4 个顶点的
     * UV 从旧 sprite 在图集里的范围，按图内比例重映射到新 sprite 的范围。
     *
     * <p>两张贴图尺寸不同也没问题 —— 走的是比例，不是像素。
     */
    private static BakedQuad skinQuad(BakedQuad q, TextureAtlasSprite skin) {
        BakedQuad.MaterialInfo mi = q.materialInfo();
        TextureAtlasSprite old = mi.sprite();
        if (skin == null || old == skin || isOwnTexture(q)) return q;

        float ou0 = old.getU0(), ou1 = old.getU1(), ov0 = old.getV0(), ov1 = old.getV1();
        float nu0 = skin.getU0(), nu1 = skin.getU1(), nv0 = skin.getV0(), nv1 = skin.getV1();
        if (ou1 == ou0 || ov1 == ov0) return q;

        long[] uv = new long[4];
        for (int i = 0; i < 4; i++) {
            long packed = q.packedUV(i);
            // 旧图集坐标 → 图内比例 0..1 → 新图集坐标
            float lu = (UVPair.unpackU(packed) - ou0) / (ou1 - ou0);
            float lv = (UVPair.unpackV(packed) - ov0) / (ov1 - ov0);
            uv[i] = UVPair.pack(nu0 + lu * (nu1 - nu0), nv0 + lv * (nv1 - nv0));
        }
        BakedQuad.MaterialInfo skinInfo = materialInfoWithSprite(mi, skin);
        if (skinInfo == null) return q; // 反射没成功：少一层皮肤，好过崩游戏
        return new BakedQuad(q.position0(), q.position1(), q.position2(), q.position3(),
                uv[0], uv[1], uv[2], uv[3], q.direction(), skinInfo);
    }

    // ── 跨版本的 MaterialInfo 重建（只能反射，见下） ──

    /** 已解析的取值方法（按方法名缓存；懒加载，只在真上皮肤时用） */
    private static final Map<String, Method> MI_GETTERS = new ConcurrentHashMap<>();
    /** MaterialInfo 的构造：按参数个数分版本缓存 */
    private static final Map<Integer, Constructor<?>> MI_CTORS = new ConcurrentHashMap<>();
    private static final Set<Integer> MI_CTORS_BAD = ConcurrentHashMap.newKeySet();

    /**
     * 复制一个 {@code MaterialInfo}，只把贴图换成皮肤。
     *
     * <p><b>这里必须走反射</b>：两版的组件根本不是一套东西，直接构造没法同时兼容 ——
     * <pre>
     * 26.1.2: sprite, layer, itemRenderType, tintIndex, shade, lightEmission, ambientOcclusion
     * 26.3  : sprite, layer, itemRenderType, itemGlintRenderType, itemGlintSpecialRenderType,
     *         tintIndex, shadeDirectionOverride, lightEmission
     * </pre>
     * 而且 {@code ambientOcclusion()} / {@code shade()} 这两个取值方法 26.3 已经删了
     * （折进新的组件设计），所以取值也得按名字反射找 —— 编译期写死任何一个版本的方法名，
     * 另一个版本就是运行期 {@code NoSuchMethodError}（2026-09-26 的崩溃就是 {@code shade()}）。
     *
     * <p>按「构造参数个数」分支：7 = 26.1.2，8 = 26.3。取不到的组件用默认值
     * （shade / AO 取 true，lightEmission 取 0，26.3 的 glint 相关取 null —— 我们的方块
     * 不发光、不附魔光效，这几个值不影响观感）。
     *
     * <p>任何一步失败都返回 {@code null}，由调用方退回原四边形：<b>宁可这层皮肤不生效，
     * 也不能崩</b>。解析结果全部缓存，反射调用只在「设了皮肤」的区块重建里发生。
     */
    private static BakedQuad.MaterialInfo materialInfoWithSprite(BakedQuad.MaterialInfo mi, TextureAtlasSprite skin) {
        Constructor<?> ctor = materialInfoCtor();
        if (ctor == null) return null;
        try {
            Class<?>[] pt = ctor.getParameterTypes();
            Object[] args;
            if (pt.length == 7) {
                args = new Object[]{skin, get(mi, "layer"), get(mi, "itemRenderType"),
                        get(mi, "tintIndex"), getOr(mi, "shade", Boolean.TRUE),
                        getOr(mi, "lightEmission", 0), getOr(mi, "ambientOcclusion", Boolean.TRUE)};
            } else if (pt.length == 8) {
                args = new Object[]{skin, get(mi, "layer"), get(mi, "itemRenderType"),
                        get(mi, "itemGlintRenderType"), get(mi, "itemGlintSpecialRenderType"),
                        get(mi, "tintIndex"), get(mi, "shadeDirectionOverride"),
                        getOr(mi, "lightEmission", 0)};
            } else {
                return null;
            }
            return (BakedQuad.MaterialInfo) ctor.newInstance(args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** MaterialInfo 的构造：两版参数个数不同，按个数挑一个能用的 */
    private static Constructor<?> materialInfoCtor() {
        for (int n : new int[]{7, 8}) {
            Constructor<?> c = MI_CTORS.get(n);
            if (c != null) return c;
            if (MI_CTORS_BAD.contains(n)) continue;
            try {
                Constructor<?> found = findCtorByArity(BakedQuad.MaterialInfo.class, n);
                if (found != null) {
                    found.setAccessible(true);
                    MI_CTORS.put(n, found);
                    return found;
                }
                MI_CTORS_BAD.add(n);
            } catch (RuntimeException e) {
                MI_CTORS_BAD.add(n);
            }
        }
        return null;
    }

    private static Constructor<?> findCtorByArity(Class<?> cls, int arity) {
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (c.getParameterCount() == arity) return c;
        }
        return null;
    }

    /** 反射取组件值；取不到抛异常（由调用方兜底成 null） */
    private static Object get(Object mi, String name) throws ReflectiveOperationException {
        Method m = MI_GETTERS.get(name);
        if (m == null) {
            m = mi.getClass().getMethod(name);
            MI_GETTERS.put(name, m);
        }
        return m.invoke(mi);
    }

    /** 反射取组件值，取不到就用默认（该组件在当前版本不存在时属于正常情况） */
    private static Object getOr(Object mi, String name, Object fallback) {
        try {
            return get(mi, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }

    /**
     * 这个四边形要不要走环境光遮蔽（平滑光照）—— 决定用光照器的哪个准备方法。
     *
     * <p>{@code ambientOcclusion()} 26.3 已删，所以同样只能反射取；取不到按 {@code true}，
     * 因为方块模型默认就是 AO，我们自己的模型也是。
     */
    private static boolean ambientOcclusion(BakedQuad.MaterialInfo mi) {
        Object v = getOr(mi, "ambientOcclusion", Boolean.TRUE);
        return v instanceof Boolean b ? b : Boolean.TRUE;
    }

    /**
     * 给棋盘本体叠一层皮肤。
     *
     * <p>做法：把棋盘模型的四边形重新发射一遍，沿各自的面法线外扩 {@link #SKIN_OFFSET}
     * （约 1/1600 格，肉眼看不出）正好盖住原版那层：
     * <ul>
     *   <li>边框 / 底板（非本模组的贴图）→ <b>换成皮肤贴图</b>，材质整个变掉。这里包含
     *       底板的<b>顶面</b> —— 那才是棋盘顶面真正的底色；</li>
     *   <li>棋盘图案层（本模组贴图）→ 保留图案（黑色格子线），按需染色。</li>
     * </ul>
     *
     * <p>棋盘模型是两层：底板（含六面）+ 贴在 y=1.001 的图案层。图案贴图是
     * 「黑色线 + 透明底」（见 {@link #BOARD_SKIN_FACES} 的说明），所以换掉底板顶面之后，
     * 格子线依然以不透明像素画在最上面，透明处直接透出皮肤材质 —— 效果就是「一块用该方块
     * 做的棋盘，上面印着格子」。
     *
     * <p>这样<b>不用碰 RenderShape、也不用改方块状态</b>：原版棋盘照常渲染，破坏进度也还在；
     * 没设皮肤的棋盘更是一个顶点都不会多画。
     */
    public static void emitBoardSkin(EmitContext ec, BlockState boardState, TextureAtlasSprite skin) {
        if (skin == null) return;
        ec.lighter.reset(); // 必须：把 cache 注入 AO 计算器，否则 prepareQuad* 会 NPE
        ec.parts.clear();
        ec.models.get(boardState).collectParts(ec.region, ec.boardPos, boardState,
                RandomSource.create(MODEL_SEED), ec.parts);

        // 两个列表都要走：立方体模型（SingleVariant）实测把**全部**四边形都放在
        // getQuads(null) 里，getQuads(d) 恒为空 —— 只遍历后者会一个面都发不出来。
        int emitted = 0;
        ec.nullDir = 0;
        for (BlockStateModelPart part : ec.parts) {
            for (Direction d : BOARD_SKIN_FACES) {
                emitted += emitBoardQuads(ec, part.getQuads(d), d, boardState, skin);
            }
            emitted += emitBoardQuads(ec, part.getQuads(null), null, boardState, skin);
        }
        // 只在出问题时说话。正常情况一个字都不打 —— 每个区块重建都会走到这里，平时刷屏没用。
        // 这两个分支正是「设了棋盘皮肤却看不出变化」的全部已知成因：
        if (emitted == 0) {
            ChessboardMod.LOGGER.warn("[chessboard] 棋盘皮肤一个面都没发射 @{} 贴图={} —— "
                    + "这块棋盘在界面上会看不出任何变化", ec.boardPos, skin.contents().name());
        } else if (ec.nullDir > 0) {
            ChessboardMod.LOGGER.debug("[chessboard] 棋盘皮肤有 {} 个面没有方向 @{}，"
                    + "只能原地重发（会和平面的原版重合）", ec.nullDir, ec.boardPos);
        }
    }

    /**
     * 发射一批皮肤四边形：沿<b>每个四边形自己的面法线</b>外扩 {@link #SKIN_OFFSET}
     * 压住原版那一层。
     *
     * <p>外扩量必须按四边形自己的方向算，<b>不能按遍历到的方向</b> —— 因为四边形可能来自
     * 无方向列表。原地重发会和原版完全重合、深度值相等，谁后画谁赢，表现就是「设了皮肤但
     * 一点没变」。
     *
     * @param lightFace 这批四边形是在哪个方向的列表里找到的（无方向列表传 null）
     * @return 实际发射的四边形数
     */
    private static int emitBoardQuads(EmitContext ec, List<BakedQuad> quads, Direction lightFace,
                                      BlockState boardState, TextureAtlasSprite skin) {
        if (quads.isEmpty()) return 0;
        int lightCoords = lightFace != null
                ? ec.lighter.getLightCoords(boardState, ec.region, ec.boardPos.relative(lightFace))
                : -1;
        PoseStack ps = ec.ps;
        int n = 0;
        for (BakedQuad q : quads) {
            Direction d = q.direction(); // FaceBakery 兜底 UP，正常不会是 null
            boolean own = isOwnTexture(q);
            // 注意：**不能跳过底板的顶面**。棋盘图案那几张贴图是「黑色格子线 + 透明底」
            // （gomoku_board.png 有 57% 是全透明像素），顶面真正的底色就是底板贴图本身，
            // 图案只是画在上面的线。跳过它 → 顶面永远是木头色，格子线底下透出原版木纹。
            // 真碰上没有方向的四边形就只能原地发（会和平面的原版重合），至少不炸
            float dx = 0, dy = 0, dz = 0;
            if (d == null) ec.nullDir++;
            else {
                dx = d.getStepX() * SKIN_OFFSET / 16f;
                dy = d.getStepY() * SKIN_OFFSET / 16f;
                dz = d.getStepZ() * SKIN_OFFSET / 16f;
            }
            // 自己的贴图（棋盘图案）不换 sprite，只逐通道染色成皮肤的颜色；其余换成皮肤贴图
            int tint = own ? tintToMatch(q.materialInfo().sprite(), skin) : 0;
            ps.pushPose();
            ps.translate(ec.ox + dx, ec.oy + dy, ec.oz + dz);
            putQuad(ec.ctx.getOrCreateChunkBuffer(q.materialInfo().layer()),
                    ec.region, ec.boardPos, boardState, ec.lighter, q, lightCoords,
                    ec.qi, ps.last(), own ? null : skin, tint);
            ps.popPose();
            n++;
        }
        return n;
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
        /** 诊断用：皮肤发射时遇到多少个没有面方向的四边形（正常应为 0） */
        int nullDir;
        /**
         * 这块棋盘已经为<b>棋子</b>发了多少个四边形（棋盘本体不算）。
         *
         * <p>只给 {@code ChessboardSectionGeometry} 的预算降级用：一个区块里棋盘太多时，
         * 几何量会超过原版顶点缓冲的容量上限（26.3 是硬崩）。按四边形数计最省事 ——
         * 发之前就知道 {@code quads.size()}，不用改 {@code putQuad}。
         */
        int pieceQuads;

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
                                 BoardGameLogic g, Direction facing, int piece, boolean textRotation,
                                 TextureAtlasSprite skin) {
        float cx = g.pieceCenterX() / 16f, cz = g.pieceCenterZ() / 16f;
        float sc = g.pieceScale();
        PoseStack ps = ec.ps;

        // 圆片类棋子的朝向跟随文字（含阵营翻转），保证棋子和自己的汉字完全对齐
        boolean textLike = textRotation || g.pieceFollowsTextRotation();
        ps.pushPose();
        ps.translate(ec.ox + wx, ec.oy + g.pieceHeight(), ec.oz + wz);
        rotateBy(ps, Axis.YP.rotationDegrees(facingDegrees(facing, textLike)));
        if (textLike && g.flipOverlayBySide() && g.side(piece) != 0) rotateBy(ps, Axis.YP.rotationDegrees(180));
        if (g.pieceFlipX(piece)) rotateBy(ps, Axis.XP.rotationDegrees(180));
        float ry = g.pieceYRotation(piece);
        if (ry != 0) rotateBy(ps, Axis.YP.rotationDegrees(ry));
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
                ec.pieceQuads += quads.size(); // 预算降级用，见 EmitContext#pieceQuads
                int lightCoords = ec.lighter.getLightCoords(state, ec.region, ec.boardPos.relative(d));
                for (BakedQuad q : quads) {
                    putQuad(ec.ctx.getOrCreateChunkBuffer(q.materialInfo().layer()),
                            ec.region, ec.boardPos, state, ec.lighter, q, lightCoords, ec.qi, pose, skin, 0);
                }
            }
            // 无方向（不参与面剔除）的四边形
            List<BakedQuad> noDir = part.getQuads(null);
            ec.pieceQuads += noDir.size();
            for (BakedQuad q : noDir) {
                putQuad(ec.ctx.getOrCreateChunkBuffer(q.materialInfo().layer()),
                        ec.region, ec.boardPos, state, ec.lighter, q, -1, ec.qi, pose, skin, 0);
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
                                 QuadInstance qi, TextureAtlasSprite skin) {
        lighter.reset(); // 必须：把 cache 注入 AO 计算器，否则 prepareQuad* 会 NPE
        parts.clear();
        models.get(state).collectParts(region, boardPos, state, RandomSource.create(MODEL_SEED), parts);
        for (BlockStateModelPart part : parts) {
            for (Direction d : DIRECTIONS) {
                List<BakedQuad> quads = part.getQuads(d);
                if (quads.isEmpty()) continue;
                int lightCoords = lighter.getLightCoords(state, region, boardPos.relative(d));
                for (BakedQuad q : quads) {
                    putQuad(vc, region, boardPos, state, lighter, q, lightCoords, qi, pose, skin, 0);
                }
            }
            for (BakedQuad q : part.getQuads(null)) {
                putQuad(vc, region, boardPos, state, lighter, q, -1, qi, pose, skin, 0);
            }
        }
    }

    /**
     * 交给原版光照器算好逐顶点光照与面朝向明暗后写入缓冲。
     *
     * @param skin 非 null 时把贴图换成它
     * @param tint 非 0 时在光照器写好的顶点色上再乘一层染色（棋盘图案用）
     */
    private static void putQuad(VertexConsumer vc, BlockAndTintGetter region, BlockPos boardPos,
                                BlockState state, BlockModelLighter lighter, BakedQuad q,
                                int lightCoords, QuadInstance qi, PoseStack.Pose pose,
                                TextureAtlasSprite skin, int tint) {
        BakedQuad quad = skinQuad(q, skin);
        if (ambientOcclusion(quad.materialInfo())) {
            lighter.prepareQuadAmbientOcclusion(region, state, boardPos, quad, qi);
        } else {
            lighter.prepareQuadFlat(region, state, boardPos, lightCoords, quad, qi);
        }
        // 染色必须放在光照器之后 —— 它会覆盖 4 个顶点的颜色
        if (tint != 0) qi.multiplyColor(tint);
        qi.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        vc.putBakedQuad(pose, quad, qi);
    }
}
