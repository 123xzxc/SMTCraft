# AGENTS.md

给在本仓库里工作的自动化代理（以及未来的自己）的约定。人类贡献者同样适用。

## 这是什么

Minecraft **1.8–1.8.9 / Forge 11.15.1.2318** 客户端模组：把系统当前播放的曲目与同步歌词画进 HUD。
曲目来自 Windows SMTC 或 Linux MPRIS2，**1.8.9 上同样没有 `java.lang.foreign`，也没有
`net.minecraft.client.*` 之外的 FFI**，所以两条平台都用`外部进程 + 每行一个 JSON`读取
（`media/ProcessBridge`：PowerShell 调 WinRT / `playerctl` 或 `gdbus`）。歌词来自酷狗 / 网易云 /
QQ 音乐的公开接口。**没有服务端组件，没有账号，没有密钥。**

- 语言级别：**Java 8**（游戏运行期的上限）。不要用 `var`、`record`、`switch` 表达式、
  `List.of`、`String.isBlank`、`readString`、文本块、模式匹配 `instanceof`
- 依赖：只有 Gson 与 Minecraft / Forge 本体。**不要新增第三方库**
- 包根：`org.yinyun.smtcraft`
- 类名/方法名用 **MCP 名**（`Minecraft.getMinecraft()`、`GuiScreen`、`FontRenderer`、
  `RenderGameOverlayEvent`），绘制走 `render/Canvas` 封的 **`GL11` 立即模式 + `GlStateManager` 状态**
  （不再用 `Tessellator`/`WorldRenderer`/`DefaultVertexFormats`：顶点格式的 srg 字段是 1.8.8 才加的）

## 构建

```bash
gradle build            # 产物 build/libs/smtcraft-<version>.jar
gradle runClient        # 开发期直接起游戏
```

- 必须 **JDK 8**：ForgeGradle 2.1 要 Gradle 2.14 以上，且要联网拉一次 MCP `stable_20` 映射反混淆 1.8.9
- 已在本机跑通：`gradle build` 出 `build/libs/smtcraft-1.0.2.jar`，reobf 后 Minecraft 引用是 srg 名；
  `gradle runClient` 起 1.8.9 开发客户端也能加载（日志出现 `SMTCraft 1.0.2 on Forge 1.8.9` 即算起来）。
  **`runClient` 会覆盖 `build/libs/` 里的 jar**：它依赖的 `:jar` 输出开发名（`net.minecraft.client.*`），
  不是 reobf 产物，跑完要重新 `gradle build` 才能拿到能装进正常 Forge 客户端的那份。自查：
  `javap -c -cp build/libs/<jar> org.yinyun.smtcraft.render.Canvas | grep -c func_` 非零才算数。
  素材下载走 http，Gradle user home 里若留了失效的 `systemProp.http.proxyHost` 会让它 `Connection refused`
- MSYS 下别直接调 `toolchain/gradle-2.14.1/bin/gradle`（脚本内部 `cd` 会把相对重定向写到
  `toolchain/gradle-2.14.1/.tmpdl/`，再报 `JAVA_HOME is not set`）。改成直启：
  `java -classpath toolchain/gradle-2.14.1/lib/gradle-launcher-2.14.1.jar org.gradle.launcher.GradleMain --no-daemon build`，
  `JAVA_HOME` 与 `GRADLE_USER_HOME` 用 Windows 风格路径（`C:/...`）
- Forge 的 maven 目录名是**完整**版本串（`net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/`）；
  剥掉 mc 前缀去拼路径会 404。`.gradle/forge-repo` 里那份暂存仓库就按完整版本串落盘
- 离线时没法出 jar（ForgeGradle 与映射都在网上），但可以用 `javac` 直编「1.8.9 API 桩 + 全部源码」
  抓 1.7.10 遗留名字，做法见下一条。桩不覆盖的地方只能靠真实编译：
  `ScaledResolution` 只有单参构造器、`mouseMovedOrUp` 已变 `mouseReleased`、
  `mouseClicked`/`keyTyped`/`handleMouseInput` 声明 `throws IOException`
