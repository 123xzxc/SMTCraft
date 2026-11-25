# 版本记录

按时间倒序。版本号形如 `1.0.2`（主版本.次版本.修订号），与上游 Fabric 版共用同一套编号：
同一个版本号在两边的功能面一致，实现层不同。
构建、自测与设计说明见 `README.md`。

## 1.0.2 — 1.8–1.8.9 移植（与 Fabric 版同号同功能面，Forge 独立实现线）

编译目标从 1.7.10 / Forge 10.13.4.1614 换到 1.8.9 / Forge 11.15.1.2318，产物在 1.8.0–1.8.9 上通用：

- 加载器包名 `cpw.mods.fml.*` → `net.minecraftforge.fml.*`（`Mod` / `SidedProxy` / `SideOnly` /
  `ClientRegistry` / `FMLCommonHandler` / `TickEvent` / `SubscribeEvent` / `FMLPreInitializationEvent` /
  `FMLInitializationEvent` / `IModGuiFactory`）
- `@Mod(acceptedMinecraftVersions)` 改成 `[1.8,1.8.9]`；版本号不再从 `Loader` 的 `ModMetadata` 取
  （1.8.9 的 `ModContainer` 只有 `getVersion()`），改成构建期展开的 `SmtcCraftClient.VERSION`
- `mc.fontRenderer` → `mc.fontRendererObj`；`KeyBinding.getIsKeyPressed()` → `isKeyDown()`
  （后者是桩编译抓出来的：1.8.9 里这个 getter 改了名）
- 绘制：状态机全部改走 `GlStateManager`（裸 `GL11.glEnable` 会让缓存与实际不一致，
  字形会从错误的贴图里取）；顶点提交用 `GL11` 立即模式 `glBegin(GL_QUADS)` + `glTexCoord2d`/`glVertex2d`
  + `glEnd()`，颜色由 `beginFill` 一次性 `GlStateManager.color` 设定。中途曾按 1.7.10→1.8.9 的常规路子
  改成 `Tessellator.getInstance().getWorldRenderer()` + `DefaultVertexFormats.POSITION_COLOR`，
  但那个 srg 字段是 1.8.8 才加的，一进世界就每帧 `NoSuchFieldError: field_181706_f`，
  于是退回立即模式（跨版本名字一致）
- 1.8.0 兼容（真机验证）：`ScaledResolution` 在 1.8.0 只有 `(Minecraft,int,int)` 构造器，
  单参调用直接 `NoSuchMethodError` 打断渲染——新增 `util/GameScale#resolution` 按 `guiScale`
  自算 {scaledWidth, scaledHeight, scaleFactor}，`render/Canvas` 不再构造 `ScaledResolution`；
  顶点格式字段缺失见上一条。验证方式：同一份 reobf jar 装进 Forge 1.8.0-11.14.4.1577，
  进世界 HUD（封面/歌名/进度/卡拉 OK 歌词）与 F9 设置页（三标签、开关、滚动条）全部正常，
  日志无 `HUD frame failed`。对照跑 1.8.9 开发客户端同样正常，说明立即模式重写没有引入回归
- 修 HUD 文字发糊/被切（1.8.0 与 1.8.9 共有）：1.8.x 的字形是 8 px 位图图集、贴图用 `GL_NEAREST`，
  面板原来按 `cfg.scale * autoFit(...)` 缩放，`0.5 * 0.88 = 0.44` 再乘 `guiScale 4` 得 **1.76 设备像素**，
  最近邻采样跳过纹素行——标题顶行被削掉、歌手名竖笔画成双线（中文笔画粗，只有小字与西文看得出）。
  修法放在字体侧：新增 `render/FontSmoothing`，HUD 绘制期间把字形页临时改成 `GL_LINEAR`、`finally`
  里恢复 `GL_NEAREST`（`Text#draw` 每画完一段回报绑定页）。缩放保持连续——设置页滑杆仍是无级的——
  设备缩放贴着整数时保留最近邻。实测日志 `hud scale: gui 480x255 px 4 design 300x106 panel 0.44
  device 1.76 linear`，8 倍放大下笔画单线、无缺行
- 构建链换成 ForgeGradle 2.1 + Gradle 2.14.1 + MCP `stable_20`：`build.gradle` 重写，
  `gradle.properties` 增 `mappings_version=stable_20`，`minecraft_version=1.8.9`、
  `forge_version=1.8.9-11.15.1.2318-1.8.9`
