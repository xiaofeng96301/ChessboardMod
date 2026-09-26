package com.chessboard.blockentity;

import com.chessboard.SkinData;
import com.chessboard.block.ChessboardBlock;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.BoardGameLogic.ClickResult;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.GomokuLogic;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 通用棋盘方块实体 —— 框架核心。
 * 负责棋子存储、选中、走棋历史、悔棋、重置、网络同步。
 * 具体规则通过 {@link ChessboardBlock#getGameLogic(BlockState)} 获取。
 */
public class ChessboardBlockEntity extends BlockEntity {

    public static BlockEntityType<ChessboardBlockEntity> TYPE;

    /**
     * 客户端数据变化钩子（客户端注入，避免通用类引用客户端类）。
     * 用于更新动画状态并触发所在区块的几何重建。
     */
    public static Consumer<ChessboardBlockEntity> clientDataHook = be -> {};

    private BoardGameLogic logic;
    private int[] pieces;
    private int selRow = -1, selCol = -1;
    private final Deque<int[]> history = new ArrayDeque<>();
    /**
     * 各槽位的皮肤（方块 ID，null = 未设 = 用模型自带贴图）。
     * 槽位含义见 {@link SkinData}：0 棋盘、1..6 各棋类阵营、7..10 飞行棋四队。
     */
    private final String[] skins = new String[SkinData.SLOT_COUNT];

    public ChessboardBlockEntity(BlockPos pos, BlockState state) {
        super(TYPE, pos, state);
    }

    // ── 逻辑 ──

    public BoardGameLogic gameLogic() {
        if (logic == null) {
            if (getBlockState().getBlock() instanceof ChessboardBlock cb) {
                logic = cb.getGameLogic(getBlockState());
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

    /** 某槽位的皮肤（null = 未设） */
    public String skin(int slot) { return skins[slot]; }
    public String[] skins() { return skins; }

    /**
     * 整体设置皮肤并同步（皮肤界面里槽位一变就调到这里，最迟下一 tick 生效）。
     *
     * <p>写进 NBT 前先过一遍 {@link SkinData#stateOf} 校验：客户端发上来的方块 ID 不可信，
     * 未知 ID / 空气 / 带方块实体的方块一律当成「未设」。
     */
    public void setSkins(List<String> incoming) {
        for (int i = 0; i < skins.length; i++) {
            String id = incoming != null && i < incoming.size() ? incoming.get(i) : null;
            skins[i] = SkinData.stateOf(id) != null ? id : null;
        }
        notifyChange();
    }

    // ── 点击 ──

    public void handleClick(int clickRow, int clickCol) {
        BoardGameLogic g = gameLogic();
        int captured = pieces[idx(clickRow, clickCol)];

        ClickResult r = g.onClick(pieces, selRow, selCol, clickRow, clickCol);
        switch (r) {
            case ClickResult.Select(int rw, int cl) -> { selRow = rw; selCol = cl; }
            case ClickResult.Deselect() -> { selRow = -1; selCol = -1; }
            // 掷骰：点数已由规则写进 pieces，不进历史（骰子不可悔）、不改选中
            case ClickResult.Roll() -> {}
            case ClickResult.Move(int fr, int fc, int tr, int tc) -> {
                history.push(new int[]{fr, fc, tr, tc, captured});
                selRow = -1; selCol = -1;
            }
            case ClickResult.Place(int rw, int cl) -> {
                history.push(new int[]{-1, -1, rw, cl, 0});
            }
            case ClickResult.None() -> {}
            case ClickResult.Flip(int rw, int cl) -> {
                pieces[idx(rw, cl)] = ChineseChessLogic.reveal(pieces[idx(rw, cl)]);
                selRow = -1; selCol = -1;
            }
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
        g.onUndo(); // 落子类需要还原下子方
        selRow = -1; selCol = -1;
        notifyChange();
        return true;
    }

    public void importCode(String code) {
        gameLogic().decodePieces(pieces, code);
        resetState();
    }

    /** 中国象棋暗棋开局：重置棋盘，类型随机打乱并盖上背面 */
    public void darkStart() {
        if (gameLogic() instanceof ChineseChessLogic ccl) {
            ccl.darkStart(pieces);
            resetState();
        }
    }

    /** 中国象棋全暗棋开局：重置棋盘，红黑双方棋子值和位置全部随机并盖上背面 */
    public void fullDarkStart() {
        if (gameLogic() instanceof ChineseChessLogic ccl) {
            ccl.fullDarkStart(pieces);
            resetState();
        }
    }

    /** 五子棋随机开局：先重置棋盘，再在随机空位放 3~10 个灰色障碍棋子 */
    public void randomStart() {
        BoardGameLogic g = gameLogic();
        if (!(g instanceof GomokuLogic)) return;
        g.initBoard(pieces);
        history.clear();
        List<Integer> empty = new ArrayList<>();
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i] == 0) empty.add(i);
        }
        if (empty.isEmpty()) return;
        Collections.shuffle(empty);
        int count = 3 + ThreadLocalRandom.current().nextInt(8); // 3~10
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
        resetState();
    }

    /** 清空选中与历史并通知同步（重置/导入/开局共用） */
    private void resetState() {
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

    /** 序列化非空皮肤槽位（key: "skinN"） */
    private void writeSkin(BiConsumer<String, String> sink) {
        for (int i = 0; i < skins.length; i++) {
            if (skins[i] != null) sink.accept("skin" + i, skins[i]);
        }
    }

    /**
     * 读取皮肤槽位（缺失 = 未设）。
     *
     * <p>顺带做一次**老数据迁移**：新系统上线前存的是 {@code mat<i>}（棋子材质的枚举名，
     * 槽位 0..5），把它映射成方块 ID 填到新槽位 {@code i+1}（新表把「棋盘」插在了最前面）。
     * 只读不写回，老键从此变成惰性数据。
     */
    private void readSkin(Function<String, String> getter) {
        for (int i = 0; i < skins.length; i++) skins[i] = SkinData.blankToNull(getter.apply("skin" + i));
        for (int i = 0; i + 1 < skins.length; i++) {
            if (skins[i + 1] != null) continue;
            String legacy = SkinData.legacyMaterialSkin(getter.apply("mat" + i));
            if (legacy != null) skins[i + 1] = legacy;
        }
    }

    /** 是否设过皮肤（决定棋盘掉落物要不要带皮肤数据） */
    private boolean hasSkin() {
        for (String s : skins) {
            if (s != null) return true;
        }
        return false;
    }

    /** 读取棋子数组（长度不符则忽略，保持现有棋盘） */
    private void loadPieces(int[] loaded) {
        if (loaded != null && loaded.length == pieces.length)
            System.arraycopy(loaded, 0, pieces, 0, pieces.length);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        gameLogic();
        out.putIntArray("pieces", pieces);
        out.putInt("selRow", selRow);
        out.putInt("selCol", selCol);
        writeSkin(out::putString);
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
        readSkin(key -> in.getString(key).orElse(null));
        int histSize = in.getIntOr("histSize", 0);
        if (histSize > 0) restoreHistory(in.getIntArray("history").orElse(null));
        if (level != null && level.isClientSide()) clientDataHook.accept(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider reg) {
        CompoundTag tag = super.getUpdateTag(reg);
        gameLogic();
        tag.putIntArray("pieces", pieces);
        tag.putInt("selRow", selRow);
        tag.putInt("selCol", selCol);
        writeSkin(tag::putString);
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput in) {
        super.handleUpdateTag(in);
        gameLogic();
        loadPieces(in.getIntArray("pieces").orElse(null));
        selRow = in.getIntOr("selRow", -1);
        selCol = in.getIntOr("selCol", -1);
        readSkin(key -> in.getString(key).orElse(null));
        // 数据变化 → 更新动画状态并重建所在区块几何
        if (level != null && level.isClientSide()) clientDataHook.accept(this);
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
        // 皮肤也算「数据」：只设了皮肤、还没落子的棋盘，挖掉后也得带着皮肤
        boolean hasProgress = hasSkin() || !Arrays.equals(pieces, init) || !history.isEmpty();
        if (!hasProgress) return;

        CompoundTag tag = new CompoundTag();
        tag.putIntArray("pieces", pieces);
        tag.putInt("selRow", selRow);
        tag.putInt("selCol", selCol);
        writeSkin(tag::putString);
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
        if (tag.contains("pieces")) loadPieces(tag.getIntArray("pieces").orElse(null));
        selRow = tag.getInt("selRow").orElse(-1);
        selCol = tag.getInt("selCol").orElse(-1);
        readSkin(key -> tag.getString(key).orElse(null));
        int histSize = tag.getInt("histSize").orElse(0);
        if (histSize > 0 && tag.contains("history")) restoreHistory(tag.getIntArray("history").orElse(null));
    }
}
