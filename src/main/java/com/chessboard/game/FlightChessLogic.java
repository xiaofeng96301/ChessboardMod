package com.chessboard.game;

import com.chessboard.SkinData;
import com.chessboard.api.BoardGameLogic;
import com.chessboard.api.DiceBoard;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * 飞行棋：17×17 棋盘，四队各 4 架飞机，正中央一格是骰子。
 *
 * <p>布局：回路<b>沿地图边走</b>，但到四个角时<b>往里凹几格绕开机库</b>，再拐回边上去；
 * 机库就卡在凹口外面的四角。中间四条队色跑道（十字）从回路通向中央终点。
 *
 * <p><b>棋盘形状不要手改这里的图</b> —— 真正的布局在 {@code tools/flight_layout.txt}，
 * 改完跑 {@code python tools/gen_flight_chess.py} 会重新生成纹理并打印本类需要的常量。
 *
 * <p>所以一个格子能同时装不同阵营的飞机（和平开局允许混编，比如 2 红 + 1 蓝）。
 * 但「一颗棋子的值」仍是老样子 {@code 1..4} = 队 0..3 —— {@link #pieceAt} 把格子展开成这种值，
 * 于是 {@code stateFor} / {@code charStateFor} / {@code SkinData#slotFor} 这些只认单颗棋子的代码
 * 一行都不用改。渲染两条路径靠 {@link #occupancy} + {@link #pieceAt} 循环发射，并按序号错开位置。
 *
 * <p>两种开局：<b>默认</b>（同队堆叠、异队整格吃回机库）、<b>和平</b>（异队也堆叠共存，永不发生吃子）。
 * 模式存在方块实体上，通过 {@code onClick(..., peaceful)} 传进来 —— 规则实例是全局单例，不能存每块棋盘的状态。
 */
public class FlightChessLogic implements BoardGameLogic, DiceBoard {

    /** 带框棋盘（格距 14/(15-1) 铺满 14 像素） */
    public static final FlightChessLogic INSTANCE = new FlightChessLogic(1f, 14f);

    private static final int SIZE = 15;

    /** 中央骰子格的数组下标 */
    public static final int DICE_CELL = (SIZE / 2) * SIZE + (SIZE / 2);

    /** 队伍数量 */
    public static final int TEAM_COUNT = 4;

    /** 骰子值基数（沿用老编码：值 = 10 + 点数，11..16） */
    public static final int DICE_BASE = 10;

    /** 每队占的位数。每队最多 4 架 ⇒ 0..4，3 位（放得下 0..7）刚好；2 位放不下 4 */
    private static final int TEAM_BITS = 3;
    /**
     * 多架打包值的标记位：<b>bit 12</b>（四个队恰好占满 bit 0..11）。
     *
     * <p><b>不能拍脑袋取 16</b> —— 那等于 bit 4，正好压在绿队计数的低位上，
     * {@code count(v, 1)} 会把标记位算进去（2 红 + 1 蓝被读成 2 红 + 2 绿）。
     *
     * <p>把 {@code 1..4} 和 {@code 11..16} 留给老语义：一架飞机单独占一格时，打包值会
     * <b>规范化</b>成老的 {@code 队号+1}。于是老存档、老导入码一个都不用迁移，
     * {@code isDice}/{@code isPlane} 对老值的行为也一个字不变。
     */
    private static final int PACK_BASE = 1 << (TEAM_BITS * TEAM_COUNT);
    private static final int TEAM_MASK = (1 << TEAM_BITS) - 1;
    /** 单队最大架数（一队总共就 4 架） */
    private static final int MAX_PER_TEAM = 4;

    // ── 位域工具（服务端规则与客户端渲染都靠这几个） ──

    /**
     * 四队架数 → 格子值。**总数恰好 1 时收敛成老编码的 {@code 1..4}**，
     * 这样「单架」永远只有一种表示，新老存档与导入码自然互通（见 {@link #PACK_BASE}）。
     */
    public static int pack(int c0, int c1, int c2, int c3) {
        int total = c0 + c1 + c2 + c3;
        if (total <= 0) return 0;
        if (total == 1) {
            if (c0 > 0) return 1;
            if (c1 > 0) return 2;
            if (c2 > 0) return 3;
            return 4;
        }
        return PACK_BASE | (Math.clamp(c0, 0, MAX_PER_TEAM))
                | (Math.clamp(c1, 0, MAX_PER_TEAM) << TEAM_BITS)
                | (Math.clamp(c2, 0, MAX_PER_TEAM) << (TEAM_BITS * 2))
                | (Math.clamp(c3, 0, MAX_PER_TEAM) << (TEAM_BITS * 3));
    }

    /** 这一格里某队有几架（老编码的单架值也能正确解读） */
    public static int count(int cellValue, int team) {
        if (cellValue < PACK_BASE) {
            return cellValue >= 1 && cellValue <= 4 && cellValue - 1 == team ? 1 : 0;
        }
        return (cellValue >> (team * TEAM_BITS)) & TEAM_MASK;
    }

    /** 把这一格某队的架数改成 n，其余队不动 */
    public static int withCount(int cellValue, int team, int n) {
        int[] c = new int[TEAM_COUNT];
        for (int t = 0; t < TEAM_COUNT; t++) c[t] = count(cellValue, t);
        c[Math.clamp(team, 0, TEAM_COUNT - 1)] = Math.clamp(n, 0, MAX_PER_TEAM);
        return pack(c[0], c[1], c[2], c[3]);
    }

    /** 这一格某队加一架 */
    public static int addPlane(int cellValue, int team) {
        return withCount(cellValue, team, count(cellValue, team) + 1);
    }

    /** 这一格某队减一架 */
    public static int removePlane(int cellValue, int team) {
        return withCount(cellValue, team, count(cellValue, team) - 1);
    }

    /** 某队机库 2×2 左上角的 (行, 列)。与 {@link #initBoard} 里的摆法一一对应 */
    public static int[] hangarCorner(int team) {
        return switch (team) {
            case 0 -> new int[]{1, 12};   // 右上 红
            case 1 -> new int[]{12, 1};   // 左下 黄
            case 2 -> new int[]{12, 12};  // 右下 蓝
            default -> new int[]{1, 1};   // 左上 绿
        };
    }

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

    /** 在 (row0, col0) 起的 2×2 机库里摆 4 架同队飞机（每格一架） */
    private void hangar(int[] p, int row0, int col0, int team) {
        p[idx(row0, col0)] = addPlane(0, team);
        p[idx(row0, col0 + 1)] = addPlane(0, team);
        p[idx(row0 + 1, col0)] = addPlane(0, team);
        p[idx(row0 + 1, col0 + 1)] = addPlane(0, team);
    }

    // ── 棋子值工具 ──

    /** 某队的「单颗棋子值」（1..4）。渲染与皮肤只认这种值，不认格子值 */
    public static int planeValue(int team) { return 1 + team; }

    /**
     * 是否骰子。范围收窄成老编码的 11..16 —— 因为多架打包值（{@code >= PACK_BASE}）也 ≥ 10，
     * 老写法 {@code >= 10} 会把堆叠格当成骰子（画面表现是所有堆叠都变红队，不崩不报错，很难查）。
     */
    @Override public boolean isDice(int piece) { return DiceValues.is(piece, DICE_BASE); }
    /** 是否「单颗飞机值」（{@code 1..4}）。只对 {@link #pieceAt} 展开出来的值有意义 */
    public static boolean isPlane(int piece) { return piece >= 1 && piece <= 4; }
    /** 是否多架打包值（同一格里 2 架及以上） */
    public static boolean isMulti(int piece) { return piece >= PACK_BASE; }

    // ── DiceBoard：骰子格与点数（编码工具在 DiceValues，任何玩法都能用）──

    @Override public int diceCell() { return DICE_CELL; }
    /** 骰子朝上的点数 1..6（非骰子返回 1） */
    @Override public int faceOf(int piece) { return DiceValues.face(piece, DICE_BASE); }
    /** 点数 → 骰子棋子值 */
    @Override public int pieceForFace(int face) { return DiceValues.encode(DICE_BASE, face); }

    // ── 一格多颗（堆叠）──

    @Override
    public int occupancy(int cellValue) {
        if (cellValue == 0) return 0;
        if (isDice(cellValue)) return 1; // 骰子格永远只有骰子（飞机进不来，isBlocked 挡着）
        int n = 0;
        for (int t = 0; t < TEAM_COUNT; t++) n += count(cellValue, t);
        return n;
    }

    /**
     * 展开第 index 颗棋子的值（队 0 的飞机在前，然后队 1、2、3）。
     * 渲染两条路径按 {@code 0..occupancy-1} 遍历，拿到的都是标准单颗值 {@code 1..4}。
     */
    @Override
    public int pieceAt(int cellValue, int index) {
        if (index < 0) return 0;
        if (isDice(cellValue)) return index == 0 ? cellValue : 0;
        int k = index;
        for (int t = 0; t < TEAM_COUNT; t++) {
            int c = count(cellValue, t);
            if (k < c) return planeValue(t);
            k -= c;
        }
        return 0;
    }

    // ── 外观 ──

    /** 不写字，圆片上贴的是飞机图标（见 ChessboardPieceGeometry#charStateFor） */
    @Override public String pieceName(int piece) { return ""; }
    @Override public int textColor(int piece) { return 0; }

    /**
     * 单颗棋子值的阵营。
     *
     * <p><b>只对 {@link #pieceAt} 展开出来的值有意义</b>：格子值里可能同时装着几个队
     * （和平开局的混编堆叠），对格子值调用会得到一个无意义的结果。
     */
    @Override
    public int side(int piece) {
        return isPlane(piece) ? Math.clamp(piece - 1, 0, TEAM_COUNT - 1) : 0;
    }

    @Override public String codePrefix() { return "fx"; }

    /**
     * 15×15 下格距 1 像素（14 / (15-1)），棋子模型宽 5 单位，
     * 缩放必须 ≤0.2 才不会互相压住；取 0.18 留一点间隙。
     */
    @Override public float pieceScale() { return 0.18f; }

    // 模型（骰子 / 四色飞机 + 飞机图标）在 client.renderer.PieceModels 里登记 ——
    // 不放这里是为了让规则类保持纯 Java，能脱离 Minecraft 跑 jshell 自测。
    // 下面这几个是纯数值的度量，留在这里没问题。

    // 骰子的缩放与轴心取自 DiceBoard 的默认值（满方块模型 0..16 缩到 4/16，
    // 几何中心是方块正中 0.5 —— 三个轴一个数，不会像角落小立方体那样换算错，踩过）

    @Override
    public float modelScale(int piece) {
        return isDice(piece) ? pieceScale() * diceModelScale() : pieceScale();
    }

    @Override public float modelCenterX(int piece) { return isDice(piece) ? dicePivot() : pieceCenterX() / 16f; }
    @Override public float modelCenterY(int piece) { return isDice(piece) ? dicePivot() : 0f; }
    @Override public float modelCenterZ(int piece) { return isDice(piece) ? dicePivot() : pieceCenterZ() / 16f; }

    /**
     * 四队各一个槽；<b>骰子不吃皮肤</b>（走本模组自己的点数贴图）。
     * 必须先判骰子 —— {@code side()} 对骰子返回 0，不判就会误映射到红队槽。
     */
    @Override public int skinSlot(int piece) {
        return isDice(piece) ? NO_SKIN_SLOT : SkinData.SLOT_FLIGHT_RED + side(piece);
    }

    @Override public int[] skinSlots() {
        return new int[]{SkinData.SLOT_BOARD, SkinData.SLOT_FLIGHT_RED, SkinData.SLOT_FLIGHT_YELLOW,
                SkinData.SLOT_FLIGHT_BLUE, SkinData.SLOT_FLIGHT_GREEN};
    }

    @Override
    public List<StartAction> startActions() {
        return List.of(new StartAction("默认开局", "reset"),
                new StartAction("和平开局", "peacefulstart"));
    }

    /** 和平开局：异阵营共格堆叠、永不发生吃子。规则模式标志由框架落盘（FLAGGED） */
    @Override
    public Start startBoard(int[] pieces, String mode, Consumer<int[]> historyPush) {
        if ("peacefulstart".equals(mode)) {
            initBoard(pieces);
            return Start.FLAGGED;
        }
        if (!"reset".equals(mode)) return Start.UNSUPPORTED;
        initBoard(pieces);
        return Start.PLAIN;
    }
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
        return idx(row, col) == diceCell();
    }

    @Override
    public ClickResult onClick(int[] pieces, int selRow, int selCol, int clickRow, int clickCol) {
        return onClick(pieces, new BoardGameLogic.Selection(selRow, selCol, 0), clickRow, clickCol, false);
    }

    /**
     * 飞行棋点击。<b>一次只移动一架</b>：源格减一，其余飞机留在原地。
     *
     * <p>落点分三种：
     * <ul>
     *   <li>空格 → 落一架；</li>
     *   <li>只有自己这一队 → 和原来一样「换选」，不落子（保持原来点自己子的手感）；</li>
     *   <li>有别的队：
     *     <ul>
     *       <li><b>默认开局</b> → 整格敌方棋子全部送回各自机库，然后自己落一架；</li>
     *       <li><b>和平开局</b> → 直接叠上去（混编共存），谁也不回机库。</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>没走 {@link BoardGameLogic#onClickMove}：那套的「异色直接覆盖」既不做堆叠、也不把被吃的
     * 棋子送回机库，而且是整格搬走 —— 和这里的三条规则都不兼容。
     */
    @Override
    public ClickResult onClick(int[] pieces, BoardGameLogic.Selection sel, int clickRow, int clickCol, boolean peaceful) {
        int cell = idx(clickRow, clickCol);
        // 点骰子 → 掷一次；面直接写进 pieces，走正常的同步/烘焙路径
        if (roll(pieces, cell)) return new ClickResult.Roll();
        if (isBlocked(clickRow, clickCol)) return new ClickResult.None();

        int clickValue = pieces[cell];
        if (sel.isEmpty()) {
            return clickValue != 0 ? new ClickResult.Select(clickRow, clickCol) : new ClickResult.None();
        }
        if (sel.row() == clickRow && sel.col() == clickCol) return new ClickResult.Deselect();

        int selCell = idx(sel.row(), sel.col());
        int selValue = pieces[selCell];
        // 走「选中的那一颗」所属的队：选中的索引由方块实体维护（点同一格会在叠里轮换），
        // 索引越界就退回队号最小的那一颗
        int team = teamAt(selValue, sel.index());
        if (team < 0) team = firstTeam(selValue);
        if (team < 0) return new ClickResult.Deselect(); // 源格空了（正常不会发生），退回未选中

        // 这里刻意**不做**「点到只有自己这一队的格子就换选」：那正是同队堆叠要落的格子，
        // 两个手势完全一样，只能保一个（需求要堆叠，所以保落子）。
        // 想换选就先点一下已选中的格子取消再点目标。

        // 起飞的那一架：源格减一，剩下的留在原地
        pieces[selCell] = removePlane(selValue, team);

        int dest = clickValue;
        if (dest != 0 && !peaceful) {
            // 默认开局：整格**敌方**全吃回机库。注意不能把整格清空 ——
            // 混编格（和平局留下来的、或机库挤兑叠出来的）里可能同时有我和敌人，我方要留在原地
            sendHome(dest, pieces, team);
            dest = withCount(0, team, count(dest, team)); // 落点只剩我方那几架
        }
        pieces[cell] = addPlane(dest, team);
        return new ClickResult.Move(sel.row(), sel.col(), clickRow, clickCol);
    }

    /** 展开顺序里第 index 颗棋子属于哪队（-1 = 没有这一颗 / 是骰子） */
    public static int teamAt(int cellValue, int index) {
        int p = INSTANCE.pieceAt(cellValue, index);
        return isPlane(p) ? p - 1 : -1;
    }

    /** 源格里会被移走的那一架属于哪队 —— 就是 {@link #pieceAt} 展开顺序里的第 0 颗 */
    public static int firstTeam(int cellValue) {
        for (int t = 0; t < TEAM_COUNT; t++) if (count(cellValue, t) > 0) return t;
        return -1;
    }

    /**
     * 默认开局的吃子：把这一格里的<b>敌方</b>飞机全部送回各自机库（落在敌方堆叠上时整格全吃）。
     *
     * <p>{@code keepTeam} 那一队留在原地 —— 混编格（和平局留下来的、或机库被挤兑叠出来的）
     * 里可能同时有我和敌人，整格清空会把我方也送回家。
     */
    private void sendHome(int cellValue, int[] pieces, int keepTeam) {
        for (int t = 0; t < TEAM_COUNT; t++) {
            if (t == keepTeam) continue;
            for (int i = count(cellValue, t); i > 0; i--) putInHangar(pieces, t);
        }
    }

    /** 把一架 t 队飞机放进它的 2×2 机库：优先空位，四格都占着就叠在已有的一格上 */
    private void putInHangar(int[] pieces, int team) {
        int[] corner = hangarCorner(team);
        int best = -1;
        outer:
        for (int dr = 0; dr < 2; dr++) {
            for (int dc = 0; dc < 2; dc++) {
                int cell = idx(corner[0] + dr, corner[1] + dc);
                int c = count(pieces[cell], team);
                if (c == 0) { best = cell; break outer; }
                if (best < 0 || c < count(pieces[best], team)) best = cell;
            }
        }
        pieces[best] = addPlane(pieces[best], team);
    }

    // ── 导入导出 ──

    /**
     * 新格式的版本标记。
     *
     * <p><b>标记里不能出现十六进制字符</b>：老码是 {@code "fx"} + 每子 3 个字符（值 hex + 行列各 1 位 36 进制），
     * 所以 {@code "fx2"} 会被老码撞上（{@code fx200...} 本身就是一条合法老码）。{@code v} 不是 hex 数字，
     * {@code fxv2} 与老码不可能混淆。
     */
    private static final String CODE_V2 = "fxv2";

    /**
     * 编码：每格固定 6 字符 —— <b>4 位十六进制值 + 行 + 列</b>（行列各 1 位 36 进制）。
     * 值可能 {@code >= 16}（一格多架），1 位十六进制装不下，所以定宽 4 位。
     */
    @Override
    public String encodePieces(int[] pieces) {
        StringBuilder sb = new StringBuilder(CODE_V2);
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i] == 0) continue;
            sb.append(String.format("%04X", pieces[i]));
            sb.append(Integer.toString(i / cols(), 36).toUpperCase());
            sb.append(Integer.toString(i % cols(), 36).toUpperCase());
        }
        return sb.toString();
    }

    /**
     * 解码，两种格式都认：
     * <ul>
     *   <li>{@code fxv2} —— 新格式（每格 6 字符）；</li>
     *   <li>{@code fx} —— 老格式（每子 3 字符）。单架仍是 {@code 1..4}、骰子仍是 {@code 10+点数}，
     *       语义没变，所以直接交给默认实现，老码照样能用。</li>
     * </ul>
     *
     * <p>任何解析失败都返回 {@code false} 并且<b>不清空棋盘</b> —— 默认实现是先 {@code Arrays.fill(0)}
     * 再解析，乱码会把整盘抹掉却没人报错，这个坑一并堵上（调用方 {@code importCode} 会据此回失败消息）。
     */
    @Override
    public boolean decodePieces(int[] pieces, String code) {
        if (code == null) return false;
        if (!code.startsWith(CODE_V2)) {
            return BoardGameLogic.super.decodePieces(pieces, code); // 老 "fx" 码
        }
        String data = code.substring(CODE_V2.length());
        if (data.length() % 6 != 0) return false; // 截断/乱码：别动棋盘
        int[] parsed = new int[pieces.length];
        for (int i = 0; i < data.length(); i += 6) {
            try {
                int value = Integer.parseInt(data.substring(i, i + 4), 16);
                int row = Integer.parseInt(data.substring(i + 4, i + 5), 36);
                int col = Integer.parseInt(data.substring(i + 5, i + 6), 36);
                if (row >= rows() || col >= cols()) return false;
                if (value != 0 && !isDice(value) && !isPlane(value) && !isMulti(value)) return false;
                parsed[row * cols() + col] = value;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        System.arraycopy(parsed, 0, pieces, 0, pieces.length);
        return true;
    }
}