- 版本号只写在 `gradle.properties`，构建时展开进 `@Mod(version)` 与 `mcmod.info`；
  `mcmod.info` 的 `mcversion` 展开成区间 `[1.8,1.8.9]`（`minecraft_version_min` + `minecraft_version`），
  与 `@Mod(acceptedMinecraftVersions)` 对齐——只写 1.8.9 会让 1.8 的玩家以为自己装错了版本
- 编译告警开的是 `-Xlint:all`（只关 `options` 与 `processing` 两类噪音）：日志里冒出别的 category
  就说明有线可修，别把「没有 warning」当成代码洁净的证据
- 输出目录被「受控文件夹访问」挡住时用 `-PbuildDirOverride=<path>`
- 上游 Fabric 26.x 的 `build.sh` / `test.sh` / `SelfTest` / `ShimProbe` 没有搬过来：
  它们断言的是 26.1↔26.4 的签名差异，这里只有一个目标版本
- 迁移到新版本时的第一步：`grep -rn "cpw\.mods\|Tessellator\|DefaultVertexFormats\|ScaledResolution\|getIsKeyPressed\|fontRenderer\b\|mouseMovedOrUp" src/`。
  1.8.9 把这些换成了 `net.minecraftforge.fml.*`、`KeyBinding.isKeyDown()`、`mc.fontRendererObj`、
  `GuiScreen.mouseReleased`；1.8.0 又要求：**不构造** `ScaledResolution`（用 `util/GameScale#resolution`
  自算），**不用** `Tessellator`/`DefaultVertexFormats`（顶点格式常量在 1.8.0 缺失，走 `GL11` 立即模式）
- 1.8.0 编不了（ForgeGradle 2.1 的 1.8.0 userdev 没有 `classes.jar`），验证方式是**真机跑 reobf jar**：
  隔离 `gameDir`（别写用户的 `.minecraft`，沙箱会 `Permission denied`）、按
  `versions/*/*.json` 的 `libraries[].downloads.artifact.path` 拼 classpath 并**手动补**
  `forge-<ver>.jar` 与 `launchwrapper`、`-Djava.library.path` 用 Windows 路径（MSYS 的 `/c/...` 会
  `UnsatisfiedLinkError: no lwjgl`）、`--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker`。
  跑起来先看 `SMTCraft 1.0.2 on Forge …` 这行，再进世界看有没有 `HUD frame failed`
- 判断手里的 jar 能不能发布：`javap -c -cp build/libs/<jar> org.yinyun.smtcraft.render.Canvas | grep -c func_`
  非零 = 已 reobf；为 0 说明是 `runClient` 覆盖出来的开发 jar，要重跑 `build`

## 改动纪律

1. **先读后写。** 不要「整文件读回 → 整文件写回」地改大文件（都在一千行以上：
   `SettingsScreen.java` 1502 行、`ProcessBridge.java` 1285 行、`LyricService.java` 1203 行）；
   用定点替换，一次一处。行数会随改动漂，别把它当契约。
2. **不要动 `gradle.properties` 的版本号**，除非在准备发布。
3. 预算与超时常量集中在 `util/Budgets.java`，关停步骤顺序在 `util/ShutdownPlan.java`。
   新增一个有超时的阶段，就把它加进预算表，并让下游步骤的预算从剩余时间里取。
   唯一一个非关停预算也在这儿：`RESPONSE_BYTES_MAX`（4 MB）是三个 provider 共用的响应体上限，
   超限的响应要当「这个源没结果」，不要读进内存。
4. 日志里的异常一律用 `util/Errors.describe(t)`，不要写 `t.toString()`。
4b. 平台差异只能留在 `media/ProcessBridge` 的内嵌脚本里。**`.ps1` 落盘必须带 UTF-8 BOM**
    （PowerShell 5.1 按 ANSI 代码页读无 BOM 脚本，中文注释会让整份脚本语法崩），`sh` 脚本必须不带
    （BOM 会让 `#!` 失效）。改脚本后先跑 `tools/probe/BridgeProbe` 验证，别靠进游戏试。
    探针在版本控制里（`tools/probe/`），编译与运行命令见 `tools/probe/README.md`，`build/probe` 只是输出目录。
