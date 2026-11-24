# AGENTS.md

给在本仓库里工作的自动化代理（以及未来的自己）的约定。人类贡献者同样适用。

## 这是什么

Minecraft **1.7.10 / Forge 10.13.4.1614** 客户端模组：把系统当前播放的曲目与同步歌词画进 HUD。
曲目来自 Windows SMTC 或 Linux MPRIS2，**1.7.10 上没有 `java.lang.foreign`，也没有
`net.minecraft.client.*` 之外的 FFI**，所以两条平台都用`外部进程 + 每行一个 JSON`读取
（`media/ProcessBridge`：PowerShell 调 WinRT / `playerctl` 或 `gdbus`）。歌词来自酷狗 / 网易云 /
QQ 音乐的公开接口。**没有服务端组件，没有账号，没有密钥。**

- 语言级别：**Java 8**（游戏运行期的上限）。不要用 `var`、`record`、`switch` 表达式、
  `List.of`、`String.isBlank`、`readString`、文本块、模式匹配 `instanceof`
- 依赖：只有 Gson 与 Minecraft / Forge 本体。**不要新增第三方库**
- 包根：`org.yinyun.smtcraft`
- 类名/方法名用 **MCP 名**（`Minecraft.getMinecraft()`、`GuiScreen`、`FontRenderer`、
  `RenderGameOverlayEvent`），绘制走 `render/Canvas` 封的 GL11 + Tessellator

## 构建

```bash
./gradlew build         # 产物 build/libs/smtcraft-<version>.jar
./gradlew runClient     # 开发期直接起游戏
```

- 必须 **JDK 8**：ForgeGradle 1.2 只跑 Gradle 2.x，且要下载 MCP 映射反混淆 1.7.10
- Gradle 版本由 wrapper 钉死（`gradle/wrapper/gradle-wrapper.properties` 里的 2.14.1），
  不要手改这一段，也不要用本机更高版本的 `gradle` 命令，那会直接报错
- 版本号只写在 `gradle.properties`，构建时展开进 `@Mod(version)` 与 `mcmod.info`
- 输出目录被「受控文件夹访问」挡住时用 `-PbuildDirOverride=<path>`
- ForgeGradle 1.2 的 Minecraft 下载地址（`s3.amazonaws.com/Minecraft.Download`）已经失效，
  `build.gradle` 在配置阶段直接从 launchermeta / launcher.mojang.com 取版本 json、客户端 jar、
  服务端 jar 放进 `caches/minecraft`，并禁用 `downloadClient` / `downloadServer` / `getVersionJson`
- 官方仓库已经不发布 `net.minecraftforge:forge` 的 pom（只剩 `-userdev` / `-universal` 两个 jar），
  `build.gradle` 会在配置阶段往 `.gradle/forge-repo/` 里写一份最小 pom 并下载那两个 jar；
  这段逻辑属于构建脚手架，改动它要连 `toolchain/m2` 的离线回退一起验证
- 上游 Fabric 26.x 的 `build.sh` / `test.sh` / `SelfTest` / `ShimProbe` 没有搬过来：
  它们断言的是 26.1↔26.4 的签名差异，1.7.10 只有一个目标版本

## 改动纪律

1. **先读后写。** 不要「整文件读回 → 整文件写回」地改大文件（`SettingsScreen.java` 1486 行、
   `LyricService.java` 1206 行、`ProcessBridge.java` 1009 行）；用定点替换，一次一处。
2. **不要动 `gradle.properties` 的版本号**，除非在准备发布。
3. 预算与超时常量集中在 `util/Budgets.java`，关停步骤顺序在 `util/ShutdownPlan.java`。
   新增一个有超时的阶段，就把它加进预算表，并让下游步骤的预算从剩余时间里取。
4. 日志里的异常一律用 `util/Errors.describe(t)`，不要写 `t.toString()`。
4b. 平台差异只能留在 `media/ProcessBridge` 的内嵌脚本里。**`.ps1` 落盘必须带 UTF-8 BOM**
    （PowerShell 5.1 按 ANSI 代码页读无 BOM 脚本，中文注释会让整份脚本语法崩），`sh` 脚本必须不带
    （BOM 会让 `#!` 失效）。改脚本后先用独立探针（不经游戏、直接轮询 `MediaBridge`）验证，别靠进游戏试。
4c. 1.7.10 的默认字体页只有 ASCII：中日韩字形依赖 `gameSettings.forceUnicodeFont`。
    首次 tick 由 `ClientProxy#ensureUnicodeFont` 按配置项 `autoUnicodeFont` 打开，改动这段要连带说明
    为什么必须 `refreshResources()`（只改字段不会重建 FontRenderer 的字形页）。
