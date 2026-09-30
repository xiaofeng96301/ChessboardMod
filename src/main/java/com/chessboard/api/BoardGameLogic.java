package com.chessboard.api;

import java.util.List;
import java.util.function.Consumer;

/**
 * 棋盘游戏规则接口。
 *
 * <p><b>这里不许出现 Minecraft 类型</b>：规则类要能脱离游戏、用 jshell 直接跑自测
 * （见 {@code tools/check_flight_rules.jsh}）。方法签名里一旦有方块状态之类的类型，
 * 加载规则类就得解析 Minecraft，那条自测线立刻断掉 —— 所以「用哪个方块当模型」这类
 * 外观钩子在 {@code client.renderer.PieceModels} 里按棋类登记，不放在这个接口上。
 * 这里只留能算成数值的东西（缩放、中心、高度……）。
 * 每种棋类只需实现此接口，框架自动处理棋子存储、悔棋、重置、动画渲染。
 */
public interface BoardGameLogic {

    /** 棋盘行数 */
    int rows();
    /** 棋盘列数 */
    int cols();

    /** 初始化棋子布局，填充 pieces 数组 */
    void initBoard(int[] pieces);

    /** 棋子中文名（渲染用），空串表示不渲染文字 */
    String pieceName(int piece);

    /** 文字颜色 ARGB，0=不渲染 */
    int textColor(int piece);

    /** 获取棋子阵营（0=红/先手, 1=黑/后手） */
    int side(int piece);

    /** 棋子缩放比例（默认 0.25） */
    default float pieceScale() { return 0.25f; }

    /** 棋子是否需要绕 X 轴翻转 180°（正面→反面），井字棋 X 方用 */
    default boolean pieceFlipX(int piece) { return false; }

    /** 棋子额外绕 Y 轴旋转角度（度），用于调整朝向 */
    default float pieceYRotation(int piece) { return 0; }

    /**
     * 棋子模型是否跟随文字朝向（并按阵营翻转 180°）。
     * 中国象棋的圆片没有正反面之分，方向应与汉字一致 —— 否则黑方棋子会和自己的汉字差 180°。
     * 国际象棋的朝向已由 {@link #pieceYRotation} 编码，保持 false。
     */
    default boolean pieceFollowsTextRotation() { return false; }

    /**
     * 文字/图标层是否按阵营翻转 180°。
     * 中国象棋的简繁字需要按方翻转；飞行棋四队共用一个飞机图标，必须朝向一致，
     * 所以那边覆写为 false —— 否则三支队伍的飞机头会朝反方向。
     */
    default boolean flipOverlayBySide() { return true; }

    /** 该格是否禁止落子/走子（飞行棋中央的骰子格）。返回 true 时点击不产生任何棋子移动 */
    default boolean isBlocked(int row, int col) { return false; }

    /** 模型中心 X 偏移（像素/16），默认 2.5 */
    default float pieceCenterX() { return 2.5f; }
    /** 模型中心 Z 偏移（像素/16），默认 2.5 */
    default float pieceCenterZ() { return 2.5f; }

    // ── 棋子模型的度量（数值，无 Minecraft 类型）──
    //
    // 框架（方块实体渲染器 / 区块几何）不再按棋类 instanceof 分派，一律问这组方法；
    // 「用哪个方块当模型」在 client.renderer.PieceModels 里按棋类登记。

    /**
     * 模型缩放倍率（默认同 {@link #pieceScale}）。
     *
     * <p>只有「模型本身不是棋子尺寸」的棋子需要覆写 —— 例如满方块模型的骰子要额外缩到 4/16。
     * 两条渲染路径都取这一个值，写岔了动画结束时就会跳一下。
     */
    default float modelScale(int piece) { return pieceScale(); }

