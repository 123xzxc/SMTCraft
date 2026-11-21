# SMTCraft

<img src="src/main/resources/assets/smtcraft/icon.png" width="96" align="right" alt="SMTCraft">

在 Minecraft 的 HUD 上显示**系统当前正在播放的音乐**：封面、曲名、歌手、专辑、进度条，加上带翻译、
带逐字卡拉 OK 填充的同步歌词。曲目信息来自操作系统的媒体会话（Windows 用 SMTC，Linux 用 MPRIS2），
歌词来自酷狗（KRC）、网易云音乐（YRC）与 QQ 音乐（QRC）。

全部逻辑都在纯 Java 里：SMTC 走 COM/WinRT interop，MPRIS2 走 D-Bus，两者都通过
**java.lang.foreign（FFM）直接调系统库**，不装辅助程序、不起外部进程、不联网中转。

- 目标版本：Minecraft **26.1 – 26.4**（26.4 含快照 1–10，同一个 jar 通吃）· Fabric Loader ≥ 0.16.0 · Fabric API ≥ 0.155.3 · Java ≥ 25（客户端）
- 平台：Windows 10/11、Linux（需 `libdbus-1`）；其它平台能启动但 HUD 不显示
- 许可：MIT（第三方片段 Apache-2.0，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）

> **说明**：本 Mod 部分/全部由 AI 生成，所有改动均经人工审阅与实机验证后发布。

## 功能

**面板**

- 封面、曲名、歌手、专辑、进度条与时间、来源应用名；可选择隐藏其中任意一项
- 封面支持普通方图与旋转黑胶（按曲目重置角度，暂停即停转，转速 0–240 °/s 可调）
- 进度在采样之间按时间外推：播放器每 150 ms（默认采样间隔，可在 60–5000 之间调）发布一次位置，
  中间由本地时钟补足，因此即使播放器几秒才更新一次，进度条也是连续走动的；取不到系统进度时可选择从 0 自行计时
- 五种停靠位置（四角 + 居中）、缩放、宽度、透明度、圆角、阴影、偏移、三组颜色全部可调
- 自适应宽度：按曲名 / 歌手 / 专辑的实际像素长度收放（歌词不参与）
- 换歌宽限期（默认 1200 ms）：切歌瞬间沿用上一首快照，面板不会闪一下消失
- 无媒体时按配置隐藏；暂停时可选隐藏；可忽略浏览器会话（Edge / Chrome 的网页播放不抢占 HUD）

**歌词**

- 当前行 + 上下文 1–4 行，自动带上歌词文件自带的翻译行；没有翻译时可选择上下双行显示
- 卡拉 OK：当前行按进度从左到右填充强调色，扫描边缘按字心距离在底色与强调色之间混色，
  所以是一道平滑推过的渐变，而不是一个个字突然变色（可关）
- 卡拉 OK 增幅：播放头附近的字轻微隆起放大（增益与半径可调）
- 长音缓行：长音按真实音符时长横向拉伸，没有逐字数据时按字长估计扫过速度
- 逐字数据优先（KRC / YRC），没有逐字数据时退化为整行均分
- 长句不再被截断：行宽超过面板时按「让已唱边缘停在 68% 处」横向滚动，新增行从行首开始
- 字号、粗体、斜体、颜色、逐行偏移各自独立；歌词本机缓存 120 天，同一首歌不重复请求

**界面与集成**

- 游戏内设置界面（`F9`），四个标签页：显示 / 歌词 / 主题 / 字体；改完立即生效并写回配置
- 六套主题（网易红、午夜蓝、极光、樱花、日落、单色），任意颜色都能单独用取色器改
- 字体：默认 / 强制 Unicode / 自定义资源 id / **本机字体**（中英日其他四槽位各选一个 .ttf/.otf，
  模组自动生成 `resourcepacks/smtcraft_fonts` 并热重载）
- 播放时静音游戏音乐（只在有曲目且播放中压到 0，之后还原原音量）
- 装 Mod Menu 后可从模组列表进入同一个设置界面

## 环境要求