- 真实编译（不只是 API 桩）又抓出三处 1.8.9 差异，已修：
  `ScaledResolution` 只剩 `(Minecraft)` 单参构造器（`render/Canvas`、`util/GameScale` 两处调用点；
  后来为兼容 1.8.0 又整段改成自算缩放，见上一条）；
  `GuiScreen.mouseMovedOrUp(int,int,int)` 改成 `mouseReleased(int,int,int)`（`gui/ScaledScreen`，
  移动事件不再经过这个方法，所以不必再判 `which == -1`）；
  `mouseClicked` / `keyTyped` / `handleMouseInput` 在 1.8.9 声明 `throws IOException`，
  重写时不带 `throws` 就调不了 `super`（同样是 `gui/ScaledScreen`）
- `build.gradle` 的 Forge maven 路径改用**完整**版本串
  （`net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-userdev.jar`）；
  早先按「forge 自己那半截」剥掉 mc 前缀去拼，三个镜像全 404。暂存仓库下载失败也不中断构建
- 语言文件与设置页里「没有字体 provider」的说法同步成 1.8.9
- 修「封面有时候会没有图」，四个根因各修一处：脚本只在 `artHash` 变化的那条快照里发 `art`，
  而 Java 侧在空窗（暂无播放 / 浏览器会话被忽略 / 换歌）时把封面清掉了，于是这首歌恢复播放后
  脚本认为"没变"、永不重发——现在封面按**所属曲目 key**（`title\u0001artist\u0001album\u0001app`，
  `ProcessBridge#artKey`）判断，`publishEmpty` 一律不清封面状态；
  空封面重试从「500 ms × 40」（20 秒）延到「500 ms × 40 + 2 s × 120」（≈4 分钟），
  耗尽时向 stderr 报一次 `this session never publishes a thumbnail`；
  超过 512 KB base64 的缩略图不再直接丢，先用 `System.Drawing` 缩到长边 512 px、JPEG q88
  再判（2400×2400 PNG：27 KB → 7 KB），缩完仍超才跳过并报一次；
  `render/ArtworkCache` 在解码抛异常或 `ImageIO` 认不出格式时各记一条 `java.util.logging` 告警，
  不再让"没封面"和"解不开"在画面上长得一样
  回归探针 `tools/probe/CoverProbe.java`（反射调 `handleLine`，不启进程）实测四步：
  带封面快照 → 空窗快照（`hasTrack=false`、封面仍是 8 字节）→ 同曲目回来但脚本不带 `art`
  （封面仍在，正是"暂停/广告之后没封面"那条路径）→ 换曲目（封面清空，没有串图）
- 兼容性实测：**1.8.8**（Forge 11.14.4.1575 + MCP `stable_20`）用同一份源码真实编译通过；
  **1.8.0** 的 ForgeGradle 2.1 userdev 只带 `binaries.jar` + `dev.json`、不带 `classes.jar`，
  `:applyBinaryPatches` 拿不到输入即失败，这是工具链缺口而不是代码差异，故 1.8.0–1.8.7 未做编译验证
- 移植完成后的自查（11 项）与修复：
  `render/FontSmoothing` 原来只改 `GL_TEXTURE_MAG_FILTER`，设备缩放小于 1 的缩小绘制走
  `GL_TEXTURE_MIN_FILTER`，那条路径依旧是最邻近采样、依旧掉纹素行——两个过滤一并设、一并还原；
  `render/Canvas#of` 改成复用同一实例（原来每帧 `new Canvas`，含 `float[192]` 矩阵栈），
  `util/GameScale#resolution` 按 `(displayWidth, displayHeight, guiScale)` 缓存，代价是返回值
  不能跨帧留存；上一帧若在 `scissor` 里抛异常，下一帧开头补一次 `glDisable(GL_SCISSOR_TEST)` 兜底；
  `mcmod.info` 的 `mcversion` 由 `minecraft_version_min`（1.8）与 `minecraft_version`（1.8.9）
  拼成 `[1.8,1.8.9]`，与 `@Mod(acceptedMinecraftVersions)` 对齐；
  编译开 `-Xlint:all`（只关 `options`/`processing`）。当时据此删掉了第二行
  `FMLCommonHandler.instance().bus()`，理由「1.8 起它就是 `MinecraftForge.EVENT_BUS` 的别名」——
  **这个理由只在 1.8.9 成立**，1.8.0 上是两条独立总线，删掉即热键与 tick 全部失效，见下一条；
  三个探针从 `build/tmp/probe` 与 `.tmpdl/probe` 搬进版本控制（`tools/probe/` 自带 README，
  `gradle clean` 不再把它们扫掉），`CoverProbe` 从新位置编译运行实测仍是 `8B / 8B / 8B / 0B`；
  README 补上「首次启动会向 `api.github.com` 查一次新版本，可用 `checkUpdates` 关掉」与
  `mcversion` 区间说明；`.gitignore` 忽略 `Github/`（上游 1.7.10 的独立 clone），
  仓库根从「没有版本控制」变成 git 仓库