4c. 1.8.9 的默认字体页同样只有 ASCII：中日韩字形依赖 `gameSettings.forceUnicodeFont`。
    首次 tick 由 `ClientProxy#ensureUnicodeFont` 按配置项 `autoUnicodeFont` 打开，改动这段要连带说明
    为什么必须 `refreshResources()`（只改字段不会重建 FontRenderer 的字形页）。
4d. **画完必须还原 GL 状态，而且只用 GlStateManager 改状态**。1.8.9 的 `FontRenderer` 只 `bindTexture`，
    自己既不 `enableTexture2D` 也不开 alpha test；而 `GlStateManager` 缓存了「贴图/混合/alpha 开着没」的
    判断，直接调裸 `GL11.glEnable` 会让缓存与实际不一致（最典型：`glBindTexture` 之后 FontRenderer
    还以为是别的贴图，从封面里取字形，文字整片糊掉）。所以 `render/Canvas` 的 `fill` / `blit`、
    `Text.draw`、`ArtworkCache` 的绑贴图全部走 `GlStateManager`，只有 `glScissor` / `glGenTextures` /
    `glTexImage2D` / `glDeleteTextures` 继续用 `GL11`（GlStateManager 不管这些）。
    顶点提交走 `GL11.glBegin(GL_QUADS)` + `vertex`/`texVertex`，**不要换回 `Tessellator`**：
    `DefaultVertexFormats.POSITION_COLOR` 这类常量在 1.8.0 没有对应 srg 字段
    （`NoSuchFieldError: field_181706_f`），而 `glBegin`/`glVertex2d`/`glTexCoord2d` 的名字两版一致。
    颜色由 `beginFill` 一次性 `GlStateManager.color(...)` 设定——固定管线下颜色是顶点状态，
    不要在 `glBegin`/`glEnd` 之间再改颜色。
4e. **字形不在整数设备像素上时要临时改线性过滤**。1.8.x 的 `FontRenderer` 是 8 px 位图图集、
    贴图默认 `GL_NEAREST`，面板整体按 0.44 / 0.78 这种非整数缩放画时，纹素行会被跳过（标题顶行
    像被削掉），竖笔画被采成双线。修法不是把缩放量化——那样设置页的滑杆会变成一档一跳——而是
    `render/FontSmoothing`：绘制前 `begin(|device - round(device)| > 0.02)`，`Text#draw` 每画完
    一段回报当前绑定页（`note()`），`end()` 把改过的页恢复 `GL_NEAREST` 并还原绑定。过滤参数是
    **贴图对象**的状态，不是绘制状态：当帧不改回去，菜单与聊天框的字会跟着糊。设备缩放本来贴着
    整数时保留最近邻，1:1 采样最锐。自查看日志 `hud scale: … device 1.76 linear` / `device 2.0 nearest`——
    这行只在第一帧与过滤模式翻转时是 INFO，其余缩放变化是 FINE（设置页滑杆是连续量，拖一次会变几十次）。
    缩放比较用 `lastPixels` / `lastPanelScale` 两个字段，不要恢复「每帧拼字符串再 equals」。
    面板缩放保持连续（`cfg.scale * autoFit`），字号也照旧连续，别再引入取整。
    进度文本同理：`render/Draw#duration` 手写拼接，**不要换回 `String.format`**——HUD 每帧调两次，
    而且 `%d` 跟随系统语言（阿拉伯语区域输出本地数字，游戏字体没有那些字形）。
