package com.chessboard.blockentity;

import com.chessboard.SkinData;
import com.chessboard.block.ChessboardBlock;
import com.chessboard.game.BoardGameLogic;
import com.chessboard.game.BoardGameLogic.ClickResult;
import com.chessboard.game.ChineseChessLogic;
import com.chessboard.game.FlightChessLogic;
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
    /**
     * 选中格里的第几颗棋子。只有「一格多颗」用得上 —— 飞行棋和平开局允许不同阵营叠在同一格，
     * 必须能指定「走的是哪一颗」。一格一子的棋类恒为 0。
     *
     * <p>重复点同一个已选中的格子会在这一叠里轮换（选中那颗抬得更高，看得见选的是谁），
     * 绕完一圈再点才取消选中；单颗格子行为不变（点了就是取消选中）。
     */
    private int selIdx = 0;
    /**
     * 历史：每条是**「变了的格子 + 它的旧值」**的增量记录 {@code [n, idx, oldValue, idx, oldValue, ...]}。
     *
     * <p>早先是固定 5 个 int 的差值记录（{@code from/to/captured}），只够还原一个格子 ——
     * 飞行棋默认开局的吃子会把多架飞机送回<b>多个</b>机库格，那种记录还原不了。
     * 换成「变化格子列表」之后：普通走子就 2 个格子（和原来等价）、吃子回库自动带上最多 4 个机库格，
     * 而且对其它棋类一行都不用改。
     *
     * <p>不用「整盘快照」是因为它会进 {@link #collectImplicitComponents} 的物品组件：
     * 225 个 int × 100 步 ≈ 180 KB 塞进 ItemStack，每次同步都带着走，太重。
     */
    private final Deque<int[]> history = new ArrayDeque<>();

    /** 历史上限：整盘快照每步 {@code rows*cols} 个 int，不封顶会一直涨 */
    private static final int MAX_HISTORY = 100;

    /**
     * 飞行棋的「和平开局」标志（false = 默认开局）。
     *
     * <p>和平：不同阵营落在同一格会堆叠共存，永不发生吃子；默认：异阵营整格被吃回机库。
     * 规则实例是全局单例（拿不到每块棋盘的状态），所以标志存在方块实体上、由 {@code handleClick}
     * 传给规则（{@link BoardGameLogic#onClick(int[], BoardGameLogic.Selection, int, int, boolean)}）。
     *
     * <p>持久化 key {@code flightMode}（0 默认 / 1 和平）。飞行棋的棋子编码本身向后兼容
     * （单架仍是 {@code 1..4}），所以<b>不需要</b>老存档迁移。
     */
    private boolean peaceful = false;
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
    /** 选中格里的第几颗（一格一子的棋类恒为 0） */
    public int selIdx() { return selIdx; }
    /** 飞行棋是否和平开局（false = 默认开局）；对别的棋类没有意义 */
    public boolean isPeaceful() { return peaceful; }

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
        // 点已经选中的那一格：如果是一叠（一格多颗），就轮换到下一颗 ——
        // 否则叠里队号不是最小的那些棋子永远点不到（旧实现的漏洞）。
        // 绕完一圈再点才真的取消选中；单颗格子照旧交给规则返回 Deselect，行为一个字不变。
        if (selRow == clickRow && selCol == clickCol) {
            int count = g.occupancy(pieces[idx(clickRow, clickCol)]);
            if (count > 1 && selIdx + 1 < count) {
                selIdx++;
                notifyChange();
                return;
            }
        }
        int[] before = pieces.clone(); // 悔棋快照（点击前的整盘）；只有真的走子/落子才入历史

        ClickResult r = g.onClick(pieces, new BoardGameLogic.Selection(selRow, selCol, selIdx),
                clickRow, clickCol, peaceful);
        switch (r) {
            case ClickResult.Select(int rw, int cl) -> { selRow = rw; selCol = cl; selIdx = 0; }
            case ClickResult.Deselect() -> { selRow = -1; selCol = -1; selIdx = 0; }
            // 掷骰：点数已由规则写进 pieces，不进历史（骰子不可悔）、不改选中
            case ClickResult.Roll() -> {}
            case ClickResult.Move(int fr, int fc, int tr, int tc) -> {
                pushDiff(before);
                selRow = -1; selCol = -1; selIdx = 0;
            }
            case ClickResult.Place(int rw, int cl) -> pushDiff(before);
            case ClickResult.None() -> {}
            case ClickResult.Flip(int rw, int cl) -> {
                pieces[idx(rw, cl)] = ChineseChessLogic.reveal(pieces[idx(rw, cl)]);
                selRow = -1; selCol = -1; selIdx = 0;
            }
        }
        notifyChange();
    }

    /**
     * 和点击前的状态比对，把变了的格子记成一条增量历史并入栈。
     *
     * <p>不按棋类写「谁从哪走到哪」的专用记录，而是直接 diff：这样飞行棋吃子时被挪进机库的
     * 那几格会自动被记上，其它棋类拿到的结果和原来的 from/to/captured 逐位等价。
     * 一次点击 225 格的比对（用户操作频率）可以忽略。没变化就不记（比如单纯的选中/取消）。
     */
    private void pushDiff(int[] before) {
        int changed = 0;
        for (int i = 0; i < pieces.length; i++) if (pieces[i] != before[i]) changed++;
        if (changed == 0) return;
        int[] rec = new int[1 + changed * 2];
        rec[0] = changed; // 记录里存的「变化格子数」；后半段成对出现 idx/旧值
        int k = 1;
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i] != before[i]) {
                rec[k++] = i;
                rec[k++] = before[i];
            }
        }
        history.push(rec);
        while (history.size() > MAX_HISTORY) history.removeLast();
    }

    // ── 悔棋/重置 ──

    public boolean undoMove() {
        if (history.isEmpty()) return false;
        int[] rec = history.pop();
        // 逆着写回旧值：吃子回库改动的多个机库格也在里面
        for (int k = 1; k + 1 < rec.length; k += 2) {
            int idx = rec[k];
            if (idx >= 0 && idx < pieces.length) pieces[idx] = rec[k + 1];
        }
        gameLogic().onUndo(); // 落子类需要还原下子方（它可能在数组之外）
        selRow = -1; selCol = -1; selIdx = 0;
        notifyChange();
        return true;
    }

    /** 导入代码。解析失败返回 false，且<b>棋盘保持原样</b>（不会像以前那样被乱码清盘） */
    public boolean importCode(String code) {
        if (!gameLogic().decodePieces(pieces, code)) return false;
        resetState();
        return true;
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
            // 一条单格增量：这一个格子放了灰子，旧值是空
            history.push(new int[]{1, idx, 0});
        }
        selRow = -1; selCol = -1; selIdx = 0;
        notifyChange();
    }

    /** 默认开局（也是重置）：清掉和平标志 */
    public void resetBoard() {
        peaceful = false;
        gameLogic().initBoard(pieces);
        resetState();
    }

    /**
     * 飞行棋和平开局：重置棋盘并设为和平模式 —— 不同阵营落在同一格会堆叠共存，永不发生吃子。
     * 非飞行棋棋盘直接不动（和 darkStart/randomStart 一样按棋类自我筛）。
     */
    public void peacefulStart() {
        if (!(gameLogic() instanceof FlightChessLogic)) return;
        peaceful = true;
        gameLogic().initBoard(pieces);
        resetState();
    }

    /** 清空选中与历史并通知同步（重置/导入/开局共用） */
    private void resetState() {
        history.clear();
        selRow = -1; selCol = -1; selIdx = 0;
        notifyChange();
    }

    private void notifyChange() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // ── 历史扁平化工具 ──

    /** 历史扁平化：每条前面放一个长度，读回时用来判格式 */
    private int[] flattenHistory() {
        if (history.isEmpty()) return null;
        int total = 0;
        for (int[] rec : history) total += 1 + rec.length;
        int[] flat = new int[total];
        int i = 0;
        for (int[] rec : history) {
            flat[i++] = rec.length;
            System.arraycopy(rec, 0, flat, i, rec.length);
            i += rec.length;
        }
        return flat;
    }

    /**
     * 还原历史。
     *
     * <p>老格式是每步固定的 5 个 int（fromRow/fromCol/toRow/toCol/captured），长度对不上
     * ⇒ <b>整条丢弃</b>：只丢悔棋栈，棋盘本身不受影响（升级的代价，写在这儿免得以后当 bug 查）。
     */
    private void restoreHistory(int[] flat) {
        history.clear();
        if (flat == null) return;
        List<int[]> recs = new ArrayList<>();
        int i = 0;
        while (i < flat.length) {
            int len = flat[i];
            // 合法记录：长度是奇数（1 + 2n）且至少 3（一个格子），并且不越界
            if (len < 3 || len % 2 == 0 || i + 1 + len > flat.length) return;
            int[] rec = new int[len];
            System.arraycopy(flat, i + 1, rec, 0, len);
            recs.add(rec);
            i += 1 + len;
        }
        // 扁平化是按「新 → 旧」写的，而 push 插队首：倒着 push 才能让队首仍是最新那条
        for (int k = recs.size() - 1; k >= 0; k--) history.push(recs.get(k));
        while (history.size() > MAX_HISTORY) history.removeLast();
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
        out.putInt("selIdx", selIdx);
        // 模式永远写：老存档靠「这个 key 在不在」判定飞行棋的老编码（见 migrateLegacyFlight）
        out.putInt("flightMode", peaceful ? 1 : 0);
        writeSkin(out::putString);
        int size = history.size();
        out.putInt("histSize", size);
        if (size > 0) out.putIntArray("hist2", flattenHistory());
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
        selIdx = in.getIntOr("selIdx", 0);
        peaceful = in.getIntOr("flightMode", 0) == 1;
        readSkin(key -> in.getString(key).orElse(null));
        // 新 key hist2（格子增量记录）。老 key "history" 是 5-int 差值格式，读不回，丢掉即可
        int histSize = in.getIntOr("histSize", 0);
        if (histSize > 0) restoreHistory(in.getIntArray("hist2").orElse(null));
        if (level != null && level.isClientSide()) clientDataHook.accept(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider reg) {
        CompoundTag tag = super.getUpdateTag(reg);
        gameLogic();
        tag.putIntArray("pieces", pieces);
        tag.putInt("selRow", selRow);
        tag.putInt("selCol", selCol);
        tag.putInt("selIdx", selIdx);
        tag.putInt("flightMode", peaceful ? 1 : 0);
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
        selIdx = in.getIntOr("selIdx", 0);
        peaceful = in.getIntOr("flightMode", 0) == 1;
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
        // 皮肤 / 和平标志也算「数据」：只设了皮肤或只切了和平开局、还没落子的棋盘，
        // 挖掉后也得带着走 —— 否则玩家会发现「和平开局后一步没走，挖起来再放下就变回默认开局了」
        boolean hasProgress = hasSkin() || peaceful || !Arrays.equals(pieces, init) || !history.isEmpty();
        if (!hasProgress) return;

        CompoundTag tag = new CompoundTag();
        tag.putIntArray("pieces", pieces);
        tag.putInt("selRow", selRow);
        tag.putInt("selCol", selCol);
        tag.putInt("selIdx", selIdx);
        tag.putInt("flightMode", peaceful ? 1 : 0);
        writeSkin(tag::putString);
        tag.putInt("histSize", history.size());
        int[] flat = flattenHistory();
        if (flat != null) tag.putIntArray("hist2", flat);
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
        selIdx = tag.getInt("selIdx").orElse(0);
        peaceful = tag.getInt("flightMode").orElse(0) == 1;
        readSkin(key -> tag.getString(key).orElse(null));
        int histSize = tag.getInt("histSize").orElse(0);
        if (histSize > 0 && tag.contains("hist2")) restoreHistory(tag.getIntArray("hist2").orElse(null));
    }
}
