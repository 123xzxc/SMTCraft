# SMTCraft — Minecraft 1.8–1.8.9 / Forge 客户端模组

在游戏 HUD 里显示系统当前正在播放的媒体（歌名、歌手、专辑、进度、来源应用、专辑封面），
并同步显示**歌词与翻译**（歌词来源：酷狗 / 网易云 / QQ 音乐，支持 KRC / QRC 词级时间轴），
支持卡拉 OK 逐字高亮、配色主题与可缩放面板。仅客户端：没有服务端组件、没有账号、没有密钥。

作者：**123xzxc** · 许可：MIT

> **说明**：本仓库是同一模组的 **1.8–1.8.9 Forge 移植版**（编译目标 1.8.9 的最后一个 Forge
> 构建，1.8.0 上同样可加载）。上游是 Minecraft 26.1–26.4 / Fabric 版（Java 25、
> `java.lang.foreign` 直调系统库）。上游原件不在本分支内分发，对照请看上游
> `main` 分支（<https://github.com/123xzxc/SMTCraft>）。

> **状态**：已用 **ForgeGradle 2.1 + JDK 8 + MCP `stable_20`** 真实跑通 `gradle build` 与
> `runClient`。产物 `build/libs/smtcraft-1.0.2.jar`（约 300 KB，`mcmod.info` 里 `mcversion=[1.8,1.8.9]`，
> 与 `@Mod(acceptedMinecraftVersions)` 一致；对 Minecraft 的引用已经 reobf 成 srg 名）。进游戏实测：Forge 1.8.9 客户端加载 4 个模组后打出
> `SMTCraft 1.0.2 on Forge 1.8.9: reader=powershell (WinRT SMTC)`，进程桥起轮询、读到当前曲目与
> 封面（106987 字节，正是旧版 96 KB 上限会直接丢掉的那一档）、从酷狗/网易云抓到歌词，
> HUD 面板（圆角、进度条、中文歌名与歌词）在游戏内渲染正常。
> 同一份源码换 `forge_version=1.8.8-11.14.4.1575-1.8.8` + MCP `stable_20` 也真实编译通过。
> **1.8.0 已实测跑通**：同一份 reobf 产物装进 Forge 1.8.0-11.14.4.1577 真机，世界内 HUD
> （封面/歌名/进度/卡拉 OK 歌词）与 F9 设置页（三个标签页、开关、滚动条）全部正常，
> 日志无 `HUD frame failed`、无崩溃报告；为此改掉的 1.8.0 三处不兼容见下方列表最后三条
> （第三处是事件总线：1.8.0 的 FML 总线是独立对象，只挂 Forge 总线会让 tick 完全不跑）。
> HUD 缩放保持连续（设置页滑杆仍是逐像素无级）；字迹发糊改在字体侧：设备缩放不是整数时
> 临时把字形贴图切成线性过滤、画完还原，日志 `hud scale: … device 1.76 linear`。
> 真实编译比 API 桩多抓出三处 1.8.9 差异（`ScaledResolution` 只剩单参构造器、
> `GuiScreen.mouseMovedOrUp` 换成 `mouseReleased`、`mouseClicked` / `keyTyped` /
> `handleMouseInput` 声明了 `IOException`），已全部改掉。
> 下面"已知差异与风险"一节列出的是实测结论与仍然存在的平台限制。

---

## 1. 功能

- HUD 面板：封面、标题、歌手、专辑、进度条、时间、来源应用
- 封面：真实专辑图（PNG/JPG 原样解码为游戏贴图，LRU 缓存）
- 进度条按采样时间在客户端外推，采样间隔可以调到 150 ms 也不抖
- 歌词：逐行滚动 + 卡拉 OK 逐字高亮，支持翻译行、注音、卷轴跑马灯
- 歌词延迟可按曲微调，并按曲记住（`config/smtcraft/lyrics/`）
- 设置页：位置、宽度、缩放、配色、圆角、封面圆角、黑胶转动、歌词字体/字号/颜色、
  主题预设、消音游戏音乐（默认开）、调试信息

## 2. 环境要求

