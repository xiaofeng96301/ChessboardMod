package com.chessboard.game;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 飞行棋：17×17 棋盘，四队各 4 架飞机，正中央一格是骰子。
 *
 * <p>布局：回路<b>沿地图边走</b>，但到四个角时<b>往里凹几格绕开机库</b>，再拐回边上去；
 * 机库就卡在凹口外面的四角。中间四条队色跑道（十字）从回路通向中央终点。
 *
 * <p><b>棋盘形状不要手改这里的图</b> —— 真正的布局在 {@code tools/flight_layout.txt}，
 * 改完跑 {@code python tools/gen_flight_chess.py} 会重新生成纹理并打印本类需要的常量。
 *
 * <pre>
 *   行\列 0  1  2  3  4  5  6  7  8  9 10 11 12 13 14
 *    0    ·  ·  ·  ·  ▓  ▓  ▓  ▓  ▓  ▓  ▓  ·  ·  ·  ·
 *    1    ·  G  G  ·  ▓  ·  ·  △  ·  ·  ▓  ·  R  R  ·
 *    2    ·  G  G  ·  ▓  ·  ·  △  ·  ·  ▓  ·  R  R  ·
 *    3    ·  ·  ·  ·  ▓  ·  ·  △  ·  ·  ▓  ·  ·  ·  ·
 *    4    ▓  ▓  ▓  ▓  ▓  ·  ·  △  ·  ·  ▓  ▓  ▓  ▓  ▓    ← 四角内凹绕开机库
 *    5    ▓  ·  ·  ·  ·  ·  ·  △  ·  ·  ·  ·  ·  ·  ▓
 *    6    ▓  ·  ·  ·  ·  ·  ◤  ▣  ◥  ·  ·  ·  ·  ·  ▓
 *    7    ▓  ◁  ◁  ◁  ◁  ◁  ▣  ◆  ▣  ▷  ▷  ▷  ▷  ▷  ▓    ← 中间十字 → 中央
 *    8    ▓  ·  ·  ·  ·  ·  ◣  ▣  ◢  ·  ·  ·  ·  ·  ▓
 *    9    ▓  ·  ·  ·  ·  ·  ·  ▽  ·  ·  ·  ·  ·  ·  ▓
 *   10    ▓  ▓  ▓  ▓  ▓  ·  ·  ▽  ·  ·  ▓  ▓  ▓  ▓  ▓
 *   11    ·  ·  ·  ·  ▓  ·  ·  ▽  ·  ·  ▓  ·  ·  ·  ·
 *   12    ·  Y  Y  ·  ▓  ·  ·  ▽  ·  ·  ▓  ·  B  B  ·
 *   13    ·  Y  Y  ·  ▓  ·  ·  ▽  ·  ·  ▓  ·  B  B  ·
 *   14    ·  ·  ·  ·  ▓  ▓  ▓  ▓  ▓  ▓  ▓  ·  ·  ·  ·
 *
 *   ▓ = 沿边回路(56格，四角内凹)   △▽◁▷ = 四条队色跑道   ▣ = 中央终点   ◆ = 骰子
 *   G/R/B/Y = 四角机库（在凹口外面），每个 2×2 摆 4 架
 * </pre>
 *
 * <p>跑道格子只是纹理好看，不参与逻辑。<b>不做真实飞行棋的规则判定</b> —— 和象棋一样
 * 点选、点走，棋子可以自由走到任意非骰子格（需求明确要求「随便移动」）。
 * 唯一的限制是中央骰子格：飞机走不上去，点它是掷骰子。
 *
 * <p>棋子值编码：
 * <ul>
 *   <li>{@code 0} 空格</li>
 *   <li>{@code 1..4} 队 0..3 的飞机（值 = {@link #TEAM_BASE} + 队号）</li>
 *   <li>{@code 10..15} 骰子，值 = {@link #DICE_BASE} + 朝上的点数(1..6)</li>
 * </ul>
 */
public class FlightChessLogic implements BoardGameLogic {

    /** 带框棋盘（格距 14/(15-1) 铺满 14 像素） */
    public static final FlightChessLogic INSTANCE = new FlightChessLogic(1f, 14f);

    private static final int SIZE = 15;

    /** 中央骰子格的数组下标 */
    public static final int DICE_CELL = (SIZE / 2) * SIZE + (SIZE / 2);

    /** 飞机值基数：值 = TEAM_BASE + 队号 */
    public static final int TEAM_BASE = 1;
    /** 骰子值基数：值 = DICE_BASE + 点数(1..6) */
    public static final int DICE_BASE = 10;
    /** 队伍数量 */
    public static final int TEAM_COUNT = 4;

    private final float offset;
    private final float span;

    public FlightChessLogic(float offset, float span) {
        this.offset = offset;
        this.span = span;
    }

    // ── 棋盘尺寸与布局 ──

    @Override public int rows() { return SIZE; }
    @Override public int cols() { return SIZE; }

    @Override
    public void initBoard(int[] p) {
        Arrays.fill(p, 0);
        // 机库在回路绕开的四角凹口外面。队色与参考图一致：左上绿、右上红、右下蓝、左下黄
        hangar(p, 1, 1, 3);    // 左上 绿
        hangar(p, 1, 12, 0);   // 右上 红
        hangar(p, 12, 12, 2);  // 右下 蓝
        hangar(p, 12, 1, 1);   // 左下 黄
        p[DICE_CELL] = pieceForFace(1); // 骰子初始朝上 1
    }

    /** 在 (row0, col0) 起的 2×2 机库里摆 4 架同队飞机 */
    private void hangar(int[] p, int row0, int col0, int team) {
        int v = TEAM_BASE + team;
        p[idx(row0, col0)] = v;
        p[idx(row0, col0 + 1)] = v;
        p[idx(row0 + 1, col0)] = v;
        p[idx(row0 + 1, col0 + 1)] = v;
    }

    // ── 棋子值工具 ──

    /** 是否骰子 */
    public static boolean isDice(int piece) { return piece >= DICE_BASE; }
    /** 是否飞机（非空且非骰子） */
    public static boolean isPlane(int piece) { return piece >= TEAM_BASE && piece < DICE_BASE; }
    /** 骰子朝上的点数 1..6（非骰子返回 1） */
    public static int faceOf(int piece) { return isDice(piece) ? piece - DICE_BASE : 1; }
    /** 点数 → 骰子棋子值 */
    public static int pieceForFace(int face) { return DICE_BASE + Math.clamp(face, 1, 6); }

    // ── 外观 ──

    /** 不写字，圆片上贴的是飞机图标（见 ChessboardPieceGeometry#charStateFor） */
    @Override public String pieceName(int piece) { return ""; }
    @Override public int textColor(int piece) { return 0; }

    @Override
    public int side(int piece) {
        return isPlane(piece) ? piece - TEAM_BASE : 0;
    }

    @Override public String codePrefix() { return "fx"; }

    /**
     * 15×15 下格距 1 像素（14 / (15-1)），棋子模型宽 5 单位，
     * 缩放必须 ≤0.2 才不会互相压住；取 0.18 留一点间隙。
     */
    @Override public float pieceScale() { return 0.18f; }
    @Override public float gridSpan() { return span; }
    @Override public float gridOffsetX() { return offset; }
    @Override public float gridOffsetZ() { return offset; }

    /**
     * 四色共用一个飞机图标，必须朝向一致 —— 不能像中国象棋那样按阵营翻 180°，
     * 否则三支队伍的飞机头会朝反方向。
     */
    @Override public boolean flipOverlayBySide() { return false; }

    // ── 点击 ──

    /** 中央骰子格不能落子 */
    @Override
    public boolean isBlocked(int row, int col) {
        return idx(row, col) == DICE_CELL;
    }

    @Override
    public ClickResult onClick(int[] pieces, int selRow, int selCol, int clickRow, int clickCol) {
        int cell = idx(clickRow, clickCol);
        // 点骰子 → 掷一次；面直接写进 pieces，走正常的同步/烘焙路径
        if (cell == DICE_CELL) {
            pieces[cell] = pieceForFace(ThreadLocalRandom.current().nextInt(1, 7));
            return new ClickResult.Roll();
        }
        return onClickMove(pieces, selRow, selCol, clickRow, clickCol);
    }
}