- 修「F7/F8/F9 全部失效、歌词不加载只显示等待播放」（用户实测报回）：上一条删掉的 FML 总线注册
  在 1.8.9 上确实无差别——`FMLCommonHandler.instance().bus()` 与 `MinecraftForge.EVENT_BUS` 是
  同一对象；但在 **1.8.0** 上构造器 `new` 的是一条独立 `EventBus`，`onPreClientTick` 只往那条上
  post `ClientTickEvent`，于是只注册 Forge 总线时 `onClientTick` 一次都不跑，热键、歌词请求、
  HUD 快照全跟着停。改成 `ClientProxy#registerFmlBus()`：`bus() != MinecraftForge.EVENT_BUS`
  时才补注册一次（1.8.9 上因此不会重复挂监听）。1.8.0 真机复验：`now playing:` 与
  `lyrics: 89 line(s) from netease:3340673539` 恢复，F8 开关 HUD 生效（`smtcraft.json` 的
  `enabled` 跟着翻），F9 弹出「SMTCraft - 设置」（临时插桩日志 `settings key fired, screen=null`
  → `after open, screen=…SettingsScreen@`，随后移除插桩重新出 jar：312162 B、`func_` 29 处）
- 修关停序列整条作废（1.8.0 真机发现）：退出游戏时 JVM 先跑 shutdown hook，此刻模组的类加载器
  已经关了，hook 里第一句 `new Runnable()`（临时看门狗）就 `NoClassDefFoundError: ClientProxy$3`，
  后面的步骤一步都没跑——配置没写盘、桥进程留着、音量没还原，下次启动只能靠
  「restored the music volume … after an unclean shutdown」补救。两份历史日志里 `shutdown: finished`
  出现 0 次，说明这不是偶发。修法：`finishShutdown` 里不再出现 `new Runnable`，步骤按编号走
  `stepNow(name, STEP_*, arg)`（动作写在 `ClientProxy` 自己的 switch 里），看门狗改成 init 期实例化的
  字段 `shutdownWatchdog = new ShutdownWatchdog()`；只在退出时才用到的工具类由
  `warmShutdownPath()` 在 init 期 `Class.forName(name, false, loader)` 提前定义。字节码复核：
  `finishShutdown`/`runStep`/`stepNow` 里只剩 `StringBuilder`，匿名类在 init 之前全部定义完；
  jar 306728 B、`func_` 29 处
- 按新一轮检查（`临时/检查结果.md`，9 条）修复，共 6 处代码/元数据 + 1 处日志纪律：
  三个歌词 provider 的 `readText` 把响应体无上限读进 `ByteArrayOutputStream`，接口被劫持或返回
  CDN 错误页时会把整条歌词线程读成 OOM——上限收到 `util/Budgets#RESPONSE_BYTES_MAX`（4 MB），
  超限抛 `IOException` 让匹配器换下一个源；
  `smtc/SongOffsets#prune` 的注释写"oldest entry first"，实现却在 `ConcurrentHashMap` 上按迭代器
  删任意键——新增 `lastUsed`（每次 nudge 记时间戳，从文件读回的条目记 0）后真的淘汰最久未调整的键；
  `LyricService#readCache` 的 `catch (Exception)` 原来是静默 `return null`，缓存被截断时只表现为
  "每首歌都重新联网"，补一行 `LOG.fine(... Errors.describe(e))`；
  删掉 `ArtworkCache` 两处 `(int) Math.round(...)` 冗余转型（`-Xlint:all` 仅剩的两条 `[cast]`
  告警，现为 0）；删掉 `LyricService` 无人调用的 `private static String query(TrackInfo)`；
  `mcmod.info` 的 `useDependencyInformation` 由字符串 `"true"` 改成布尔；
  `NowPlayingHud` 不再每帧拼一个 `String` 比较缩放（改成 `lastPixels` / `lastPanelScale` 两个字段），
  `hud scale` 那行只在**过滤模式翻转与第一帧**打 INFO、其余变化降到 FINE——拖一次滑杆不再刷几十行。
  检查里 P3-2「8 个 STEP_* 常量无人引用」在上一条修复之后已不成立（`stepNow` 正式引用了它们）。
  构建 `BUILD SUCCESSFUL`，jar 307239 B、`func_` 29 处、`-Xlint:all` 零告警
