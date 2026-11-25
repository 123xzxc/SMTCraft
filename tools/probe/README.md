# 探针（tools/probe）

给「改脚本 / 改快照解析 / 改间隔调度 / 改封面归属」之后做回归用的一次性程序。它们**不进模组运行时**，
也不参与 `gradle build`：放在这里只是为了让它们进版本控制，`gradle clean` 不会把它们扫掉。

| 探针 | 用途 | 判定 |
| --- | --- | --- |
| `BridgeProbe` | 起一遍真实的媒体桥，逐秒打印快照 | 有歌时有标题/进度/封面字节数；Windows 上还看得到 `platform`/`tool` |
| `IntervalProbe` | 每 tick 一次 `setConfig` 不许重启适配器 | 日志里 `bridge adapter started` 稳态 1 次、拖 30 格后再 1 次（共 2 次） |
| `CoverProbe` | 封面归属：空窗与同曲目回来不许清封面，换曲目才清 | 四步依次 `8B / 8B / 8B / 0B` |

## 前置

先构建出类目录（探针要 `build/classes/main`）：

```bash
gradle build          # 或只 `gradle classes`，但 release 前照旧跑 build
```

Gson 供 `SmtcConfig` 用，路径在 Gradle 缓存里（版本与 1.8.9 自带的一致）：

```bash
GSON=toolchain/ghome/caches/modules-2/files-2.1/com.google.code.gson/gson/2.2.4/*/gson-2.2.4.jar
```

## BridgeProbe / IntervalProbe（在 `org.yinyun.smtcraft.media` 包里）

```bash
mkdir -p build/probe
javac -encoding UTF-8 -cp "build/classes/main;build/resources/main;$GSON" \
      -d build/probe tools/probe/BridgeProbe.java tools/probe/IntervalProbe.java
java -cp "build/classes/main;build/resources/main;build/probe;$GSON" \
     org.yinyun.smtcraft.media.BridgeProbe
java -cp "build/classes/main;build/resources/main;build/probe;$GSON" \
     org.yinyun.smtcraft.media.IntervalProbe
```

`IntervalProbe` 的临时配置放 `build/probe/config`（构建产物目录，不入库）。想看
`bridge adapter started` 的计数就在这之后查日志，或把输出重定向到文件再 grep。

## CoverProbe（默认包，只调纯解析路径）

```bash
javac -encoding UTF-8 -cp "build/classes/main;build/resources/main;$GSON" \
      -d build/probe tools/probe/CoverProbe.java
java -cp "build/classes/main;build/resources/main;build/probe;$GSON" CoverProbe
```

## 注意

- 全部要求 **JDK 8**（与模组同一运行期），并且工作目录是**仓库根目录**——探针里的相对路径按根目录写
- `BridgeProbe` 会真的拉起 `powershell.exe` / `playerctl`；跑完自己退出，不会留进程
- 这些程序不是测试框架：没有断言，靠读输出判断（`CoverProbe` 的四步输出已经很直白）
