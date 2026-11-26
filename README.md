# SMTC Now Playing — Minecraft 26.1–26.4 Fabric 客户端模组

在游戏 HUD 里显示系统当前正在播放的媒体（歌名、歌手、专辑、进度、来源应用、专辑封面），
并同步显示**歌词与翻译**（歌词来源：酷狗 / QQ 音乐，支持 KRC / QRC 词级时间轴），
支持卡拉 OK 逐字高亮、可切换字体与配色主题。
播放信息在 Windows 上来自系统媒体会话 **SMTC**（GlobalSystemMediaTransportControls），在 Linux 上来自
**MPRIS2**（D-Bus 会话总线），两条路径都由模组**直接用 Java 读取**（FFM 直调系统库）：
没有 PowerShell、没有外部进程、没有 JNI/JNA、没有额外前置模组。

作者：**123xzxc** · 许可：MIT · 环境：仅客户端（`environment: client`，不发送任何数据包）

> **说明**：本 Mod 部分/全部由 AI 生成，所有改动均经人工审阅与实机验证后发布。

---

## 目录

| 章节 | 内容 |
| --- | --- |
| [1. 功能](#1-功能) | HUD 显示内容与歌词能力 |
| [2. 环境要求](#2-环境要求) | 游戏版本与依赖 |
| [2.3 版式：上下与左右](#23-版式上下与左右) | 并排面板、贴缝对齐与两块的前后顺序 |
| [3. 安装](#3-安装) | 放进 mods 目录 |
| [4. 使用](#4-使用) | 快捷键、设置入口、滚轮调延迟 |
| [5. 配置文件](#5-配置文件) | `config/smtcraft/smtcraft.json` 全字段与默认值 |
| [6. 工作原理](#6-工作原理) | SMTC 读取、采样、HUD 绘制 |
| [6.1 歌词链路](#61-歌词链路) | 词源、匹配、缓存、逐字时间轴 |
| [6.2 Linux 与 MPRIS](#62-linux-与-mpris) | 平台选择、播放器挑选、依赖 |
| [7. 从源码构建](#7-从源码构建) | `build.sh` / Gradle 与可复现构建 |
| [7.1 线程模型](#71-线程模型) | 五条后台通道与退出顺序 |
| [8. 目录结构](#8-目录结构) | 源码布局 |
| [9. 版本记录](#9-版本记录) | 一行一版的摘要，完整历史在 CHANGELOG.md |
| [10. 许可](#10-许可) | MIT 与第三方组件 |

## 1. 功能

- HUD 面板：封面、标题、歌手、专辑、进度条、时间、来源应用
- 封面：真实专辑图（SMTC Thumbnail 流，PNG/JPG 原样解码为游戏贴图，LRU 12 张缓存）
- 进度条按采样时间在客户端外推，700 ms 采样也能连续走动
- 四角/居中五种停靠位置，缩放、宽度、透明度、偏移、颜色都可调
- **游戏内设置界面**：按 `F9` 打开，改完立即生效并写回配置
- **关于页**：设置界面第五个标签页显示当前版本，一键检查最新发布标签
  （只读最新发布标签：先试站点的 `releases.atom`，再问 `api.github.com` 的 releases 接口，
  最后读 `releases/latest` 的跳转，都不上传任何信息），
  有新版时按钮直接打开发布页；失败时只显示一行中文原因（限流 / 连不上 / 证书或代理挡住 /
  没读到），三条路的原始诊断串留在日志里。下面一行的 `检查更新` 开关关掉就完全不请求：
  关于页与 Mod Menu 徽标答「更新检查已关闭」，按钮点一下就是「打开并检查」，而不是拿旧答案或一句「正在检查」充数。
  Mod Menu 的模组卡片上也会显示同一条更新提示，并带 网站 / 源码 / 问题反馈 三个链接，都指向仓库
  https://github.com/123xzxc/SMTCraft （Mod Menu 每个模组只问一次更新检查器，所以返回答案前最多等
  2.5 秒；关掉开关时直接答「已关闭」）。
- `F8` 一键开关 HUD（可在“按键设置”里改键）
- 没有媒体播放时按配置隐藏；暂停时可选择隐藏
- **歌词窗口**：当前行 + 上下文（1–4 行可调），自动带上歌词自带的翻译行，按进度滚动
- **卡拉 OK**：当前行按播放进度从左到右填充强调色；扫描边缘做 10 px 羽化，按每个字的中心距离混色，
  所以是一道平滑推过的渐变，不是一个个字突然变色（可关，关掉即整行高亮）
- **独立字号**：歌词与翻译各有自己的字号滑条（0.6–2.0 倍），互不牵连
- **黑胶唱片**：封面可切换成旋转的黑胶盘（按曲目重置角度，暂停即停转）
- **播放时静音游戏音乐**：只在 SMTC 有曲目且在播放时把音乐音量压到 0，停止后原值还原
- **字体**：默认 / 强制 Unicode / 本机字体（中/英/日/其他四槽位，带逐槽位效果预览）/ 自定义字体资源 id
- **主题**：网易红 / 午夜蓝 / 极光 / 樱花 / 日落 / 单色 六套配色；主文字、次要文字、强调色、
  歌词底色、卡拉 OK 色都能单独点开取色器改（色板 + RGB 滑条 + 直接输 #RRGGBB）
- 歌词本机缓存 120 天（`config/smtcraft/lyrics/*.json`），同一首歌不会重复请求
- **自适应宽度**：面板宽度按「曲名 / 歌手 / 专辑」的实际像素长度收放（设下限为设计宽度、
  上限 470 px），歌词宽度不参与；关掉即固定宽度
- **跑马灯**：曲名、歌手、专辑、歌词行超长时都左右循环滚动（曲名与歌词可分别关闭）
- **换歌不闪**：切歌宽限期（默认 1200 ms）内沿用上一首快照，不会把切歌瞬间判成「没有媒体」而整个面板消失
- **卡拉 OK 增幅**：播放头附近的字会隆起放大（增益 0–0.6、半径 0–80 ms 可调，可关）
- **长音缓行**：正在唱的长音会被横向拉长成真正的卡拉 OK 长音。没有逐字数据时，扫过速度还按字长估计
  （延长符、重复元音占更多时间）；有逐字数据时时间轴本身是精确的，这一项就负责按**真实音符时长**决定拉伸幅度
  ——超过约 0.2 秒开始拉、0.7 秒拉满（约 42%），比本行中位数明显更长的字再加权，
  真正的卡拉 OK 观感（可关，关掉就是每个字均分扫过）
- **歌词粗体/斜体**：两个开关，作用于歌词与翻译
- **无翻译双行**：没翻译的歌词默认显示成上下两行（可关），换行带 0.28 s 过渡；第二行用翻译字号
- **圆角面板**：0–12 px 圆角；**进度条平滑**开关；**黑胶转速** 20–120 °/s（需先开黑胶封面）
- **进度从头计时**：取不到播放进度时（如网易云官方客户端不写 SMTC Timeline）可选从 0 开始自行计时
- **忽略浏览器会话**：只认音乐软件，Edge/Chrome 的网页播放不再抢占 HUD
- **本机字体**：中 / 英 / 日 / 其他四种语言各选一个本机 `.ttf/.otf/.ttc`，模组自动生成
  `resourcepacks/smtcraft_fonts` 资源包并热重载；字体 id 为 `smtcraft:systemfont`
- **不显示曲绘时标题居中**（可关；来源应用名仍靠右）
- 颜色设置项上方标注该颜色具体作用于什么；`key.categories.smtcraft.main` 显示为 **SMTCraft**

## 2. 环境要求

| 项目 | 版本 |
| --- | --- |
| Minecraft | 26.1、26.1.1、26.1.2、26.2、26.3、26.4（含 26.4 快照 1–10）——**同一个 jar 通吃**，见 2.1 |
| Fabric Loader | 0.16.0 及以上（开发时使用 0.20.1） |
| Fabric API | 0.155.3 及以上（编译时用 0.166.x 的模块，模组只用到其中五个类，见 2.1） |
| Java | 25 及以上（`fabric.mod.json` 声明 `>=25`，26.x 官方即 JDK 25） |
| 系统 | Windows 10/11（SMTC）；Linux（MPRIS2，需 `libdbus-1`）；其它平台不显示 HUD |

### 2.1 跨版本兼容

一个 jar 覆盖 26.1 到 26.4 快照 10。做法是：**编译对齐区间里最旧的 26.1**，运行期把七处
签名与语义变化用反射消解，各自选到这一版真正存在的那条路。

| 差异点 | 26.1 – 26.4-snapshot-N 的变化 | 本模组的处理 |
| --- | --- | --- |
| 按键状态 | 26.1–26.2 是 `InputConstants.isKeyDown(Window,int)`，26.3 起是 `isKeyDown(int)` | `util/Keys`：先试 `(int)`，退化到 `(Window,int)` |
| 控件可见性 | 26.1–26.4-snapshot-1 是 public 字段 `visible`；snapshot-2 起变 protected 并给出 `setVisible(boolean)` | `gui/Widgets`：优先 `setVisible`，没有就写字段 |
| 屏幕渲染入口 | 26.1–snapshot-4 是 `extractRenderState(extractor,int,int,float)`；snapshot-5 起只传 extractor（坐标改从 extractor 取） | `gui/ScreenHooks`：两个签名都声明在 Screen 子类上，实际绘制走同一个私有 `draw(...)` |
| 封面纹理上传 | 26.1–snapshot-2 用 `DynamicTexture` + `TextureManager.register`；snapshot-3…7 删掉 DynamicTexture，只留 `TextureResources.from2dImage` + `register(Identifier,TextureResources)`；snapshot-8 起 `register` 也没了，要 `ExternalTextureProvider` + `registerProvider` | `render/CoverTexture`：按「direct → provider → dynamic」顺序试，第一条能解析的就用 |
| HUD 渲染入口 | 26.1–26.3 的 `HudElement.extractRenderState` 收 `(extractor, DeltaTracker)`；26.4 只收 `(extractor)` | `render/NowPlayingHud`：两个重载都写成实方法，共用私有 `drawHud(...)`；26.4 没有 tracker，帧时间自己在 `wallStep()` 里量 |
| 滚轮事件 | 26.1–26.2 走 GLFW（classpath 里没有 `org.lwjgl.sdl`），26.3 起走 SDL | `util/WheelWatch`：纯反射，SDL 那边用 `SDL_AddEventWatch`（返回 `false` 也算拒绝）；GLFW 那边换成自己的 `glfwSetScrollCallback` 并把每个滚动事件转发回 `MouseHandler`。两个后端在启动瞬间都可能还没就绪，所以安装失败会重试到十秒、成功后每秒把回调再按回去一次。**不碰 `SDL_SetEventFilter`**：SDL3 只有一个全局过滤器，抢下来会顶掉游戏自己注册的那个且无法还原 |
| 按键编号 | 名字没变但含义变了：`InputConstants.KEY_F7` 在 26.1 是 296（GLFW 键码），在 26.3 是 64（SDL scancode，因为它直接按 scancode 索引 SDL 的键盘状态缓冲区）；Ctrl 同理 341/345 → 224/228 | `util/Keys.key("KEY_F7", 296)`：按名字反射读这一版自己声明的常量值，再交给 `isKeyDown`。写死任一编号都会在另一半版本上问成一个**别的键**——这正是「26.3 之外 Ctrl+F7 全部无效」以及早期滚轮微调失效的原因 |

启动时日志会打两行：`game api: cover=…, screen=…, widget=…, key=…, wheel=…` 写明这一版实际走了哪条路
（`wheel=` 在第一个 tick 之前是 `unresolved`，之后另起一行写 `mouse wheel watch: glfw` 或 `: sdl-watch`），
`keys: offset=…, LCONTROL=…, RCONTROL=…` 在第一个 tick 打：`offset` 是「歌词延迟调整」这个映射当前绑的键（改键后就跟着变），
后两个是修饰键的键号（没有映射可查，26.1 是 341/345，26.3 起是 224/228）；
`screen=` 与 `key=` 还会带后缀：`/coords(field)`、`/coords(method)`，两轴路线不同（或只有一轴在）时写成
`/coords(mouseX=field,mouseY=absent)`——括号里两个值各自可能是 `field` / `method` / `absent`
（这一版的 frame 把鼠标坐标放在哪——两版都是私有字段 `mouseX`/`mouseY`，只认 public 方法的查找会
两轴都缺，页面就把整页画在 (0,0)：高亮全灭而点击照常）、`/scancodes`（这一版的键号是
scancode）。同一个 tick 还会打 `screens: minecraft.screen`（或 `gui.screen()`、`unavailable`）——
那是「打开菜单时隐藏」靠的那条反射路，答 `unavailable` 会走 WARN 并说明这个开关在本版不起作用。
反馈问题时带上这三行就够了。
解析不到只会降级（封面不显示、按键无响应、滚轮微调失效），不会崩；滚轮彻底装不上时 HUD 会自己说一句，
不必翻日志。
需要提醒的是：最后三处（HUD 渲染入口、滚轮、按键编号）都是**真机跑起来才暴露**的——前四处靠逐版本
编译就能发现，这三处编译期两者都合法：26.1 上是入口类加载期炸、26.4 上是进世界后第一帧炸，
按键编号则一句错话也不报，只是 Ctrl 永远读成「没按」。

### 2.2 界面尺寸与缩放

设置页与 HUD 都按**界面尺寸 2** 排版：尺寸 4 时整屏逻辑像素只有 480×270，14 行设置被压成 9 px 的文字；
尺寸 1 时反过来，所有东西只有一半大小。模组因此自己乘一个系数 `2 / 界面尺寸`（限制在 0.4–2.0），
页面按「设计像素」排（`ScaledScreen.vw()` / `vh()`），绘制时整体缩放一次：

| 界面尺寸 | 系数 | 页面效果 |
| --- | --- | --- |
| 1 | 2.0 | 与尺寸 2 观感一致 |
| 2 | 1.0 | 原始设计（基准） |
| 3 | 0.667 | 同上 |
| 4 | 0.5 | 同上 |

设置页「显示」页有一行 `跟随界面尺寸（以界面尺寸 2 为基准）`，默认开启；关掉就退回游戏自己的缩放
（尺寸 4 下会重新挤成一团）。HUD 的 `scale` 也跟着乘同一个系数，`autoScale` 的自动缩小仍在其后生效。
这一行的按钮文字**故意反着印**：显示「关」表示跟随已经开启，显示「开」表示跟随已关闭。

行距不再随窗口压缩：页面至少 22 设计像素一行，放不下就滚动（右侧滚动条，鼠标滚轮/拖拽都能用）。
跨在内容区边界上的那一行整行隐藏，而不是用 `enableScissor` 裁剪——裁剪矩形是按当前 pose 变换后与
**逻辑像素**的指针比较的，缩放过的页面用它会让所有控件都判定为「未悬停」，高亮和提示全部消失。
### 2.3 版式：上下与左右

HUD 是两块：**曲目块**（封面、曲名、歌手、专辑、进度）与**歌词块**。默认上下叠放。
设置页「显示」页有两行开关，配置键分别是 `hudLayout` 与 `trackFirst`：

| 开关 | 配置 | 效果 |
| --- | --- | --- |
| 左右排版 | `hudLayout = side` | 两块并排：曲目块一列、歌词一列，中间一条竖细线；关掉回到上下 |
| 曲目块在前 | `trackFirst = true` | 上下时曲目块在上、左右时在左；关掉则歌词块先占上 / 左 |

版式是**开关**，不是对窗口形状的猜测：横屏窗口也能用并排，全屏也能用上下（`autoScale` 仍会在
面板超出视口 55% 宽 / 72% 高时整体缩小）。

并排时的细节：曲目块保持自己的宽度（`panelWidth`，`adaptiveWidth` 打开时按标题长到 470），
歌词列固定设计宽度——让长标题把歌词行也撑宽，一行就只剩两三个字了。两块高度不同时较矮的那块
**垂直居中**，而不是吊在顶边。上下时两块之间是一条横细线，并排时换成两块之间的竖线。

并排时歌词**贴着那条竖线对齐**，而不是在列里居中：曲目块在前（`trackFirst = true`，歌词在右）
时每行从缝的左沿起排（左对齐）；歌词在左时每行靠缝的右沿收齐（右对齐）。这样两块读起来像一张
表格的两栏，而不是两座各自居中的孤岛。行宽超过列宽（跑马灯滚动的那种长句）时这一行仍旧居中——
跑马灯是在那个框里滚的，挪动框会把字推出列外。上下叠放时歌词仍居中，这一条只对并排生效。

两种情形会自动退回上下：**没有歌词块**（没在放歌、`showLyrics` 关掉、或者这个源拿不到歌词）
时没有第二块可以并；**只显示歌词**（`lyricsOnly`）时曲目块不存在，版式开关对它没有影响。

### 2.4 竖排面板（整块转 90°）

`hudLayout = vertical` 时整块 HUD 变成竖排：封面在左上，封面**底端是一条共用基线**——曲名、歌手、
专辑、时间全部从这条线往下排（没有封面时就是面板内边距），像一页印刷品的各列共用一个顶边；
再往右是时间、进度条，最右边是歌词列。

| 开关 / 滑杆 | 配置 | 效果 |
| --- | --- | --- |
| 竖排面板 | `hudLayout = vertical` | 整块转 90°；关掉回到上下 |
| 竖排面板高度 | `verticalPanelHeight` | 0 跟随全曲最长的一句歌词（168–320）；96–480 之间固定（1–95 会被吸附到 96） |

细节：

- **汉字、假名、谚文保持直立**（字头朝上，一个字叠一个字往下走），**拉丁字母、数字与 ASCII 标点
  顺时针转 90°**——直立排下来的英文每个字母都像首字母，转过去才读得出是一个词
  （`LyricsRenderer.upright`）。两者都占一格，所以列距与字峰的位置不跳。
- 歌词是**两列**，贴着左边那条竖线（曲目块与歌词的分界）从内往外排：当前行**紧贴分界线**
  （这一列没有内边距，字就压在线的右侧 1 px），译文在右一列（以前译文续在本行下面，长句看起来像
  同一句念了两遍）。**不画第三列「下一句」的灰字**——它读起来像这句的第二个译文。
- **歌词大小滑杆在竖排里同样生效**：竖排的列宽、字高、自动高度都按同一个 `lyricScale` 计算。
  汉字是逐字直立画的，这一支曾经只应用字峰、不应用缩放，滑杆看起来对汉字完全没反应
  （`drawTurned` 的 upright 分支）。
- **长句会自己上下走，只走一趟**：一列比面板高时，先停 1.3 s，再以 26 px/s 缓缓下移，最后一个字
  露出来后**就停在那儿**，不再往回弹（`HudLayout.verticalScroll`）。竖排没法用横向跑马灯，只能让
  自己的轴走——否则多出来的字就被裁掉了；反过来弹的话，一列字每几秒被倒着读一遍，眼睛永远落不到句尾。
  关掉「歌词跑马灯」（`marqueeLyrics`）这一趟也停：超出的字留在格子外，不再动。
- **竖排的速度不跟着播放头走，是有意的**：上面那趟按「这一句自己的年龄」推进，而不是把已唱的字钉在
  面板的某个比例上。并排/上下版式能钉在 68% 是因为有宽度可钉；一列字没有这个维度，真按播放头缩进
  的话，还没唱到的那半句会整段停在格子外面，一等就是好几个小节。固定步速是一列字唯一走得通的形状。
- **跑马灯只有一个速度**：横排的跑马灯、竖排的歌词列、曲名/歌手/专辑列共用
  `HudLayout.MARQUEE_SPEED`（26 px/s）与 `HudLayout.MARQUEE_PAUSE`（1.3 s）。竖排的歌词列曾经比跑马灯
  慢 4 px/s、少停 0.1 s，两条一起看就像两个面板。
- **曲名、歌手、专辑也会上下走，而且是来回走**：它们比歌词短，停在句尾会让人看不到开头，所以走到底
  再退回开头（`HudLayout.verticalScrollCycle`）。每一列都裁在自己的格子里，滚到封面以上的字不会画到
  封面上。这几列跟着「曲名跑马灯」（`marqueeTrack`）走，两行开关各管各的一半。
- **面板宽度算上了封面**：只开「显示曲目」+「显示封面」、文案又极短时，面板至少留出
  `内边距 × 2 + 封面宽`（50 设计像素），封面不会顶出面板右缘。以前宽度只按文字列算，这种组合下
  封面会溢出 28 px，还没有裁剪兜着。
- **挂机三天以上不会越走越快，也不会停住**：跑马灯的时钟是 `double` 累加。
  用 `float` 累加时，超过 3.03 天（2^18 s）每帧的步长就不足半个 ULP，时钟会以 1.875 倍速走；
  超过 6.07 天加法直接舍回原值，跑马灯**冻在半个字上**，只有换歌或重开游戏才恢复。
- **整点也不再各自回跳**：相位的取模按每条文本**自己的周期**在 `double` 里做，不再先对某个固定秒数取模。
  先前对 3600 s 取模，而 3600 不是任何一条滚动周期的整数倍，于是每挂满一小时，曲名、歌手、专辑几列
  就在同一帧里各自跳回自己的第一个字（跳幅是文本宽度的函数，实测 0–159 px，三列看着像三处故障）。
  现在 `HudLayout.scrollCycle` / `verticalScrollCycle` 收 `double` 时钟、按本周期取模，任何一帧的位移都只
  取决于文本长度与速度。
- **字峰在竖排下也生效**：播放头所在的那个字一部分已唱、一部分未唱（逐字插值），头部附近的字会提亮
  并轻微放大，放大不改字距，免得整列随节奏抖动。
- **时间夹着进度条**：已播在条的上方、总长在条的下方，字号 0.72 倍；整组从面板顶端开始，
  第一个数字和封面顶边齐平。总长与进度条之间留 6 px，免得看着粘在条上。
- **间距比横排紧**：面板内边距 3 px，曲目列间 3 px，歌词列间只留 1 px（一列汉字自带边白，
  3 px 在两句之间看着像个洞），曲目块与歌词之间 3 px。
- **高度「自动」跟着歌词走**：整首歌词里最长的那一句（以及它的译文）决定面板高度，所以长句不会被
  截断、短句也不会让面板忽高忽低；量一次按歌缓存，不用每帧重算。最低 168 px，再矮就把歌词压掉了。
- **关掉「显示歌词」时这里没有歌词块**：面板收到只剩曲目列，不画分隔线，也不写「暂无歌词」。
- 没有音乐时提示语也竖排；`lyricsOnly` 时只剩歌词列，宽度自动收到一列多。
- 「竖排面板高度」滑杆**单独占一整行**：它一度和「横向偏移」抢同一个格子，把后者整个盖住了。

## 3. 安装

1. 安装 26.1 – 26.4 中任意一版的 Fabric Loader，并把 Fabric API（≥ 0.155.3）放进 `mods`
2. 把 `build/libs/smtcraft-1.0.3+mc26.1-26.4.jar`（文件名里的区间就是这个 jar 的适用范围）
   放进 `.minecraft/mods`（同一 mod 只能留一个版本，旧的必须删掉）
3. 启动器的 JVM 参数加上 `--enable-native-access=ALL-UNNAMED`（老版本启动器写作
   `-J--enable-native-access=ALL-UNNAMED`），并去掉 `--illegal-native-access=deny`。
   JDK 24+ 对此只警告不报错，缺这一项时表现为「没有会话」、HUD 空白；原因见第 7 节排障
4. 启动游戏。HUD 默认出现在左上角
5. 可选：装 Mod Menu（modrinth.com/mod/modmenu）后，模组列表里本模组右侧会出现 ⚙ 设置按钮
6. Linux 额外需要系统的 D-Bus 库：Debian/Ubuntu `libdbus-1-3`、Arch `dbus`、Fedora `dbus-libs`。
   缺库时游戏能正常启动，只是不显示 HUD（设置页与日志会写明原因）

## 4. 使用

| 入口 | 作用 |
| --- | --- |
| `F8` | 开关 HUD |
| `F9` | 打开设置界面（可改键） |
| 模组列表 → 本模组 → ⚙ | 同一个设置界面（需要 Mod Menu，关闭后回到模组列表） |
| 模组列表 → 本模组 → 网站 / 源码 / 问题反馈 | 由 `fabric.mod.json` 的 `contact` 块提供（需要 Mod Menu） |
| 设置界面 → 关于 | 版本号、检查更新、发布页 / 问题反馈 / 项目主页三个链接 |

设置界面底部会显示当前读取状态：正在读取的曲目、来源应用、距上次采样的毫秒数，
若读取失败会显示具体 HRESULT 错误，便于排查。界面底部还会显示歌词状态
（`searching` 搜索中、`cached` 用缓存、`no match` 没匹配到、`hit <曲名>` 命中）。

设置界面分五个标签页：**显示**（开关、滑条、停靠位置、偏移、进度、黑胶、自适应宽度、恢复默认）、
**歌词**（歌词/翻译/卡拉OK/增幅/长音缓行/粗体斜体/跑马灯/双行开关、行数、偏移、字号、增幅增益与半径）、
**主题**（六套配色 + 歌词色/卡拉 OK 色取色器 + 透明度/阴影 + 每个颜色上方一行说明）、
**字体**（字体模式选择、中/英/日/其他四个槽位各选一个本机字体，每个槽位右侧用它自己的字体
实时预览一行示例字，另有重建/清除资源包按钮）、**关于**（版本号、检查更新按钮、
发布页/问题反馈/项目主页三个链接）。
每个滑条松手即生效并写回配置；颜色按钮点开是取色器（色板 + RGB 滑条 + `#RRGGBB` 输入）。

## 5. 配置文件

`config/smtcraft.json`（首次启动自动生成；游戏内改设置会写回此文件，外部编辑后 500 ms 内热重载）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | true | HUD 总开关 |
| `showArtwork` | true | 显示封面 |
| `showAlbum` | true | 显示专辑行 |
| `showProgress` | true | 显示进度条与时间 |
| `showSourceApp` | true | 显示来源应用（如 VutronMusic） |
| `hideWhenNoSession` | true | 无媒体时隐藏 |
| `hideWhenPaused` | false | 暂停时隐藏 |
| `anchor` | top-left | top-left / top-right / bottom-left / bottom-right / center |
| `offsetX` `offsetY` | 8 / 8 | 像素偏移 |
| `scale` | 1.0 | 面板缩放 0.5–3.0 |
| `autoScale` | true | 窗口较小时自动缩小：面板不超过视口 55% 宽 / 72% 高，最小 0.45 倍 |
| `followGuiScale` | true | 页面与 HUD 跟随「界面尺寸」缩放，以界面尺寸 2 为基准（见 2.2） |
| `checkUpdates` | true | 关于页与 Mod Menu 徽标去读最新发布标签（唯一的网络请求，关掉就完全不发）；关掉后两处都答「更新检查已关闭」，点那个按钮就是打开并检查 |
| `panelWidth` | 300 | 面板设计宽度（封面与文字自适应） |
| `backgroundOpacity` | 0.62 | 背景不透明度 0–1 |
| `borderOpacity` | 0.10 | 面板描边不透明度 0–1（旧版写死 10% 白线） |
| `lyricsOnly` | false | 只画歌词带：不画封面、曲名、进度与时间 |
| `hudLayout` | stack | 版式：`stack` 上下（曲目块在上、歌词在下）/ `side` 左右并排（歌词贴缝对齐，见 2.3）/ `vertical` 整块竖排（见 2.4） |
| `trackFirst` | true | 曲目块在前：上下时在上、左右时在左；关掉则歌词块先，曲目块落到下 / 右 |
| `verticalPanelHeight` | 0 | 竖排面板高度：0 按最长一列自适应（168–320），96–480 固定；滑杆上 1–95 那一段没有含义，读数会吸附到 96（见 2.4） |
| `hideWhenScreenOpen` | false | 打开任意界面（背包、设置、暂停、聊天）时隐藏面板 |
| `artRound` | true | 平面封面是否倒圆角（黑胶唱片关闭时才有角可倒） |
| `artRadius` | 4 | 封面圆角半径 0–12，HUD 像素；`artRound` 关掉后滑块失效 |
| `shadow` | true | 文字阴影 |
| `textColor` `dimTextColor` `accentColor` | #FFFFFF / #B8BCC4 / #3FDC6E | 支持 #RGB / #RRGGBB / #AARRGGBB |
| `showLyrics` | true | 显示歌词窗口 |
| `showTranslation` | true | 显示翻译行（酷狗 `[language:]` / QQ `contentts` / 缓存里的 `tlyric`） |
| `karaoke` | true | 卡拉 OK 逐字填充当前行 |
| `lyricLines` | 1 | 同时显示行数 1–4（1080p 下默认 1 行） |
| `lyricOffsetMs` | 0 | 歌词整体偏移 −3000…3000 ms（播放器时间轴偏了就来调这个） |
| `lyricOrder` | kugou,qq,netease | 歌词源尝试顺序（默认 酷狗 → QQ → 网易云兜底），歌词页「歌词来源」按钮循环切换：酷狗→QQ→网易云 / 网易云→酷狗→QQ / QQ→酷狗→网易云 / 仅网易云 |
| `wordTiming` | true | 有词级数据时按真实逐字时间轴扫字（关掉则用字长权重估算） |
| `lyricFontMode` | default | default / unicode（强制 Unicode）/ custom（资源 id）/ system（本机字体） |
| `lyricFontId` | "" | `lyricFontMode=custom` 时的字体 id，如 `minecraft:uniform` |
| `lyricFontSize` | 1.45 | 歌词字号倍率 0.6–2.0（1080p 默认 1.45） |
| `translationFontSize` | 1.20 | 翻译字号倍率 0.6–2.0（1080p 默认 1.20） |
| `lyricColor` | #FFFFFF | 歌词未唱部分颜色（跟随主题） |
| `karaokeColor` | #3FDC6E | 卡拉 OK 已唱部分颜色（跟随主题） |
| `translationColor` | #FFFFFF | 翻译行颜色（`showTranslation` 打开后生效） |
| `candidateColor` | #B8BCC4 | 没有翻译时，第二行那句「下一句预览」的颜色 |
| `vinyl` | true | 封面画成旋转黑胶盘 |
| `muteGameMusic` | true | 播放时静音原版背景音乐（仅 MUSIC 声道） |
| `theme` | netease | netease / midnight / aurora / sakura / sunset / mono |
| `pollIntervalMs` | 150 | 采样间隔（100–5000）；媒体属性已按曲目缓存，调小只影响状态/进度刷新 |
| `cornerRadius` | 8 | 面板圆角 0–12 px |
| `adaptiveWidth` | true | 按曲名/歌手/专辑长度收放面板宽度（不含歌词） |
| `centerTextWithoutArtwork` | false | 不显示曲绘时标题区整体居中（来源应用不居中） |
| `marqueeTrack` | true | 曲名/歌手/专辑超长时跑马灯 |
| `marqueeLyrics` | true | 歌词行超长时跑马灯 |
| `swapGraceMs` | 1200 | 换歌宽限期，避免切歌瞬间面板闪一下 |
| `progressSmooth` | true | 进度条平滑（关掉则直接显示采样值） |
| `progressFromStart` | true | 取不到播放进度时从 0 开始自行计时 |
| `ignoreBrowserSessions` | true | 忽略浏览器（Edge/Chrome 等）的媒体会话 |
| `vinylSpinSpeed` | 38.0 | 黑胶转速 20–120 °/s（需开 `vinyl`） |
| `lyricBold` `lyricItalic` | true / false | 歌词与翻译的粗体/斜体 |
| `karaokePeak` | true | 卡拉 OK 播放头附近的字放大 |
| `karaokePeakAmount` | 0.22 | 放大增益 0–0.6 |
| `karaokePeakRange` | 24 | 放大半径：播放头前后多少像素内的字参与放大，0–80 |
| `lyricNoteEase` | false | 长音缓行：长音横向拉长成卡拉 OK 长音；无逐字时扫过时间按字长估计，有逐字时拉伸幅度按真实音符时长 |
| `dualLineWhenNoTranslation` | false | 无翻译时歌词按双行显示（第二行用翻译字号） |
| `fontLatin` `fontCjk` `fontJapanese` `fontOther` | "" | 各语言槽位的本机字体绝对路径；空 = 用内置字体 |
| `debug` | false | 详细日志开关（界面已不提供入口，需要时改配置文件） |

## 6. 工作原理

```
HUD (NowPlayingHud)  ← volatile 快照 ← 采样线程 (SmtcBridge)
                                        └── SmtcNative (FFM + WinRT)
                                              ├─ RoGetActivationFactory  → SessionManager 静态类
                                              ├─ RequestAsync (slot 6)   → IAsyncOperation<SessionManager>
                                              ├─ GetCurrentSession (6) / SourceAppUserModelId (6)
                                              ├─ TryGetMediaPropertiesAsync (7) → Title 6 / Subtitle 7 /
                                              │     AlbumArtist 8 / Artist 9 / AlbumTitle 10 / Thumbnail 15
                                              ├─ GetPlaybackInfo (9)     → PlaybackStatus 7 / PlaybackRate 10
                                              ├─ GetTimelineProperties (8) → StartTime 6 / EndTime 7 / Position 10
                                              └─ Thumbnail → ReadAsync（IRandomAccessStreamReference 6）
                                                    → CreateStreamOverRandomAccessStream
                                                    → IStream::Read 循环 → PNG/JPG 字节
```

要点（都是在本机实测得出的，写在这里免得以后踩坑）：

1. **只用一个线程**：`SmtcNative` 必须在同一线程内初始化 MTA（`CoInitializeEx`/RoInitialize）并一直用它；
   渲染线程只读 `volatile` 快照。采样线程是 daemon，退出游戏时由 `CLIENT_STOPPING` 关闭。
2. **异步对象走 slot 8**：Windows 返回的 `IAsyncOperation<T>` 在本机 vtable 里 `GetResults` 在**第 8 个槽**，
   按文档的 13 号槽调用会读到越界函数指针直接崩 JVM。现在只轮询 8 号槽（`0x8000000e` = 还没完成，继续等）。
3. **HSTRING 必须 reinterpret**：`WindowsGetStringRawBuffer` 返回的 `MemorySegment` 长度是 0，
   直接按字符读会抛异常并被吞成空串。读取前先 `reinterpret(length * 2 + 2)`。
4. **封面只在换歌时拉取**：按 `标题/歌手/专辑` 生成 key，key 不变就不重复读流，避免无谓开销。
5. **FC/时间单位**：Timeline 的 `TimeSpan` 是 100 ns tick，除 10000 得毫秒。

### 6.1 歌词链路

```
LyricService（daemon 线程 smtcraft-lyrics）
  ├─ 以「标题\u0001歌手」为 key 去重，切歌才动作
  ├─ 本地缓存 config/smtcraft/lyrics/<sha1(key)>.json
  │    命中 120 天；「这首歌没有歌词」只缓存 6 小时
  │    缓存里同时记录 provider 与各格式原文（lrc/tlyric/yrc/qrc/tqrc/krc）
  ├─ 按 config.lyricOrder 依次尝试（默认 kugou,qq,netease，可在歌词页循环切换）
  │    kugou  ：lyrics.kugou.com/search（只用歌名）→ /download（base64 KRC）
  │    qq     ：musicu.fcg 搜索 → lyric_download.fcg（content=QRC 密文，contentts=译文）
  │             解密失败时回落到 fcg_query_lyric_new.fcg 的 base64 LRC
  │    netease：/api/search/get/web 搜索 → eapi/song/lyric/v1（yrc 逐字 + tlyric 译文）
  │             取不到就回落旧 /api/song/lyric；作为最后一档兜底，永远排在最后
  │    三个都没有 → 停手，HUD 显示「没有歌词」，不再等待网慢的词源
  ├─ 候选打分（SongMatch）：55×标题相似度 + 25×歌手相似度 + 20×时长项，≥62 采信
  │    相似度取「字符二元组 Dice」与「1 − 编辑距离/长度」的较大值，
  │    所以标题错一个字也能认（愛がやえに → 愛がゆえに）；
  │    标题不像（<0.72）直接否决，歌手对不上（<0.45）除非时长几乎一致才放行，
  │    时长差 >60 s 否决；标题与歌手在两处对调也能识别（SMTC 与平台字段颠倒时常见）
  │    版本标记必须一致：括号段与「 - 」之后认 14 类版本词（remix/live/伴奏/TV size…），
  │    两侧标记集合相等才继续，同一种版本还要求描述词相似（US40 Remix ≠ Cloudy.exe Remix）；
  │    一侧写了描述词、另一侧只写了版本（Euphoria (US40 Remix) ↔ Euphoria (Remix)）也判不同；
  │    feat. 只扣 8 分不改判定（换嘉宾 = 换人声，不是换歌）
  ├─ 词源质量分（满分 6）：有逐字 +4，译文覆盖率 ≥50% +2、≥10% +1
  │    满 6 分才停止轮询；不到 6 分会继续问下一个词源，并取「逐字」那张表，
  │    再按最近行（±1.2 s）把别家译文覆盖率更高的译文贴过来，贴好的译文会回写进缓存
  │    酷狗一次返回多个 KRC 候选，逐个解析后按覆盖率择优（带 [language:] 的不一定最好）
  └─ LrcParser / SyllableParsers → LyricTrack
       yrc  → 行级 + 词级（绝对毫秒）
       QRC  → 行级 + 词级（绝对毫秒）
       KRC  → 行级 + 词级（词时间相对行首，解析时加行首）
```

三种词级格式都由 `Crypto` 解包：

| 格式 | 封装 | 解密 |
| --- | --- | --- |
| QRC（QQ 音乐） | hex 文本 | 3DES-ECB（key `!@#)(*$%123ZXC!@!@#)(NHL`）→ zlib → 去 BOM |
| KRC（酷狗） | base64 | 去 4 字节头 → 16 字节 XOR 掩码 → zlib → 去首字节 |
| YRC（网易云） | 明文 | 无需解密，`[start,dur](wStart,wDur,0)词`；由 eapi 直接返回 |

> 现状：酷狗 KRC 与网易云 YRC 是真正能提供逐字的两个词源（酷狗按字切分，KRC 候选里带
> `[language:]` 的那份还附带整篇译文；网易云 eapi 同时返回 `yrc` 逐字与 `tlyric` 译文）。
> QQ 的 `lyric_download` 换了 `content` 的密钥，3DES 解出来不是 zlib 流（试遍 DESede/DES、
> ECB/CBC、各种补位与密钥顺序都不成立），所以 QQ 目前只贡献行级 LRC 与 `contentts` 译文。
>
> 网易云 eapi 细节：POST `https://interface3.music.163.com/eapi/song/lyric/v1`，参数
> `params`（AES-128-ECB 加密后的 hex，明文为 `路径-36cd479b6b5-参数JSON-36cd479b6b5-md5摘要`，
> 摘要路径是 `/api/song/lyric/v1`）+ `header`（客户端伪装 JSON 与同序 Cookie）。
> 老接口的 JSON 行歌词 `{"t":1370,"c":[{"tx":"夜"}…]}` 会先转成 `[mm:ss.mmm]歌词` 再解析。
>
> 译文清洗：各家都用 `//`、空串或直接重复原文表示「这一行没有译文」，三种情况一律当空处理，
> 否则副字幕行会重复一遍原文；KRC 的 `[language:]` 偶尔整体错位一行（把下一句原文当译文），
> 这类「译文等于另一行原文」的行也会被判为无译文；酷狗 / QQ 塞在译文块开头的
> 「以下歌词翻译由文曲大模型提供」这类水印也一并过滤。

网易云已停用（`music.163.com/api/song/lyric` 现在需要签名，返回空），历史要点记在这里备查：

- 请求要带桌面版 `User-Agent`（`Referer`、`Cookie: appver=2.9.7; os=pc` 也带上更稳），否则返回空。
- 用 `/api/song/lyric`（老接口）并带 `yv=-1`：老接口同时返回标准 LRC 与 yrc 逐字表，
  v1 的形状随歌曲变化，解析器要写两套。
- 歌词里 `[00:00.000] 作词 : xxx` 这类制作信息是定时行，解析时按「时间戳 < 15 s 且文本匹配
  作词/作曲/编曲等」丢弃，否则会在开头刷出几行字幕。

歌词行末时间取「下一行开始时间」，没有下一行时用估算值（拉丁字符 120 ms/字、CJK 240 ms/字，
夹在 1.4–9 s 之间），这样间奏不会被某一行的卡拉 OK 扫到底。

### 6.2 Linux 与 MPRIS

```
HUD (NowPlayingHud)  ← volatile 快照 ← 采样线程 (MprisBridge, smtcraft-mpris)
                                        └── MprisBus (FFM → libdbus-1)
                                              ├─ ListNames         → org.mpris.MediaPlayer2.*
                                              ├─ Properties.GetAll → PlaybackStatus / Metadata / Rate
                                              ├─ Properties.Get    → Position（微秒）、Identity
                                              └─ mpris:artUrl      → file:// 或 http(s)://
```

平台在启动时决定一次：`MediaBridges.create()` 看 `os.name`，Windows 走 SMTC、Linux 走 MPRIS，
其它平台给一个空实现（HUD 不显示，设置页状态行写明「当前平台没有媒体会话桥」）。

要点：

1. **没有「当前会话」**：MPRIS 里每个播放器都是总线上的独立总线名，所以每轮对所有
   `org.mpris.MediaPlayer2.*` 各做一次 `Properties.GetAll`，按「正在播放 > 有曲目 > 非 Stopped」排序；
   当前播放器额外加 3 分保持粘性，否则两个空播放器会每轮把卡片抢来抢去。
2. **依赖**：发行版的 `libdbus-1`（Debian/Ubuntu `libdbus-1-3`、Arch `dbus`、Fedora `dbus-libs`）。
   缺库时 `MprisBus` 直接报 `libdbus-1 is not installed (package: dbus / libdbus-1-3)`，
   采样线程继续按间隔重试，装好库、重启游戏即可，不需要重装模组。
3. **进度**：优先读 `Position`（微秒，除 1000 得毫秒）；播放器不实现该属性时回落到墙钟累加。
   `Rate` 参与外推，暂停立即停住；播放器报的时间轴以 `mpris:length`（微秒）为总长。
4. **封面**：`mpris:artUrl` 支持 `file://`（本地缓存图，常见于网页播放器）与 `http(s)://`，
   单张上限 4 MiB、连接 1.5 s / 读取 3 s 超时；失败 6 s 后重试，连续 3 次失败就放弃到换歌为止，
   解码与降采样仍在 `smtcraft-cover` 线程，渲染线程只做纹理上传。
5. **浏览器**：Firefox / Chromium 也会注册 MPRIS 播放器，「忽略浏览器会话」用的是同一份白名单，
   开关同样生效。
6. **线程**：`MprisBus` 的创建、每次调用、关闭都在 `smtcraft-mpris` 这一个线程里
   （FFM 的 `Arena.ofConfined()` 不允许跨线程使用）；总线连续 5 轮没有响应才判定失效并重连。
## 7. 从源码构建

26.x 已去混淆，因此本工程**不使用 Loom**（Loom 找不到 26.x 的映射），而是普通 `java` 插件
+ 本地 jar 编译。编译目标是区间里最旧的 **26.1**（要兼容 26.1 就只能对着 26.1 编），
新版差异由 2.1 的四条反射路径在运行期处理：

```
build.gradle          普通 java 插件，compileOnly 本地 jar
gradle.properties     minecraft_version=26.1 / loader_version=0.20.1 / fabric_version=0.155.3+26.1
toolchain/
  jdk25/              JDK 25（26.x 目标版本）
  gradle9/            Gradle 9.x（8.x 太旧）
  mc-client-26.1.jar  官方 26.1 客户端 jar（未混淆，编译目标）
  mc-libs/            最新支持的版本（26.4）的客户端依赖库
  mc-libs-26.1/       26.1 的客户端依赖库（编译目标那一版，脚本按 jar 名自动选）
  fabric-loader-0.20.1.jar
  apiJars/            解包后的 Fabric API 模块
```

```bash
cd smtcraft
JAVA_HOME=<toolchain>/jdk25 \
GRADLE_USER_HOME=<toolchain>/gradle-home \
<toolchain>/gradle9/bin/gradle.bat -PtoolchainDir=<toolchain> jar
# 产物：build/libs/smtcraft-<mod_version>.jar
```

`GRADLE_USER_HOME` 建议指向一个可写目录（默认 `~/.gradle` 在某些受管机器上会因
`native-platform.dll` 加载失败而报错）。

不想装 Gradle 时，可以直接用等价的一步构建（javac + jar，参数与 `build.gradle` 完全一致）：

```bash
cd smtcraft
./build.sh <toolchain>     # 产物：build/libs/smtcraft-<version>+mc26.1-26.4.jar
./test.sh <toolchain>      # 离线自测，不需要 Minecraft
```

`build.sh` 默认用 `<toolchain>/mc-client-26.1.jar`；想换客户端 jar（例如验证某个快照）时设
`SMTCRAFT_MC_CLIENT=<path to client.jar>`，`test.sh` 认同一个变量。依赖库跟着客户端 jar 走：
26.1/26.2 的目标用 `mc-libs-26.1`，其余用 `mc-libs`（都可用 `SMTCRAFT_MC_LIBS` 覆盖）。两个集合
必须分开——26.4 那套里有 `lwjgl-sdl`，拿它编 26.1 的树会链接到一个 26.1 上根本加载不了的
滚轮后端。

构建是**可复现**的：jar 里每个条目的时间戳固定为 `2026-01-01T00:00:00Z`、条目按名字排序后写入，
所以同一棵树重复构建得到同一份字节，脚本最后打印的那行 `md5` 你自己也能复现出来（早前两个版本
大小一致而 md5 不同，因为 jar 记的是文件的修改时间）。

`build.sh` 把 `.class` 先编到 `SMTCRAFT_STAGE`（默认 toolchain 旁边的 `smtc-build/`；相对路径会先
按当前目录展开成绝对路径，因为打包那一步在子 shell 里 `cd` 进了 class 目录），最后只把 jar 拷回 `build/libs`。

### 7.1 线程模型

Minecraft 只有一个客户端线程，它同时跑 tick、渲染和输入。一次磁盘访问、一次网络等待或一次
PNG 解码落在这条线程上，直接就是一帧掉到十几毫秒。模组把能搬的工作全部分成五条后台通道：

| 通道 | 线程名 | 干什么 | 谁提交 |
| --- | --- | --- | --- |
| SMTC 采样 | `smtcraft-smtc` | 通过 WinRT 读「正在播放」的曲名、歌手、进度、封面字节 | `SmtcBridge` 自己的循环（默认 150 ms 一轮） |
| 歌词检索 | `smtcraft-lyric` / `smtcraft-lyric-probe` | 搜索、下载、解密（QRC/KRC）、解析、写缓存 | 歌词 worker，每个词源单独限时 |
| 通用 I/O | `smtcraft-io`（2–4 线程） | 配置读写、字体资源包写入、缓存清理、延迟表落盘 | `Async.run(...)` |
| 快照合并 | `smtcraft-latest` | 只保留最新一份配置快照再写盘，拖滑条不会排队几十次写文件 | `Async.latest(...)` |
| 封面解码 | `smtcraft-cover` | PNG/JPEG 解码、降采样到 256 px、圆形裁切 | `ArtworkCache` |

几条硬规则：

- 后台线程不碰任何 Minecraft 对象。结果一律通过 `Async.onClient(...)`（内部就是
  `Minecraft.execute`）回到客户端线程，再改 HUD、配置字段或纹理。
- 渲染线程只做 `DynamicTexture` 注册与上传；解码结果在队列里等下一帧取，取不到就画占位音符。
- 配置 `save()` 只在调用线程做一次序列化快照，写盘在 I/O 通道；退出时先 `Async.awaitQuiet`
  再 `saveNow()`，避免排队中的旧快照覆盖刚改的新值。
- 逐曲延迟表的读取走 `ConcurrentHashMap`（HUD 每帧读，不加锁），写入由后台通道合并。
- 客户端 tick 超过 80 ms 会打一条
  `slow client tick: total=… ms (config=…, lyrics=…, mute=…, keys/fonts=…)`，哪一段慢一眼可见；
  封面纹理上传超过 30 ms 也会单独打一条。

## 8. 目录结构

```
src/main/java/org/yinyun/smtcraft/
  SmtcCraftClient.java        客户端入口：配置、按键、HUD 注册、生命周期
  SmtcConfig.java           Gson 配置 + mtime 热重载
  audio/MusicMuter.java     播放时静音原版音乐，停止后还原用户音量
  gui/SettingsScreen.java   游戏内设置界面（三标签页：开关/滑条/停靠/字体/颜色）
  gui/ColorPickerScreen.java 取色器（色板 + RGB 滑条 + #RRGGBB 输入）
  gui/FontPickerScreen.java 本机字体选择（枚举 %WINDIR%\Fonts 与用户字体目录，可搜索、翻页）
  font/FontPack.java       按选中字体生成 resourcepacks/smtcraft_fonts 并热重载（smtcraft:systemfont）
  media/MediaBridge.java    采样桥接口（Windows/Linux 共用的快照契约）
  media/MediaBridges.java   按 os.name 选择 SMTC / MPRIS / 空实现
  media/NullBridge.java     不支持的平台上的空实现
  smtc/SmtcNative.java      SMTC 读取（FFM + WinRT，纯 Java）
  smtc/WinRt.java           WinRT 基础设施：MTA、HSTRING、激活工厂、vtable 调用、IStream
  smtc/SmtcBridge.java      Windows 采样线程（SMTC）+ volatile 快照 + 封面缓存
  mpris/MprisBus.java       libdbus-1 的 FFM 绑定（会话总线、GetAll/Get、属性变体解析）
  mpris/MprisBridge.java    Linux 采样线程：挑选播放器、Position/Identity、封面
  mpris/MprisTrack.java     MPRIS 元数据 → 快照（xesam:* / mpris:length / artUrl）
  mpris/MprisArt.java       artUrl 取值：file:// 与 http(s)://，4 MiB 上限
  smtc/TrackInfo.java       不可变曲目快照（进度外推）
  render/NowPlayingHud.java HUD 绘制（HudElement）
  render/ArtworkCache.java  封面 → 贴图 LRU 缓存
  render/Draw.java          颜色解析、省略号截断、时间格式化
  render/Theme.java         六套配色预设（写入配置的颜色字段）
  render/LyricsRenderer.java 歌词排版：逐字填充、居中、省略号、自定义字体
  render/LyricClock.java    播放位置的单调化：小回退先保持、大回退当 seek
  render/LyricScroll.java   滚动目标：把已唱边缘钉在 68% 处（渲染器与 HUD 共用）
  smtc/LyricService.java    歌词服务线程：去重、缓存、搜索打分、拉取
  smtc/NetEaseClient.java   网易云搜索/歌词 HTTP 客户端（JDK HttpClient）
  smtc/LrcParser.java       LRC 解析（多时间戳、offset、制作信息过滤、译文合并）
  smtc/LyricTrack.java      已解析歌词（按时间二分查找当前行、行内进度）
  smtc/LyricLine.java       单行歌词（起止时间 + 原文 + 译文）
  smtc/ProgressSmoother.java 进度防回弹
build.sh                    不依赖 Gradle 的一步构建脚本
src/main/resources/
  fabric.mod.json
  assets/smtcraft/icon.png
  assets/smtcraft/lang/{en_us,zh_cn}.json
```

## 9. 版本记录

一行一版，每次发布只合并成**一条**摘要；每个版本的完整变更见 [CHANGELOG.md](CHANGELOG.md)。

| 版本 | 变更 |
| --- | --- |
| **1.0.3** | 增加左右排版与竖排面板功能。 |
| **1.0.2** | 修复按键设置后 `F7` + 滚轮仍能改延迟、同名不同版本（remix / live / 伴奏等）串用原版歌词、界面尺寸不是 2 时页面被压扁且点击错位这三处缺陷——页面改为按界面尺寸 2 的设计像素排版，任意分辨率与游戏缩放下都能正常显示与命中；主题页每个颜色项右侧的按钮直接显示该颜色本身（不再是十六进制文本）；新增曲绘圆角（关闭黑胶唱片时）、打开菜单时不显示、只显示歌词、边框不透明度、歌词翻译颜色与无翻译时的候选行颜色；关于页与 Mod Menu 加入更新检查（只读最新发布标签）；版本号改为四段日期式并修正比较语义，若干文案收短。 |
| **1.0.1** | 加入 26.1 至 26.4-snapshot-10 版本支持：一个 jar 通吃 26.1 / 26.1.1 / 26.1.2 / 26.2 / 26.3 / 26.4（含快照 1–10），Fabric API 下限降到 0.155.3。相对 1.0.0 另修正了兼容层的按键编号读取（按名字读 `InputConstants` 的常量，不再写死一套编号）与滚轮监视的安装判定。 |
| 1.0.0 | 首个公开发布版本：HUD 与同步歌词、卡拉 OK 逐字填充、游戏内设置界面、Windows SMTC 与 Linux MPRIS2。 |

## 10. 许可

本项目自身代码：**MIT**，见 [LICENSE](LICENSE)。

第三方组件按原协议随 jar 分发，署名与协议全文见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)。

| 组件 | 协议 | 用途 |
| --- | --- | --- |
| [Lyricify-Lyrics-Helper](https://github.com/WXRIW/Lyricify-Lyrics-Helper) | Apache-2.0 | 酷狗/QQ/网易云歌词的请求参数、约定密钥与解密规则（改写为 Java） |

jar 内随附 `LICENSE_smtcraft` 与 `THIRD_PARTY_NOTICES_smtcraft.md`，由构建脚本从仓库同名文件复制而来。

按 Apache-2.0 §4(b)，上述改写属于修改版本：语言由 C# 改为 Java，去除 SharpZipLib / Newtonsoft.Json
（分别以 `java.util.zip.Inflater` 与 Gson 替代），KRC 解压后的首字节改为条件截断。逐条对照与完整协议全文见
[THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)。