| 项目 | 版本 |
| --- | --- |
| 游戏 | Minecraft **1.8 – 1.8.9**（编译目标 1.8.9） |
| 加载器 | **Forge 1.8.9-11.15.1.2318**（`gradle.properties` 的 `forge_version`；1.8.0 用 11.14.x 也能加载） |
| Java | **8**（1.8.9 的运行期上限；构建也需要 JDK 8，理由见第 7 节） |
| 依赖 | 无。不用 Forge 之外的前置模组 |

读取系统媒体会话靠外部进程，所以还有一层软依赖：

| 平台 | 需要 | 说明 |
| --- | --- | --- |
| Windows 10/11 | `powershell.exe`（系统自带） | 走 WinRT `Windows.Media.Control`，即 SMTC |
| Linux | `playerctl`，没有则 `gdbus` + `dbus-send` | 走 MPRIS2；封面为 `http` 时需要 `curl` 或 `wget` |

## 3. 安装

1. 装好 Forge 1.8.9（或 1.8.0），启动一次游戏生成 `mods/` 目录
2. 把 `build/libs/smtcraft-<version>.jar` 放进 `mods/`
3. 进游戏，HUD 默认显示在左上角

## 4. 使用

| 操作 | 默认键 | 说明 |
| --- | --- | --- |
| 开关 HUD | `F8` | 只在这一次会话里记状态，会写回配置 |
| 打开设置 | `F9` | 也可以在模组列表里点 **Config** |
| 微调歌词延迟 | 按住 `F7` + 滚轮 | 每格 50 ms，向上提前、向下延后，按曲记住 |
| 重置延迟 | `F7` + `Ctrl` | 回到歌词表自带的时序 |

键位可以在 选项 → 控制 里重绑（分类名 `SMTCraft`）。

## 5. 配置文件

路径：`<游戏目录>/config/smtcraft/smtcraft.json`（英文键名，字段与上游一致）。

- 文件改了会被热重载，不需要重启；格式写坏会另存为 `.bad-<毫秒>` 并从默认值开始
- `checkUpdates`（默认 `true`）：首次启动向 `api.github.com` 查一次新版本，只读公开接口；
  设成 `false` 就不再联网
- 歌词缓存与逐曲延迟表在 `config/smtcraft/lyrics/`
- 媒体桥脚本在第一次启动时写到 `config/smtcraft/bridge/`（`smtc.ps1` / `mpris.sh`），
  可以直接改来适配自己的播放器，删掉会自动重建

## 6. 工作原理

```
ClientProxy（@Mod 入口的客户端代理）
 ├─ SmtcConfig          配置读写与热重载
 ├─ MediaBridge         当前播放快照（TrackInfo + 封面字节）
 │    └─ ProcessBridge  外部进程每 pollIntervalMs 打一行 JSON
 ├─ LyricService        歌词抓取、匹配、解析、逐曲偏移
 │    └─ KugouClient / NetEaseClient / QQMusicClient
 ├─ NowPlayingHud       HUD 绘制（Canvas = GL11 立即模式顶点 + GlStateManager 状态）
 └─ MusicMuter          歌词在唱时把游戏音乐压到静音
```

线程模型与上游一致：网络/磁盘走 `util/Async` 的后台通道，渲染线程只读不可变快照；
后台结果通过一个客户端队列在 tick 里回到主线程。

## 7. 从源码构建

```bash
git clone https://github.com/123xzxc/SMTCraft.git smtcraft-189   # 移植分支已入库，见下方说明
cd smtcraft-189

./gradlew build         # Linux / macOS；产物 build/libs/smtcraft-1.0.2.jar
gradlew.bat build       # Windows（CMD / PowerShell）
```

远端仓库上实际存在的是 `1.7.10Forge` 分支；1.8–1.8.9 的移植在本地仓库里（`git log` 首条即
「1.8–1.8.9 Forge 移植首次入库」），推上去之后按 `-b <分支名>` 取即可。

只需 JDK 8：Gradle 由 wrapper 自己下载（2.14.1，ForgeGradle 2.1 的下限），**首次构建要联网一次**
——ForgeGradle 2.1 本体与 MCP `stable_20` 映射得从 `maven.minecraftforge.net` 拉，之后留在
`~/.gradle/caches` 里，可以离线重复构建。

- `build.gradle` 用 **ForgeGradle 2.1**：它负责拉 ForgeGradle 本体、MCP 映射，把 Minecraft
  反混淆成 `net.minecraft.*` 的名字；源码里用的就是这套 MCP 名（`mappings_version=stable_20`）