- 关停的自查行改走文件：上一条修完之后正常退出仍读不到 `shutdown: finished in N ms`——不是序列没跑
  （同一秒里 `config/smtcraft/smtcraft.json` 被重写、桥进程也收了），而是 Forge 1.8 把
  `System.out`/`System.err` 都接进了 log4j（日志里的 `[STDOUT]` 前缀），关停时 appender 已经关了，
  连 `System.err.println` 也一起丢。现在三处关停提示走 `ClientProxy#trace`，追加到
  `logs/smtcraft-shutdown.log`（`java.io.FileOutputStream`，已登记进 `warmShutdownPath()`）。
  1.8.0 真机正常退出实测：文件里只有一行 `shutdown: finished in 44 ms`，日志
  `NoClassDefFoundError` 计数 0，本次运行没有留下 powershell 进程。jar 307723 B、`func_` 29 处
- 进度文本不再走 `String.format`：`render/Draw#duration` 每帧被调两次（`render/NowPlayingHud.java:368-369`
  的已播与总时长），一次 `format` 要新建 `Formatter`，而且 `%d` 按默认语言环境取数字——阿拉伯语
  等区域会输出本地数字，游戏字体里没有那些字形，读秒直接变成空白。改成手写 `StringBuilder(9)`
  拼接；`-Duser.language=ar -Duser.country=SA` 下实测与默认区域输出一致（`0:56` / `5:50` / `1:39:59`）
- 按第三轮检查（`临时/检查结果.md`，8 条 N-7…N-14）修了 7 条、1 条只补说明：
  设置页点过文本框再点别的控件时焦点不释放——`SettingsScreen#onClick` 是按页面顺序把点击交给控件、
  命中即返回，而"失焦即提交"写在 `Controls.Field#click` 里，开关/滑条/按钮都排在文本框前面，那段
  永远不执行，于是键盘被隐形文本框吃掉、第一次 Esc 只换来一次 commit（要按两次才退出设置页）。
  新增 `commitFocus()`，派发点击前与 `onGuiClosed` 都走它；
  `SmtcConfig#saveNow` 落盘改成先试 `ATOMIC_MOVE`、不支持时退回 `REPLACE_EXISTING`（每首歌静音/恢复
  都会写一次配置，非原子 rename 断电会留下半份 json，下次启动只能 quarantine 回默认值）；
  `SyllableParsers#qrcLineTexts` 的格式判据从"翻译表里有没有左括号"改成"有没有一行命中 QRC 行头"——
  纯 LRC 翻译里带 `(Live)` / `(feat. …)` 时整张表会被送进 QRC 解析器、第二行翻译整段消失；
  `font/FontPack#appliedKey` 是只写不读的死字段，删掉；`render/ArtworkCache` 的纹理上传改用一块按需
  长大的复用直接缓冲（夹具最大 256²=256 KB，原来每张封面都新分配一次堆外内存）；
  `UpdateCheck` 的 `{@link #look}` 断链改成 `{@link #fetch}`；`ClientProxy#trace` 的说明与
  `shutdownLogDir` 字段的说明原来是两段 javadoc 连写、`trace()` 自己没有文档，已拆开；
  N-14（关停末步删纹理时 GL 上下文可能已销毁）只在 `ArtworkCache#clear` 补了说明——异常被 `stepNow`
  记进 `logs/smtcraft-shutdown.log` 就结束，退出本来就要丢纹理，不算缺陷。
  回归探针 `build/probe/TransProbe`：纯 LRC 带括号的翻译表两行翻译都在、真 QRC 翻译表照旧解析。
  构建 `BUILD SUCCESSFUL`，jar 308331 B、`func_` 29 处、`-Xlint:all` 零告警
> `gradle build` 已真实跑通：产物 `build/libs/smtcraft-1.0.2.jar`（305 KB），`mcmod.info` 的
> `mcversion=[1.8,1.8.9]`，且对 Minecraft 的引用已 reobf 成 srg 名（`javap` 里 `func_` 29 处）。`gradle runClient` 也跑通：
> 1.8.9 开发客户端加载 4 个模组，打出 `SMTCraft 1.0.2 on Forge 1.8.9: reader=powershell (WinRT SMTC)`，
> 进程桥 150 ms 轮询启动、读到当前曲目 + 19099 字节封面、抓到 77 行网易云歌词、自动打开 unicode 字体，
> HUD 面板在游戏内渲染正常（圆角底、进度条、中文歌名与歌词，无白方块）。
> 素材下载一度 `Connection refused`，根因是 Gradle user home 里一条失效的 http 代理，删掉即通。
> 详见 README「已知差异与风险」。

## 1.0.2 — 1.7.10 Forge 移植（历史）

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