4f. 封面只能从媒体属性上取：`Properties.Thumbnail` 是 `RandomAccessStreamReference`，要再
    `OpenReadAsync()` 才拿到流；返回的 `__ComObject` 上没有可用的 `Size`，读流要用反射拿
    `System.IO.WindowsRuntimeStreamExtensions.AsStreamForRead` 的单参重载。改完同样先跑探针。
    体积上限卡的是 **base64 长度**（512 KB ≈ 384 KB 图），脚本里不要拿原始字节数去比同一个常量；
    超限先走 `Shrink`（`System.Drawing` 缩到 512 px、JPEG q88）再判，缩完还超才放弃并报一次。
    空封面要跟到底：前 40 次每 500 ms，之后每 2 秒、跟到 160 次（≈4 分钟），耗尽报一次
    `never publishes a thumbnail`；判定超限后要立刻停手，别反复解同一张大图。
    封面字节只在 `artHash` 变化的那一条快照里发（150 ms 一轮重发 100 KB base64 会白烧 IO），
    **所以 Java 侧不能在"没在播"时清封面**：清掉之后脚本不会重发，同一首歌后半段就永远是占位音符。
    `ProcessBridge` 现在按"封面所属曲目 key"（`artKey`）判断，`publishEmpty` 不清任何封面状态；
    换歌时重置发送记录（同一张专辑封面换曲也要重发一次，Java 侧按哈希缓存贴图）。
    改完脚本先跑探针：反射导出 `WINDOWS_SCRIPT` → `Parser::ParseFile` 语法检查 → 命令行跑 8 秒看 JSON。
    Java 侧这套"封面跟着曲目走"的逻辑有回归探针：`tools/probe/CoverProbe.java` 反射调
    `ProcessBridge#handleLine`，四步分别是带封面 → 空窗 → 同曲目不带 art → 换曲目，
    断言分别是 8B / 8B / 8B / 0B。改 `publishEmpty` 或那段 key 判断前后都跑一遍。
4g. `ClientProxy` 每个 tick 都会调 `bridge().setConfig(config)`，那是「读一下配置」，不是「配置变了」。
    在 `reconfigure()` 里做任何重置（`gaveUp` / `failures` / `restartAt`）之前，先比较
    `intervalOf(config)` 与上次已知值，并且**进程没在跑时不要动退避**：否则适配器起不来会变成
    每 tick 拉一次进程、刷屏告警，5 次上限与 5 秒退避全部失效。间隔真变了也要静置
    `INTERVAL_SETTLE_MS` 再 `destroy`，拖滑杆只该重启一次。改动后用探针数日志里
    "bridge adapter started" 出现了几次（`tools/probe/IntervalProbe`：稳态 2.5 s + 连续拖 30 格，应为 2 次）。
4h. **事件必须两条总线都挂，但只能各挂一次**。1.8.0 的 `FMLCommonHandler.instance().bus()` 是
    构造函数里 `new EventBus()` 出来的**独立总线**，`TickEvent` 只在那条上 post；1.8.9 起才改成
    指向 `MinecraftForge.EVENT_BUS`（于是 `bus()` 被标 deprecated）。只注册 `MinecraftForge.EVENT_BUS`
    时：1.8.9 正常，1.8.0 上 `onClientTick` 一次都不跑——热键 F7/F8/F9 全无反应、歌词不请求、
    HUD 卡在「等待播放」（用户就是这么报的）。注册处是 `ClientProxy#registerFmlBus`：拿到 `bus()`，
    只有它不是 `MinecraftForge.EVENT_BUS` 时才再注册一次。**不要**无条件注册两行——1.8.9 上每个
    `@SubscribeEvent` 会挂两遍、tick 跑两次。验证看日志有没有 `lyrics: N line(s)`（这句只在 tick 里
    发请求后才会出现），以及热键是否响应。
4i. **退出阶段不许新建类**。走到 JVM shutdown hook 时，模组的类加载器已经关掉了：那一步再
    `new` 一个匿名类就是 `NoClassDefFoundError`（1.8.0 上实测 `NoClassDefFoundError:
    ClientProxy$3`，整条关停序列当场作废——配置没写盘、桥进程留着、音量没还原，只有下次启动时
    「restored the music volume … after an unclean shutdown」那句能看出来）。所以 `finishShutdown`
    里不出现 `new Runnable`，步骤按编号走 `stepNow(name, STEP_*, arg)`，动作写在 `ClientProxy`
    自己的 switch 里；看门狗是字段 `shutdownWatchdog = new ShutdownWatchdog()`（init 期就实例化）。
    新增任何「只在退出时用到」的类，必须同时登记进 `warmShutdownPath()` 的 `Class.forName(name,
    false, loader)` 列表。
