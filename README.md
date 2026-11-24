# SMTCraft — Minecraft 1.7.10 / Forge 客户端模组

在游戏 HUD 里显示系统当前正在播放的媒体（歌名、歌手、专辑、进度、来源应用、专辑封面），
并同步显示**歌词与翻译**（歌词来源：酷狗 / 网易云 / QQ 音乐，支持 KRC / QRC 词级时间轴），
支持卡拉 OK 逐字高亮、配色主题与可缩放面板。仅客户端：没有服务端组件、没有账号、没有密钥。

作者：**123xzxc** · 许可：MIT

> **说明**：本仓库是同一模组的 **1.7.10 Forge 移植版**。上游是 Minecraft 26.1–26.4 / Fabric 版
> （Java 25、`java.lang.foreign` 直调系统库，见 <https://github.com/123xzxc/SMTCraft>）。
> 这里只放 Forge 版源码，不重复上游的副本。

> **状态**：已用 JDK 8 + ForgeGradle 1.2 真机编译并通过 `reobf`，进程桥在 Windows 上
> 用独立探针验证过读取与封面；**整轮进游戏验收请自己过一遍**。下面"已知差异与风险"
> 一节列出的是实测结论与仍然存在的平台限制。

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
| 游戏 | Minecraft **1.7.10** |
| 加载器 | **Forge 1.7.10-10.13.4.1614**（`gradle.properties` 的 `forge_version`） |
| Java | **8**（1.7.10 的运行期上限；构建也需要 JDK 8，理由见第 7 节） |
| 依赖 | 无。不用 Forge 之外的前置模组 |

读取系统媒体会话靠外部进程，所以还有一层软依赖：

| 平台 | 需要 | 说明 |
| --- | --- | --- |
| Windows 10/11 | `powershell.exe`（系统自带） | 走 WinRT `Windows.Media.Control`，即 SMTC |
| Linux | `playerctl`，没有则 `gdbus` + `dbus-send` | 走 MPRIS2；封面为 `http` 时需要 `curl` 或 `wget` |

## 3. 安装

1. 装好 Forge 1.7.10，启动一次游戏生成 `mods/` 目录
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
 ├─ NowPlayingHud       HUD 绘制（Canvas = GL11 + Tessellator）
 └─ MusicMuter          歌词在唱时把游戏音乐压到静音
```

线程模型与上游一致：网络/磁盘走 `util/Async` 的后台通道，渲染线程只读不可变快照；
后台结果通过一个客户端队列在 tick 里回到主线程。

## 7. 从源码构建

```bash
git clone -b 1.7.10Forge https://github.com/123xzxc/SMTCraft.git smtcraft-1710
cd smtcraft-1710