| 项目 | 版本 / 说明 |
| --- | --- |
| Minecraft | 26.1 / 26.1.1 / 26.1.2 / 26.2 / 26.3 / 26.4（含快照 1–10）；26.x 客户端已去混淆，无需 Yarn 映射 |
| Fabric Loader | ≥ 0.16.0（开发使用 0.20.1） |
| Fabric API | ≥ 0.155.3（必需） |
| Mod Menu | 可选，只影响「从模组列表进设置」这一个入口 |
| Java | 客户端 ≥ 25 |
| Windows | 10 / 11，走 SMTC |
| Linux | 需要 `libdbus-1`：Debian/Ubuntu `libdbus-1-3`、Arch `dbus`、Fedora `dbus-libs` |

### 跨版本兼容

26.1 到 26.4 之间有几处客户端 API 变了签名，同一个 jar 要覆盖整段，就不能只按一个版本写。做法是
**编译对齐区间最旧的 26.1**，差异在运行期解析：各垫片先探这一版到底有哪个类 / 哪个方法，走到存在的
那条路上，实在没有才降级。目前有七处：

| 差异 | 26.1 – 26.4 的分布 | 垫片 |
| --- | --- | --- |
| 封面纹理注册 | `DynamicTexture` 到 snapshot-2；snapshot-3 起只剩 `TextureResources` + `TextureManager.register`；snapshot-8 起改 `ExternalTextureProvider` + `registerProvider` | `render/CoverTexture`（三条路按 direct → provider → dynamic 取第一条能解析的） |
| HUD 渲染入口 | 26.1–26.3 收 `(extractor, DeltaTracker)`；26.4 只收 `(extractor)` | `render/NowPlayingHud`（两个重载都实现） |
| 屏幕背景钩子 | 26.1–snapshot-4 是 `(extractor, mouseX, mouseY, partialTick)`；snapshot-5 起只收 extractor，指针改由 extractor 提供 | `gui/ScreenHooks` |
| 控件可见性 | 26.1–snapshot-1 是 public 字段；snapshot-2 起 protected + `setVisible` | `gui/Widgets` |
| 按键编号 | `InputConstants.isKeyDown` 在 26.1–26.2 收 `(Window, GLFW 键码)`，26.3 起收 `(SDL scancode)`；`KEY_F7` 在 26.1 是 296、26.3 是 64 | `util/Keys`（按名字反射读这一版声明的常量） |
| 滚轮事件 | 26.1–26.2 走 GLFW 回调，26.3 起走 SDL 事件监视 | `util/WheelWatch` |
| 鼠标坐标 | extractor 在 26.1 不带 `mouseX/mouseY` | `gui/ScreenHooks`（缺失时退回 0,0 并在日志里注明） |

启动日志里有 `game api:` 与 `key codes:` 两行，就是这一版实际解析到的结果。前几处逐版本编译就能发现，
后两处（HUD 入口、滚轮后端）只有真机启动才暴露——两版编译都合法，运行期才崩。

## 安装

1. 在启动器里装 Fabric Loader（≥ 0.16.0）与 Fabric API ≥ 0.155.3
2. 把 `smtcraft-<version>+mc26.1-26.4.jar` 放进 `.minecraft/mods`（同一个模组只留一个版本；文件名里的区间就是适用范围，26.4 的快照也用这一个）
3. **启动器 JVM 参数加 `--enable-native-access=ALL-UNNAMED`（必做）**，老启动器写作
   `-J--enable-native-access=ALL-UNNAMED`；同时去掉 `--illegal-native-access=deny`。
   JDK 24+ 对 FFM 受限方法默认只警告，但部分启动器以 `deny` 启动；一旦被拒绝，
   症状是「没有会话」、HUD 一直空白。打包 jar 的 manifest 里也写了这个属性，
   但那只对 `java -jar` 生效——模组走 classpath 加载，启动器不会读它，所以这一步不能省
4. 启动游戏，HUD 默认在左上角，按 `F8` 开关
5. 可选：装 Mod Menu，模组列表里本模组右侧会出现 ⚙ 设置按钮
6. Linux 额外装好系统 D-Bus 库；缺库时游戏照常启动，只是不显示 HUD（日志与设置页会写明原因）