4j. **退出阶段的输出不能走日志**。Forge 1.8 把 `System.out` / `System.err` 都接进了 log4j
    （日志里 `[STDOUT]` 前缀就是证据），关停时 appender 已经关了：实测正常退出，配置确实写盘
    （`config/smtcraft/smtcraft.json` 的时间戳与 `Stopping!` 同秒），但 `shutdown: finished` 一行
    既没进控制台也没进 `logs/latest.log`。所以关停相关的三处提示走 `trace(String)`，它追加到
    `logs/smtcraft-shutdown.log`（`java.io.FileOutputStream`，类已登记进 `warmShutdownPath()`）。
    自查：正常退出（Esc → 退出游戏）后 `logs/smtcraft-shutdown.log` 要有
    `shutdown: finished in N ms`，且 `grep -c NoClassDefFoundError` 为 0；`taskkill /F` 或直接
    关窗**不会**跑 hook，别拿它当验证。
5. 每个成员上方最多一个 javadoc 块。注释解释**为什么**，不是复述代码；审计编号
   （如 `AUDIT-71`）可以写进注释，指向当时的条目。
6. 网络、磁盘、渲染互不阻塞：IO 走 `util/Async`，结果通过 `ClientProxy` 的客户端队列
   回到主线程；渲染线程只读不可变快照。
7. 歌词的横向度量只能有一个来源：有逐字时间的行走 `render/LyricsRenderer` 的字形跟随，
   没有的行走 `render/LyricScroll` 的滚动目标。不要在同一行上叠两套。
8. 新增界面文案要同时进 `assets/smtcraft/lang/en_US.lang` 与 `zh_CN.lang`（`key=value`）；
   漏了不会崩，但会直接显示键名。
9. 上游 Fabric 26.x 原件**不在本分支内**：要逐文件对照就直接看上游 `main` 分支
   （<https://github.com/123xzxc/SMTCraft>），不要在本地或仓库里维护副本。

## 这个移植版特有的坑

- `gui/ScaledScreen` 的钩子拿到的坐标是**设计像素**（已除以 `ui`），不要再用
  `ScaledResolution` 换算第二次
- `render/Canvas` 的 `scissor` 吃设计坐标，内部换算成物理像素；用完必须 `scissorOff()`。
  `Canvas.of(mc)` 返回的是**复用的同一个实例**（省掉每帧一次分配）：一条绘制链里随便用，但不要
  同时持有两个（界面与 HUD 是先后两趟，不重叠）。上一帧若在 `scissor` 里抛异常，下一帧开头会补
  一次 `glDisable(GL_SCISSOR_TEST)`——这条兜底不要删
- 换系统字体在 1.8.9 无法生效（没有 font provider）：`font/FontPack` 只记录选择，
  设置页里如实说明
- 进程桥的脚本第一次运行会写到 `config/smtcraft/bridge/`；改脚本等于改行为，
  不要把它当成构建产物
- **设置页派发点击之前先 `commitFocus()`**。`SettingsScreen#onClick` 是按 `controls` 列表顺序
  把点击交给控件、命中即 `return`，而"失焦即 `commit()`"写在 `Controls.Field#click` 里：被点中的
  开关/滑条/按钮排在文本框前面，那段永远不执行，`focused` 一直留着，之后所有按键被隐形文本框吃掉、
  第一次 Esc 只换来一次 `commit()`（要按两次才退出设置页）。新增控件时不要把提交动作放回控件内部
- 配置落盘用 `ATOMIC_MOVE`（不支持时退回普通 `move`），和 `smtc/SongOffsets#flush` 保持一致：
  每首歌静音/恢复都会写一次配置，非原子 rename 断电会留下半份 json

## 提交前

```bash
gradle build
git status --short          # 确认没有构建产物、配置文件、本机路径混进来
```

- `build/`、`.gradle/`、`run/`、`config/`、`logs/` 一律不入库
- 提交信息写清「改了什么 + 为什么」；一次提交只做一件事
- 不要把审计报告、调试脚本、日志、截图放进仓库根目录

## 不要提交的东西

密钥、Token、Cookie、账号、本机绝对路径、别人的日志。本仓库目前不需要任何密钥；
如果将来需要，走环境变量或本地配置，并在提交前用 `git diff --cached` 复查一遍。