- `gradle.properties` 里 `minecraft_version=1.8.9`、`forge_version=1.8.9-11.15.1.2318-1.8.9`、
  `mappings_version=stable_20`；要编 1.8.8 就把前两个改成 `1.8.8` 与 `1.8.8-11.14.4.1575-1.8.8`。
  **1.8.0 编不了**（ForgeGradle 拿不到 1.8.0 的 `classes.jar`），它只做运行期验证，见 §9 最后一条
- 版本号只写在 `gradle.properties`；构建时展开进 `@Mod(version = "@VERSION@")` 与 `mcmod.info`
- Windows「受控文件夹访问」会挡住 `java.exe` 在 `Downloads` 下建目录，需要换输出位置时
  用 `-PbuildDirOverride=<path>`
- 只需要 **JDK 8**。`gradle/wrapper/gradle-wrapper.properties` 把 Gradle 钉在 **2.14.1**
  （ForgeGradle 2.1 要 2.14 以上、3.x 以前最稳），`./gradlew` 首次运行会自己下载；
  用 JDK 9+ 会在配置阶段就报错
- 上游的 `build.sh` / `test.sh`（javac 直编 + 源码护栏 + `ShimProbe` 垫片断言）**没有搬过来**：
  `ShimProbe` 断言的正是 26.1↔26.4 之间的签名差异，这里只有一个目标版本，不需要垫片
- **离线校验**：把 1.8.9 的 API 桩写在临时目录（不入库），用 JDK 8 的 `javac` 直编全部源码，
  能在没有 ForgeGradle 的机器上抓出「1.7.10 遗留名字」（例如 `KeyBinding.getIsKeyPressed()`
  在 1.8.9 已改名 `isKeyDown()`）与自身类型错误
- 桩编译抓不全的地方就靠真实编译补：第一次 `gradle build` 又报出 `ScaledResolution` 只剩
  `(Minecraft)` 单参构造器、`GuiScreen.mouseMovedOrUp(int,int,int)` 已改为
  `mouseReleased(int,int,int)`、以及 `mouseClicked` / `keyTyped` / `handleMouseInput`
  现在声明 `throws IOException`（重写时不写 `throws` 就调不了 `super`）
- Forge 的 maven 路径要用**完整**版本串：`net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/`
  `forge-1.8.9-11.15.1.2318-1.8.9-userdev.jar`。按「forge 自己那半截」剥掉 mc 前缀去拼会 404
- `build.gradle` 在 `.gradle/forge-repo` 里暂存一份最小仓库（手写 pom + userdev/universal），
  下载失败也不中断构建，会退回 `maven.minecraftforge.net` 让 ForgeGradle 自己解析
- MSYS 下别直接调 `toolchain/gradle-2.14.1/bin/gradle`（脚本内部 `cd` 会把相对重定向写错、
  再报 `JAVA_HOME is not set`）；用 `java -classpath .../lib/gradle-launcher-2.14.1.jar
  org.gradle.launcher.GradleMain` 直启，配 `JAVA_HOME` / `GRADLE_USER_HOME` 的 Windows 路径

## 8. 与上游 Fabric 版的差异

| 方面 | 上游（26.1–26.4 / Fabric，Java 25） | 本仓库（1.8.9 / Forge，Java 8） |
| --- | --- | --- |
| 读取播放信息 | `java.lang.foreign` 直调 WinRT / libdbus | 外部进程 + 每行一个 JSON（`media/ProcessBridge`） |
| Windows | 进程内 WinRT | PowerShell 调 `Windows.Media.Control` |
| Linux | 进程内 libdbus + MPRIS2 | `playerctl`，退化到 `gdbus` / `dbus-send` |
| 绘制 | `GuiGraphics` + 矩形/矩阵栈 | `render/Canvas`：`GL11` 立即模式顶点 + `GlStateManager` 状态（跨 1.8.0/1.8.9），裁剪按物理像素换算 |
| HUD 挂载 | `HudElementRegistry` | `RenderGameOverlayEvent.Post(TEXT)` |
| 屏幕 | `Screen` / `AbstractWidget` / `EditBox` | `gui/ScaledScreen` + `gui/Controls`（自绘控件） |
| 配置入口 | Mod Menu `ModMenuApi` | `@Mod(guiFactory = ...)` + `IModGuiFactory` |
| 按键 | `KeyMapping` + `Category` | `KeyBinding` + `ClientRegistry.registerKeyBinding` |
| 语言文件 | `assets/.../lang/en_us.json` | `assets/.../lang/en_US.lang`（`key=value`） |
| 系统字体 | 运行期写资源包 + `font` provider | **不生效**：只把选择记进配置（1.8.9 无注入点） |
| 滚轮监听 | `WheelWatch`（多后端探测） | 只在按住延迟键时轮询 `Mouse.getDWheel()` |
| 离线自测 | `SelfTest` + `ShimProbe` + 源码护栏 | 移除（见第 7 节） |