> 上传之后最常见的反馈是「HUD 空白 / 没有会话」，第一件事就是查启动参数里有没有
> `--enable-native-access=ALL-UNNAMED`。

## 使用

| 入口 | 作用 |
| --- | --- |
| `F8` | 开关 HUD |
| `F9` | 打开设置界面 |
| `F7` | 调整歌词偏移（正负各一档，设置页里也能拖） |
| 模组列表 → 本模组 → ⚙ | 同一个设置界面（需要 Mod Menu） |

三个键位都能在「选项 → 控制 → 按键设置」里改。设置界面底部显示当前读取状态（正在读取的曲目、
来源应用、距上次采样毫秒数）与歌词状态（`searching` / `cached` / `no match` / `hit <曲名>`）；
写入失败、配置损坏、媒体桥报错会以 `!` 前缀显示分类原因，具体堆栈在日志里。


## 配置

配置文件是 `config/smtcraft.json`（启动器实例的 `config` 目录下），普通 Gson 文本，
可以手改，改完约 0.5 秒内热加载；写坏时会把原文件改名成 `smtcraft.json.bad-<时间戳>` 再写默认值。
歌词缓存是 `config/smtcraft/lyrics/*.json`，保留 120 天。

常用键（其余见文件本身，全部带默认值）：

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `enabled` | `true` | HUD 总开关 |
| `anchor` | `"top-left"` | `top-left` / `top-right` / `bottom-left` / `bottom-right` / `center` |
| `offsetX` / `offsetY` | `8` / `8` | 相对停靠角的像素偏移 |
| `scale` | `1.0` | 整体缩放 |
| `panelWidth` | `300` | 面板设计宽度（像素） |
| `backgroundOpacity` | `0.62` | 面板底色不透明度 |
| `cornerRadius` | `8` | 圆角半径（0–24，与设置界面滑条同一来源） |
| `theme` | `"netease"` | `netease` / `midnight` / `aurora` / `sakura` / `sunset` / `mono` |
| `textColor` / `dimTextColor` / `accentColor` | `#FFFFFF` / `#B8BCC4` / `#3FDC6E` | 主文字 / 次要文字 / 强调色 |
| `customColors` | `""` | 逗号分隔的自定义颜色键；非空时主题标签显示「自定义」 |
| `adaptiveWidth` | `true` | 按标题长度自适应面板宽度 |
| `vinyl` / `vinylSpinSpeed` | `true` / `38.0` | 黑胶封面与转速（0–240 °/s） |
| `showLyrics` | `true` | 歌词窗口 |
| `showTranslation` | `true` | 歌词翻译行 |
| `karaoke` | `true` | 卡拉 OK 填充 |
| `karaokePeak` / `karaokePeakAmount` / `karaokePeakRange` | `true` / `0.22` / `24` | 卡拉 OK 增幅 |
| `lyricNoteEase` | `false` | 长音缓行 |
| `wordTiming` | `true` | 使用逐字时间轴（关掉则整行均分） |
| `lyricLines` | `1` | 歌词显示行数（1–4） |
| `lyricOffsetMs` | `0` | 歌词相对播放位置的偏移（毫秒） |
| `lyricFontSize` / `translationFontSize` | `1.45` / `1.20` | 歌词 / 翻译字号倍率 |
| `lyricColor` / `karaokeColor` | `#FFFFFF` / `#3FDC6E` | 歌词底色 / 卡拉 OK 颜色 |
| `lyricBold` / `lyricItalic` | `true` / `false` | 歌词与翻译的粗体 / 斜体 |
| `marqueeLyrics` / `marqueeTrack` | `true` / `true` | 歌词行 / 曲名过长时滚动 |
| `dualLineWhenNoTranslation` | `true` | 没有翻译时上下双行 |
| `lyricOrder` | `"kugou,qq,netease"` | 歌词源优先级 |
| `lyricFontMode` | `"default"` | `default` / `unicode` / `system` / `resource` |
| `fontLatin` / `fontCjk` / `fontJapanese` / `fontOther` | `""` | 本机字体路径（四槽位） |
| `muteGameMusic` | `true` | 播放时静音游戏音乐 |
| `hideWhenNoSession` / `hideWhenPaused` | `true` / `false` | 无媒体 / 暂停时隐藏 |
| `ignoreBrowserSessions` | `true` | 忽略浏览器媒体会话 |
| `progressSmooth` / `progressFromStart` | `true` / `true` | 进度平滑 / 取不到进度时自行计时 |
| `pollIntervalMs` | `150` | 采样间隔（60–5000） |
| `swapGraceMs` | `1200` | 换歌宽限期 |
| `debug` | `false` | 输出详细日志 |

