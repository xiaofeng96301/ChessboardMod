package com.chessboard.game;

/**
 * 落子类游戏（五子棋/井字棋）：点击空格放置棋子，双方轮流。
 */
public interface PlaceGameLogic extends BoardGameLogic {

    /** 当前应下的棋子（黑/先手 或 白/后手） */
    int nextStone();

    /** 下完一步后切换下子方 */
    void toggleSide();

    @Override
    default ClickResult onClick(int[] pieces, int selRow, int selCol, int clickRow, int clickCol) {
        if (pieces[idx(clickRow, clickCol)] != 0) return new ClickResult.None();
        pieces[idx(clickRow, clickCol)] = nextStone();
        toggleSide();
        return new ClickResult.Place(clickRow, clickCol);
    }
}