## 9. 已知差异与风险

- **构建与进游戏都已通过**：`gradle build` 出 `build/libs/smtcraft-1.0.2.jar`，55 秒内完成
  （反混淆与 MCP 映射走缓存时）。首次构建需要联网拉 ForgeGradle 2.1、MCP `stable_20`、
  Forge userdev 与 Minecraft 1.8.9 本体。`gradle runClient` 起的是 1.8.9 开发客户端：
  日志里 `Forge Mod Loader has successfully loaded 4 mods`、`bridge adapter started:
  powershell.exe every 150 ms`、`now playing: … cover 19099 bytes`、`lyrics: 77 line(s) from
  netease`、`enabled the vanilla Unicode font`，桌面上 HUD 面板正常显示中文歌名与歌词
  （`GlStateManager` 改造后文字没有变成白方块，即贴图/alpha 状态还原正确）
- **`runClient` 的素材下载走 http**：如果 Gradle user home 的 `gradle.properties` 里留着一个
  失效的 `systemProp.http.proxyHost`（本机曾有一条指向 `127.0.0.1:8765` 的旧代理），
  maven 依赖因为是 https 直连照样成功，而 `resources.download.minecraft.net` 的素材会
  `Connection refused` 刷满日志。删掉那两行即可，不要以为是资源服务器挂了
- **封面读取在 Windows 上走通**：`Properties.Thumbnail` 是 `RandomAccessStreamReference`，
  再 `OpenReadAsync()` 一次才拿到字节（探针读到 92447 字节、游戏内读到 19099 字节，哈希稳定）
- **Linux 分支未实测**：`mpris.sh` 只用 `playerctl` 与 `gdbus` 的公开命令
  （跑不了就退化到后者），播放器不支持 MPRIS2 时拿不到任何会话（这是平台限制，不是 bug）
- **PowerShell 脚本必须带 BOM**：5.1 读无 BOM 的 `.ps1` 会按系统 ANSI 代码页解释，
  UTF-8 的中文注释能把脚本整体读坏（`意外的标记 }`）。`ProcessBridge` 落盘时自动加 BOM，
  `sh` 脚本反而不能加（会让 `#!` 失效）
- **中日韩字形要 unicode 字体**：1.8.9 的默认字体页同样只有 ASCII，没开 `forceUnicodeFont`
  时中文歌名、日文歌词是方块和杂符。首次进游戏会按配置项 `autoUnicodeFont`（默认 true）
  自动打开并 `refreshResources()`；不喜欢就把它改成 `false`，自己在「选项 → 语言」里开
- **画矩形/贴图后必须还原 GL 状态**：1.8.9 的 `FontRenderer` 只管 `bindTexture`，自己
  不 `enableTexture2D`、也不开 alpha test；而 `GlStateManager` 会把开关状态缓存起来、
  不跟着裸 `GL11` 调用走。所以 `Canvas.fill` / `blit` 与 `Text.draw` 的每一次开关都必须走
  `GlStateManager`，收尾也把贴图与 alpha test 打开回去，否则字形 quad 被画成实心白方块
- **封面有体积上限，但先缩图再判定**：上限仍是 base64 512 KB（≈384 KB 图），只是超限时
  先让脚本用 `System.Drawing` 缩到 512 像素、JPEG q88 再发（实测 2400×2400 的 PNG：27 KB → 7 KB，
  含程序集加载 252 ms），缩完还超才跳过并报一次日志。早先卡在 96 KB，等于 72 KB 图，
  实测常见的 300×300 封面就有 92 KB，一半曲目被误杀；取封面走媒体属性上的 `Thumbnail` 引用，
  `__ComObject` 上没有可用的 `Size`，读流要用反射拿 `AsStreamForRead` 的单参重载