## 工作原理

```
系统媒体会话 ──┬─ Windows: SMTC (COM/WinRT) ─┐
               └─ Linux:   MPRIS2 over D-Bus ─┤
                                              ├─→ MediaBridge（统一接口）
歌词接口 ── 酷狗 KRC / 网易云 YRC / QQ 音乐 QRC ─┘        │
                                                       ↓
                         采样线程 → ProgressSmoother（外推播放位置）
                                                       ↓
          NowPlayingHud（渲染线程）← ArtworkCache（封面贴图）+ LyricService（歌词缓存）
```

- **媒体桥**：`media.MediaBridges` 按 `os.name` 选择 `SmtcBridge`（Windows）或 `MprisBridge`（Linux），
  其它平台是 `NullBridge`。两者都在自己的线程里采样并发布不可变快照，渲染侧只读。
- **FFM**：D-Bus 与 WinRT 都通过 `java.lang.foreign` 调系统库；Linux 侧 17 个 libdbus 符号的原型集中在
  `mpris/DbusSymbols.java`，启动时逐条比对函数描述符，符号缺失或形状不符会在加载期直接报出符号名。
- **歌词**：`LyricService` 按 `lyricOrder` 依次尝试三家接口，命中后解析成逐字时间轴并落盘缓存；
  网络与解析都在后台线程，渲染线程只读已就绪的结果。
- **线程**：一个 IO 池（写配置、写缓存）、一条「只跑最新任务」的车道（磁盘写入合并）、
  一条媒体采样线程、一条歌词工作线程；预算常量集中在 `util/Budgets.java`，关停按
  `util/ShutdownPlan.java` 的固定顺序执行（排空 → 关桥 → 写配置 → 落偏移 → 停车道 → 停歌词），
  每步独立兜底，并有一个看门狗兜住超预算的情况。

## 从源码构建

### 前置

- JDK 25（`java` / `javac` / `jar` 在 `jdk25/bin` 下；脚本同时认带与不带 `.exe` 的文件名）
- bash（Windows 用 Git for Windows 的 bash）
- 一个「工具链目录」，把所有编译依赖平铺进去：

```
toolchain/
├── jdk25/bin/{java,javac,jar}
├── mc-client-26.1.jar                # 官方去混淆客户端 jar（编译对齐区间最旧版本）
├── fabric-loader-0.20.1.jar
├── modmenu-21.0.0.jar                # 硬编译依赖（ModMenuIntegration 实现其 API）
├── mc-libs/*.jar                     # 客户端 jar 的 libraries
└── apijars/META-INF/jars/*.jar       # fabric-api 各模块
```

26.x 没有 Yarn / Intermediary 映射，所以**不经过 Loom、不 remap**：编译产物就是最终 jar。

上面的文件都能从公开渠道拿到：`mc-client-26.1.jar` 就是启动器 `.minecraft/versions/26.1/` 下那个
版本 jar（26.x 客户端未混淆），`fabric-loader` 与 `fabric-api` 模块来自 maven.fabricmc.net，
Mod Menu 来自 Modrinth 或它的 maven，`mc-libs` 是客户端 jar 里 `libraries` 列出的库。

编译目标故意取支持范围里**最旧**的 26.1：这样每处版本差异都是「旧版有、新版没了」，编译期就能
暴露出来；要换目标时设 `SMTCRAFT_MC_CLIENT=<path to client.jar>`，两个脚本都认这个变量。

### 方式 A：build.sh（最短路径）

```bash
./build.sh /path/to/toolchain        # 省略参数则用 ../toolchain
# 产物：build/libs/smtcraft-<mod_version>.jar
```

