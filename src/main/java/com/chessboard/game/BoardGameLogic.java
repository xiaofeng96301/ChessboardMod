package com.chessboard.game;

/**
 * 棋盘游戏规则接口。
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

    /** 棋子模型资源路径（根据棋子值），用于渲染时加载方块模型 */
    String pieceModelPath(int piece);

    /** 棋子缩放比例（默认 0.25） */
    default float pieceScale() { return 0.25f; }

    /** 棋子是否需要绕 X 轴翻转 180°（正面→反面），井字棋 X 方用 */
    default boolean pieceFlipX(int piece) { return false; }

    /** 棋子额外绕 Y 轴旋转角度（度），用于调整朝向 */
    default float pieceYRotation(int piece) { return 0; }

    /** 模型中心 X 偏移（像素/16），默认 2.5 */
    default float pieceCenterX() { return 2.5f; }
    /** 模型中心 Z 偏移（像素/16），默认 2.5 */
    default float pieceCenterZ() { return 2.5f; }

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

    /** 解码：每 3 字符一组（值hex + 行b36 + 列b36） */
    default void decodePieces(int[] pieces, String code) {
        java.util.Arrays.fill(pieces, 0);
        String prefix = codePrefix();
        if (!code.startsWith(prefix)) return;
        String data = code.substring(prefix.length());
        for (int i = 0; i + 3 <= data.length(); i += 3) {
            try {
                int piece = Integer.parseInt(data.substring(i, i + 1), 16);
                int row = Integer.parseInt(data.substring(i + 1, i + 2), 36);
                int col = Integer.parseInt(data.substring(i + 2, i + 3), 36);
                if (row >= 0 && row < rows() && col >= 0 && col < cols())
                    pieces[row * cols() + col] = piece;
            } catch (NumberFormatException ignored) {}
        }
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

    // ── 结果类型 ──

    sealed interface ClickResult permits ClickResult.None, ClickResult.Select, ClickResult.Move, ClickResult.Place, ClickResult.Reset, ClickResult.Flip {
        /** 无效点击，没有任何变化 */
        record None() implements ClickResult {}

        /** 选中了 (row, col) 处的棋子 */
        record Select(int row, int col) implements ClickResult {}

        /** 棋子从 (fromRow, fromCol) 移动到 (toRow, toCol)，覆盖了 captured 棋子 */
        record Move(int fromRow, int fromCol, int toRow, int toCol) implements ClickResult {}

        /** 在 (row, col) 放置了一颗新棋子 */
        record Place(int row, int col) implements ClickResult {}

        /** 请求重置棋盘 */
        record Reset() implements ClickResult {}

        /** 翻开 (row, col) 处的暗棋 */
        record Flip(int row, int col) implements ClickResult {}
    }
}
