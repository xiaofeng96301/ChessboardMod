package com.chessboard.blockentity;

import com.chessboard.block.ChessboardBlock;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.BoardGameLogic.ClickResult;
import com.chessboard.game.GomokuLogic;
import com.chessboard.game.TicTacToeLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * 通用棋盘方块实体 —— 框架核心。
 * 负责棋子存储、选中、走棋历史、悔棋、重置、网络同步。
 * 具体规则通过 {@link ChessboardBlock#getGameLogic()} 获取。
 */
public class ChessboardBlockEntity extends BlockEntity {

    public static BlockEntityType<ChessboardBlockEntity> TYPE;

    private BoardGameLogic logic;
    private int[] pieces;
    private int selRow = -1, selCol = -1;
    private final Deque<int[]> history = new ArrayDeque<>();

    public ChessboardBlockEntity(BlockPos pos, BlockState state) {
        super(TYPE, pos, state);
    }

    // ── 逻辑 ──

    public BoardGameLogic gameLogic() {
        if (logic == null) {
            if (getBlockState().getBlock() instanceof ChessboardBlock cb) {
                logic = cb.getGameLogic();
                pieces = new int[logic.rows() * logic.cols()];
                logic.initBoard(pieces);
            }
        }
        return logic;
    }

    private int idx(int row, int col) {
        BoardGameLogic g = gameLogic();
        return row * g.cols() + col;
    }

    // ── 访问 ──

    public int[] pieces() { gameLogic(); return pieces; }
    public int selRow() { return selRow; }
    public int selCol() { return selCol; }
    // ── 点击 ──

    public void handleClick(int clickRow, int clickCol) {
        BoardGameLogic g = gameLogic();
        int captured = pieces[idx(clickRow, clickCol)];

        ClickResult r = g.onClick(pieces, selRow, selCol, clickRow, clickCol);
        switch (r) {
            case ClickResult.Select(int rw, int cl) -> { selRow = rw; selCol = cl; }
            case ClickResult.Move(int fr, int fc, int tr, int tc) -> {
                history.push(new int[]{fr, fc, tr, tc, captured});
                selRow = -1; selCol = -1;
            }
            case ClickResult.Place(int rw, int cl) -> {
                history.push(new int[]{-1, -1, rw, cl, 0});
            }
            case ClickResult.None() -> {}
            case ClickResult.Reset() -> resetBoard();
        }
        notifyChange();
    }

    // ── 悔棋/重置 ──

    public boolean undoMove() {
        if (history.isEmpty()) return false;
        int[] rec = history.pop();
        int fr = rec[0], fc = rec[1], tr = rec[2], tc = rec[3];
        BoardGameLogic g = gameLogic();
        if (fr >= 0) {
            pieces[idx(fr, fc)] = pieces[idx(tr, tc)];
            pieces[idx(tr, tc)] = rec[4];
        } else {
            pieces[idx(tr, tc)] = 0;
        }
        if (g instanceof GomokuLogic gmk) gmk.toggleSide();
        else if (g instanceof TicTacToeLogic ttt) ttt.toggleSide();
        selRow = -1; selCol = -1;
        notifyChange();
        return true;
    }

    public void importCode(String code) {
        gameLogic().decodePieces(pieces, code);
        history.clear();
        selRow = -1; selCol = -1;
        notifyChange();
    }

    /** 五子棋随机开局：先重置棋盘，再在随机空位放 3~10 个灰色障碍棋子 */
    public void randomStart() {
        BoardGameLogic g = gameLogic();
        if (!(g instanceof GomokuLogic)) return;
        g.initBoard(pieces);
        history.clear();
        java.util.ArrayList<Integer> empty = new java.util.ArrayList<>();
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i] == 0) empty.add(i);
        }
        if (empty.isEmpty()) return;
        java.util.Collections.shuffle(empty);
        int count = 3 + java.util.concurrent.ThreadLocalRandom.current().nextInt(8); // 3~10
        count = Math.min(count, empty.size());
        for (int i = 0; i < count; i++) {
            int idx = empty.get(i);
            pieces[idx] = GomokuLogic.GRAY;
            history.push(new int[]{-1, -1, idx / g.cols(), idx % g.cols(), 0});
        }
        selRow = -1; selCol = -1;
        notifyChange();
    }

    public void resetBoard() {
        gameLogic().initBoard(pieces);
        history.clear();
        selRow = -1; selCol = -1;
        notifyChange();
    }

    private void notifyChange() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // ── 历史扁平化工具 ──

    private int[] flattenHistory() {
        if (history.isEmpty()) return null;
        int[] flat = new int[history.size() * 5];
        int i = 0;
        for (int[] rec : history) { System.arraycopy(rec, 0, flat, i * 5, 5); i++; }
        return flat;
    }

    private void restoreHistory(int[] flat) {
        history.clear();
        if (flat != null) {
            for (int i = flat.length / 5 - 1; i >= 0; i--) {
                int off = i * 5;
                history.push(new int[]{flat[off], flat[off + 1], flat[off + 2], flat[off + 3], flat[off + 4]});
            }
        }
    }

    // ── 持久化 ──

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        gameLogic();
        out.putIntArray("pieces", pieces);
        out.putInt("selRow", selRow);
        out.putInt("selCol", selCol);
        int size = history.size();
        out.putInt("histSize", size);
        if (size > 0) out.putIntArray("history", flattenHistory());
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        gameLogic();
        int[] loaded = in.getIntArray("pieces").orElse(null);
        if (loaded != null && loaded.length == pieces.length) System.arraycopy(loaded, 0, pieces, 0, pieces.length);
        else gameLogic().initBoard(pieces);
        selRow = in.getIntOr("selRow", -1);
        selCol = in.getIntOr("selCol", -1);
        int histSize = in.getIntOr("histSize", 0);
        if (histSize > 0) restoreHistory(in.getIntArray("history").orElse(null));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider reg) {
        CompoundTag tag = super.getUpdateTag(reg);
        gameLogic();
        tag.putIntArray("pieces", pieces);
        tag.putInt("selRow", selRow);
        tag.putInt("selCol", selCol);
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput in) {
        super.handleUpdateTag(in);
        gameLogic();
        int[] loaded = in.getIntArray("pieces").orElse(null);
        if (loaded != null && loaded.length == pieces.length) System.arraycopy(loaded, 0, pieces, 0, pieces.length);
        selRow = in.getIntOr("selRow", -1);
        selCol = in.getIntOr("selCol", -1);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    public void onDataPacket(Connection net, ValueInput in) {
        super.onDataPacket(net, in);
        if (level != null && level.isClientSide()) handleUpdateTag(in);
    }

    // ── 数据组件 ──

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        gameLogic();
        int[] init = new int[pieces.length];
        gameLogic().initBoard(init);
        boolean hasProgress = !Arrays.equals(pieces, init) || !history.isEmpty();
        if (!hasProgress) return;

        CompoundTag tag = new CompoundTag();
        tag.putIntArray("pieces", pieces);
        tag.putInt("selRow", selRow);
        tag.putInt("selCol", selCol);
        tag.putInt("histSize", history.size());
        int[] flat = flattenHistory();
        if (flat != null) tag.putIntArray("history", flat);
        builder.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter input) {
        super.applyImplicitComponents(input);
        CompoundTag tag = input.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        gameLogic();
        if (tag.contains("pieces")) {
            int[] loaded = tag.getIntArray("pieces").orElse(null);
            if (loaded != null && loaded.length == pieces.length)
                System.arraycopy(loaded, 0, pieces, 0, pieces.length);
        }
        selRow = tag.getInt("selRow").orElse(-1);
        selCol = tag.getInt("selCol").orElse(-1);
        int histSize = tag.getInt("histSize").orElse(0);
        if (histSize > 0 && tag.contains("history")) restoreHistory(tag.getIntArray("history").orElse(null));
    }
}