- **空封面要一直跟，不是只跟 20 秒**：切歌瞬间缩略图常常比歌名晚到，脚本前 40 次每 500 ms 一拍，
  之后每 2 秒一拍、跟到 160 次（≈4 分钟）；真的一次都不发（浏览器、部分 UWP 播放器）才向 stderr
  报一次 `this session never publishes a thumbnail`，免得用户只能靠"这首没封面"去猜
- **封面跟着曲目走，不跟着"有没有在播"走**：脚本只在 `artHash` 变化的那一条快照里带 `art`
  （每 150 ms 重发 base64 是白烧 IO，大图能到 3 MB/s），所以 Java 侧一旦在空窗期把封面清掉，
  这首歌后半段就再也拿不到图——这正是"暂停/广告之后回来没封面"的根因。现在
  `ProcessBridge` 只在新曲目的 key 与"封面所属曲目"不同时才丢封面，`publishEmpty` 一律不清
- **解码失败要留痕**：`ArtworkCache` 在解码抛异常、或 `ImageIO` 认不出格式（返回空像素）时各记一次
  `warning`（`java.util.logging`，走 FML 的日志桥），否则"没封面"和"解不开"在画面上长得一样
- **配置、桥脚本、歌词缓存统一在 `mcDataDir`**：都在 `config/smtcraft/` 下同一个基准，
  启动器改了 `user.dir` 也不会分家；只有探针（没有游戏实例）才退回工作目录
- **轮询间隔只有一套范围 100–2000 ms**：常量在 `SmtcConfig.MIN_POLL_MS` / `MAX_POLL_MS`，
  设置页滑杆、配置归一化、桥脚本三处都取它（早先是滑杆 60–2000、配置 50–2000、实际 100–5000 三套）
- **改间隔要静置 400 ms 才重启进程**：`setConfig` 每个 tick 都会调进来，只有间隔真的变了才算
  "配置变了"；真变了也要等它静置（`ProcessBridge.INTERVAL_SETTLE_MS`）再杀进程重拉，
  否则拖一次滑杆会连续重启几十次。连续失败 5 次的退避与"停手"不受调度影响
- **Linux 封面按 `artUrl` 缓存**：同一个 URL 只下一次、只算一次 `cksum`；`curl` 限
  `--connect-timeout 1 --max-time 2`；下载失败不当成"这首没有封面"，同一首歌最多试
  `ART_MAX_TRIES`（20）次，试满才向 stderr 报一次 `cover unavailable` 并停止重试；
  超限时向 stderr 报一次 `cover too large`
- **换系统字体无效**：设置页里的字体项会写进配置，但 1.8.9 的字形来自
  `FontRenderer` 贴图，没有可注入的 provider
- **逐曲延迟表的落盘时机**：走 `ShutdownPlan` 的 `FLUSH` 步，与上游同一套预算
  （`util/Budgets`），预算耗尽时后一步只拿到剩余时间
- **跑过 `runClient` 之后必须重跑 `build`**：ForgeGradle 2.1 的 `runClient` 依赖 `:jar`，
  而那个任务输出的是**开发名**（`net.minecraft.client.*`）的 jar，会直接覆盖 `build/libs/` 里
  已经 reobf 成 srg 名的产物。产线用的 jar 一定要在最后一次 `gradle build` 之后取；
  判断方法：`javap -c -cp build/libs/smtcraft-<v>.jar org.yinyun.smtcraft.render.Canvas | grep -c func_`
  应该是非零，为 0 说明手里这份是开发 jar