./gradlew build         # Linux / macOS；产物 build/libs/smtcraft-1.0.2.jar
gradlew.bat build       # Windows（CMD / PowerShell）
```

拉下来只要本机有 **JDK 8** 就能编译：不用装 Gradle（`gradlew` 自己下载 2.14.1），不用预置
Minecraft jar（`build.gradle` 自己取 1.7.10 的版本 json、客户端与服务端 jar，以及 Forge 的
`-userdev` / `-universal`）。首次构建需要联网，约 3–5 分钟，之后走本地缓存。打出来的 jar 直接
丢进 `.minecraft/mods` 就能用，不必跑 `runClient`。

- `build.gradle` 用 **ForgeGradle 1.2**：它负责下载 MCP 映射、把 Minecraft 反混淆成
  `net.minecraft.*` 的名字，源码里用的就是这套 MCP 名
- 版本号只写在 `gradle.properties`；构建时展开进 `@Mod(version = "@VERSION@")` 与 `mcmod.info`
- Windows「受控文件夹访问」会挡住 `java.exe` 在 `Downloads` 下建目录，需要换输出位置时
  用 `-PbuildDirOverride=<path>`
- 只需要 **JDK 8**。`gradle/wrapper/gradle-wrapper.properties` 把 Gradle 钉在 **2.14.1**
  （ForgeGradle 1.2 的上限），`./gradlew` 首次运行会自己下载；用更高版本的 JDK 会在配置阶段就报错
- ForgeGradle 1.2 把 1.7.10 的版本 json / 客户端 jar / 服务端 jar 地址硬编码成
  `s3.amazonaws.com/Minecraft.Download`（2019 年下线，插件里没有可配置项）。`build.gradle`
  会在配置阶段自己从 `launchermeta.mojang.com` / `launcher.mojang.com` 取这三个文件放进
  ForgeGradle 的缓存位置，然后禁用那三个下载任务，所以首次构建不用手工准备任何文件
- 2026 年起官方仓库不再发布 `net.minecraftforge:forge` 的 pom 与无 classifier 的 jar，只剩
  `-userdev` / `-universal` 两个 jar（官方站与 CleanroomMC 镜像都一样）。所以 `build.gradle`
  在 `.gradle/forge-repo/` 里拼一个最小 maven 仓库：手写 pom + 首次构建时下载那两个 jar，
  只下一次，之后直接复用。要完全离线，把这两个 jar 放
  `toolchain/m2/net/minecraftforge/forge/<forge_version>/`，脚本检测到会先复制这一份
- 上游的 `build.sh` / `test.sh`（javac 直编 + 源码护栏 + `ShimProbe` 垫片断言）**没有搬过来**：
  `ShimProbe` 断言的正是 26.1↔26.4 之间的签名差异，1.7.10 只有一个目标版本，不需要垫片
- 仓库带 **Gradle wrapper**（`gradlew` / `gradlew.bat` / `gradle/wrapper/`，钉在 2.14.1）：
  clone 下来只要有 JDK 8 就能 `gradlew.bat build`，不用在本机装 Gradle。仓库不带在线 CI，
  构建就在本地跑——能不能过，一次 `build` 就知道
- 这条自举只覆盖 `build`。`./gradlew runClient` 还要 `s3.amazonaws.com` 上的 1.7.10 资源索引
  （`:getAssetsIndex`），那条路径没有拦：想跑客户端请用自己的正版启动器 + 装 jar，或把资源索引
  手工放进 ForgeGradle 缓存

## 8. 与上游 Fabric 版的差异

| 方面 | 上游（26.1–26.4 / Fabric，Java 25） | 本仓库（1.7.10 / Forge，Java 8） |
| --- | --- | --- |
| 读取播放信息 | `java.lang.foreign` 直调 WinRT / libdbus | 外部进程 + 每行一个 JSON（`media/ProcessBridge`） |
| Windows | 进程内 WinRT | PowerShell 调 `Windows.Media.Control` |
| Linux | 进程内 libdbus + MPRIS2 | `playerctl`，退化到 `gdbus` / `dbus-send` |
| 绘制 | `GuiGraphics` + 矩形/矩阵栈 | `render/Canvas` 封 GL11 + Tessellator，裁剪按物理像素换算 |
| HUD 挂载 | `HudElementRegistry` | `RenderGameOverlayEvent.Post(TEXT)` |
| 屏幕 | `Screen` / `AbstractWidget` / `EditBox` | `gui/ScaledScreen` + `gui/Controls`（自绘控件） |
| 配置入口 | Mod Menu `ModMenuApi` | `@Mod(guiFactory = ...)` + `IModGuiFactory` |
| 按键 | `KeyMapping` + `Category` | `KeyBinding` + `ClientRegistry.registerKeyBinding` |
| 语言文件 | `assets/.../lang/en_us.json` | `assets/.../lang/en_US.lang`（`key=value`） |
| 系统字体 | 运行期写资源包 + `font` provider | **不生效**：只把选择记进配置（1.7.10 无注入点） |
| 滚轮监听 | `WheelWatch`（多后端探测） | 只在按住延迟键时轮询 `Mouse.getDWheel()` |
| 离线自测 | `SelfTest` + `ShimProbe` + 源码护栏 | 移除（见第 7 节） |

## 9. 已知差异与风险

- **已编译、已验证读取、未整轮进游戏**：JDK 8 + ForgeGradle 1.2 在本机编出
  `build/libs/smtcraft-1.0.2.jar`；`ProcessBridge` 用独立探针（不经游戏）在
  Windows 上跑通了 PowerShell + WinRT SMTC，歌名/歌手/专辑/进度/时长/播放状态逐字段正确。
  整轮「开游戏 → 看 HUD → 开设置页 → 关游戏」仍建议自己过一遍
  Windows 上封面也走通了：`Properties.Thumbnail` 是 `RandomAccessStreamReference`，
  再 `OpenReadAsync()` 一次才拿到字节（探针读到 92447 字节，哈希稳定）
- **Linux 分支未实测**：`mpris.sh` 只用 `playerctl` 与 `gdbus` 的公开命令
  （跑不了就退化到后者），播放器不支持 MPRIS2 时拿不到任何会话（这是平台限制，不是 bug）
- **PowerShell 脚本必须带 BOM**：5.1 读无 BOM 的 `.ps1` 会按系统 ANSI 代码页解释，
  UTF-8 的中文注释能把脚本整体读坏（`意外的标记 }`）。`ProcessBridge` 落盘时自动加 BOM，
  `sh` 脚本反而不能加（会让 `#!` 失效）
