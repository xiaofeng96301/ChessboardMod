package com.chessboard.client.renderer;

import com.chessboard.game.BoardGameLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * 单块棋盘的不可变几何快照。
 *
 * <p>在主线程（事件处理器里）构建，随后被区块网格 worker 线程只读使用。
 * 所有可变数据都已拷贝 —— <b>禁止</b>把实时对象（方块实体、实时材质数组等）带进 worker 线程。
 *
 * @param boardPos   棋盘世界坐标（算光照用）
 * @param offset     棋盘相对区块原点的偏移
 * @param states     棋子值 → 棋子方块状态（主线程预构建，避免 worker 线程碰注册表）
 * @param charStates 棋子值 → 汉字方块状态（无汉字的棋子不在表中）
 */
record BoardGeometrySnapshot(
        BlockPos boardPos,
        BlockPos offset,
        Direction facing,
        BoardGameLogic logic,
        int[] pieces,
        boolean[] excluded,
        Map<Integer, BlockState> states,
        Map<Integer, BlockState> charStates) {
}
