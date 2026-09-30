// 飞行棋规则自测：用 jshell 直接跑 FlightChessLogic（纯 Java，不碰 MC 类型）
//   "C:\Program Files\Java\jdk-27\bin\jshell.exe" --class-path build/classes/java/main -q tools/check_flight_rules.jsh
// 注意 JDK 版本：项目用 Java 25 编译（类文件 version 69），JDK 21 的 jshell 读不了。
//
// 全部检查必须包在 run() 里：jshell 是逐条语句求值的，散着写的话某条 throw 之后
// 后面的 println 照样会打印，看起来像「全过了」，其实前面早就炸了。
import com.chessboard.game.*;
import com.chessboard.api.*;

int COLS = FlightChessLogic.INSTANCE.cols();
int idx(int r, int c) { return r * COLS + c; }
int occ(int[] p, int r, int c) { return FlightChessLogic.INSTANCE.occupancy(p[idx(r, c)]); }
int cnt(int[] p, int r, int c, int team) { return FlightChessLogic.count(p[idx(r, c)], team); }
int[] fresh() {
    int[] p = new int[FlightChessLogic.INSTANCE.rows() * COLS];
    FlightChessLogic.INSTANCE.initBoard(p);
    return p;
}
void move(int[] p, int r1, int c1, int r2, int c2, boolean peaceful) {
    moveIdx(p, r1, c1, 0, r2, c2, peaceful);   // 默认走叠里的第 0 颗
}
/** 用指定索引走（模拟「重复点同一格」在叠里轮换之后的状态） */
void moveIdx(int[] p, int r1, int c1, int idx, int r2, int c2, boolean peaceful) {
    FlightChessLogic.INSTANCE.onClick(p, -1, -1, r1, c1);   // 第一次点 = 选中
    FlightChessLogic.INSTANCE.onClick(p, new BoardGameLogic.Selection(r1, c1, idx),
            r2, c2, peaceful);                              // 第二次点 = 走第 idx 颗
}

