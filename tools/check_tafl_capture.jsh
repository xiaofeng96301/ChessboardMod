// 板棋夹击吃子自测：全部走 onClick，跟玩家点出来的是同一条路径（capture 是私有的，不直接调）。
// 跑法： "C:\Program Files\Java\jdk-27\bin\jshell.exe" --class-path build/classes/java/main -q tools/check_tafl_capture.jsh
// 结果看 build/tafl_capture_result.txt
// 全部断言包在 run() 里：jshell 逐条求值，散着写的话炸了也照样打印后面的「通过」。
import com.chessboard.game.*;
import com.chessboard.api.*;
import com.chessboard.api.BoardGameLogic.ClickResult;   // 通配符 import 带不进嵌套类型，得单独写

// 走一步：先点起点选中，再点落点。返回落点那一下的结果
ClickResult step(TaflLogic g, int[] p, int fr, int fc, int tr, int tc) {
    ClickResult sel = g.onClick(p, -1, -1, fr, fc);
    if (!(sel instanceof ClickResult.Select))
        throw new RuntimeException("选不中 (" + fr + "," + fc + ") → " + sel);
    return g.onClick(p, fr, fc, tr, tc);
}

void want(String what, boolean ok) {
    if (!ok) throw new RuntimeException("断言失败: " + what);
    System.out.println("  ok  " + what);
}

void run() {
    TaflLogic g = TaflLogic.NINE;          // 9×9
    int n = g.rows(), mid = n / 2;
    int[] p = new int[n * n];

    // 1) 横向夹击：护王兵在 (mid,2)，把 (mid,5) 那颗走到 (mid,4)，夹掉中间的捉王兵 (mid,3)
    p[g.idx(mid, 2)] = TaflLogic.SWEDE;
    p[g.idx(mid, 5)] = TaflLogic.SWEDE;
    p[g.idx(mid, 3)] = TaflLogic.MUSCOVITE;
    ClickResult r = step(g, p, mid, 5, mid, 4);
    want("走子返回 Move", r instanceof ClickResult.Move);
    want("棋子落在 (mid,4)、起点空出来",
            p[g.idx(mid, 4)] == TaflLogic.SWEDE && p[g.idx(mid, 5)] == 0);
    want("被夹在中间的敌子没了", p[g.idx(mid, 3)] == 0);

    // 2) 纵向也吃（换个轴，确认不是只查了横的）
    java.util.Arrays.fill(p, 0);
    p[g.idx(0, mid)] = TaflLogic.SWEDE;     // 走过去的那颗
    p[g.idx(3, mid)] = TaflLogic.SWEDE;     // 另一侧的靠背
    p[g.idx(2, mid)] = TaflLogic.MUSCOVITE;
    step(g, p, 0, mid, 1, mid);
    want("竖向夹击吃子", p[g.idx(2, mid)] == 0);

    // 3) 另一侧没有己方棋子 → 不是夹击，不吃
    java.util.Arrays.fill(p, 0);
    p[g.idx(mid, 4)] = TaflLogic.SWEDE;
    p[g.idx(mid, 7)] = TaflLogic.MUSCOVITE;
    step(g, p, mid, 4, mid, 6);
    want("另一侧空着就不吃", p[g.idx(mid, 7)] == TaflLogic.MUSCOVITE);

    // 4) 另一侧也是敌子 → 靠背必须是自己人，所以照样不吃
    java.util.Arrays.fill(p, 0);
    p[g.idx(mid, 4)] = TaflLogic.SWEDE;
    p[g.idx(mid, 7)] = TaflLogic.MUSCOVITE;
    p[g.idx(mid, 8)] = TaflLogic.MUSCOVITE;
    step(g, p, mid, 4, mid, 6);
    want("靠背是敌子就不吃", p[g.idx(mid, 7)] == TaflLogic.MUSCOVITE);

    // 5) 目标贴着棋盘边：另一侧越界，夹不住
    java.util.Arrays.fill(p, 0);
    p[g.idx(mid, 0)] = TaflLogic.MUSCOVITE;
    p[g.idx(mid, 3)] = TaflLogic.SWEDE;
    step(g, p, mid, 3, mid, 1);
    want("贴着边界夹不住", p[g.idx(mid, 0)] == TaflLogic.MUSCOVITE);

    // 6) 一次落子夹两颗（横一颗、竖一颗）
    java.util.Arrays.fill(p, 0);
    p[g.idx(mid, 5)] = TaflLogic.SWEDE;        // 走过去的那颗
    p[g.idx(mid, 2)] = TaflLogic.SWEDE;        // 横向的靠背
    p[g.idx(mid - 2, mid)] = TaflLogic.SWEDE;  // 竖向的靠背
    p[g.idx(mid, 3)] = TaflLogic.MUSCOVITE;    // 落点左边那颗
    p[g.idx(mid - 1, mid)] = TaflLogic.MUSCOVITE; // 落点上边那颗
    step(g, p, mid, 5, mid, 4);
    want("一子落下夹掉两颗",
            p[g.idx(mid, 3)] == 0 && p[g.idx(mid - 1, mid)] == 0);

    // 7) 抬着棋子点敌子 → 什么都不发生（板棋没有「踩上去吃掉」）
    java.util.Arrays.fill(p, 0);
    p[g.idx(mid, 4)] = TaflLogic.SWEDE;
    p[g.idx(mid, 5)] = TaflLogic.MUSCOVITE;
    int[] before = p.clone();
    ClickResult bad = g.onClick(p, mid, 4, mid, 5);
    want("点敌子返回 None", bad instanceof ClickResult.None);
    want("棋盘一个格子都没动", java.util.Arrays.equals(before, p));

    // 8) 王目前按普通棋子吃（真实规则要四面围死，这里没做，先把现状钉住）
    java.util.Arrays.fill(p, 0);
    p[g.idx(mid, 5)] = TaflLogic.MUSCOVITE;
    p[g.idx(mid, 2)] = TaflLogic.MUSCOVITE;
    p[g.idx(mid, 3)] = TaflLogic.KING;
    step(g, p, mid, 5, mid, 4);
    want("王现在也会被两颗夹掉（未做四面围王）", p[g.idx(mid, 3)] == 0);

    // 9) 真开局的盘面上走一步：王挪到空地，双方子数都不该变
    java.util.Arrays.fill(p, 0);
    g.initBoard(p, TaflLogic.Variant.TABLUT);
    int sw = 0, mu = 0;
    for (int v : p) { if (v == TaflLogic.SWEDE) sw++; else if (v == TaflLogic.MUSCOVITE) mu++; }
    step(g, p, mid, mid, mid + 2, mid + 2);
    int sw2 = 0, mu2 = 0;
    for (int v : p) { if (v == TaflLogic.SWEDE) sw2++; else if (v == TaflLogic.MUSCOVITE) mu2++; }
    want("开局走王：王到了新格、没人被误吃",
            p[g.idx(mid + 2, mid + 2)] == TaflLogic.KING
                    && p[g.idx(mid, mid)] == 0 && sw == sw2 && mu == mu2);

    System.out.println("全部通过: 夹击吃子 / 不吃自己人 / 边界夹不住 / 点敌子无效 / 王暂按普通子");
}

String result;
try {
    run();
    result = "PASS";
} catch (Throwable t) {
    result = "FAIL: " + t;
}
java.nio.file.Files.writeString(java.nio.file.Path.of("build/tafl_capture_result.txt"), result + "\n");
System.out.println(result);
