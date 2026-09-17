package com.chessboard.client.renderer;

import com.chessboard.blockentity.ChessboardBlockEntity;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.ChineseChessLogic;
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
    public static final int WIN_ANIM_MS = 1500;
    /** 动画结束后方块实体渲染器继续绘制的宽限期：等区块网格重建完再交还，避免出现空隙 */
    public static final long GRACE_MS = 300;

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
        int flipRow = -1, flipCol = -1;
        int[] winCells;
        long selMs, unselMs, moveMs, flipMs, winMs;
        /** 每格动画截止时刻：0 = 静止可烘焙，Long.MAX_VALUE = 选中期间一直动态 */
        long[] dynamicUntil;
        boolean rebakePending;
        long rebakeAt;
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
            int moved = 0;
            for (int i = 0; i < total; i++) {
                if (a.prevPieces[i] != 0 && pieces[i] == 0) {
                    a.fromRow = i / g.cols(); a.fromCol = i % g.cols();
                    moved = a.prevPieces[i];
                    break;
                }
            }
            for (int i = 0; i < total; i++) {
                if (pieces[i] == moved && a.prevPieces[i] != moved) {
                    a.toRow = i / g.cols(); a.toCol = i % g.cols();
                    break;
                }
            }
            // 暗棋翻面：prev 为暗棋、now 为明棋
            a.flipRow = a.flipCol = -1;
            if (g instanceof ChineseChessLogic) {
                for (int i = 0; i < total; i++) {
                    if (ChineseChessLogic.isHidden(a.prevPieces[i])
                            && !ChineseChessLogic.isHidden(pieces[i])
                            && a.prevPieces[i] != pieces[i]) {
                        a.flipRow = i / g.cols(); a.flipCol = i % g.cols(); a.flipMs = now;
                        break;
                    }
                }
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

        // 连五胜利（非落子类返回 null）；只在数据变化时算一次，而非每帧
        int[] win = g.winLine(pieces);
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
        // 走子/吃子：起点已空，只需动态终点
        mark(d, a.toRow, a.toCol, cols, a.moveMs + g.pieceMoveMs(), now);
        // 暗棋翻面
        mark(d, a.flipRow, a.flipCol, cols, a.flipMs + g.pieceFlipMs(), now);
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

    /** 方块实体渲染器用：该格是否应逐帧绘制（含宽限期，避免网格重建完成前出现空隙） */
    public boolean isDynamic(BlockPos pos, int cell, long now) {
        BoardAnim a = boards.get(pos);
        if (a == null || a.dynamicUntil == null || cell < 0 || cell >= a.dynamicUntil.length) return false;
        long d = a.dynamicUntil[cell];
        if (d == 0) return false;
        if (d == Long.MAX_VALUE) return true;
        return now < d + GRACE_MS;
    }

    /**
     * 区块几何用：快照出「不应烘焙」的格子。
     * 不含宽限期 —— 动画一结束就交还给几何，宽限期内由渲染器双方重叠绘制（位姿相同，肉眼无差别）。
     */
    public boolean[] exclusionSnapshot(BlockPos pos, int total) {
        boolean[] out = new boolean[total];
        BoardAnim a = boards.get(pos);
        if (a == null || a.dynamicUntil == null) return out;
        long now = System.currentTimeMillis();
        for (int i = 0; i < total && i < a.dynamicUntil.length; i++) {
            long d = a.dynamicUntil[i];
            out[i] = d == Long.MAX_VALUE || d > now;
        }
        return out;
    }

    BoardAnim get(BlockPos pos) { return boards.get(pos); }

    // ── 区块重建 ──

    /** 标记棋盘所在区块需要重建几何 */
    public void markDirty(BlockPos pos) {
        var lr = Minecraft.getInstance().levelRenderer;
        if (lr == null) return;
        lr.setSectionDirty(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /**
     * 每客户端 tick（20Hz）驱动：动画全部结束后重建一次区块，把棋子烘回几何；
     * 宽限期过后清除标记，恢复纯烘焙。必须在后台也生效，否则棋子会永久缺失。
     */
    public void tick() {
        if (boards.isEmpty()) return;
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            boards.clear();
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, BoardAnim>> it = boards.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BoardAnim> e = it.next();
            // 方块实体已消失（区块卸载/棋盘被拆）→ 清理
            if (!(level.getBlockEntity(e.getKey()) instanceof ChessboardBlockEntity)) {
                it.remove();
                continue;
            }
            BoardAnim a = e.getValue();
            long[] d = a.dynamicUntil;
            if (d == null) continue;

            boolean anyActive = false;
            long maxEnd = 0;
            for (long v : d) {
                if (v == 0) continue;
                if (v == Long.MAX_VALUE || v > now) anyActive = true;
                else maxEnd = Math.max(maxEnd, v);
            }
            if (!anyActive && maxEnd > 0 && !a.rebakePending) {
                a.rebakePending = true;
                a.rebakeAt = maxEnd;
                markDirty(e.getKey());
            }
            if (a.rebakePending && now >= a.rebakeAt + GRACE_MS) {
                a.rebakePending = false;
                for (int i = 0; i < d.length; i++) {
                    if (d[i] != 0 && d[i] != Long.MAX_VALUE) d[i] = 0;
                }
            }
        }
    }
}