- **1.8.0 编不了，但能跑**：ForgeGradle 2.1 给 1.8.0 的 userdev 只带 `binaries.jar` + `dev.json`
  （新式布局），没有 `:applyBinaryPatches` 需要的 `classes.jar`，任务直接
  `FileNotFoundException`——这是工具链缺口，不是代码差异。能真实编译的目标是 **1.8.8** 与 **1.8.9**。
  1.8.0 的验证方式因此换成「拿 reobf 后的 jar 装进真机跑」：隔离 `gameDir`、把
  `libraries[].downloads.artifact.path` 拼成 classpath（`forge-1.8-11.14.4.1577.jar` 与
  `launchwrapper` 不在 json 里，要手动补）、`-Djava.library.path` 必须写 Windows 路径。
  实测结果：HUD 与设置页正常。过程中修掉两处 1.8.0 特有的运行期崩溃：
  1. `NoSuchMethodError: ScaledResolution.<init>(Minecraft)`——1.8.0 只有 `(Minecraft,int,int)`，
     现在 `util/GameScale#resolution` 自己按 `guiScale` 算出缩放，不再构造 `ScaledResolution`
  2. `NoSuchFieldError: field_181706_f`（`DefaultVertexFormats.POSITION_COLOR`，1.8.8 才加）——
     `render/Canvas` 放弃 `Tessellator`/`WorldRenderer`/`DefaultVertexFormats`，改回 `GL11` 立即模式
     （`glBegin(GL_QUADS)` + `glColor`/`glTexCoord2d`/`glVertex2d`），状态仍全部由 `GlStateManager` 管
  3. 事件总线只挂 `MinecraftForge.EVENT_BUS` 时，1.8.0 上热键 F7/F8/F9 全无反应、歌词不加载、
     HUD 停在「等待播放」：1.8.0 的 `FMLCommonHandler` 自己 `new` 了一条独立 `EventBus`，
     `ClientTickEvent` 只在那条上 post（1.8.9 起两条合并成一条，所以开发期看不出来）。
     `ClientProxy#registerFmlBus()` 按「`bus() != MinecraftForge.EVENT_BUS`」补注册一次，
     两版都能收到 tick，且 1.8.9 上不会把监听挂两遍
- **非整数设备像素会糊字，改在字体侧**：1.8.x 的字形是 8 px 位图图集、`FontRenderer` 贴图默认
  `GL_NEAREST`，`panelScale = cfg.scale * autoFit(...)` 会算出 0.44 / 0.78 这种非整数设备缩放
  （0.44 × guiScale 4 = **1.76**），最近邻采样跳过纹素行——标题顶部像被削掉、歌手名竖笔画变双线
  （1.8.0 与 1.8.9 一样，是移植带进来的：上游 Fabric 用高分辨率字形，不暴露这个采样问题）。
  现在 `render/FontSmoothing` 在 HUD 绘制期间把字形页临时改成 `GL_LINEAR`、`finally` 里还原，
  缩放滑杆仍是无级的；设备缩放贴着整数时保留最近邻（1:1 采样最锐）。**放大与缩小两条路径都要改**：
  纹理放大才用 `GL_TEXTURE_MAG_FILTER`，设备缩放小于 1（缩小绘制）走的是 `GL_TEXTURE_MIN_FILTER`，
  只改前者的话小面板依旧是最近邻跳过纹素行。日志会打
  `hud scale: gui 480x255 px 4 design 300x106 panel 0.44 device 1.76 linear lyric 1.45 trans 1.2`，
  但只在**第一帧与过滤模式翻转**时是 INFO（其余缩放变化降成 FINE），拖滑杆不会把告警刷掉
- **歌词响应体有 4 MB 上限**：三个 provider 的 `readText` 都要过
  `util/Budgets#RESPONSE_BYTES_MAX`，超限当「这个源没结果」让匹配器换下一个，不把劫持页/错误页
  读进内存；缓存文件解析失败也不再是静默 `null`，会留一行 FINE 日志（否则只表现为"每次重新联网"）
- **首次启动会联网查一次新版本**：`ClientProxy` 在第一次 tick 触发一次 `util/UpdateCheck`，向
  `api.github.com` 取本仓库最新 release（只读公开接口，不带任何本机信息）。不想让它联网就把
  `smtcraft.json` 里的 `checkUpdates` 设为 `false`；查询失败只是静默跳过，不影响任何功能
- **1.8.1–1.8.7 未验证**：代码走的是 1.8.0 与 1.8.9 都存在的 API 交集，理论上同样可加载，
  但没有对应 Forge 构建的实测记录