void run() {
    // 1) 机库：每队 2×2 里各 4 架，一格一架
    int[] p = fresh();
    for (int t = 0; t < FlightChessLogic.TEAM_COUNT; t++) {
        int[] corner = FlightChessLogic.hangarCorner(t);
        int n = 0;
        for (int dr = 0; dr < 2; dr++) for (int dc = 0; dc < 2; dc++) n += cnt(p, corner[0] + dr, corner[1] + dc, t);
        if (n != 4) throw new RuntimeException("队 " + t + " 机库应有 4 架，实际 " + n);
        if (occ(p, corner[0], corner[1]) != 1) throw new RuntimeException("队 " + t + " 机库首格应为 1 架");
    }

    // 2) 骰子初始 1；点骰子掷出 1..6
    if (FlightChessLogic.INSTANCE.faceOf(p[FlightChessLogic.DICE_CELL]) != 1) throw new RuntimeException("骰子初始应为 1");
    FlightChessLogic.INSTANCE.onClick(p, BoardGameLogic.Selection.NONE, 7, 7, false);
    int face = FlightChessLogic.INSTANCE.faceOf(p[FlightChessLogic.DICE_CELL]);
    if (face < 1 || face > 6) throw new RuntimeException("掷骰应得 1..6，实际 " + face);

    // 3) 同队堆叠 + 一次只移一架
    p = fresh();
    move(p, 1, 12, 1, 13, false);
    if (occ(p, 1, 12) != 0 || cnt(p, 1, 13, 0) != 2) throw new RuntimeException("同队堆叠失败：源 " + occ(p, 1, 12) + " 目标红 " + cnt(p, 1, 13, 0));
    move(p, 1, 13, 1, 14, false);
    if (cnt(p, 1, 13, 0) != 1 || cnt(p, 1, 14, 0) != 1) throw new RuntimeException("一次应只移一架：剩 " + cnt(p, 1, 13, 0) + " 移到 " + cnt(p, 1, 14, 0));

    // 4) 默认开局：敌机落到我方堆叠上 → 整格敌方全回机库
    p = fresh();
    move(p, 1, 12, 1, 13, false);
    move(p, 12, 12, 1, 13, false);
    if (cnt(p, 1, 13, 0) != 0) throw new RuntimeException("默认开局未吃掉红机");
    if (cnt(p, 1, 13, 2) != 1) throw new RuntimeException("蓝机没留在落点");
    int redHome = 0;
    int[] rc = FlightChessLogic.hangarCorner(0);
    for (int dr = 0; dr < 2; dr++) for (int dc = 0; dc < 2; dc++) redHome += cnt(p, rc[0] + dr, rc[1] + dc, 0);
    // 4 架红机全都该在机库里：2 架一直在机库没动 + 被吃掉的那 2 架被送回来
    if (redHome != 4) throw new RuntimeException("红机应 4 架全回机库，实际 " + redHome);

    // 5) 和平开局：混编共存，谁也不回库
    p = fresh();
    move(p, 1, 12, 1, 13, true);
    move(p, 12, 12, 1, 13, true);
    if (cnt(p, 1, 13, 0) != 2 || cnt(p, 1, 13, 2) != 1) throw new RuntimeException("和平应 2 红 + 1 蓝共存，实际红 " + cnt(p, 1, 13, 0) + " 蓝 " + cnt(p, 1, 13, 2));
    // 和平模式下红机只是在机库内部挪了格，总数应该还是 4（一架都没回家也不该消失）
    int redHome5 = 0;
    for (int dr = 0; dr < 2; dr++) for (int dc = 0; dc < 2; dc++) redHome5 += cnt(p, rc[0] + dr, rc[1] + dc, 0);
    if (redHome5 != 4) throw new RuntimeException("和平开局不该有红机离开机库，实际 " + redHome5);

    // 6) 展开顺序 = 队号升序；从混编堆移走的是最小队的红机
    if (FlightChessLogic.INSTANCE.occupancy(p[idx(1, 13)]) != 3) throw new RuntimeException("混编格应有 3 颗");
    if (FlightChessLogic.INSTANCE.pieceAt(p[idx(1, 13)], 0) != FlightChessLogic.planeValue(0)) throw new RuntimeException("第 0 颗应是红队");
    if (FlightChessLogic.INSTANCE.pieceAt(p[idx(1, 13)], 2) != FlightChessLogic.planeValue(2)) throw new RuntimeException("第 2 颗应是蓝队");
    move(p, 1, 13, 5, 5, true);
    if (cnt(p, 1, 13, 0) != 1 || cnt(p, 1, 13, 2) != 1) throw new RuntimeException("移走应是最小队的红机");

    // 7) 混编格 + 默认模式：只吃敌方，我方留在原地（这条曾经是 bug：整格清空会把我方也送回家）
    p = fresh();
    move(p, 1, 12, 1, 13, true);    // 和平：红进 (1,13)
    move(p, 12, 12, 1, 13, true);   // 和平：蓝也进 (1,13) → 1 红 + 1 蓝
    move(p, 2, 12, 1, 13, false);   // 默认模式：另一架红（还留在机库里的）落上去
    // 原来是 2 架红，落上去的那架自己也留在格子里 → 3 架
    if (cnt(p, 1, 13, 0) != 3) throw new RuntimeException("我方红机应留在原地并叠上（应 3 架），实际 " + cnt(p, 1, 13, 0));
    if (cnt(p, 1, 13, 2) != 0) throw new RuntimeException("敌方蓝机应被吃回机库");

    // 8) 单架仍是老编码 1..4，骰子 11 → 老存档/老导入码零迁移
    int[] s = fresh();
    if (s[idx(1, 12)] != 1) throw new RuntimeException("单架红机应编码为 1，实际 " + s[idx(1, 12)]);
    if (s[idx(12, 12)] != 3) throw new RuntimeException("单架蓝机应编码为 3，实际 " + s[idx(12, 12)]);
    if (s[FlightChessLogic.DICE_CELL] != FlightChessLogic.INSTANCE.pieceForFace(1)) throw new RuntimeException("骰子编码应为 10+1=11");

    // 9) 编解码往返（含混编堆叠）；老 "fx" 码仍可解；乱码被拒且不动棋盘
    p = fresh();
    move(p, 1, 12, 1, 13, true);
    move(p, 12, 12, 1, 13, true);
    String code = FlightChessLogic.INSTANCE.encodePieces(p);
    int[] back = new int[p.length];
    if (!FlightChessLogic.INSTANCE.decodePieces(back, code)) throw new RuntimeException("新格式解码应成功");
    if (!java.util.Arrays.equals(p, back)) throw new RuntimeException("编解码往返不一致");
    int[] old = fresh();
    if (!FlightChessLogic.INSTANCE.decodePieces(old, "fx2110")) throw new RuntimeException("老 fx 格式应仍可解");
    int[] keep = fresh();
    if (FlightChessLogic.INSTANCE.decodePieces(keep, "fxv2ZZZZ")) throw new RuntimeException("乱码不该被接受");
    if (keep[idx(1, 12)] != 1) throw new RuntimeException("解码失败时棋盘必须保持原样");

    // 10) 选中索引：2 红 + 1 蓝的叠里必须能把**蓝机**走出去。
    //     旧实现只会移走队号最小的那一颗，其余阵营永远点不到（用户指出的漏洞）
    p = fresh();
    move(p, 1, 12, 1, 13, true);
    move(p, 12, 12, 1, 13, true);                  // (1,13) = 2 红 + 1 蓝
    if (FlightChessLogic.INSTANCE.occupancy(p[idx(1, 13)]) != 3) throw new RuntimeException("应是 2 红 + 1 蓝共 3 颗");
    if (FlightChessLogic.teamAt(p[idx(1, 13)], 2) != 2) throw new RuntimeException("索引 2 应是蓝队");
    moveIdx(p, 1, 13, 2, 5, 5, true);              // 索引 2 → 走蓝机
    if (cnt(p, 5, 5, 2) != 1) throw new RuntimeException("索引 2 应该把蓝机走出去");
    if (cnt(p, 1, 13, 0) != 2 || cnt(p, 1, 13, 2) != 0) throw new RuntimeException("红机不该被动、蓝机该走了");

    // 11) 移动动画认步（BoardGameLogic#detectMove）：客户端靠它决定「哪一颗从哪飞到哪」。
    //     这里要覆盖的正是渲染器修过的那几种：源格还剩棋子、落点原本非空、吃子引起的多变格。
    BoardGameLogic g11 = FlightChessLogic.INSTANCE;

    p = fresh();
    move(p, 1, 12, 1, 13, true);                   // 源走空 + 落点原本为空：干净一步
    int[] prev11 = p.clone();
    moveIdx(p, 1, 13, 0, 5, 5, true);
    BoardGameLogic.Move mv = BoardGameLogic.detectMove(g11, prev11, p);
    if (mv.isEmpty()) throw new RuntimeException("干净一步应被认出");
    if (mv.fromCell() != idx(1, 13) || mv.toCell() != idx(5, 5)) throw new RuntimeException("干净一步起终点认错");
    if (mv.piece() != FlightChessLogic.planeValue(0)) throw new RuntimeException("干净一步认错了棋子");

    p = fresh();
    move(p, 1, 12, 1, 13, true);
    move(p, 12, 12, 1, 13, true);                  // (1,13) = 2 红 + 1 蓝
    move(p, 2, 12, 4, 4, true);                    // 先把一架红挪走，让 (2,12) 空出来备用
    prev11 = p.clone();
    moveIdx(p, 1, 13, 2, 5, 5, true);              // 从叠里走第 2 颗（蓝）
    mv = BoardGameLogic.detectMove(g11, prev11, p);
    if (mv.isEmpty()) throw new RuntimeException("从叠里拿走一颗必须能认出来（源格还剩 2 红）");
    if (mv.fromCell() != idx(1, 13)) throw new RuntimeException("叠里拿走一颗：源格应仍是 (1,13)");
    if (mv.toCell() != idx(5, 5)) throw new RuntimeException("叠里拿走一颗：落点认错");
    if (mv.piece() != FlightChessLogic.planeValue(2)) throw new RuntimeException("叠里拿走一颗：应认出蓝机，实际 " + mv.piece());

    p = fresh();
    move(p, 1, 12, 1, 13, false);                  // (1,13) = 1 红
    prev11 = p.clone();
    move(p, 2, 12, 1, 13, false);                  // 另一架红落到我方身上 → (1,13) = 2 红
    mv = BoardGameLogic.detectMove(g11, prev11, p);
    if (mv.fromCell() != idx(2, 12) || mv.toCell() != idx(1, 13)) throw new RuntimeException("落点原本有同队棋子：起终点认错");

    p = fresh();
    move(p, 1, 12, 1, 13, true);
    move(p, 12, 12, 1, 13, true);                  // (1,13) = 2 红 + 1 蓝
    prev11 = p.clone();
    move(p, 2, 12, 1, 13, false);                  // 默认模式吃子：蓝机回机库（多个格子同时变）
    mv = BoardGameLogic.detectMove(g11, prev11, p);
    if (mv.fromCell() != idx(2, 12) || mv.toCell() != idx(1, 13)) throw new RuntimeException("吃子：起终点认错");
    if (mv.piece() != FlightChessLogic.planeValue(0)) throw new RuntimeException("吃子：应认出红机");
    // 翻面/升变那类「原地换值」不该冒出移动动画
    int[] same = fresh();
    if (!BoardGameLogic.detectMove(g11, same, same.clone()).isEmpty()) throw new RuntimeException("棋盘没变时不该认出移动");

    System.out.println("\n全部通过: 机库/骰子/堆叠/一次一架/默认吃子/和平混编/混编下只吃敌方/老编码/编解码/叠内换人/动画认步");
}

// 结果写到文件里：Windows 控制台的编码会把中文输出搅成乱码，读文件最稳
String result;
try {
    run();
    result = "PASS";
} catch (Throwable t) {
    result = "FAIL: " + t;
}
java.nio.file.Files.writeString(java.nio.file.Path.of("build/rules_test_result.txt"), result + "\n", java.nio.charset.StandardCharsets.UTF_8);
System.out.println(result);

