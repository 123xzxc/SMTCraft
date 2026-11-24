# 版本记录

按时间倒序。版本号形如 `1.0.2`（主版本.次版本.修订号），与上游 Fabric 版共用同一套编号：
同一个版本号在两边的功能面一致，实现层不同。
构建、自测与设计说明见 `README.md`。

## 1.0.2 — 1.7.10 Forge 移植

同版本号的第一次 1.7.10 构建。功能面与 Fabric 版一致，实现层按 1.7.10 的能力重写：

- 读取播放信息从 `java.lang.foreign` 直调系统库改成外部进程 + 每行 JSON
  （Windows：PowerShell + WinRT `Windows.Media.Control`；Linux：`playerctl`，退化到 `gdbus`）
- 绘制从 `GuiGraphics` 改成 `render/Canvas` 封装的 GL11 + Tessellator；HUD 挂到
  `RenderGameOverlayEvent.Post(TEXT)`
- 界面从 `Screen` / `AbstractWidget` / `EditBox` 改成 `gui/ScaledScreen` + `gui/Controls` 自绘控件
- 配置入口从 Mod Menu 改成 `@Mod(guiFactory)` + `IModGuiFactory`
- 语言文件从 JSON 改成 `.lang`；键位改成 `KeyBinding` + `ClientRegistry`
- 换系统字体在 1.7.10 没有注入点，设置页里如实标注「不生效」
- 上游 `build.sh` / `test.sh` / 离线自测没有搬过来，构建改走 ForgeGradle 1.2 + JDK 8

> 这一版用 JDK 8 + ForgeGradle 1.2 真机编译并通过 `reobf`，产物
> `build/libs/smtcraft-1.0.2.jar`（Java 8 字节码）。进程桥用独立探针在 Windows 上
> 实测读到了当前播放（歌名/歌手/专辑/进度/时长/状态全部正确）；整轮进游戏流程待自行确认。
> 详见 README「已知差异与风险」。

### 可发布仓库的构建脚手架

- build.gradle 在配置阶段自行补齐两处已经失效的下载：ForgeGradle 1.2 硬编码的
  s3.amazonaws.com/Minecraft.Download（版本 json / 客户端 jar / 服务端 jar，改从
  launchermeta / launcher.mojang.com 取，取到后禁用那三个下载任务），以及官方仓库已不再发布的
  net.minecraftforge:forge maven 元数据（在 .gradle/forge-repo 里写最小 pom 并下载
  -userdev / -universal 两个 jar）
- 用全新的 GRADLE_USER_HOME（无本地代理、无 toolchain/m2）实测：一次 gradle build
  1 分 33 秒通过，产物 137 个条目、Java 8 字节码
- 仓库带 Gradle wrapper（钉在 2.14.1）：clone 下来只要有 JDK 8，`gradlew.bat build` /
  `./gradlew build` 就能出 jar。不放在线 CI，构建在本地完成

### 同版本内代码审查修复

- 切歌后封面丢失且不再恢复：缩略图只在换歌那一瞬取一次，媒体属性先到、图后到就永远空着；
  改成空封面重试（每 500 ms、最多 40 次，失败告警每首歌只报一次）
- 封面体积上限卡错量纲：脚本拿 base64 长度去比 96 KiB（等于 72 KiB 图），实测 92 KB 的封面被
  静默丢掉，于是「明明有封面却显示不出来」；上限提到 512 KiB base64，超限时记一次日志并停止重试
- 设置页「项目主页/问题反馈/发布页」打不开浏览器时显示键名：补 `smtcraft.about.link_copied`，
  并且复制提示占位时让出「注意」那行，不再挤在 8 px 之内
- 取色页在网格左/上方空白点下去会误选第 0 列/行：补 `mouseX < gridX` 边界判断
- 圆角面板逐行一次 `glDisable` + Tessellator 提交：`Canvas.fillRound` / `outlineRound` 改成
  一次 `startDrawingQuads` 里塞完所有行，`Draw` 里两个方法转调
- 逐字歌词每帧四组小数组：`Glyphs` 跨行复用，与 `localBuf` 等缓冲同一套写法
- 滑杆滚轮注释承诺 Shift 十倍速而实现没有：按 Shift 时步长 ×10
- `ArtworkCache.texture` 的 `size` 参数被丢弃（永远 88×88）：尺寸进缓存 key、进解码，上限 256
- 依赖显式化：`build.gradle` 写 `compile "com.google.code.gson:gson:2.2.4"`（与游戏自带同版本）
- 歌词缓存目录改用 `mcDataDir`：启动器改了 `user.dir` 时不再把缓存写到别处
- `bridge` 未装配时客户端队列这一 tick 不抽干：`drainClientQueue` 移到空检查之前
- 子进程 stderr 按 1024 字节块严格解 UTF-8：多字节字符跨块会被拆，改为保留残字节下一块拼
- 封面 base64 每条快照都重发（150 ms × 123 KB ≈ 0.8 MB/s，大图 3.4 MB/s）：脚本只在 `artHash`
  变化的那一条里带 `art`，换歌重置发送记录，Java 侧本来就按哈希缓存贴图
