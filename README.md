# Chessboard Mod

Minecraft 棋盘游戏模组，支持四种棋类：中国象棋、国际象棋、五子棋、井字棋。

## 功能

- **四种棋盘** — 中国象棋（10×9）、国际象棋（8×8）、五子棋（15×15）、井字棋（3×3）
- **完整交互** — 右键点击棋盘格点下棋，选中棋子有抬起动画，走棋有移动动画
- **立体棋子** — 国际象棋六种棋子（王/后/象/马/车/兵）均使用 Blockbench 3D 模型，白方浅色木（白桦）、黑方深色木（深色橡木）
- **中文棋子** — 中国象棋棋子渲染中文汉字（帅/将、仕/士 等），支持红黑双色
- **暗棋模式** — 中国象棋支持暗棋开局与全暗棋开局，点击暗棋棋子有翻面动画后揭示
- **随机开局** — 五子棋可在随机位置生成 3~10 个灰色障碍棋子
- **管理界面** — 默认 SHIFT+右键打开，支持悔棋、重置、导入/导出棋局码、特殊模式开局
- **右键侧面开菜单** — 可选开启：右键棋盘侧面直接打开管理界面，上下面仍正常落子
- **指令系统** — 支持 `/chessboard` 系列指令远程操作
- **NBT 持久化** — 挖掘棋盘保留棋局状态，带数据掉落

## 棋盘种类

| 棋盘 | 尺寸 | 交互模式 | 特殊模式 |
|------|------|----------|----------|
| 中国象棋 | 10×9 | 选子→走子 | 暗棋开局、全暗棋开局 |
| 国际象棋 | 8×8 | 选子→走子 | — |
| 五子棋 | 15×15 | 轮流放置 | 随机开局（3~10 个灰子障碍） |
| 井字棋 | 3×3 | 轮流放置 | — |

## 操作

| 操作 | 方式 |
|------|------|
| 下棋 | 空手右键点击棋盘格点 |
| 打开管理界面 | 默认 SHIFT + 右键（可在按键设置中更改） |
| 右键侧面打开菜单 | 管理界面内「右键侧面打开菜单」开关，或改配置文件 `chessboard-client.toml` 的 `rightClickOpensMenu` |
| 管理界面 | 悔棋、重置、导入/导出棋局码、特殊模式开局 |

## 指令

```
/chessboard click        <x> <y> <z> <row> <col>  — 模拟点击
/chessboard undo         <x> <y> <z>              — 悔棋
/chessboard reset        <x> <y> <z>              — 重置棋盘
/chessboard import       <x> <y> <z> <code>       — 导入棋局码
/chessboard darkstart    <x> <y> <z>              — 暗棋开局（中国象棋）
/chessboard fulldarkstart <x> <y> <z>             — 全暗棋开局（中国象棋）
/chessboard randomstart  <x> <y> <z>              — 随机开局（五子棋）
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
├── ChessboardClient.java           # 客户端入口（渲染器注册、按键绑定）
├── Config.java                     # 客户端配置文件
├── block/
│   └── ChessboardBlock.java        # 棋盘方块（右键交互、朝向、挖掘掉落）
├── blockentity/
│   └── ChessboardBlockEntity.java  # 方块实体（棋局状态、NBT 持久化、网络同步、特殊模式）
├── client/
│   ├── renderer/
│   │   └── ChessboardRenderer.java # 通用棋子渲染器（方块模型 + 文字 + 动画 + 翻面动画）
│   └── screen/
│       └── ChessboardScreen.java   # 棋盘管理 GUI（悔棋/重置/导入导出/特殊模式/开关）
├── game/
│   ├── BoardGameLogic.java         # 游戏规则接口（点击交互、编解码、渲染参数）
│   ├── ChineseChessLogic.java      # 中国象棋规则（含暗棋）
│   ├── ChessLogic.java             # 国际象棋规则
│   ├── GomokuLogic.java            # 五子棋规则（含随机开局灰子）
│   └── TicTacToeLogic.java         # 井字棋规则
└── mixin/
    └── MultiPlayerGameModeMixin.java # 拦截右键交互（菜单键/侧面开菜单）
```