- **热路径上不做重复分配**：`Canvas.of(mc)` 返回**复用的同一个实例**（每帧一次 
  `new Canvas(...)` 含 `float[192]` 矩阵栈，纯属白烧），`GameScale.resolution` 也按
  `(displayWidth, displayHeight, guiScale)` 缓存结果。代价是这两处的返回值**不能跨帧留存**，
  也不能同时持有两个 `Canvas`（HUD 与设置页是先后两趟绘制，不重叠）；上一帧若在 `scissor` 里
  抛异常，下一帧开头会补一次 `glDisable(GL_SCISSOR_TEST)` 兜底
- **秒数文本不依赖系统语言**：`render/Draw#duration` 手写拼接而不用 `String.format`——HUD 每帧
  调两次，`format` 每次新建 `Formatter`，且 `%d` 取默认语言环境的数字（阿拉伯语等区域会输出
  本地数字，游戏字体里没有字形，读秒变空白）。改这段别换回 `String.format`
- **`mcmod.info` 的 `mcversion` 是区间**：构建期由 `minecraft_version_min`（1.8）与
  `minecraft_version`（1.8.9）拼成 `[1.8,1.8.9]`；只写 1.8.9 会让 1.8 的玩家以为自己装错了版本
- **退出时不能新建类**：JVM 跑 shutdown hook 的时候模组的类加载器已经关了，hook 里再 `new` 一个
  匿名类就是 `NoClassDefFoundError`（1.8.0 上实测 `ClientProxy$3`），后面每一步都不执行——配置没
  写盘、桥进程留着、游戏音乐音量没还原。现在关停步骤按编号走 `ClientProxy#stepNow`，动作写在本类
  里，看门狗是 init 期就建好的字段，只在退出时才用到的工具类由 `warmShutdownPath()` 提前定义。
  自查：正常退出（Esc → 退出游戏）后日志里有 `shutdown: finished in N ms`；强杀进程不会跑 hook，
  不能拿它当验证
- **关停的日志要写文件**：Forge 1.8 把 `System.out`/`System.err` 都接进 log4j（日志里的
  `[STDOUT]` 前缀），退出时 appender 已关，关停那几行既不出现在控制台也不在 `logs/latest.log`。
  所以它们走 `ClientProxy#trace` 追加到 `logs/smtcraft-shutdown.log`——要确认关停跑完了，看这个文件
- **设置页的文本框会吃掉键盘**：控件是按页面顺序派发点击的，开关/滑条排在文本框前面，所以点别处时
  文本框收不到那次点击、不会自动失焦。现在 `SettingsScreen` 在派发之前先 `commitFocus()`；新增控件
  时不要把提交动作放回控件内部，否则键盘输入无效、Esc 要按两次才能退出设置页
- **配置写盘是原子的**：`SmtcConfig#saveNow` 先试 `ATOMIC_MOVE`，文件系统不支持时退回普通替换。
  每首歌静音/恢复都会写一次配置，非原子 rename 断电会留下半份 json（启动时会被 quarantine 成
  `.bad-<ms>`，偏好设置回默认值）

## 10. 目录结构

```
src/main/java/org/yinyun/smtcraft/
  SmtcCraftClient.java     @Mod 入口（平台无关：配置/媒体桥/歌词服务）
  ClientProxy.java         客户端装配：按键、HUD 挂钩、tick、关停
  CommonProxy.java         专用服务端的空实现
  SmtcConfig.java          JSON 配置、热重载、字段归一
  audio/MusicMuter.java    游戏音乐静音（gameSettings.musicVolume）
  font/FontPack.java       1.8.9 下的退化实现（记录选择，不注入）
  gui/                     ScaledScreen / Controls / SettingsScreen / 取色 / 字体选择 / 工厂
  media/                   MediaBridge 接口、ProcessBridge、NullBridge、平台选择
  render/                  Canvas / Draw / Text / 主题 / HUD / 歌词渲染 / 封面贴图
  smtc/                    歌词提供方、解析、匹配、偏移、媒体快照
  util/                    Async / Budgets / Errors / Keys / UiScale / UpdateCheck / Watchdog …
src/main/resources/
  mcmod.info
  assets/smtcraft/icon.png
  assets/smtcraft/lang/{en_US,zh_CN}.lang
tools/probe/                可独立运行的探针：BridgeProbe / IntervalProbe / CoverProbe（见其 README）
```

## 11. 许可

MIT，见 `LICENSE`。第三方组件与歌词接口的来源见 `THIRD_PARTY_NOTICES.md`。