- Linux 每轮轮询都重新下封面（`curl --max-time 3` 比轮询间隔还长，外加两次 `cksum` fork）：
  按 `artUrl` 作 key 缓存，超时改成 `--connect-timeout 1 --max-time 2`
- 配置与桥脚本按 `user.dir`、歌词缓存按 `mcDataDir`：三样东西两个基准，改成统一走
  `config/smtcraft/`（`MediaBridges.create(config, configDir)` 转 `ProcessBridge(config, configDir)`）
- 轮询间隔三套范围（滑杆 60–2000、配置 50–2000、实际 100–5000）：统一成
  `SmtcConfig.MIN_POLL_MS` / `MAX_POLL_MS`，脚本里的 clamp 同步成 2000
- 脚本上限常量与封面告警：`$ART_LIMIT` / `ART_LIMIT` 只在一处定义、门禁引用变量；
  `mpris.sh` 超限不再静默，`printf ... >&2` 报一次
- `ProcessBridge` 两块连续 javadoc 合并，残留的「96 KiB」说明改掉
- 连续失败上限与 5 秒退避被"每 tick 一次 `setConfig`"打穿：适配器起不来时 `reconfigure`
  每次都清 `gaveUp`、清 `restartAt`，退化成约 50 ms 拉一次进程刷警告；现在只有间隔真的
  变了才算配置变更，进程没活着时也不碰退避（这条同时修了 `builder.start()` 抛异常时
  `launchedIntervalMs` 一直为 -1 的重复重试）
- 拖轮询滑杆连续重启适配器：间隔每跨一个整数就 `destroy` 重拉；改成静置
  `INTERVAL_SETTLE_MS`（400 ms）后才重启，一次拖动只重启一次
- Linux 封面下载失败一次就整首歌空着：`prepare_art` 在下载前就把 `ART_URL` 记成新地址，
  失败即"已处理"，再也不会重试；改成成功后才记、失败按 `ART_MAX_TRIES` 重试并告警一次
- 面板宽度两个真值：滑杆 120–420、`normalise()` 夹紧 160–470；统一成
  `SmtcConfig.MIN_PANEL_WIDTH` / `MAX_PANEL_WIDTH`
- 「恢复出厂」漏 `autoUnicodeFont`：被 JSON 关掉之后再点恢复出厂仍是关着的，补上这一项

### 同版本内实机修复

- 桥脚本读不到 SMTC：PowerShell 5.1 启动时不加载 `System.Runtime.WindowsRuntime`，改成先
  `Add-Type`；`.ps1` 落盘加 UTF-8 BOM，否则中文注释按 ANSI 读会整段语法错
- 模组列表里点 Config 崩溃：FML 反射找的是 `(GuiScreen)` 单参构造器，`SettingsScreen` 补上
- 中文/日文全是方块：首次 tick 按 `autoUnicodeFont` 打开 `forceUnicodeFont` 并重建资源
- 连英文都变白方块：`Canvas.fill` / `blit` 关掉 `GL_TEXTURE_2D` 后没有还原，`FontRenderer`
  自己不打开它，于是每个字形都画成实心矩形；`Canvas` / `Text` 现在把贴图、alpha test、
  blend 全部还原
- 封面一直是 0 字节：缩略图在媒体属性上（`RandomAccessStreamReference`），要先
  `OpenReadAsync()`；`__ComObject` 上没有 `Size`，读流改走反射的 `AsStreamForRead`

## 1.0.2（Fabric 版同步内容）

- 修复按键设置后仍然可以使用 F7+滚轮修改延迟的 Bug
- 修复了部分情况下歌曲有多个名字的版本会优先匹配到原版名字歌曲歌词的 Bug
- 修复缩放问题，现在在任何分辨率/游戏缩放下都可以正常显示了
- 主题设置中选项按钮将会显示实际颜色，而非滚动的十六进制颜色代码
- 加入了在关闭黑胶唱片选项时的曲绘圆角设置
- 加入了打开菜单时不显示的选项
- 加入了只显示歌词的选项
- 优化版本号规范
- 优化文本
- 加入了设置歌词翻译颜色的功能
- 加入了边框透明度选项
- 加入了更新检查功能

## 1.0.1

- 单个 jar 支持 Minecraft 26.1 到 26.4-snapshot-10

## 1.0.0

- 首个公开版本：把系统当前播放的曲目与同步歌词画进 HUD