脚本用 `javac --release 25` 编译 `src/main/java`，拷入 `src/main/resources`，把版本占位符替换成
`gradle.properties` 里的 `mod_version`，再连同 `LICENSE` 与 `THIRD_PARTY_NOTICES.md` 一起打包。
类文件默认暂存在工具链旁边的 `smtc-build/`（Windows 的「受控文件夹访问」会阻止 `java.exe` 在
`Downloads` 下建目录）；需要换位置时设环境变量 `SMTCRAFT_STAGE`。

### 方式 B：Gradle

仓库**不带 Gradle wrapper**（没有 `gradlew`，也没有 `gradle/wrapper/`），所以下面用本机已安装的
`gradle`；只装了 JDK 的话直接用方式 A。

```bash
gradle build -PtoolchainDir=/path/to/toolchain
# 产物同样在 build/libs/
```

- `-PtoolchainDir` 默认是 `../toolchain`
- 在 `Downloads` 这类受限目录里构建时，用 `-PbuildDirOverride=/path/outside/Downloads` 把
  `build/` 与 `.gradle/` 移出去
- `gradle selfTest` 直接调用 `test.sh`；`gradle check` 也挂在它上面
- 方式 A 与方式 B 产出同一个 jar（都是纯 `javac` + `jar`，没有 Loom、不 remap）

### 离线自测

```bash
./test.sh /path/to/toolchain
```

不需要网络、不需要起游戏，包含三部分：

1. **整树编译**：先用客户端 jar + loader + 所有库把 `src/main/java` 整个编译一遍
   —— 只跑自测（`-sourcepath` 按需编译）会漏掉没被引用的类。
2. **源码护栏**：两条 awk 规则 + 自带的反向样本。日志调用里出现裸 `.toString()` 会失败
   （必须用 `util/Errors.describe`）；同一个成员上方出现两个连续 javadoc 块会失败。
   每条护栏都配一个「必须变红」的样本文件，护栏改坏了自检先炸。
3. **行为断言**：`src/test/java/org/yinyun/smtcraft/SelfTest.java`，覆盖进度外推与停顿、歌词时钟不回退、
   滚动目标、预算常量与关停计划顺序、配置热加载代数、libdbus 原型表、D-Bus 解码等。

`test.sh` 通过 `-Dsmtcraft.protos=src/test/resources/libdbus-prototypes.txt` 把 libdbus 的
C 原型文件交给自测，用它作为符号表的第二来源（改一处不一致就会红）。

### 发布前检查

```bash
./build.sh /path/to/toolchain && ./test.sh /path/to/toolchain
```

两个都绿再发；改动涉及时间轴、滚动或缓存时，故意改坏一处实现、确认对应断言变红，再还原
（自测的价值在于它会红，不在于它全绿）。

## 网络接口与密钥

**本模组不需要任何账号、Token 或 API Key，开箱即用。** 仓库里没有私钥、证书、代理凭据，
也没有需要你申请才能运行的东西。三个歌词接口用的都是公开的客户端常量：

| 位置 | 内容 | 说明 |
| --- | --- | --- |
| `smtc/KugouClient.java` | 浏览器 UA、`ver=1&client=pc` 等查询参数 | 公开客户端参数；`accesskey` 由搜索接口在响应里返回，不是内置密钥 |
| `smtc/QQMusicClient.java` | 浏览器 UA | 同上 |
| `smtc/NetEaseClient.java` | Android UA | 同上 |
| `smtc/Crypto.java` | QQ 音乐 3DES key、KRC XOR mask | 歌词格式的**固定公开常量**（移植自 Lyricify-Lyrics-Helper，Apache-2.0）；不含任何账号信息 |

### 接口变了怎么自己修

三家接口都是非官方接口，参数或 UA 可能变。定位顺序：

1. 打开 `debug: true`，日志会打出每次请求的 URL 与状态码
2. 用浏览器 / curl 复现同一个 URL，确认是 403（UA 或签名）还是 200 但结构变了（解析）
3. UA 与查询参数就在上表那几个文件里，改常量即可；返回结构变化改对应的解析方法，
   并补一条 `SelfTest` 断言

### 如果将来需要密钥

目前没有任何接口要求密钥。若某家开始要求（或你要换成带签名的自建代理），**不要把密钥写进源码**：

