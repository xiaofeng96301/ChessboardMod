// 胜利判定自测：各棋类的 winCells（要表演的格子）与 winStyle。纯逻辑，不用开游戏。
// 跑法： "C:\Program Files\Java\jdk-27\bin\jshell.exe" --class-path build/classes/java/main -q tools/check_win_conditions.jsh
// 结果看 build/win_conditions_result.txt
import com.chessboard.game.*;
import com.chessboard.api.*;
import java.util.Arrays;

boolean same(int[] a, int[] b) { return Arrays.equals(a, b); }

void want(String what, boolean ok) {
    if (!ok) throw new RuntimeException("断言失败: " + what);
    System.out.println("  ok  " + what);
}

void run() {
    // ── 中国象棋 ──
    ChineseChessLogic xq = ChineseChessLogic.INSTANCE;
    int[] p = new int[90];
    xq.initBoard(p);
    want("象棋：开局不误报将军", xq.winCells(p) == null);

    // 黑车与红帅同列，中间无子 → 将军，报帅那一格
    Arrays.fill(p, 0);
    p[xq.idx(0, 4)] = ChineseChessLogic.pack(0, 1);      // 红帅
    p[xq.idx(9, 4)] = ChineseChessLogic.pack(1, 1);      // 黑将
    p[xq.idx(5, 4)] = ChineseChessLogic.pack(1, 5);      // 黑车
    int[] w = xq.winCells(p);
    want("象棋：车照着帅 → 报帅那一格", w != null && w.length == 1 && w[0] == xq.idx(0, 4));
    p[xq.idx(3, 4)] = ChineseChessLogic.pack(0, 7);      // 中间塞个红兵
    want("象棋：中间有子挡着就不是将军", xq.winCells(p) == null);

    // 炮：中间恰好一个炮架才算
    Arrays.fill(p, 0);
    p[xq.idx(0, 4)] = ChineseChessLogic.pack(0, 1);
    p[xq.idx(9, 4)] = ChineseChessLogic.pack(1, 1);
    p[xq.idx(5, 4)] = ChineseChessLogic.pack(1, 6);      // 黑炮
    p[xq.idx(3, 4)] = ChineseChessLogic.pack(1, 7);      // 炮架
    want("象棋：炮翻一个子打帅 → 将军", xq.winCells(p) != null);
    p[xq.idx(2, 4)] = ChineseChessLogic.pack(0, 7);      // 再塞一个，炮架变两个
    want("象棋：两个子挡着炮就打不到", xq.winCells(p) == null);

    // 马：日字，别腿
    Arrays.fill(p, 0);
    p[xq.idx(2, 3)] = ChineseChessLogic.pack(0, 1);      // 红帅 (2,3)
    p[xq.idx(9, 4)] = ChineseChessLogic.pack(1, 1);
    p[xq.idx(4, 4)] = ChineseChessLogic.pack(1, 4);      // 黑马 (4,4) → 日字攻到 (2,3)
    want("象棋：马能攻到帅 → 将军", xq.winCells(p) != null);
    p[xq.idx(3, 4)] = ChineseChessLogic.pack(1, 7);      // 别马腿
    want("象棋：别了马腿就不算", xq.winCells(p) == null);

    // 帅被吃 → 对面（黑，side 1）全体
    Arrays.fill(p, 0);
    p[xq.idx(9, 4)] = ChineseChessLogic.pack(1, 1);
    p[xq.idx(9, 3)] = ChineseChessLogic.pack(1, 5);
    w = xq.winCells(p);
    want("象棋：红帅没了 → 报黑方全体 2 格", w != null && w.length == 2
            && xq.side(p[w[0]]) == 1 && xq.side(p[w[1]]) == 1);

    // 暗棋：盖着的棋子身份未知，不参与攻击判定
    Arrays.fill(p, 0);
    p[xq.idx(0, 4)] = ChineseChessLogic.pack(0, 1);
    p[xq.idx(9, 4)] = ChineseChessLogic.pack(1, 1);
    p[xq.idx(5, 4)] = ChineseChessLogic.hide(ChineseChessLogic.pack(1, 5));   // 盖着的黑车
    want("象棋：盖着的棋子不算攻击", xq.winCells(p) == null);

    // ── 国际象棋 ──
    ChessLogic ic = ChessLogic.INSTANCE;
    int[] c = new int[64];
    ic.initBoard(c);
    want("国际象棋：开局不误报", ic.winCells(c) == null);
    Arrays.fill(c, 0);
    c[ic.idx(0, 4)] = ChessLogic.pack(1, ChessLogic.KING);
    c[ic.idx(0, 3)] = ChessLogic.pack(1, ChessLogic.ROOK);
    w = ic.winCells(c);
    want("国际象棋：白方一个子都没有 → 黑方赢 2 格 + 自转样式", w != null && w.length == 2
            && ic.side(c[w[0]]) == 1 && ic.winStyle() == BoardGameLogic.WIN_SPIN);
    c[ic.idx(7, 4)] = ChessLogic.pack(0, ChessLogic.KING);
    want("国际象棋：两个王都在 → 没结果", ic.winCells(c) == null);
    c[ic.idx(7, 4)] = 0;
    want("国际象棋：白王被吃 → 又判黑方赢", ic.winCells(c) != null);

    // ── 板棋 ──
    TaflLogic tf = TaflLogic.NINE;
    int[] t = new int[81];
    tf.initBoard(t, TaflLogic.Variant.TABLUT);
    int mid = 4;
    want("板棋：开局（王在正中）没结果", tf.winCells(t) == null);
    // 王到边上 → 护王方（side 0）全体跳
    t[tf.idx(mid, mid)] = 0;
    t[tf.idx(0, 3)] = TaflLogic.KING;
    w = tf.winCells(t);
    boolean allSide0 = w != null && w.length > 0;
    if (allSide0) for (int cell : w) if (tf.side(t[cell]) != 0) allSide0 = false;
    want("板棋：王到边上 → 护王方全体（都是 side 0）", allSide0);
    // 王被吃 → 捉王方（side 1）全体跳
    t[tf.idx(0, 3)] = 0;
    w = tf.winCells(t);
    boolean allSide1 = w != null && w.length > 0;
    if (allSide1) for (int cell : w) if (tf.side(t[cell]) != 1) allSide1 = false;
    want("板棋：王被吃 → 捉王方全体（都是 side 1）", allSide1);
    // 四角也算「边缘」
    Arrays.fill(t, 0);
    t[tf.idx(8, 8)] = TaflLogic.KING;
    t[tf.idx(4, 4)] = TaflLogic.MUSCOVITE;
    want("板棋：王到角落也算赢", tf.winCells(t) != null);

    // ── 开关范围 ──
    want("有胜利效果的棋类才有开关（飞行棋、井字棋没有）",
            GomokuLogic.INSTANCE.winToggleable() && ic.winToggleable()
                    && tf.winToggleable() && xq.winToggleable()
                    && !FlightChessLogic.INSTANCE.winToggleable()
                    && !TicTacToeLogic.INSTANCE.winToggleable());
    // 默认值在方块实体上（一律关），这里是纯逻辑测不到 —— 只钉住「哪些棋类有开关」
    want("飞行棋不参与胜利判定", FlightChessLogic.INSTANCE.winCells(new int[225]) == null);

    System.out.println("全部通过: 象棋将军（车/炮/马/挡子/暗棋）/ 吃将 / 象棋吃掉帅 != 空盘 "
            + "/ 国际象棋 / 板棋（边缘·被吃·角落）/ 开关范围");
}

String result;
try {
    run();
    result = "PASS";
} catch (Throwable t) {
    result = "FAIL: " + t;
}
java.nio.file.Files.writeString(java.nio.file.Path.of("build/win_conditions_result.txt"), result + "\n");
System.out.println(result);