    /**
     * 模型几何中心的 x / y / z（<b>方块空间</b>）。
     *
     * <p>普通棋子画在方块一角，中心由 {@link #pieceCenterX}/{@link #pieceCenterZ} 上报；
     * y 只有会绕自身中心自转的模型（骰子）才用得上，其余棋子保持 0。
     */
    default float modelCenterX(int piece) { return pieceCenterX() / 16f; }
    default float modelCenterY(int piece) { return 0f; }
    default float modelCenterZ(int piece) { return pieceCenterZ() / 16f; }

    // ── 皮肤槽 ──
    //
    // 槽位编号是**存档契约**（SkinData 的 1..10 对应老 matN+1 的迁移），只能追加不能改号。
    // 数值仍以 SkinData.SLOT_* 为准，这里只声明两个供接口默认值使用的哨兵。

    /** 不吃皮肤的哨兵 */
    int NO_SKIN_SLOT = -1;
    /** 棋盘本体的槽位（唯一一个所有棋类都有的槽） */
    int BOARD_SLOT = 0;

    /**
     * 这颗棋子吃哪个皮肤槽；{@link #NO_SKIN_SLOT} = 不吃（井字棋、骰子）。
     *
     * <p>实现里请直接写 {@code SkinData.SLOT_*}：那些都是编译期常量，会被内联进本类，
     * <b>不会</b>因此把 Minecraft 类型拖进规则类（这是规则类能脱离游戏跑 jshell 自测的前提）。
     */
    default int skinSlot(int piece) { return NO_SKIN_SLOT; }

    /** 该棋类在皮肤界面里显示哪些槽（顺序即显示顺序，第一项固定是棋盘） */
    default int[] skinSlots() { return new int[]{BOARD_SLOT}; }

    // ── 开局方式 ──

    /** 界面里能选的一种开局：{@code label} 是显示名，{@code command} 是命令字面量（如 {@code darkstart}） */
    record StartAction(String label, String command) {}

    /** {@link #startBoard} 的结果 */
    enum Start {
        /** 这种开局不属于本棋类，调用方应把棋盘还原回去 */
        UNSUPPORTED,
        /** 普通开局 */
        PLAIN,
        /** 开局顺带置位「规则模式标志」（飞行棋的和平开局），标志由调用方落盘 */
        FLAGGED,
    }

    /** 界面「开局方式」下拉里的额外选项（「默认开局」由框架固定给出，不要在这里重复） */
    default List<StartAction> startActions() { return List.of(); }

    /**
     * 按开局方式重设棋盘。实现负责改写 {@code pieces}。
     *
     * <p>新增的悔棋增量通过 {@code historyPush} 逐条交回，每条格式与
     * {@code ChessboardBlockEntity#pushDiff} 一致：{@code {变化格数, idx, 旧值, ...}}。
     * 一次开局推多条 = 悔棋时能逐颗回退（五子棋的随机开局就靠这个保持原有粒度）。
     *
     * <p>不认识 {@code mode} 时必须返回 {@link Start#UNSUPPORTED} 且<b>不得改动 pieces</b>，
     * 框架会据此还原并返回失败。
     */
    default Start startBoard(int[] pieces, String mode, Consumer<int[]> historyPush) {
        if (!"reset".equals(mode)) return Start.UNSUPPORTED;
        initBoard(pieces);
        return Start.PLAIN;
    }

    // ── 暗棋翻面 ──

    /**
     * 前后两代棋盘的同一格，是不是「翻面」这一步（例如暗棋翻成明棋）。
     *
     * <p>翻面要播翻面动画，而不是走子动画，所以框架得能认出来 —— 别再用 instanceof 判棋类。
     */
    default boolean isFlipTransition(int prev, int now) { return false; }

    /** 翻面后那一格的新棋子值（默认原样）。中国象棋是揭掉隐藏位 */
    default int onFlip(int piece) { return piece; }

    /**
     * 翻面动画<b>前半程</b>该显示哪颗棋子的模型（默认还是它自己）。
     * 中国象棋在这里返回背面模型（盖上隐藏位），翻到一半时正好换面。
     */
    default int flippedModelPiece(int piece) { return piece; }

    /** 棋盘格子总跨度（像素），格间距 = span / (cols-1) 或 span / (rows-1) */
    default float gridSpan() { return 14f; }

