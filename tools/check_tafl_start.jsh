// 板棋家族开局自测：三块棋盘 × 四种变体（纯 Java，不用开游戏）
//   "C:\Program Files\Java\jdk-27\bin\jshell.exe" --class-path build/classes/java/main -q tools/check_tafl_start.jsh
// 全部断言包在 run() 里：jshell 逐条求值，散着写的话炸了也照样打印后面的「通过」。
import com.chessboard.game.*;
import com.chessboard.api.*;

String board(TaflLogic g, TaflLogic.Variant v) {
    int n = g.rows();
    int[] p = new int[n * n];
    g.initBoard(p, v);
    StringBuilder sb = new StringBuilder();
    for (int r = 0; r < n; r++) {
        for (int c = 0; c < n; c++) {
            int x = p[r * n + c];
            sb.append(x == TaflLogic.KING ? 'K' : x == TaflLogic.SWEDE ? 'D'
                    : x == TaflLogic.MUSCOVITE ? 'A' : '.');
        }
        if (r < n - 1) sb.append('\n');
    }
    return sb.toString();
}

int[] counts(TaflLogic g, TaflLogic.Variant v) {
    int n = g.rows();
    int[] p = new int[n * n];
    g.initBoard(p, v);
    int[] out = new int[3];   // 王 / 护王兵 / 捉王兵
    for (int x : p) {
        if (x == TaflLogic.KING) out[0]++;
        else if (x == TaflLogic.SWEDE) out[1]++;
        else if (x == TaflLogic.MUSCOVITE) out[2]++;
        else if (x != 0) throw new RuntimeException("未知棋子值 " + x);
    }
    return out;
}

/** 上下、左右、中心对称都要成立 —— 这几套摆法本来就该是四边对称的 */
void checkSymmetric(String b) {
    String[] rows = b.split("\n");
    int n = rows.length;
    for (int r = 0; r < n; r++) {
        if (!rows[r].equals(new StringBuilder(rows[r]).reverse().toString()))
            throw new RuntimeException("第 " + (n - r) + " 行左右不对称: " + rows[r]);
        if (!rows[r].equals(rows[n - 1 - r]))
            throw new RuntimeException("第 " + (n - r) + " 行与镜像行不对称: " + rows[r]);
    }
}

void check(TaflLogic g, TaflLogic.Variant v, int king, int swede, int muscovite) {
    int[] c = counts(g, v);
    if (c[0] != king || c[1] != swede || c[2] != muscovite)
        throw new RuntimeException(v + " 棋子数不对：王 " + c[0] + " 护王兵 " + c[1] + " 捉王兵 " + c[2]
                + "（应为 " + king + "/" + swede + "/" + muscovite + "）");
    int n = g.rows();
    int mid = n / 2;
    int[] p = new int[n * n];
    g.initBoard(p, v);
    if (p[mid * n + mid] != TaflLogic.KING) throw new RuntimeException(v + " 国王不在正中");
    System.out.println("--- " + v.label + "（" + n + "×" + n + "）王 " + c[0] + " 护王兵 " + c[1]
            + " 捉王兵 " + c[2]);
    System.out.println(board(g, v));
    checkSymmetric(board(g, v));
}

void run() {
    check(TaflLogic.NINE, TaflLogic.Variant.TABLUT, 1, 8, 16);
    check(TaflLogic.SMALL, TaflLogic.Variant.BRANDUBH, 1, 4, 8);   // 7×7 默认开局
    check(TaflLogic.SMALL, TaflLogic.Variant.ARD_RI, 1, 8, 16);
    check(TaflLogic.LARGE, TaflLogic.Variant.HNEFATAFL, 1, 12, 24); // 11×11 默认开局

    // 萨米板棋 9×9 与用户给的表逐格比对
    String tablut = board(TaflLogic.NINE, TaflLogic.Variant.TABLUT);
    String[] want = {"...AAA...", "....A....", "....D....", "A...D...A", "AADDKDDAA",
                     "A...D...A", "....D....", "....A....", "...AAA..."};
    String[] got = tablut.split("\n");
    for (int r = 0; r < 9; r++) {
        if (!got[r].equals(want[r]))
            throw new RuntimeException("萨米板棋第 " + (9 - r) + " 行应为 " + want[r] + "，实际 " + got[r]);
    }
    // 默认开局（不带变体的 initBoard）= 该尺寸列出的第一个变体
    int[] def = new int[49];
    TaflLogic.SMALL.initBoard(def);
    int[] brandubh = new int[49];
    TaflLogic.SMALL.initBoard(brandubh, TaflLogic.Variant.BRANDUBH);
    if (!java.util.Arrays.equals(def, brandubh))
        throw new RuntimeException("7×7 的默认开局应等于第一个变体（爱尔兰板棋）");
    System.out.println("全部通过: 四种变体的棋子数与居中王 / 四向对称 / 萨米板棋逐格");
}

String result;
try {
    run();
    result = "PASS";
} catch (Throwable t) {
    result = "FAIL: " + t;
}
java.nio.file.Files.writeString(java.nio.file.Path.of("build/tafl_test_result.txt"), result + "\n");
System.out.println(result);
