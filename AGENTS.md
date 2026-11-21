# AGENTS.md

给在本仓库里工作的自动化代理（以及未来的自己）的约定。人类贡献者同样适用。

## 这是什么

Minecraft 26.1–26.4（含 26.4 快照 1–10）/ Fabric 客户端模组：把系统当前播放的曲目与同步歌词画进 HUD。曲目信息来自
Windows SMTC 或 Linux MPRIS2，两者都用 `java.lang.foreign` 直接调系统库；歌词来自酷狗 / 网易云 /
QQ 音乐的公开接口。**没有服务端组件，没有账号，没有密钥。**

- 语言级别：Java 25（`javac --release 25`）；注释与文档用中文，标识符用英文
- 依赖：只有 Gson 与 Minecraft / Fabric 本体。**不要新增第三方库**（打包体积、许可证、映射都受影响）
- 包根：`org.yinyun.smtcraft`

## 构建与测试

```bash
./build.sh /path/to/toolchain     # javac + jar，产物 build/libs/smtcraft-<version>+mc26.1-26.4.jar
./test.sh  /path/to/toolchain     # 整树编译 + 源码护栏 + 离线行为断言
```

- 工具链目录布局见 README「从源码构建」；版本号与依赖版本在 `gradle.properties`
- 编译目标固定为支持区间里**最旧的 26.1**（`SMTCRAFT_MC_CLIENT` 可覆盖）。新版独有的 API 不要直接用：
  加一个垫片类（见 `util/Keys`、`gui/ScreenHooks`、`render/CoverTexture`），在运行期解析，并让
  `src/test/java/.../ShimProbe` 在编译目标的客户端 jar 上断言它能解析到
- Windows 上必须传 **Windows 形式**的路径（`C:/...`）。传 MSYS 形式（`/c/...`）会让
  `cygpath` 拼出坏 classpath，报出成百条「找不到符号 com.google.gson」
- `build.sh` 的类文件暂存在工具链旁边的 `smtc-build/`（Windows「受控文件夹访问」不允许
  `java.exe` 在 `Downloads` 下建目录）；需要换位置用环境变量 `SMTCRAFT_STAGE`
- Gradle 路径等价（仓库没有 wrapper，用本机 `gradle`）：`gradle build -PtoolchainDir=...`，
  `gradle selfTest` 转调 `test.sh`

## 改动纪律

1. **先读后写。** 不要「整文件读回 → 整文件写回」地改大文件（读取有字节上限，会把文件截断）；
   用定点替换，一次一处。
2. **不要动 `gradle.properties` 的版本号**，除非在准备发布。
3. 预算与超时常量集中在 `util/Budgets.java`，关停步骤顺序在 `util/ShutdownPlan.java`。
   新增一个有超时的阶段，就把它加进预算表，并让 `SelfTest` 的加法闭合。
4. 日志里的异常一律用 `util/Errors.describe(t)`，不要写 `t.toString()`（`test.sh` 会拦）。
5. 每个成员上方最多一个 javadoc 块（`test.sh` 会拦连续两块）。注释解释**为什么**，
   不是复述代码；审计编号（如 `R23-3`）可以写进注释，指向当时的条目。
6. 网络、磁盘、渲染互不阻塞：IO 走 `util/Async`，渲染线程只读不可变快照。
   新增的阻塞调用要么搬到后台线程，要么量出上界写进预算。
7. 歌词的横向度量只能有一个来源：有逐字时间的行走 `render/LyricsRenderer` 的字形跟随，
   没有的行走 `render/LyricScroll` 的滚动目标。不要在同一行上叠两套。

## 测试怎么写

- 断言放在 `src/test/java/org/yinyun/smtcraft/SelfTest.java`，在 `main()` 里注册；
  优先纯逻辑（引用 Minecraft 类型的类在离线编译里不存在，自测会编译失败）
- 每条断言都要能在实现写错时**变红**：写完先故意改坏一处实现、确认对应断言失败，再还原。
  没验证过会红的断言等于没有断言
- 护栏（`test.sh` 里的 awk 规则）必须配一个反向样本文件；改护栏时样本与断言一起改
- 兼容垫片不止要编译过：`test.sh` 会用编译目标的客户端 jar 跑 `ShimProbe`，解析不到就红
- 时间相关的断言不要 `sleep` 固定值，轮询到条件成立并设上限

## 提交前

```bash
./build.sh /path/to/toolchain && ./test.sh /path/to/toolchain
git status --short          # 确认没有构建产物、配置文件、本机路径混进来
```

- `build/`、`.gradle/`、`run/`、本机工具链、`config/` 一律不入库
- 提交信息写清「改了什么 + 为什么」；一次提交只做一件事
- 不要把审计报告、调试脚本、日志、截图放进仓库根目录

## 不要提交的东西

密钥、Token、Cookie、账号、本机绝对路径、别人的日志。本仓库目前不需要任何密钥；
如果将来需要，走环境变量或本地配置，并在提交前用 `git diff --cached` 复查一遍。