- **中日韩字形要 unicode 字体**：1.7.10 的默认字体页只有 ASCII，没开 `forceUnicodeFont`
  时中文歌名、日文歌词是方块和杂符。首次进游戏会按配置项 `autoUnicodeFont`（默认 true）
  自动打开并 `refreshResources()`；不喜欢就把它改成 `false`，自己在「选项 → 语言」里开
- **画矩形/贴图后必须还原 GL 状态**：1.7.10 的 `FontRenderer` 只管 `bindTexture`，自己
  不 `glEnable(GL_TEXTURE_2D)`、也不开 alpha test，所以 `Canvas.fill` / `blit` 收尾必须把
  贴图与 alpha test 打开回去，否则字形 quad 被画成实心白方块（英文也一样）
- **封面有体积上限**：base64 超过 512 KB（≈384 KB 图）的缩略图直接跳过并报一次日志；
  早先卡在 96 KB，等于 72 KB 图，实测常见的 300×300 封面就有 92 KB，一半曲目被误杀；
  取封面走媒体属性上的 `Thumbnail` 引用，`__ComObject` 上没有可用的 `Size`，
  读流要用反射拿 `AsStreamForRead` 的单参重载；切歌瞬间缩略图常常比歌名晚到，
  所以空封面会重试（每 500 ms 一次，最多 40 次），不靠"只取一次"
- **封面只在哈希变化时过管道**：每 150 ms 重发一次 base64 是白烧 IO（大图能到 3 MB/s），
  所以脚本只在 `artHash` 变化的那一条里带 `art`，Java 侧按哈希缓存、重复快照直接复用贴图；
  换歌会清空发送记录，同一张专辑封面换曲也重发一次
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
- **换系统字体无效**：设置页里的字体项会写进配置，但 1.7.10 的字形来自
  `FontRenderer` 贴图，没有可注入的 provider
- **逐曲延迟表的落盘时机**：走 `ShutdownPlan` 的 `FLUSH` 步，与上游同一套预算
  （`util/Budgets`），预算耗尽时后一步只拿到剩余时间

## 10. 目录结构

```
src/main/java/org/yinyun/smtcraft/
  SmtcCraftClient.java     @Mod 入口（平台无关：配置/媒体桥/歌词服务）
  ClientProxy.java         客户端装配：按键、HUD 挂钩、tick、关停
  CommonProxy.java         专用服务端的空实现
  SmtcConfig.java          JSON 配置、热重载、字段归一
  audio/MusicMuter.java    游戏音乐静音（gameSettings.musicVolume）
  font/FontPack.java       1.7.10 下的退化实现（记录选择，不注入）
  gui/                     ScaledScreen / Controls / SettingsScreen / 取色 / 字体选择 / 工厂
  media/                   MediaBridge 接口、ProcessBridge、NullBridge、平台选择
  render/                  Canvas / Draw / Text / 主题 / HUD / 歌词渲染 / 封面贴图
  smtc/                    歌词提供方、解析、匹配、偏移、媒体快照
  util/                    Async / Budgets / Errors / Keys / UiScale / UpdateCheck / Watchdog …
src/main/resources/
  mcmod.info
  assets/smtcraft/icon.png
  assets/smtcraft/lang/{en_US,zh_CN}.lang
```

## 11. 许可

MIT，见 `LICENSE`。第三方组件与歌词接口的来源见 `THIRD_PARTY_NOTICES.md`。