1. 到对应开放平台注册开发者应用（QQ 音乐开放平台、网易云音乐开放平台、酷狗开放平台），
   创建应用后拿到 `app_id` / `app_secret`（或签名用的 `key`）
2. 把密钥放进**本地配置**而不是仓库：在 `config/smtcraft.json` 里加自己的字段，或读环境变量
   （例如 `SMTCRAFT_QQ_KEY`），代码里只在发送请求时读取
3. 提交前确认 `git status` 里没有配置文件、没有 `.env`；本仓库的 `.gitignore` 已排除
   `build/`、`.gradle/`、`run/` 与本机工具链
4. 若密钥已经误提交：在平台侧重置密钥，而不是只删提交记录

## 目录结构

```
src/main/java/org/yinyun/smtcraft/
├── SmtcCraftClient.java        客户端入口：键位、生命周期、关停编排
├── SmtcConfig.java             配置读写与热加载（Gson，原子替换）
├── ModMenuIntegration.java     Mod Menu 入口
├── audio/MusicMuter.java       播放时静音游戏音乐
├── font/FontPack.java          本机字体 → 资源包 → 热重载
├── gui/SettingsScreen.java     游戏内设置界面（四标签页 + 滚动）
├── gui/ScreenHooks.java        跨版本：屏幕背景钩子与指针坐标
├── gui/Widgets.java            跨版本：控件可见性
├── media/                      MediaBridge 接口、按平台选择、空实现
├── mpris/                      D-Bus 绑定、MPRIS2 桥、Wire 解码、符号原型表
├── render/                     HUD、歌词渲染、封面缓存与跨版本纹理注册、滚动与时钟
├── smtc/                       Windows SMTC 桥、歌词服务与三家接口客户端、格式解析
└── util/                       Async、Budgets、ShutdownPlan、Watchdog、Errors、跨版本垫片（Keys/WheelWatch）
src/main/resources/             fabric.mod.json、语言文件、图标
src/test/java/                  SelfTest（离线行为断言）、ShimProbe（兼容垫片探针）
src/test/resources/             libdbus 原型文件（自测第二来源）
build.sh / test.sh              无 Gradle 的直接构建与离线自测
build.gradle                   等价的可选构建路径 + selfTest 任务
```

## 已知限制

- 只做**读取**：不能控制播放、切歌、调音量（游戏音乐静音是唯一会改动系统状态的行为，且会还原）
- 只在客户端生效（`environment: client`），服务器无需安装
- 除 Windows 与 Linux 外的平台显示空 HUD
- 三家歌词接口都不是官方接口，可能随时变化，见上一节的自修流程
- 播放器发布周期长于 2.5 s 时，进度按最近一次真实步进外推，最多可能落后 2.4 s 再被追平
- 开启跑马灯的歌词行在换行退场的那一帧，横向偏移偶有轻微跳变
- 26.4 一侧的兼容层按快照 10 实测，快照之间接口仍在变动；换快照后先看日志的 `game api:` 一行
- 仅当播放器把信息写进系统媒体会话时才可见（部分播放器默认关闭该功能）

## 版本记录

| 版本 | 变更 |
| --- | --- |
| **1.0.1** | 加入 26.1 至 26.4-snapshot-10 版本支持：一个 jar 通吃 26.1 / 26.1.1 / 26.1.2 / 26.2 / 26.3 / 26.4（含快照 1–10），Fabric API 下限降到 0.155.3。相对 1.0.0 另修正了兼容层的按键编号读取（按名字读 `InputConstants` 的常量，不再写死一套编号）与滚轮监视的安装判定。 |
| 1.0.0 | 首个公开发布版本：HUD 与同步歌词、卡拉 OK 逐字填充、游戏内设置界面、Windows SMTC 与 Linux MPRIS2。 |

## 许可

- 本项目：MIT，见 [LICENSE](LICENSE)
- 第三方：从 Lyricify-Lyrics-Helper 移植的歌词解密 / 解析片段为 Apache-2.0，
  完整归属与修改说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；
  构建出的 jar 内随附 `LICENSE_smtcraft` 与 `THIRD_PARTY_NOTICES_smtcraft.md`
