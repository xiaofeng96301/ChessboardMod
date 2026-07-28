# Chessboard Mod

Minecraft 棋盘游戏模组，支持三种棋类：中国象棋、五子棋、井字棋。

## 功能

- **三种棋盘** — 中国象棋（10×9，选子走子）、五子棋（15×15，轮流放置）、井字棋（3×3，轮流放置）
- **完整交互** — 右键点击棋盘格点下棋，选中棋子有抬起动画，走棋有移动动画
- **中文棋子** — 中国象棋棋子渲染中文汉字（帅/将、仕/士 等），支持红黑双色
- **管理界面** — 默认 SHIFT+右键打开，支持悔棋、重置、导入/导出棋局码
- **指令系统** — 支持 `/chessboard click/undo/reset/import` 远程操作
- **NBT 持久化** — 挖掘棋盘保留棋局状态，带数据掉落

## 棋盘种类

| 棋盘 | 尺寸 | 交互模式 | 说明 |
|------|------|----------|------|
| 中国象棋 | 10×9 | 选子→走子 | 完整标准开局，红方先手 |
| 五子棋 | 15×15 | 轮流放置 | 黑方先手，白方后手 |
| 井字棋 | 3×3 | 轮流放置 | O 方先手，X 方后手 |

## 操作

| 操作 | 方式 |
|------|------|
| 下棋 | 空手右键点击棋盘格点 |
| 打开管理界面 | 默认 SHIFT + 右键（可在按键设置中更改） |
| 管理界面 | 悔棋、重置、导入/导出棋局码 |

## 指令

```
/chessboard click  <x> <y> <z> <row> <col>  — 模拟点击
/chessboard undo   <x> <y> <z>               — 悔棋
/chessboard reset  <x> <y> <z>               — 重置棋盘
/chessboard import <x> <y> <z> <code>        — 导入棋局码
```

## 开发

- **Minecraft** 26.1.2
- **NeoForge** 26.1.2.76
- **Java** 25
- **Gradle** 9.x

```bash
./gradlew runClient   # 启动客户端
./gradlew runServer   # 启动服务端
./gradlew build       # 构建
```

## 项目结构

```
src/main/java/com/chessboard/
├── ChessboardMod.java              # 主类（方块/物品注册、指令）
├── ChessboardClient.java           # 客户端入口（渲染器注册、按键绑定、管理界面）
├── Config.java                     # 配置文件
├── block/
│   ├── ChessboardBlock.java        # 棋盘方块（右键交互、朝向、挖掘掉落）
│   └── EditiorBlock.java           # 编辑器方块
├── blockentity/
│   └── ChessboardBlockEntity.java  # 方块实体（棋局状态、NBT 持久化、网络同步）
├── client/
│   ├── renderer/
│   │   └── ChessboardRenderer.java # 通用棋子渲染器（方块模型 + 文字 + 动画）
│   └── screen/
│       └── ChessboardScreen.java   # 棋盘管理 GUI
└── game/
    ├── BoardGameLogic.java         # 游戏规则接口（点击交互、编解码、渲染参数）
    ├── ChineseChessLogic.java      # 中国象棋规则
    ├── GomokuLogic.java            # 五子棋规则
    └── TicTacToeLogic.java         # 井字棋规则
```
