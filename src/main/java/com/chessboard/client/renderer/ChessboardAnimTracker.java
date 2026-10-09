package com.chessboard.client.renderer;

import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.api.BoardGameLogic;
import com.chessboard.api.DiceBoard;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 客户端棋盘动画状态中心（仅主线程访问）。
 *
 * <p>区块几何是<b>静态</b>的 —— {@code AddSectionGeometryEvent} 只在区块重建时重新生成几何，
 * 所以逐帧动画的棋子必须从烘焙几何里排除，改由方块实体渲染器逐帧绘制。
 * 本类维护「哪些格子当前在动画中」，是两条渲染路径唯一的状态来源：
 * <ul>
 *   <li>区块几何（worker 线程）：跳过 {@link #exclusionSnapshot} 标记为 true 的格子</li>
 *   <li>方块实体渲染器（主线程）：只为 {@link #isDynamic} 为 true 的格子画模型，文字始终由它画</li>
 * </ul>
 *
 * <p><b>维护须知：以后新增任何逐帧动画，都必须把受影响的格子写进
 * {@link BoardAnim#dynamicUntil}（在 {@link #recompute} 里加一行），
 * 否则该棋子会被静默烘焙成静止的，动画消失且画面出现重影。</b>
 */
public final class ChessboardAnimTracker {

    public static final ChessboardAnimTracker INSTANCE = new ChessboardAnimTracker();

    /** 五子棋连五胜利动画总时长（毫秒） */
    public static final int WIN_ANIM_MS = 1100;

    private final Map<BlockPos, BoardAnim> boards = new HashMap<>();

    private ChessboardAnimTracker() {}

    /** 单块棋盘的动画状态（包内可见，供 {@link ChessboardRenderer} 读取） */
    static final class BoardAnim {
        BlockPos pos;
        BoardGameLogic logic;
        int[] prevPieces;
        int prevSelRow = -1, prevSelCol = -1;
        int unselRow = -1, unselCol = -1;
        int fromRow = -1, fromCol = -1, toRow = -1, toCol = -1;
        /** 被搬走的是「值等于它」的那一颗（0 = 没有）。堆叠里拿走的未必是第 0 颗，渲染器要靠它挑出飞行中的那一颗 */
        int movePiece;
        int flipRow = -1, flipCol = -1;
        int[] winCells;
        long selMs, unselMs, moveMs, flipMs, winMs;
        /** 飞行棋骰子上一次被掷出的时刻（0 = 从未掷过） */
        long rollMs;
        /** 每格期望由渲染器绘制：0 = 交给几何烘焙，非 0 = 渲染器画。只在数据变化时重算（见 {@link #recompute}） */
        long[] dynamicUntil;
        /**
         * 最近两代「几何快照的排除集」。
         *
         * <p>网格是异步重建的，我们观测不到它何时换成新的，但渲染器必须画的恰恰是
         * <b>当前显示的那份网格所排除的格子</b>。取最近两代快照的并集即可给出严格保证：
         * 显示的网格要么是当前代要么是上一代，并集必然覆盖它 → 不会出现空档。
         * 多画的只有「本代刚释放」的格子，而它们此刻动画早已播完、处于静止姿态，
         * 与烘焙的完全一致，看不出重影。
         */
        boolean[] curExcluded;
        boolean[] prevExcluded;
    }

    // ── 状态更新（主线程，由方块实体客户端数据钩子驱动）──

    /**
     * 棋盘数据变化时更新动画状态。首次接触只记录基线，不触发动画。
     * 调用方随后需要 {@link #markDirty} 一次。
     */
    public void update(ChessboardBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        BoardGameLogic g = be.gameLogic();
        int total = g.rows() * g.cols();
        int[] pieces = be.pieces();
        int selRow = be.selRow(), selCol = be.selCol();
        long now = System.currentTimeMillis();

        BoardAnim a = boards.get(pos);
        if (a == null || a.prevPieces == null || a.prevPieces.length != total) {
            a = new BoardAnim();
            a.pos = pos;
            a.prevPieces = pieces.clone();
            a.dynamicUntil = new long[total];
            a.prevSelRow = selRow;
            a.prevSelCol = selCol;
            a.logic = g;
            boards.put(pos, a);
            return;
        }
        a.logic = g;

        boolean changed = !Arrays.equals(pieces, a.prevPieces);
        if (changed) {
            a.fromRow = a.fromCol = a.toRow = a.toCol = -1;
            // 源格 / 落点 / 被搬走的那一颗（按颗数比，认法见 BoardGameLogic#detectMove）。
            // 源格非空也要播 —— 从叠里拿走一颗就是这样：留下的那几颗照旧烘进几何，
            // 渲染器只画落点那一格、并把飞行中的那颗从落点里挖掉，两边不重不漏。
            BoardGameLogic.Move mv = BoardGameLogic.detectMove(g, a.prevPieces, pieces);
            a.movePiece = mv.piece();
            int cols = g.cols();
            a.fromRow = mv.isEmpty() ? -1 : mv.fromCell() / cols;
            a.fromCol = mv.isEmpty() ? -1 : mv.fromCell() % cols;
            a.toRow = mv.isEmpty() ? -1 : mv.toCell() / cols;
            a.toCol = mv.isEmpty() ? -1 : mv.toCell() % cols;
            // 暗棋翻面：由棋类自己认（BoardGameLogic#isFlipTransition），这里不再 instanceof
            a.flipRow = a.flipCol = -1;
            for (int i = 0; i < total; i++) {
                if (g.isFlipTransition(a.prevPieces[i], pieces[i])) {
                    a.flipRow = i / g.cols(); a.flipCol = i % g.cols(); a.flipMs = now;
                    break;
                }
            }
            // 骰子点数变了 → 播翻滚动画（哪个格是骰子、动画多长都问 DiceBoard）
            if (g instanceof DiceBoard db
                    && a.prevPieces[db.diceCell()] != pieces[db.diceCell()]) {
                a.rollMs = now;
            }
            a.moveMs = now;
            System.arraycopy(pieces, 0, a.prevPieces, 0, total);
        }

        if (a.prevSelRow != selRow || a.prevSelCol != selCol) {
            // 同一次变化里棋子没动过 → 是单纯的取消选中，记录回落动画的起点
            if (!changed && a.prevSelRow >= 0) {
                a.unselRow = a.prevSelRow; a.unselCol = a.prevSelCol; a.unselMs = now;
            }
            a.selMs = now;
            a.prevSelRow = selRow; a.prevSelCol = selCol;
        }

        // 胜利（或将军提示）要表演的格子；只在数据变化时算一次，而非每帧
        int[] win = g.winCells(pieces);
        // 开关是棋盘自己的属性（五子棋默认关、其余默认开），跟着方块实体同步过来
        if (win != null && !be.winFx()) win = null;
        if (!Arrays.equals(win, a.winCells)) {
            a.winCells = win != null ? win.clone() : null;
            if (win != null) a.winMs = now;
        }

        recompute(a, now);
    }

    /** 依据当前动画状态重算每格截止时刻 */
    private static void recompute(BoardAnim a, long now) {
        BoardGameLogic g = a.logic;
        int cols = g.cols();
        long[] d = a.dynamicUntil;
        Arrays.fill(d, 0L);

        // 选中抬起：选中期间一直动态
        if (a.prevSelRow >= 0) {
            int c = a.prevSelRow * cols + a.prevSelCol;
            if (c >= 0 && c < d.length) d[c] = Long.MAX_VALUE;
        }
        // 取消选中回落
        mark(d, a.unselRow, a.unselCol, cols, a.unselMs + g.pieceLiftMs(), now);
        // 走子/吃子：只动态终点。起点（含「从叠里拿走一颗、还剩几颗」的情况）不必动态 ——
        // 数据已经变了，重建后的几何里就是剩下的那几颗，静止姿态、不需要逐帧画。
        mark(d, a.toRow, a.toCol, cols, a.moveMs + g.pieceMoveMs(), now);
        // 暗棋翻面
        mark(d, a.flipRow, a.flipCol, cols, a.flipMs + g.pieceFlipMs(), now);
        // 骰子翻滚：骰子格交还给渲染器逐帧画
        if (g instanceof DiceBoard db) {
            mark(d, db.diceCell() / cols, db.diceCell() % cols, cols, a.rollMs + db.diceRollMs(), now);
        }
        // 连五胜利：整条连线一起动
        if (a.winCells != null && a.winMs + WIN_ANIM_MS > now) {
            long until = a.winMs + WIN_ANIM_MS;
            for (int c : a.winCells) {
                if (c >= 0 && c < d.length) d[c] = Math.max(d[c], until);
            }
        }
    }

    private static void mark(long[] d, int row, int col, int cols, long until, long now) {
        if (row < 0 || until <= now) return;
        int c = row * cols + col;
        if (c >= 0 && c < d.length) d[c] = Math.max(d[c], until);
    }

    /** 渲染器兜底：确保条目存在（只建基线，不触发动画、无需重建几何） */
    public void ensureBaseline(ChessboardBlockEntity be) {
        if (boards.containsKey(be.getBlockPos())) return;
        update(be); // 首次调用只记录基线后返回
    }

    // ── 查询 ──

    /**
     * 方块实体渲染器用：该格是否由渲染器绘制 —— 取最近两代几何快照的并集（理由见
     * {@link BoardAnim#curExcluded}）。
     */
    public boolean isDynamic(BlockPos pos, int cell) {
        BoardAnim a = boards.get(pos);
        if (a == null || cell < 0) return false;
        return in(a.curExcluded, cell) || in(a.prevExcluded, cell);
    }

    private static boolean in(boolean[] arr, int cell) {
        return arr != null && cell < arr.length && arr[cell];
    }

    /**
     * 区块几何用：快照出「不应烘焙」的格子，并把代数往前推一位
     * （当前代 → 上一代，最新状态 → 当前代）。只有主线程调用。
     */
    public boolean[] exclusionSnapshot(BlockPos pos, int total) {
        BoardAnim a = boards.get(pos);
        if (a == null || a.dynamicUntil == null) return new boolean[total];
        if (a.prevExcluded == null || a.prevExcluded.length != total) a.prevExcluded = new boolean[total];
        if (a.curExcluded == null || a.curExcluded.length != total) a.curExcluded = new boolean[total];
        boolean[] tmp = a.prevExcluded;
        a.prevExcluded = a.curExcluded;
        a.curExcluded = tmp;
        for (int i = 0; i < total; i++) {
            a.curExcluded[i] = a.dynamicUntil[i] != 0;
        }
        return a.curExcluded.clone(); // 副本交给 worker 线程只读
    }

    BoardAnim get(BlockPos pos) { return boards.get(pos); }

    // ── 区块重建 ──

    /**
     * 标记棋盘所在区块需要重建几何。
     *
     * <p>必须走 {@link net.minecraft.client.multiplayer.ClientLevel#setSectionDirtyWithNeighbors}，
     * <b>不能</b>用 {@code LevelRenderer.setSectionDirty} —— 后者在 26.2 被删除了，
     * 调用会在收到棋盘数据包时抛 {@code NoSuchMethodError} 直接崩客户端。
     *
     * <p>而这里用的入口在 26.1.2 和 26.2 都存在，且两版语义一致
     * （26.1.2 上它同样转发给 {@code LevelRenderer.setSectionDirtyWithNeighbors}），
     * 所以同一个 jar 可以同时跑两个版本。顺带把邻接区块也标脏 —— 棋子可能跨区块边界。
     */
    public void markDirty(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        level.setSectionDirtyWithNeighbors(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /**
     * 每客户端 tick（20Hz）：只负责清理已消失棋盘的条目。
     *
     * <p><b>这里不做任何「动画到期后交还几何」的动作</b> —— 交还只发生在下一次数据变化时
     * （{@link #update} → {@link #recompute} 重算）。这样「几何排除集」和「渲染器绘制集」
     * 永远一起变化，不存在「几何还没烘回去、渲染器却已经停手」的空档。
     * 之前靠计时器 / 重建代数握手来交还，都会因为重建请求被吞掉而让棋子消失。
     */
    public void tick() {
        if (boards.isEmpty()) return;
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            boards.clear();
            return;
        }
        Iterator<Map.Entry<BlockPos, BoardAnim>> it = boards.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BoardAnim> e = it.next();
            // 方块实体已消失（区块卸载/棋盘被拆）→ 清理
            if (!(level.getBlockEntity(e.getKey()) instanceof ChessboardBlockEntity)) {
                it.remove();
            }
        }
    }
}