4d. **画完必须还原 GL 状态**。1.7.10 的 `FontRenderer` 只 `bindTexture`，自己既不 `glEnable(GL_TEXTURE_2D)`
    也不开 alpha test，所以 `render/Canvas` 的 `fill` / `blit` 收尾要把贴图开关打开回去，`Text.draw`
    每次绘制前重新 `glEnable` 贴图、alpha test、blend。漏一处，全屏文字就变成实心白方块（英文也一样）。
4f. 封面只能从媒体属性上取：`Properties.Thumbnail` 是 `RandomAccessStreamReference`，要再
    `OpenReadAsync()` 才拿到流；返回的 `__ComObject` 上没有可用的 `Size`，读流要用反射拿
    `System.IO.WindowsRuntimeStreamExtensions.AsStreamForRead` 的单参重载。改完同样先跑探针。
    体积上限卡的是 **base64 长度**（512 KB ≈ 384 KB 图），脚本里不要拿原始字节数去比同一个常量；
    空封面要重试（500 ms × 40），但判定超限后要立刻停手，别每 500 ms 反复解同一张大图。
    封面字节也只在 `artHash` 变化的那一条快照里发：150 ms 一轮重发 100 KB base64 会白烧 IO，
    换歌时重置发送记录（同一张专辑封面换曲也要重发一次，Java 侧按哈希缓存贴图）。
4g. `ClientProxy` 每个 tick 都会调 `bridge().setConfig(config)`，那是「读一下配置」，不是「配置变了」。
    在 `reconfigure()` 里做任何重置（`gaveUp` / `failures` / `restartAt`）之前，先比较
    `intervalOf(config)` 与上次已知值，并且**进程没在跑时不要动退避**：否则适配器起不来会变成
    每 tick 拉一次进程、刷屏告警，5 次上限与 5 秒退避全部失效。间隔真变了也要静置
    `INTERVAL_SETTLE_MS` 再 `destroy`，拖滑杆只该重启一次。改动后用探针数日志里
    "bridge adapter started" 出现了几次（`IntervalProbe`：稳态 2.5 s + 连续拖 30 格，应为 2 次）。
5. 每个成员上方最多一个 javadoc 块。注释解释**为什么**，不是复述代码；审计编号
   （如 `AUDIT-71`）可以写进注释，指向当时的条目。
6. 网络、磁盘、渲染互不阻塞：IO 走 `util/Async`，结果通过 `ClientProxy` 的客户端队列
   回到主线程；渲染线程只读不可变快照。
7. 歌词的横向度量只能有一个来源：有逐字时间的行走 `render/LyricsRenderer` 的字形跟随，
   没有的行走 `render/LyricScroll` 的滚动目标。不要在同一行上叠两套。
8. 新增界面文案要同时进 `assets/smtcraft/lang/en_US.lang` 与 `zh_CN.lang`（`key=value`）；
   漏了不会崩，但会直接显示键名。
9. 本仓库只收 1.7.10 Forge 版源码：上游 Fabric 版在 <https://github.com/123xzxc/SMTCraft>，
   不要把它的副本、本机工具链、探针脚本、检查报告、日志一起提交进来。

## 这个移植版特有的坑

- `gui/ScaledScreen` 的钩子拿到的坐标是**设计像素**（已除以 `ui`），不要再用
  `ScaledResolution` 换算第二次
- `render/Canvas` 的 `scissor` 吃设计坐标，内部换算成物理像素；用完必须 `scissorOff()`
- 换系统字体在 1.7.10 无法生效（没有 font provider）：`font/FontPack` 只记录选择，
  设置页里如实说明
- 进程桥的脚本第一次运行会写到 `config/smtcraft/bridge/`；改脚本等于改行为，
  不要把它当成构建产物

## 提交前

```bash
./gradlew build
git status --short          # 确认没有构建产物、配置文件、本机路径混进来
```

- `build/`、`.gradle/`、`run/`、`config/`、`logs/` 一律不入库
- 提交信息写清「改了什么 + 为什么」；一次提交只做一件事
- 不要把审计报告、调试脚本、日志、截图放进仓库根目录

## 不要提交的东西

密钥、Token、Cookie、账号、本机绝对路径、别人的日志。本仓库目前不需要任何密钥；
如果将来需要，走环境变量或本地配置，并在提交前用 `git diff --cached` 复查一遍。