    /** 棋子模型基础高度（格，默认 1/16 = 0.0625） */
    default float pieceHeight() { return 1f / 16f; }

    /** 文字在棋子上表面的抬高量（格，默认 0.02） */
    default float pieceTextHeight() { return 0.02f; }
    /** 文字左右偏移（像素，默认 0.5） */
    default float pieceTextOffsetX() { return 0.5f; }
    /** 文字前后偏移（像素，默认 0.5） */
    default float pieceTextOffsetZ() { return 0.5f; }
    /** 文字缩放（默认 0.006） */
    default float pieceTextScale() { return 0.006f; }

    /** 选中棋子抬升高度（格，默认 0.045） */
    default float pieceLift() { return 0.045f; }
    /** 选中抬升动画时长（毫秒，默认 150） */
    default int pieceLiftMs() { return 150; }
    /** 走棋移动动画时长（毫秒，默认 250） */
    default int pieceMoveMs() { return 250; }
    /** 暗棋翻面动画时长（毫秒，默认 300） */
    default int pieceFlipMs() { return 300; }

    /** 棋类代码前缀，用于导入导出 */
    String codePrefix();

    /** 编码：prefix + 每棋子 3 字符（值hex + 行b36 + 列b36） */
    default String encodePieces(int[] pieces) {
        var sb = new StringBuilder(codePrefix());
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i] == 0) continue;
            int row = i / cols(), col = i % cols();
            sb.append(Integer.toHexString(pieces[i]).toUpperCase());
            sb.append(Integer.toString(row, 36).toUpperCase());
            sb.append(Integer.toString(col, 36).toUpperCase());
        }
        return sb.toString();
    }

    /**
     * 解码：每 3 字符一组（值hex + 行b36 + 列b36）。
     *
     * <p>解析到临时数组、成功了才写回：代码不合法时<b>棋盘保持原样</b>。
     * 早先是先 {@code Arrays.fill(0)} 再解析，一条乱码就能把整盘抹掉、调用方还报「导入成功」。
     *
     * @return 是否解析成功（前缀对得上）
     */
    default boolean decodePieces(int[] pieces, String code) {
        String prefix = codePrefix();
        if (code == null || !code.startsWith(prefix)) return false;
        String data = code.substring(prefix.length());
        int[] parsed = new int[pieces.length];
        for (int i = 0; i + 3 <= data.length(); i += 3) {
            try {
                int piece = Integer.parseInt(data.substring(i, i + 1), 16);
                int row = Integer.parseInt(data.substring(i + 1, i + 2), 36);
                int col = Integer.parseInt(data.substring(i + 2, i + 3), 36);
                if (row >= 0 && row < rows() && col >= 0 && col < cols())
                    parsed[row * cols() + col] = piece;
            } catch (NumberFormatException ignored) {}
        }
        System.arraycopy(parsed, 0, pieces, 0, pieces.length);
        return true;
    }
    /** 格子水平偏移（像素），默认 1 */
    default float gridOffsetX() { return 1f; }
    /** 格子垂直偏移（像素），默认 1 */
    default float gridOffsetZ() { return 1f; }

    /** 获取指定行的 Y 坐标（像素，0~16），默认均匀分布 */
    default float rowPixel(int row) { return gridOffsetZ() + (rows() - 1 - row) * gridSpan() / (rows() - 1); }
    /** 获取指定列的 X 坐标（像素，0~16），默认均匀分布 */
    default float colPixel(int col) { return gridOffsetX() + col * gridSpan() / (cols() - 1); }

    /**
     * 处理玩家点击。
     * @param pieces 当前棋子数组（可变，直接在数组上修改）
     * @param selRow 当前选中的行，-1 表示无选中
     * @param selCol 当前选中的列
     * @param clickRow 点击的行
     * @param clickCol 点击的列
     * @return 点击结果
     */
    ClickResult onClick(int[] pieces, int selRow, int selCol, int clickRow, int clickCol);

    /**
     * 选中状态：选中了哪一格（{@code row < 0} = 没选中），以及那一格里的第几颗棋子。
     *
     * <p>索引是给「一格多颗」用的（飞行棋和平开局的混编堆叠）：同一格里可能同时停着几个阵营的
     * 飞机，必须能指定「走的是哪一颗」。一格一子的棋类永远用 0。
     */
    record Selection(int row, int col, int index) {
        public static final Selection NONE = new Selection(-1, -1, 0);
        public boolean isEmpty() { return row < 0; }
    }

    /**
     * 同 {@link #onClick}，带上完整选中状态与棋盘的「和平模式」标志。
     *
     * <p>规则实例是<b>全局单例</b>（{@code FlightChessLogic.INSTANCE} 等），不能存每块棋盘的状态，
     * 所以这些只能由调用方（方块实体，它持久化标志、跟着同步选中）传进来。
     * 默认实现忽略它们、转发到 5 参版本 —— 其它棋类一行都不用改。
     */
    default ClickResult onClick(int[] pieces, Selection sel, int clickRow, int clickCol, boolean peaceful) {
        return onClick(pieces, sel.row(), sel.col(), clickRow, clickCol);
    }

    /** 行列 → 棋子数组下标 */
    default int idx(int row, int col) { return row * cols() + col; }

    /**
     * 这一格里有几颗棋子（0 = 空格）。
     *
     * <p>默认实现是「一格一子」：数组里那个值就是唯一的棋子。飞行棋覆写成按位域展开 ——
     * 和平开局下同一个格子里可以堆着不同阵营的飞机（比如 2 红 + 1 蓝），
     * 所以一个格子值能代表多颗棋子。
     *
     * <p>渲染两条路径（{@code ChessboardSectionGeometry} / {@code ChessboardRenderer}）靠
     * {@link #occupancy} + {@link #pieceAt} 把一格展开成 N 颗，其余代码（{@code stateFor}、
     * {@code charStateFor}、{@code SkinData#slotFor}）拿到的仍然是「一颗棋子的值」。
     */
    default int occupancy(int cellValue) { return cellValue == 0 ? 0 : 1; }

    /**
     * 这一格里第 {@code index} 颗棋子的值（0 = 没有这一颗）。
     *
     * <p>只保证 {@code index} 在 {@code 0..occupancy-1} 之间时返回非 0，顺序由实现自己定
     * （飞行棋按队号从低到高）。默认实现只认 index 0。
     */
    default int pieceAt(int cellValue, int index) { return index == 0 ? cellValue : 0; }

    /**
     * 从前后两代棋盘认出「这一步把哪一颗棋子从哪搬到了哪」—— 客户端的移动动画用。
     *
     * <p>不能按「从有到无 + 值相等」判：一格多颗时源格可能还剩着别的棋子（从叠里拿走一颗），
     * 而按值匹配会命中棋盘上任意一个同值的格子，把无关的格子标成动画格（那格会从烘焙几何里消失）。
     * 这里一律按<b>颗数</b>比：源格 = 占用数变少的那一格，被搬走的是它里面「出现次数变少」的
     * 那一颗（堆叠里拿走的未必是最低位那颗，和平开局可以指定走哪一队），落点 = 那一颗变多的格子。
     *
     * @return 认不出来时返回 {@link Move#NONE}（例如原地翻面、升变：没棋子挪窝）
     */
    static Move detectMove(BoardGameLogic g, int[] prev, int[] now) {
        int total = Math.min(prev.length, now.length);
        int from = -1, piece = 0;
        for (int i = 0; i < total; i++) {
            if (g.occupancy(prev[i]) <= g.occupancy(now[i])) continue;
            from = i;
            for (int k = 0, c = g.occupancy(prev[i]); k < c; k++) {
                int cand = g.pieceAt(prev[i], k);
                if (cand != 0 && countOf(g, prev[i], cand) > countOf(g, now[i], cand)) {
                    piece = cand;
                    break;
                }
            }
            break;
        }
        if (piece == 0) return Move.NONE;
        for (int i = 0; i < total; i++) {
            if (countOf(g, now[i], piece) > countOf(g, prev[i], piece)) {
                return new Move(from, i, piece);
            }
        }
        return Move.NONE;
    }

    /** 这一格里有几颗「值等于 piece」的棋子 */
    static int countOf(BoardGameLogic g, int cellValue, int piece) {
        int n = 0;
        for (int i = 0, c = g.occupancy(cellValue); i < c; i++) {
            if (g.pieceAt(cellValue, i) == piece) n++;
        }
        return n;
    }

    /** {@link #detectMove} 的结果：源格 / 落点的数组下标 + 被搬走那一颗的值（{@code NONE} = 没认出来） */
    record Move(int fromCell, int toCell, int piece) {
        public static final Move NONE = new Move(-1, -1, 0);
        public boolean isEmpty() { return piece == 0; }
    }

    /** 悔棋时还原下子方（默认空操作，落子类覆写） */
    default void onUndo() {}

    /** 胜利连线（落子类游戏）：返回连成一线（如五子连珠）的格子下标数组，无胜利返回 null */
    default int[] winLine(int[] pieces) { return null; }

    /**
     * 移动类默认点击：空点选子，点同色换选，点空格或异色走子。
     * 中国象棋/国际象棋共用；中国象棋需先处理暗棋翻面再调用。
     */
    default ClickResult onClickMove(int[] pieces, int selRow, int selCol, int clickRow, int clickCol) {
        // 禁区（飞行棋中央的骰子格）：既不能选也不能落子
        if (isBlocked(clickRow, clickCol)) return new ClickResult.None();
        int cp = pieces[idx(clickRow, clickCol)];
        if (selRow < 0) {
            return cp != 0 ? new ClickResult.Select(clickRow, clickCol) : new ClickResult.None();
        }
        // 再次点击已抬起的那颗棋子 → 放下。必须放在「同色换选」之前判断，
        // 否则和自己同色，会走换选分支重新选中自己，永远放不下。
        if (clickRow == selRow && clickCol == selCol) {
            return new ClickResult.Deselect();
        }
        int sp = pieces[idx(selRow, selCol)];
        if (cp != 0) {
            if (side(cp) == side(sp))
                return new ClickResult.Select(clickRow, clickCol);
            pieces[idx(selRow, selCol)] = 0;
            pieces[idx(clickRow, clickCol)] = sp;
            return new ClickResult.Move(selRow, selCol, clickRow, clickCol);
        }
        pieces[idx(selRow, selCol)] = 0;
        pieces[idx(clickRow, clickCol)] = sp;
        return new ClickResult.Move(selRow, selCol, clickRow, clickCol);
    }

    // ── 结果类型 ──

    sealed interface ClickResult permits ClickResult.None, ClickResult.Select, ClickResult.Deselect, ClickResult.Move, ClickResult.Place, ClickResult.Flip, ClickResult.Roll {
        /** 无效点击，没有任何变化 */
        record None() implements ClickResult {}

        /** 选中了 (row, col) 处的棋子 */
        record Select(int row, int col) implements ClickResult {}

        /** 放下了已抬起的棋子（再次点击同一格）。位置由调用方自己的 selRow/selCol 可知，故不携带 */
        record Deselect() implements ClickResult {}

        /**
         * 掷了骰子（飞行棋中央格）。新点数已由规则自己写进 pieces，本结果只用来告诉
         * 方块实体「刚发生了掷骰」，不产生走棋历史、也不改变选中。
         */
        record Roll() implements ClickResult {}

        /** 棋子从 (fromRow, fromCol) 移动到 (toRow, toCol)，覆盖了 captured 棋子 */
        record Move(int fromRow, int fromCol, int toRow, int toCol) implements ClickResult {}

        /** 在 (row, col) 放置了一颗新棋子 */
        record Place(int row, int col) implements ClickResult {}

        /** 翻开 (row, col) 处的暗棋 */
        record Flip(int row, int col) implements ClickResult {}
    }
}
