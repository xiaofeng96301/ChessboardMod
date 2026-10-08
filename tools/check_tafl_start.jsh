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
    // 格子参数钉死 = 三张手绘贴图上量到的格心位置（贴图像素，见 TaflLogic 顶部注释）。
    // 贴图重画过就得重新量，并把这里的期望值一起改掉 —— 免得「参数」和「贴图」偷偷对不上，
    // 表现就是棋子渐渐偏出格子（以前用五子棋那套 1/14 时整圈边框都没了）。
    float[][] measured = {{7f, 16f, 112f}, {9f, 15.5f, 111.5f}, {11f, 13.5f, 113.5f}};
    TaflLogic[] boards = {TaflLogic.SMALL, TaflLogic.NINE, TaflLogic.LARGE};
    for (int i = 0; i < boards.length; i++) {
        TaflLogic g = boards[i];
        int n = g.rows();
        float first = measured[i][1], last = measured[i][2];
        if (Math.abs(g.gridOffsetX() - first / 8f) > 1e-4f
                || Math.abs(g.gridOffsetX() + g.gridSpan() - last / 8f) > 1e-4f)
            throw new RuntimeException(n + "×" + n + " 格心应为 " + first + ".." + last + " 像素，实际 "
                    + 8f * g.gridOffsetX() + ".." + 8f * (g.gridOffsetX() + g.gridSpan()));
        // 格子整体要居中（贴图是手工画的，允许 1 像素的取整误差）
        if (Math.abs(8f * (g.rowPixel(0) + g.rowPixel(n - 1)) - 128f) > 1f)
            throw new RuntimeException(n + "×" + n + " 格心没有上下居中");
        // 无框变体：无框模型把贴图 3..125 像素铺在 0.5..15.5 的面上，
        // 换算后落到贴图的像素位置必须和带框时一模一样（棋子和格子仍然对得上）
        TaflLogic fl = g.frameless();
        for (float[] pair : new float[][]{{g.gridOffsetX(), fl.gridOffsetX()},
                                          {g.gridOffsetX() + g.gridSpan(), fl.gridOffsetX() + fl.gridSpan()}}) {
            float withFrame = 8f * pair[0];
            float noFrame = 3f + (pair[1] - 0.5f) * 122f / 15f;
            if (Math.abs(withFrame - noFrame) > 0.05f)
                throw new RuntimeException(n + "×" + n + " 无框变体落到贴图 " + noFrame
                        + " 像素，带框是 " + withFrame);
        }
    }

    System.out.println("全部通过: 四种变体的棋子数与居中王 / 四向对称 / 萨米板棋逐格 / 格心位置与无框换算");
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
