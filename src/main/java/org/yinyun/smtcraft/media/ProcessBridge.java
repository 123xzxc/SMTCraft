package org.yinyun.smtcraft.media;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.render.Draw;
import org.yinyun.smtcraft.smtc.ProgressSmoother;
import org.yinyun.smtcraft.smtc.TrackInfo;
import org.yinyun.smtcraft.util.Budgets;
import org.yinyun.smtcraft.util.Errors;

/**
 * 用外部适配器进程读“系统当前播放”。
 *
 * <p>Fabric 版靠 {@code java.lang.foreign} 直接调 WinRT 与 libdbus，1.8.9 的 Java 8
 * 没有那套 API，Panama 也不在 8 上；把 WinRT 的 COM 激活、MPRIS 的 D-Bus 报文手工写成
 * JNI 则是另一条更长的路（每平台一份头文件、一份崩溃面，还得替玩家加载我们自己编的
 * so）。所以这里把平台差异整个推到一个随模组落盘的脚本里：脚本每 {@code pollIntervalMs}
 * 往 stdout 打一行 JSON，本类只负责“读一行、解析、发布不可变快照”。</p>
 *
 * <p>代价是机器上得有一个能跑的 PowerShell 5.1 / {@code /bin/sh}，收益是 Java 侧零
 * native、零平台代码、零崩溃面，而且适配器可以脱离游戏直接在命令行里调试。对外契约与
 * Fabric 版完全一致：{@link TrackInfo} 快照、装饰用封面、{@code swapGraceMs} 的换歌
 * 空窗、以及 {@link #close(long)} 的预算。</p>
 */
public final class ProcessBridge implements MediaBridge {

    private static final Logger LOG = Logger.getLogger("smtcraft-media");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** 宿主进程报错用的字符集：中文 Windows 上是 GBK，取不到就退回 UTF-8。 */
    private static final Charset ANSI = ansi();
    /** 封面走 base64 文本过管道，这里卡的是 base64 字符数（= 原始字节的 4/3）：512 KiB 约等于
     *  384 KiB 的原图，常见 300×300 封面装得下，解码也只发生在换歌那一次。两个脚本里有同名上限
     *  （{@code $ART_LIMIT} / {@code ART_LIMIT}），改这里要连脚本一起改。 */
    private static final int ART_LIMIT_B64 = 512 * 1024;
    /** stderr 只留最近这么多字节：够看清脚本为什么死，又不至于把内存喂胖。 */
    private static final int STDERR_TAIL = 8 * 1024;
    /** 适配器退出后的重启间隔。 */
    private static final long RESTART_BACKOFF_MS = 5_000L;
    /** 轮询间隔改过之后先等这么久再重启进程：拖滑条时它每一格都在变，真重启只该有一次。 */
    private static final long INTERVAL_SETTLE_MS = 400L;
    /** 连续失败到这个数就停手：一个永远起不来的进程不该每 5 秒被拉起一次。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final int MIN_INTERVAL_MS = 100;
    private static final int MAX_INTERVAL_MS = 2_000;
    private static final int DEFAULT_INTERVAL_MS = 150;
    private static final String WINDOWS_SCRIPT_NAME = "smtc.ps1";
    private static final String LINUX_SCRIPT_NAME = "mpris.sh";

    /** 串行化“拉起”与“关停”，免得 close() 和一次重启撞在同一瞬间。 */
    private final Object gate = new Object();
    private final Object stderrLock = new Object();
    /** 最近的 stderr，{@link #lastError()} 把它带出去。 */
    private final StringBuilder stderr = new StringBuilder();
    private final ProgressSmoother smoother = new ProgressSmoother();

    private volatile SmtcConfig config;
    private volatile TrackInfo latest = TrackInfo.EMPTY;
    private volatile byte[] artwork = new byte[0];
    private volatile String artworkHash = "";
    /** 当前这份封面属于哪条曲目（{@code title\u0001artist\u0001album\u0001app}）。 */
    private volatile String artKey = "";
    private volatile boolean artTooLarge;
    private volatile boolean running;
    private volatile String lastError = "";
    private volatile long lastSampleAt;
    private volatile long sampleCount;
    private volatile Process process;
    /** 连续失败次数；读到过一行有效 JSON 就回到 1。 */
    private volatile int failures;
    /** 连续失败到上限后置位：不再拉起进程，直到配置变了或桥被重建。 */
    private volatile boolean gaveUp;
    private volatile long restartAt;
    /** 当前进程是用哪个间隔拉起来的：reconfigure() 靠它判断到底要不要重启。 */
    private volatile int launchedIntervalMs = -1;
    /** 上一次被 reconfigure() 看见的间隔：每个 tick 都会调进来，没变就不该打扰 supervisor。 */
    private volatile int knownIntervalMs = -1;
    /** 正在等待静置的新间隔与它的起始时刻（见 {@link #INTERVAL_SETTLE_MS}）。 */
    private volatile int pendingIntervalMs = -1;
    private volatile long pendingAt;
    /** reconfigure() 主动杀进程时置位：那一轮退出不算崩溃，不计数。 */
    private volatile boolean planned;
    private Thread supervisor;
    private volatile File script;
    private boolean scriptExecutable;

    // 以下字段只被 stdout 线程写，close() 之后没人再读
    private String heldKey = "";
    private long heldSince;
    private long lastTrackAt;
    private long playedMs;
    private long playedSampledAt;
    /** 这一轮进程有没有产出过有效行：有就说明它至少干过活，不算“起不来”。 */
    private boolean sampledThisRun;
    private String loggedError = "";
    private String loggedTrack = "";

    /** {@code config} 目录（{@code <游戏目录>/config}）：桥脚本落在它下面的 {@code smtcraft/bridge}。 */
    private final File configRoot;

    public ProcessBridge(SmtcConfig config) {
        this(config, null);
    }

    /** @param configRoot 配置根；null 表示没有游戏实例（探针），退回工作目录 */
    public ProcessBridge(SmtcConfig config, File configRoot) {
        this.config = config;
        this.configRoot = configRoot;
    }

    @Override
    public void start() {
        synchronized (gate) {
            if (running) return;
            running = true;
            gaveUp = false;
            failures = 0;
            restartAt = 0L;
            Thread worker = new Thread(this::supervise, "smtcraft-bridge");
            worker.setDaemon(true);
            supervisor = worker;
            worker.start();
        }
    }

    /**
     * 每个 tick 都会被调用一次；只有真影响进程的字段（此刻只有轮询间隔）才让适配器重启，
     * 别的开关都在 Java 侧生效，重启进程是这一层最贵的动作。
     */
    @Override
    public void setConfig(SmtcConfig config) {
        if (config == null) return;
        this.config = config;
        reconfigure();
    }

    @Override
    public void reconfigure() {
        int wanted = intervalOf(config);
        // setConfig 每个 tick 都会调进来。只有间隔真的变了才算“配置变了”，否则下面重置 gaveUp
        // 会把连续失败计数与 5 秒退避一起打穿：适配器起不来时会退化成每 tick 拉一次进程。
        if (wanted == knownIntervalMs && pendingIntervalMs != wanted) return;
        knownIntervalMs = wanted;
        if (gaveUp) {
            // 上一次已经停手，这次间隔真变了：给它一次重新开始的机会
            gaveUp = false;
            failures = 0;
            lastError = "";
            LOG.info("bridge adapter: retrying after the poll interval changed to " + wanted + " ms");
        }
        if (wanted == launchedIntervalMs) return;
        // 一次都还没拉起来（启动瞬间或连续起不来）：supervisor 会用 config 里的间隔自己起，不用杀谁
        if (launchedIntervalMs < 0) return;
        Process live = process;
        // 进程还没起来（或刚退出）：supervisor 会用新间隔自己拉起，这里不许碰 restartAt，
        // 否则退避被清零，起不来的适配器又会每 tick 重试一次。
        if (live == null || !live.isAlive()) return;
        if (pendingIntervalMs != wanted) {
            pendingIntervalMs = wanted;
            pendingAt = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - pendingAt < INTERVAL_SETTLE_MS) return;
        pendingIntervalMs = -1;
        planned = true;
        live.destroy();
        synchronized (gate) {
            gate.notifyAll();
        }
    }

    @Override
    public TrackInfo latest() {
        return latest;
    }

    @Override
    public byte[] artwork() {
        return artwork;
    }

    @Override
    public String artworkHash() {
        return artworkHash;
    }

    @Override
    public boolean running() {
        Process live = process;
        if (live == null) return false;
        // Java 8 的 Process 没有 isAlive()：exitValue() 抛 IllegalThreadStateException 就还活着
        try {
            live.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public long sampleCount() {
        return sampleCount;
    }

    @Override
    public long millisSinceSample() {
        long at = lastSampleAt;
        return at == 0L ? -1L : System.currentTimeMillis() - at;
    }

    @Override
    public void close() {
        close(MediaBridge.CLOSE_GRACE_MS);
    }

    /**
     * 先 destroy，预算内等它自己退，超时 destroyForcibly 再给一次短等待，最后 join 监视线程。
     * 脚本里挂着 Start-Sleep / sleep，收到终止信号通常几十毫秒就退，但 PowerShell 冷启动慢，
     * 预算给不够就会留下一个孤儿进程继续往死管道里写。
     */
    @Override
    public void close(long budgetMs) {
        running = false;
        long budget = Math.max(Budgets.MIN_WAIT_MS, Math.min(budgetMs, MediaBridge.CLOSE_GRACE_MS));
        long deadline = System.currentTimeMillis() + budget;
        Process live;
        synchronized (gate) {
            live = process;
            gate.notifyAll();
        }
        if (live != null) {
            live.destroy();
            if (!awaitExit(live, Math.max(Budgets.MIN_WAIT_MS, budget * 2L / 3L))) {
                live.destroyForcibly();
                awaitExit(live, Math.max(Budgets.MIN_WAIT_MS, deadline - System.currentTimeMillis()));
            }
        }
        Thread watcher = supervisor;
        if (watcher != null) {
            joinQuietly(watcher, Math.max(Budgets.MIN_WAIT_MS, deadline - System.currentTimeMillis()));
        }
        supervisor = null;
        process = null;
        if (lastError.isEmpty()) {
            LOG.fine("bridge adapter stopped");
        }
    }

    /** 监视线程：拉起、等退出、判断是收尾还是崩了，再按退避重启，连败到上限就停手。 */
    private void supervise() {
        while (running) {
            if (gaveUp) {
                pause(500L);
                continue;
            }
            long wait = restartAt - System.currentTimeMillis();
            if (wait > 0L) {
                pause(wait);
                continue;
            }
            restartAt = 0L;
            sampledThisRun = false;
            Process live = null;
            try {
                live = launch();
                int code = live.waitFor();
                // 进程已结束：别让 reconfigure() 对着一个死进程做“重启还是退避”的判断
                process = null;
                if (!running) break;
                if (planned) {
                    // 我们自己杀的：换间隔而已，不是崩溃
                    planned = false;
                    continue;
                }
                String tail = stderrTail();
                String detail = (code == 0 ? "bridge adapter exited" : "bridge adapter exited with code " + code)
                        + (tail.isEmpty() ? "" : ": " + tail);
                failures = sampledThisRun ? 1 : failures + 1;
                lastError = detail;
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    gaveUp = true;
                    lastError = gaveUpReason(failures, detail);
                    LOG.warning(lastError);
                } else {
                    restartAt = System.currentTimeMillis() + RESTART_BACKOFF_MS;
                    LOG.warning(lastError + " — restarting in " + RESTART_BACKOFF_MS + " ms");
                }
            } catch (Throwable failed) {
                if (!running) break;
                failures++;
                lastError = "cannot start the bridge adapter: " + Errors.describe(failed);
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    gaveUp = true;
                    lastError = gaveUpReason(failures, lastError);
                    LOG.warning(lastError);
                } else {
                    restartAt = System.currentTimeMillis() + RESTART_BACKOFF_MS;
                    LOG.warning(lastError + " — retrying in " + RESTART_BACKOFF_MS + " ms");
                }
            } finally {
                closeQuietly(live);
            }
            logState(config);
        }
        running = false;
    }

    private static String gaveUpReason(int attempts, String detail) {
        return "bridge adapter failed " + attempts + " times in a row, stopped: " + detail;
    }

    /** 起一次适配器：Windows 走 powershell.exe，Linux 优先直接执行脚本，没有执行位就交给 /bin/sh。 */
    private Process launch() throws IOException {
        SmtcConfig cfg = config;
        int interval = intervalOf(cfg);
        File file = script();
        List<String> command = new ArrayList<String>();
        if (isWindows()) {
            command.add("powershell.exe");
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(file.getAbsolutePath());
            command.add("-IntervalMs");
            command.add(Integer.toString(interval));
        } else if (scriptExecutable) {
            command.add(file.getAbsolutePath());
            command.add(Integer.toString(interval));
        } else {
            // 没有执行位（FAT、只读 home、noexec 挂载）：用 sh 显式解释，脚本内容一样
            command.add("/bin/sh");
            command.add(file.getAbsolutePath());
            command.add(Integer.toString(interval));
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        File dir = file.getParentFile();
        if (dir != null && dir.isDirectory()) builder.directory(dir);
        builder.redirectErrorStream(false);
        Process live = builder.start();
        // 脚本不读 stdin；管道开着会被某些播放器客户端当成“等输入”而挂住
        closeQuietly(live.getOutputStream());
        synchronized (gate) {
            process = live;
            launchedIntervalMs = interval;
        }
        LOG.info("bridge adapter started: " + command.get(0) + " every " + interval + " ms");
        startReader(live, false);
        startReader(live, true);
        return live;
    }

    private static Charset ansi() {
        try {
            return Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8"));
        } catch (Throwable ignored) {
            return UTF8;
        }
    }

    /** 脚本只写一次，内容没变就不碰磁盘；模组升级换了脚本内容才重写。 */
    private File script() throws IOException {
        File cached = script;
        if (cached != null && cached.isFile()) return cached;
        // 和歌词缓存同一个基准：启动器改写工作目录时，配置与脚本不该留在旧目录
        File root = configRoot != null ? configRoot : new File(System.getProperty("user.dir", "."), "config");
        File dir = new File(new File(root, "smtcraft"), "bridge");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir.getAbsolutePath());
        }
        boolean windows = isWindows();
        File file = new File(dir, windows ? WINDOWS_SCRIPT_NAME : LINUX_SCRIPT_NAME);
        writeIfChanged(file, windows ? WINDOWS_SCRIPT : LINUX_SCRIPT);
        if (!windows) scriptExecutable = makeExecutable(file);
        script = file;
        LOG.fine("bridge adapter script: " + file.getAbsolutePath());
        return file;
    }

    private static void writeIfChanged(File file, String text) throws IOException {
        byte[] body = text.getBytes(UTF8);
        // PowerShell 5.1 读没有 BOM 的 .ps1 时按系统 ANSI 代码页解释：UTF-8 的中文注释
        // 会被拆成半个字符，把下一行的引号吞掉，整个脚本随后报“意外的标记 }”。
        // 所以 .ps1 一律写 BOM；sh 脚本反而不能带（BOM 会让 #! 失效）。
        byte[] bytes = body;
        if (file.getName().endsWith(".ps1")) {
            bytes = new byte[body.length + 3];
            bytes[0] = (byte) 0xEF;
            bytes[1] = (byte) 0xBB;
            bytes[2] = (byte) 0xBF;
            System.arraycopy(body, 0, bytes, 3, body.length);
        }
        if (file.isFile() && file.length() == bytes.length) {
            byte[] present = Files.readAllBytes(file.toPath());
            if (Arrays.equals(present, bytes)) return;
        }
        Files.write(file.toPath(), bytes);
    }

    private static boolean makeExecutable(File file) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
            Files.setPosixFilePermissions(file.toPath(), perms);
            return true;
        } catch (Throwable unsupported) {
            // 没有 POSIX 权限位：不抛，改成 /bin/sh 执行，玩家看到的行为一样
            LOG.fine("cannot set the executable bit: " + Errors.describe(unsupported));
            return false;
        }
    }

    /** stdout 与 stderr 各一条 daemon 线程：阻塞读不该占住任何人的线程池。 */
    private void startReader(final Process live, final boolean errors) {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                if (errors) {
                    readStderr(live);
                } else {
                    readStdout(live);
                }
            }
        }, errors ? "smtcraft-bridge-err" : "smtcraft-bridge-out");
        reader.setDaemon(true);
        reader.start();
    }

    /** 逐行读 stdout：一行就是一个快照，读不完的行留着，绝不按块切行。 */
    private void readStdout(Process live) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(live.getInputStream(), UTF8));
            String line;
            while ((line = reader.readLine()) != null) {
                // 关停中也要把管道读干净，否则子进程可能卡在写阻塞上退不出去
                if (!running) continue;
                handleLine(line);
            }
        } catch (Throwable failed) {
            if (running) {
                lastError = "bridge stdout: " + Errors.describe(failed);
                LOG.log(Level.FINE, lastError, failed);
            }
        } finally {
            closeQuietly(reader);
        }
    }

    private void readStderr(Process live) {
        InputStream stream = null;
        try {
            stream = live.getErrorStream();
            byte[] buffer = new byte[1024];
            byte[] carry = new byte[0];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                byte[] all = new byte[carry.length + read];
                System.arraycopy(carry, 0, all, 0, carry.length);
                System.arraycopy(buffer, 0, all, carry.length, read);
                int cut = completeUtf8(all, all.length);
                if (cut > 0) appendStderr(decode(all, cut));
                // 块边界上被劈开的多字节字符留到下一块，否则中文告警会整行退成 ANSI 乱码
                carry = Arrays.copyOfRange(all, cut, all.length);
            }
            if (carry.length > 0) appendStderr(decode(carry, carry.length));
        } catch (Throwable failed) {
            LOG.log(Level.FINE, "bridge stderr: " + Errors.describe(failed), failed);
        } finally {
            closeQuietly(stream);
        }
    }

    /** 末尾那段可能是被劈开的多字节字符：返回可以安全解码的长度（0 表示整块都是残片）。 */
    private static int completeUtf8(byte[] bytes, int length) {
        int i = length - 1;
        int back = 0;
        while (i >= 0 && back < 4 && (bytes[i] & 0xC0) == 0x80) {
            i--;
            back++;
        }
        if (i < 0) return length;
        int lead = bytes[i] & 0xFF;
        int need;
        if (lead < 0x80) need = 1;
        else if ((lead & 0xE0) == 0xC0) need = 2;
        else if ((lead & 0xF0) == 0xE0) need = 3;
        else if ((lead & 0xF8) == 0xF0) need = 4;
        else return length;
        return length - i >= need ? length : i;
    }

    /**
     * 子进程 stderr 有两套编码：脚本自己写的告警按我们设的 UTF-8 出去，PowerShell 宿主
     * 自己的语法错误仍按 ANSI 代码页写。先按 UTF-8 严格解，解不动再用平台默认。
     */
    private static String decode(byte[] buffer, int length) {
        try {
            return UTF8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buffer, 0, length))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(buffer, 0, length, ANSI);
        }
    }

    private void appendStderr(String text) {
        synchronized (stderrLock) {
            stderr.append(text);
            int over = stderr.length() - STDERR_TAIL;
            if (over > 0) stderr.delete(0, over);
        }
    }

    private String stderrTail() {
        synchronized (stderrLock) {
            String text = stderr.toString().trim();
            if (text.isEmpty()) return "";
            String flat = text.replace('\n', ' ');
            return flat.length() <= 400 ? flat : flat.substring(flat.length() - 400);
        }
    }

    /** 一行 JSON 变一次发布；{@code {"ok":false}} 走换歌空窗那条路，而不是清空 HUD。 */
    private void handleLine(String line) {
        String text = line.trim();
        if (text.isEmpty()) return;
        JsonObject node;
        try {
            JsonElement parsed = new JsonParser().parse(text);
            if (parsed == null || !parsed.isJsonObject()) return;
            node = parsed.getAsJsonObject();
        } catch (Throwable notJson) {
            // 脚本被杀时可能留下半行：跳过它，而不是废掉整条通道
            lastError = "bridge line is not JSON: " + shorten(text);
            LOG.fine(lastError);
            return;
        }
        SmtcConfig cfg = config;
        long now = System.currentTimeMillis();
        lastSampleAt = now;
        sampleCount++;
        sampledThisRun = true;
        lastError = "";
        if (!flag(node, "ok", false)) {
            publishEmpty(cfg, text(node, "reason"));
            logState(cfg);
            return;
        }
        String title = text(node, "title");
        String app = text(node, "app");
        if (title.isEmpty()) {
            publishEmpty(cfg, text(node, "reason"));
            logState(cfg);
            return;
        }
        if (cfg != null && cfg.ignoreBrowserSessions && Draw.isBrowserApp(app)) {
            publishEmpty(cfg, "ignoring the browser session " + app);
            logState(cfg);
            return;
        }
        String artist = text(node, "artist");
        String album = text(node, "album");
        String status = text(node, "status");
        boolean playing = flag(node, "playing", false);
        String key = title + '\u0001' + artist + '\u0001' + album + '\u0001' + app;
        if (!key.equals(heldKey)) {
            // 换歌：封面属于曲目，不跟着桥走，否则新歌没封面时会留着上一张
            heldKey = key;
            heldSince = now;
            playedMs = 0L;
            playedSampledAt = now;
            // 只在新曲目不等于“当前封面所属的曲目”时才丢封面：同一首歌从广告、暂停
            // 或浏览器会话里回来时（heldKey 已被 publishEmpty 清空）封面要留着，
            // 因为脚本只在 artHash 变化时才重发，清掉就再也拿不回来
            if (!key.equals(artKey)) {
                artwork = new byte[0];
                artworkHash = "";
                artKey = "";
            }
        } else if (playing && playedSampledAt > 0L) {
            // 只累计“真的在播”的时间，暂停时歌词时钟要冻住
            playedMs += Math.max(0L, Math.min(now - playedSampledAt, 10_000L));
        }
        playedSampledAt = now;
        adoptArt(node, key);
        lastTrackAt = now;
        long rawPosition = number(node, "positionMs", 0L);
        float rate = (float) number(node, "rate", 1.0d);
        long shown = rawPosition;
        if (cfg == null || cfg.progressSmooth) {
            // 播放器多半按整秒发布位置，直接显示会一跳一跳；smoother 负责补帧
            shown = smoother.presented(rawPosition, playing, rate, key, now);
        }
        latest = new TrackInfo(true, playing, title, artist, album, app, status, shown,
                number(node, "startMs", 0L), number(node, "endMs", 0L), rate, now,
                heldSince, playedMs);
        logState(cfg);
    }

    /** 同一个 artHash 只解码一次；脚本没给哈希时用内容自己造一个稳定的。 */
    private void adoptArt(JsonObject node, String trackKey) {
        String encoded = text(node, "art");
        if (encoded.isEmpty()) return;
        if (encoded.length() > ART_LIMIT_B64) {
            // 只有到阈值才说话：正常封面几十 KB，超限说明脚本那边的缩图没生效
            if (!artTooLarge) {
                artTooLarge = true;
                LOG.warning("bridge cover is " + encoded.length() + " chars of base64, over the "
                        + ART_LIMIT_B64 + " limit — skipped");
            }
            return;
        }
        String hash = text(node, "artHash");
        if (hash.isEmpty()) hash = hashOf(encoded);
        if (hash.equals(artworkHash)) {
            artKey = trackKey;
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 0) return;
            artwork = bytes;
            artworkHash = hash;
            artKey = trackKey;
            artTooLarge = false;
        } catch (Throwable bad) {
            LOG.fine("bridge cover is not base64: " + Errors.describe(bad));
        }
    }

    /**
     * 一段没有播放的时间：换歌和“这一轮没读出来”在 HUD 上都长得一样，所以在
     * {@code swapGraceMs} 内保留上一张卡片，而不是闪白一下。
     */
    private void publishEmpty(SmtcConfig cfg, String note) {
        long now = System.currentTimeMillis();
        String reason = note == null ? "" : note.trim();
        if (!reason.isEmpty()) lastError = reason;
        long grace = cfg == null ? 0L : Math.max(0, cfg.swapGraceMs);
        if (latest.hasTrack() && now - lastTrackAt <= grace) return;
        latest = TrackInfo.EMPTY;
        heldKey = "";
        heldSince = 0L;
        playedMs = 0L;
        playedSampledAt = 0L;
        // 封面不在“没有播放”时清掉：脚本只在 artHash 变化时才重发封面，这里一清，
        // 同一首歌从广告/暂停回来（或浏览器会话被忽略的那段）后半段就再也拿不到图。
        // 换歌时的那次清空（publishSnapshot 的 key 变化分支）已经够防止串封面了，
        // 而 hasTrack 为 false 时 HUD 根本不会走 renderPanel。
        smoother.reset();
    }

    /** 只在曲目或失败原因真的变了时说话：150 ms 一轮不能每轮刷日志。 */
    private void logState(SmtcConfig cfg) {
        String err = lastError == null ? "" : lastError;
        if (!err.equals(loggedError)) {
            String had = loggedError;
            loggedError = err;
            if (err.isEmpty()) {
                LOG.info("bridge adapter is healthy again (was: " + (had.isEmpty() ? "starting" : had) + ")");
            } else {
                LOG.warning("bridge adapter: " + err);
            }
        }
        TrackInfo info = latest;
        String key = info.hasTrack() ? info.dedupeKey() : "";
        if (!key.equals(loggedTrack)) {
            loggedTrack = key;
            if (!key.isEmpty()) {
                LOG.info("now playing: " + info.title() + " - " + info.artist()
                        + " (" + info.app() + ") " + info.status() + " "
                        + info.positionMs() + "/" + info.endMs() + " ms, cover "
                        + artwork.length + " bytes");
            } else {
                LOG.fine("nothing is playing");
            }
        }
        if (cfg != null && cfg.debug) {
            LOG.fine("sample #" + sampleCount + " err='" + err + "'");
        }
    }

    private static int intervalOf(SmtcConfig cfg) {
        int ms = cfg == null ? DEFAULT_INTERVAL_MS : cfg.pollIntervalMs;
        if (ms < MIN_INTERVAL_MS) return MIN_INTERVAL_MS;
        return Math.min(ms, MAX_INTERVAL_MS);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static boolean flag(JsonObject node, String name, boolean fallback) {
        JsonElement element = node.get(name);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return element.getAsBoolean();
        } catch (Throwable notBoolean) {
            return fallback;
        }
    }

    private static long number(JsonObject node, String name, long fallback) {
        JsonElement element = node.get(name);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return element.getAsLong();
        } catch (Throwable notNumber) {
            return fallback;
        }
    }

    private static double number(JsonObject node, String name, double fallback) {
        JsonElement element = node.get(name);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return element.getAsDouble();
        } catch (Throwable notNumber) {
            return fallback;
        }
    }

    private static String text(JsonObject node, String name) {
        JsonElement element = node.get(name);
        if (element == null || element.isJsonNull()) return "";
        try {
            String value = element.getAsString();
            return value == null ? "" : value;
        } catch (Throwable notString) {
            return "";
        }
    }

    /** 脚本没给 artHash 时的替代品：内容决定值，长度一起进哈希，短碰撞不值得防。 */
    private static String hashOf(String encoded) {
        long sum = 1125899906842597L;
        for (int i = 0; i < encoded.length(); i++) sum = 31L * sum + encoded.charAt(i);
        return Long.toHexString(sum) + "-" + encoded.length();
    }

    private static String shorten(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static boolean awaitExit(Process live, long millis) {
        try {
            return live.waitFor(Math.max(Budgets.MIN_WAIT_MS, millis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(Math.max(Budgets.MIN_WAIT_MS, millis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 等一会儿，但能被 reconfigure() / close() 立刻叫醒。 */
    private void pause(long millis) {
        synchronized (gate) {
            try {
                gate.wait(Math.max(20L, millis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 管道跟着进程一起走
        }
    }

    private static void closeQuietly(Process live) {
        if (live == null) return;
        closeQuietly(live.getOutputStream());
        closeQuietly(live.getInputStream());
        closeQuietly(live.getErrorStream());
    }

    /**
     * Windows 适配器：PowerShell 5.1 直连 WinRT 的 SMTC，每行打一个 JSON 快照。
     *
     * <p>读 {@code GetCurrentSession()} 的播放状态、{@code TryGetMediaPropertiesAsync()}
     * 的标题/歌手/专辑与 AppUserModelId、{@code GetTimelineProperties()} 的三段时间（毫秒），
     * 封面从媒体属性上的 {@code Thumbnail} 取成 base64（超过 {@code $ART_LIMIT} 丢弃并告警一次）。没有会话就
     * 输出 {@code {"ok":false}}，Java 侧据此走换歌空窗。</p>
     */
    private static final String WINDOWS_SCRIPT =
            "param([int]$IntervalMs = 150)\n"
            + "$ErrorActionPreference = 'Stop'\n"
            + "try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }\n"
            + "if ($IntervalMs -lt 100) { $IntervalMs = 100 }\n"
            + "if ($IntervalMs -gt 2000) { $IntervalMs = 2000 }\n"
            + "# 只报告一次：150 ms 一轮，同一个错误反复刷进 stderr 只会把 Java 侧那 8 KiB 尾巴冲掉\n"
            + "# 封面 base64 上限：和 Java 的 ART_LIMIT_B64 一致（这里量的是 base64 字符数）\n"
+ "$ART_LIMIT = 524288\n"
+ "# 超过上限的封面先缩到 512 像素再发：HUD 最多也只画到 256 像素，整张 2000×2000 的\n"
+ "# PNG 光 base64 就有好几 MB，直接丢等于让用户“这首没封面”。System.Drawing 在 PS 5.1\n"
+ "# 上随系统就有，取不到就返回 $null，由调用方走“超限就跳过”的老路。\n"
+ "function Shrink([byte[]]$data) {\n"
+ "    try { Add-Type -AssemblyName System.Drawing -ErrorAction Stop } catch { return $null }\n"
+ "    $src = $null\n"
+ "    $dst = $null\n"
+ "    $gfx = $null\n"
+ "    try {\n"
+ "        $ms = New-Object System.IO.MemoryStream(, $data)\n"
+ "        $src = [System.Drawing.Image]::FromStream($ms, $true, $true)\n"
+ "        $side = 512\n"
+ "        $w = $src.Width\n"
+ "        $h = $src.Height\n"
+ "        $long = [Math]::Max($w, $h)\n"
+ "        $scale = 1.0\n"
+ "        if ($long -gt $side) { $scale = $side / $long }\n"
+ "        $nw = [int][Math]::Max(1, [Math]::Round($w * $scale))\n"
+ "        $nh = [int][Math]::Max(1, [Math]::Round($h * $scale))\n"
+ "        $dst = New-Object System.Drawing.Bitmap($nw, $nh)\n"
+ "        $gfx = [System.Drawing.Graphics]::FromImage($dst)\n"
+ "        $gfx.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic\n"
+ "        $gfx.Clear([System.Drawing.Color]::Black)\n"
+ "        $gfx.DrawImage($src, 0, 0, $nw, $nh)\n"
+ "        $codec = $null\n"
+ "        foreach ($c in [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders()) { if ($c.MimeType -eq 'image/jpeg') { $codec = $c } }\n"
+ "        if ($null -eq $codec) { return $null }\n"
+ "        $pars = New-Object System.Drawing.Imaging.EncoderParameters(1)\n"
+ "        $pars.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]88)\n"
+ "        $out = New-Object System.IO.MemoryStream\n"
+ "        $dst.Save($out, $codec, $pars)\n"
+ "        return $out.ToArray()\n"
+ "    } catch {\n"
+ "        return $null\n"
+ "    } finally {\n"
+ "        if ($null -ne $gfx) { $gfx.Dispose() }\n"
+ "        if ($null -ne $dst) { $dst.Dispose() }\n"
+ "        if ($null -ne $src) { $src.Dispose() }\n"
+ "    }\n"
+ "}\n"
            + "$script:reported = @{}\n"
            + "function Warn([string]$Message) {\n"
            + "    if ($script:reported.ContainsKey($Message)) { return }\n"
            + "    $script:reported[$Message] = $true\n"
            + "    [Console]::Error.WriteLine($Message)\n"
            + "}\n"
            + "# PowerShell 5.1 没有 await：用 System.WindowsRuntimeSystemExtensions.AsTask 桥接 WinRT 的\n"
            + "# IAsyncOperation，等满超时就返回 $null，绝不无限挂住（挂住就等于拿到一个永远没有输出的进程）\n"
            + "# 关键：System.WindowsRuntimeSystemExtensions 在 System.Runtime.WindowsRuntime 里，\n"
            + "# PowerShell 5.1 启动时不会自动加载它，直接写类型名只会得到 “Unable to find type”，\n"
            + "# 所以先显式 Add-Type（只靠 LoadWithPartialName 在部分主机上取不到）。\n"
            + "try { Add-Type -AssemblyName System.Runtime.WindowsRuntime -ErrorAction Stop } catch {\n"
            + "    [void][System.Reflection.Assembly]::LoadWithPartialName('System.Runtime.WindowsRuntime')\n"
            + "}\n"
            + "$script:AsTask = $null\n"
            + "try {\n"
            + "    $script:AsTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]\n"
            + "} catch { }\n"
            + "if ($null -eq $script:AsTask) {\n"
            + "    [Console]::Error.WriteLine('smtc.ps1: this PowerShell has no WinRT AsTask bridge')\n"
            + "    exit 3\n"
            + "}\n"
            + "$script:AsRead = $null\n"
            + "try {\n"
            + "    $script:AsRead = @([System.IO.WindowsRuntimeStreamExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsStreamForRead' -and $_.GetParameters().Count -eq 1 })[0]\n"
            + "} catch { }\n"
            + "if ($null -eq $script:AsRead) {\n"
            + "    [Console]::Error.WriteLine('smtc.ps1: this PowerShell has no AsStreamForRead bridge')\n"
            + "    exit 3\n"
            + "}\n"
            + "function Await($Operation, $Type, $TimeoutMs) {\n"
            + "    if ($null -eq $Operation -or $null -eq $Type) { return $null }\n"
            + "    try {\n"
            + "        $task = $script:AsTask.MakeGenericMethod($Type).Invoke($null, @($Operation))\n"
            + "    } catch {\n"
            + "        Warn ('smtc.ps1: await failed: ' + $_.Exception.Message)\n"
            + "        return $null\n"
            + "    }\n"
            + "    if ($task.Wait($TimeoutMs)) { return $task.Result }\n"
            + "    Warn ('smtc.ps1: a WinRT call timed out after ' + $TimeoutMs + ' ms')\n"
            + "    return $null\n"
            + "}\n"
            + "$ManagerClass = $null\n"
            + "$PropsClass = $null\n"
            + "$ThumbClass = $null\n"
            + "try {\n"
            + "    $ManagerClass = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media, ContentType = WindowsRuntime]\n"
            + "    $PropsClass = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media, ContentType = WindowsRuntime]\n"
            + "    $ThumbClass = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]\n"
            + "} catch {\n"
            + "    Warn ('smtc.ps1: WinRT SMTC is unavailable: ' + $_.Exception.Message)\n"
            + "}\n"
            + "$md5 = [System.Security.Cryptography.MD5]::Create()\n"
            + "function HashBytes([byte[]]$Bytes) {\n"
            + "    $sum = $md5.ComputeHash($Bytes)\n"
            + "    $sb = New-Object System.Text.StringBuilder\n"
            + "    foreach ($b in $sum) { [void]$sb.Append($b.ToString('x2')) }\n"
            + "    return $sb.ToString()\n"
            + "}\n"
            + "function Emit([string]$Line) {\n"
            + "    [Console]::Out.WriteLine($Line)\n"
            + "    [Console]::Out.Flush()\n"
            + "}\n"
            + "$manager = $null\n"
            + "$artKey = ''\n"
            + "$art64 = ''\n"
            + "$artHash = ''\n"
            + "$artTries = 0\n"
            + "$artTryAt = 0\n"
            + "$artWarned = $false\n"
            + "$artSkipped = $false\n"
            + "$artGaveUp = $false\n"
            + "$artSent = ''\n"
            + "while ($true) {\n"
            + "    $started = Get-Date\n"
            + "    try {\n"
            + "        if ($null -eq $ManagerClass) {\n"
            + "            Emit '{\"ok\":false,\"reason\":\"WinRT SMTC is unavailable\"}'\n"
            + "        } else {\n"
            + "            if ($null -eq $manager) {\n"
            + "                try { $manager = Await ($ManagerClass::RequestAsync()) $ManagerClass 3000 } catch { Warn ('smtc.ps1: ' + $_.Exception.Message) }\n"
            + "            }\n"
            + "            $session = $null\n"
            + "            if ($null -ne $manager) { $session = $manager.GetCurrentSession() }\n"
            + "            if ($null -eq $session) {\n"
            + "                Emit '{\"ok\":false,\"reason\":\"no SMTC session\"}'\n"
            + "            } else {\n"
            + "                $play = $session.GetPlaybackInfo()\n"
            + "                $status = 'Closed'\n"
            + "                if ($null -ne $play) { $status = [string]$play.PlaybackStatus }\n"
            + "                $rate = 1.0\n"
            + "                try { if ($null -ne $play -and $null -ne $play.PlaybackRate) { $rate = [double]$play.PlaybackRate } } catch { }\n"
            + "                $media = Await ($session.TryGetMediaPropertiesAsync()) $PropsClass 1500\n"
            + "                $title = ''\n"
            + "                $artist = ''\n"
            + "                $album = ''\n"
            + "                $app = [string]$session.SourceAppUserModelId\n"
            + "                if ($null -ne $media) {\n"
            + "                    $title = [string]$media.Title\n"
            + "                    $artist = [string]$media.Artist\n"
            + "                    $album = [string]$media.AlbumTitle\n"
            + "                    if ([string]::IsNullOrEmpty($app)) {\n"
            + "                        $probe = $media.PSObject.Properties['AppUserModelId']\n"
            + "                        if ($null -ne $probe) { $app = [string]$probe.Value }\n"
            + "                    }\n"
            + "                }\n"
            + "                if ([string]::IsNullOrWhiteSpace($title)) {\n"
            + "                    Emit '{\"ok\":false,\"reason\":\"the SMTC session has no title\"}'\n"
            + "                } else {\n"
            + "                    $posMs = 0\n"
            + "                    $startMs = 0\n"
            + "                    $endMs = 0\n"
            + "                    try {\n"
            + "                        $tl = $session.GetTimelineProperties()\n"
            + "                        if ($null -ne $tl) {\n"
            + "                            $posMs = [long]$tl.Position.TotalMilliseconds\n"
            + "                            $startMs = [long]$tl.StartTime.TotalMilliseconds\n"
            + "                            $endMs = [long]$tl.EndTime.TotalMilliseconds\n"
            + "                        }\n"
            + "                    } catch { Warn ('smtc.ps1: timeline: ' + $_.Exception.Message) }\n"
            + "                    $key = $title + [char]1 + $artist + [char]1 + $album + [char]1 + $app\n"
            + "                    if ($key -ne $artKey) {\n"
            + "                        $artKey = $key\n"
            + "                        $art64 = ''\n"
            + "                        $artHash = ''\n"
            + "                        $artTries = 0\n"
            + "                        $artTryAt = 0\n"
            + "                        $artWarned = $false\n"
            + "                        $artSkipped = $false\n"
            + "                        $artGaveUp = $false\n"
            + "                        $artSent = ''\n"
            + "                    }\n"
            + "                    # 切歌那一瞬媒体属性先到、缩略图后到：只取一次就会整首歌空着，所以空封面要重试；\n"
            + "                    # 只有真的拿到图才停手，最多 40 次、每 500 ms 一次（约 20 秒），失败告警每首歌只报一次\n"
            + "                    if ($null -ne $media -and [string]::IsNullOrEmpty($art64) -and $artTries -lt 160) {\n"
            + "                        $nowTicks = [long]([DateTime]::UtcNow.Ticks / 10000)\n"
            + "                        # 前 40 次每 500 ms 一拍（切歌瞬间缩略图常常慢半秒到几秒），之后每 2 秒一次、\n"
            + "                        # 一直跟到 160 次（约 4 分钟）：整首歌都可能才把封面发出来\n"
            + "                        $artGap = 500\n"
            + "                        if ($artTries -ge 40) { $artGap = 2000 }\n"
            + "                        if ($artTryAt -eq 0 -or ($nowTicks - $artTryAt) -ge $artGap) {\n"
            + "                            $artTryAt = $nowTicks\n"
            + "                            $artTries = $artTries + 1\n"
            + "                            try {\n"
            + "                                # TryGetThumbnailAsync 返回的是 RandomAccessStreamReference，不是流本身：\n"
            + "                                # 还要再 OpenReadAsync 一次才拿得到带 ContentType 的 IRandomAccessStream\n"
            + "                                $thumbRef = $media.Thumbnail\n"
            + "                                $thumb = $null\n"
            + "                                if ($null -ne $thumbRef) { $thumb = Await ($thumbRef.OpenReadAsync()) $ThumbClass 1500 }\n"
            + "                                if ($null -ne $thumb -and $null -ne $script:AsRead) {\n"
            + "                                # __ComObject 上没有 Size，PS 也选不到 AsStreamForRead 的重载：用反射的单参版本\n"
            + "                                    $stream = $script:AsRead.Invoke($null, @($thumb))\n"
            + "                                    $buffer = New-Object System.IO.MemoryStream\n"
            + "                                    try { $stream.CopyTo($buffer) } finally { $stream.Dispose() }\n"
            + "                                    $bytes = $buffer.ToArray()\n"
            + "                                    $buffer.Dispose()\n"
            + "                                    $encoded = [Convert]::ToBase64String($bytes)\n"
            + "                                    if ($encoded.Length -gt $ART_LIMIT) {\n"
            + "                                        $small = Shrink $bytes\n"
            + "                                        if ($null -ne $small) {\n"
            + "                                            $bytes = $small\n"
            + "                                            $encoded = [Convert]::ToBase64String($bytes)\n"
            + "                                        }\n"
            + "                                    }\n"
            + "                                    if ($bytes.Length -gt 0) {\n"
            + "                                        if ($encoded.Length -le $ART_LIMIT) {\n"
            + "                                            $art64 = $encoded\n"
            + "                                            $artHash = (HashBytes $bytes)\n"
            + "                                        } elseif (-not $artSkipped) {\n"
            + "                                            # 缩完还超限就别每 500 ms 重试同一张图了，报一次就收手\n"
            + "                                            $artSkipped = $true\n"
            + "                                            $artGaveUp = $true\n"
            + "                                            $artTries = 160\n"
            + "                                            Warn ('smtc.ps1: cover too large: ' + $bytes.Length + ' bytes')\n"
            + "                                        }\n"
            + "                                    }\n"
            + "                                }\n"
            + "                            } catch {\n"
            + "                                if (-not $artWarned) { $artWarned = $true; Warn ('smtc.ps1: thumbnail: ' + $_.Exception.Message) }\n"
            + "                            }\n"
            + "                        }\n"
            + "                    }\n"
            + "                    # 跟到 160 次还没图就认了：有些应用（浏览器、部分 UWP 播放器）根本不发缩略图，\n"
            + "                    # 这里报一次，免得用户只能靠“封面一直没有”去猜\n"
            + "                    if ($null -ne $media -and [string]::IsNullOrEmpty($art64) -and $artTries -ge 160 -and -not $artGaveUp) {\n"
            + "                        $artGaveUp = $true\n"
            + "                        Warn 'smtc.ps1: this session never publishes a thumbnail'\n"
            + "                    }\n"
            + "                    # 封面只在哈希变化的那一条里发：每 150 ms 重发几百 KB base64 纯属白烧\n"
            + "                    $artBody = ''\n"
            + "                    if (-not [string]::IsNullOrEmpty($art64) -and $artHash -ne $artSent) {\n"
            + "                        $artBody = $art64\n"
            + "                        $artSent = $artHash\n"
            + "                    }\n"
            + "                    $document = [ordered]@{\n"
            + "                        ok = $true\n"
            + "                        playing = ($status -eq 'Playing')\n"
            + "                        title = $title\n"
            + "                        artist = $artist\n"
            + "                        album = $album\n"
            + "                        app = $app\n"
            + "                        status = $status\n"
            + "                        positionMs = $posMs\n"
            + "                        startMs = $startMs\n"
            + "                        endMs = $endMs\n"
            + "                        rate = $rate\n"
            + "                        artHash = $artHash\n"
            + "                        art = $artBody\n"
            + "                    }\n"
            + "                    Emit ($document | ConvertTo-Json -Compress -Depth 4)\n"
            + "                }\n"
            + "            }\n"
            + "        }\n"
            + "    } catch {\n"
            + "        Warn ('smtc.ps1: ' + $_.Exception.Message)\n"
            + "        $manager = $null\n"
            + "        Emit '{\"ok\":false,\"reason\":\"reading the SMTC session failed\"}'\n"
            + "    }\n"
            + "    $spent = ((Get-Date) - $started).TotalMilliseconds\n"
            + "    $wait = [int]($IntervalMs - $spent)\n"
            + "    if ($wait -lt 20) { $wait = 20 }\n"
            + "    Start-Sleep -Milliseconds $wait\n"
            + "}\n"
            + "\n";

    /**
     * Linux 适配器：先试 playerctl（一次 {@code --format} 拿全字段，再补 position/status），
     * 没有再退到 gdbus 直接读 {@code org.mpris.MediaPlayer2.Player} 的 GetAll；两条路都不成
     * 就输出 {@code {"ok":false}}，让 Java 侧把它们当成“暂时没有播放器”而不是崩溃。
     */
    private static final String LINUX_SCRIPT =
            "#!/bin/sh\n"
            + "# smtcraft 的 MPRIS2 适配器：每 INTERVAL 毫秒往 stdout 打一行 JSON 快照。\n"
            + "# 参数 1 是轮询间隔（毫秒）。playerctl 优先，gdbus 兜底；两条路都不成就报 ok:false，\n"
            + "# 让 Java 侧走换歌空窗，而不是把“这台机器没有播放器”当成适配器崩了。\n"
            + "INTERVAL=\"${1:-150}\"\n"
            + "case \"$INTERVAL\" in\n"
            + "  ''|*[!0-9]*) INTERVAL=150 ;;\n"
            + "esac\n"
            + "if [ \"$INTERVAL\" -lt 100 ]; then INTERVAL=100; fi\n"
            + "if [ \"$INTERVAL\" -gt 2000 ]; then INTERVAL=2000; fi\n"
            + "SLEEP=$(awk -v ms=\"$INTERVAL\" 'BEGIN { printf \"%.3f\", ms / 1000 }')\n"
            + "PLAYER_IFACE=\"org.mpris.MediaPlayer2.Player\"\n"
            + "PREFIX=\"org.mpris.MediaPlayer2.\"\n"
            + "# 封面 base64 上限：和 Java 的 ART_LIMIT_B64 一致（这里量的是 base64 字符数）\n"
            + "ART_LIMIT=524288\n"
            + "SEP=$(printf '\\037')\n"
            + "ART_B64=''\n"
            + "ART_HASH=''\n"
            + "ART_URL=''\n"
            + "ART_WARNED=''\n"
            + "ART_SENT=''\n"
            + "# 同一首歌的封面最多试这么多次（一次 curl 最多 2 秒，试满才认栽并告警一次）\n"
            + "ART_MAX_TRIES=20\n"
            + "ART_PENDING=''\n"
            + "ART_TRIES=0\n"
            + "# 值进 JSON 之前只做最小限度的转义：反斜杠、双引号、控制字符（含换行）\n"
            + "escape() {\n"
            + "  sed -e 's/\\\\/\\\\\\\\/g' -e 's/\"/\\\\\"/g' | tr -d '\\r\\n\\001-\\010\\013\\014\\016-\\037'\n"
            + "}\n"
            + "encode_file() {\n"
            + "  base64 \"$1\" 2>/dev/null | tr -d '\\r\\n'\n"
            + "}\n"
            + "# file:// 的路径可能是百分号编码的：先按原样试，再用 python3 解一次（没有 python3 就算了）\n"
            + "local_path() {\n"
            + "  p=\"${1#file://}\"\n"
            + "  if [ -f \"$p\" ]; then printf '%s' \"$p\"; return 0; fi\n"
            + "  if command -v python3 >/dev/null 2>&1; then\n"
            + "    q=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.unquote(sys.argv[1]))' \"$p\" 2>/dev/null)\n"
            + "    if [ -n \"$q\" ] && [ -f \"$q\" ]; then printf '%s' \"$q\"; return 0; fi\n"
            + "  fi\n"
            + "  return 1\n"
            + "}\n"
            + "art_from_url() {\n"
            + "  url=\"$1\"\n"
            + "  [ -n \"$url\" ] || return 0\n"
            + "  case \"$url\" in\n"
            + "    file://*)\n"
            + "      p=$(local_path \"$url\") || return 0\n"
            + "      encode_file \"$p\"\n"
            + "      ;;\n"
            + "    http://*|https://*)\n"
            + "      command -v curl >/dev/null 2>&1 || return 0\n"
            + "      # 单次尝试最多 2 秒：轮询间隔只有 100–2000 ms，卡更久会让每一轮都在补上一次的欠账\n"
            + "      curl -fsS --connect-timeout 1 --max-time 2 \"$url\" 2>/dev/null | base64 | tr -d '\\r\\n'\n"
            + "      ;;\n"
            + "  esac\n"
            + "}\n"
            + "prepare_art() {\n"
            + "  url=\"$1\"\n"
            + "  if [ -z \"$url\" ]; then ART_B64=''; ART_HASH=''; ART_URL=''; ART_PENDING=''; ART_TRIES=0; return 0; fi\n"
            + "  if [ \"$url\" = \"$ART_URL\" ]; then return 0; fi\n"
            + "  # 换歌才重下一张图：每轮都 curl + base64 + cksum 会把轮询开销翻几倍。\n"
            + "  # 下载失败不等于“这首没有封面”：同一首歌里最多试 ART_MAX_TRIES 次（Windows 侧同样重试），\n"
            + "  # 一次网络抖动不该让整首歌空着。\n"
            + "  if [ \"$url\" != \"$ART_PENDING\" ]; then ART_PENDING=\"$url\"; ART_TRIES=0; fi\n"
            + "  ART_TRIES=$((ART_TRIES + 1))\n"
            + "  b64=$(art_from_url \"$url\")\n"
            + "  if [ -z \"$b64\" ]; then\n"
            + "    if [ \"$ART_TRIES\" -ge \"$ART_MAX_TRIES\" ]; then\n"
            + "      ART_URL=\"$url\"\n"
            + "      if [ \"$ART_WARNED\" != \"$url\" ]; then\n"
            + "        ART_WARNED=\"$url\"\n"
            + "        printf 'mpris.sh: cover unavailable: %s (gave up after %s tries)\\n' \"$url\" \"$ART_TRIES\" >&2\n"
            + "      fi\n"
            + "    fi\n"
            + "    return 0\n"
            + "  fi\n"
            + "  ART_URL=\"$url\"\n"
            + "  ART_PENDING=''\n"
            + "  ART_SENT=''\n"
            + "  ART_B64=''\n"
            + "  ART_HASH=''\n"
            + "  if [ \"${#b64}\" -gt \"$ART_LIMIT\" ]; then\n"
            + "    if [ \"$ART_WARNED\" != \"$url\" ]; then\n"
            + "      ART_WARNED=\"$url\"\n"
            + "      printf 'mpris.sh: cover too large: %s base64 chars, dropped\\n' \"${#b64}\" >&2\n"
            + "    fi\n"
            + "    return 0\n"
            + "  fi\n"
            + "  ART_B64=\"$b64\"\n"
            + "  ART_HASH=\"crc$(printf '%s' \"$b64\" | cksum | awk '{ print $1 }')\"\n"
            + "}\n"
            + "emit_json() {\n"
            + "  title_e=$(printf '%s' \"$TITLE\" | escape)\n"
            + "  artist_e=$(printf '%s' \"$ARTIST\" | escape)\n"
            + "  album_e=$(printf '%s' \"$ALBUM\" | escape)\n"
            + "  app_e=$(printf '%s' \"$APP\" | escape)\n"
            + "  status_e=$(printf '%s' \"$STATUS\" | escape)\n"
            + "  # 封面只在哈希变化的那一条里发：150 ms 一轮重发几百 KB base64 纯属白烧\n"
            + "  art_out=''\n"
            + "  if [ -n \"$ART_B64\" ] && [ \"$ART_HASH\" != \"$ART_SENT\" ]; then art_out=\"$ART_B64\"; ART_SENT=\"$ART_HASH\"; fi\n"
            + "  printf '{\"ok\":true,\"playing\":%s,\"title\":\"%s\",\"artist\":\"%s\",\"album\":\"%s\",\"app\":\"%s\",\"status\":\"%s\",\"positionMs\":%s,\"startMs\":0,\"endMs\":%s,\"rate\":1.0,\"artHash\":\"%s\",\"art\":\"%s\"}\\n' \"$PLAYING\" \"$title_e\" \"$artist_e\" \"$album_e\" \"$app_e\" \"$status_e\" \"$POS_MS\" \"$LEN_MS\" \"$ART_HASH\" \"$art_out\"\n"
            + "}\n"
            + "read_playerctl() {\n"
            + "  command -v playerctl >/dev/null 2>&1 || return 1\n"
            + "  FMT=\"{{xesam:title}}${SEP}{{xesam:artist}}${SEP}{{xesam:album}}${SEP}{{mpris:length}}\"\n"
            + "  meta=$(playerctl metadata --format \"$FMT\" 2>/dev/null)\n"
            + "  if [ -n \"$meta\" ]; then\n"
            + "    TITLE=$(printf '%s' \"$meta\" | awk -F \"$SEP\" '{ print $1 }')\n"
            + "    ARTIST=$(printf '%s' \"$meta\" | awk -F \"$SEP\" '{ print $2 }')\n"
            + "    ALBUM=$(printf '%s' \"$meta\" | awk -F \"$SEP\" '{ print $3 }')\n"
            + "    len_us=$(printf '%s' \"$meta\" | awk -F \"$SEP\" '{ print $4 }')\n"
            + "  else\n"
            + "    # 老版本 playerctl 不认模板里的某个键：退回逐项读，多两次 fork 但能用\n"
            + "    TITLE=$(playerctl metadata xesam:title 2>/dev/null)\n"
            + "    ARTIST=$(playerctl metadata xesam:artist 2>/dev/null)\n"
            + "    ALBUM=$(playerctl metadata xesam:album 2>/dev/null)\n"
            + "    len_us=$(playerctl metadata mpris:length 2>/dev/null)\n"
            + "  fi\n"
            + "  [ -n \"$TITLE\" ] || return 1\n"
            + "  STATUS=$(playerctl status 2>/dev/null)\n"
            + "  [ -n \"$STATUS\" ] || STATUS='Stopped'\n"
            + "  pos_s=$(playerctl position 2>/dev/null)\n"
            + "  APP=$(playerctl -l 2>/dev/null | sed -n '1p')\n"
            + "  [ -n \"$APP\" ] || APP='unknown'\n"
            + "  POS_MS=$(awk -v v=\"$pos_s\" 'BEGIN { if (v == \"\") v = 0; printf \"%d\", v * 1000 }')\n"
            + "  LEN_MS=$(awk -v v=\"$len_us\" 'BEGIN { if (v == \"\") v = 0; printf \"%d\", v / 1000 }')\n"
            + "  if [ \"$STATUS\" = \"Playing\" ]; then PLAYING=true; else PLAYING=false; fi\n"
            + "  prepare_art \"$(playerctl metadata mpris:artUrl 2>/dev/null)\"\n"
            + "  emit_json\n"
            + "  return 0\n"
            + "}\n"
            + "# gdbus 的 GetAll 输出是 GVariant 文本：只按属性名截取，够用且不引入任何依赖\n"
            + "gval_str() {\n"
            + "  printf '%s' \"$OUT\" | sed -n \"s/.*'$1': <'\\([^']*\\)'>.*/\\1/p\" | sed -n '1p'\n"
            + "}\n"
            + "gval_num() {\n"
            + "  printf '%s' \"$OUT\" | sed -n \"s/.*'$1': <\\([a-z0-9]* \\)\\{0,1\\}\\([0-9-]\\{1,\\}\\)>.*/\\2/p\" | sed -n '1p'\n"
            + "}\n"
            + "read_gdbus() {\n"
            + "  command -v gdbus >/dev/null 2>&1 || return 1\n"
            + "  names=$(gdbus call --session --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.ListNames 2>/dev/null)\n"
            + "  [ -n \"$names\" ] || return 1\n"
            + "  dest=$(printf '%s' \"$names\" | tr ',' '\\n' | sed -n \"s/.*'\\($PREFIX[A-Za-z0-9._-]*\\)'.*/\\1/p\" | sed -n '1p')\n"
            + "  [ -n \"$dest\" ] || return 1\n"
            + "  OUT=$(gdbus call --session --dest \"$dest\" --object-path /org/mpris/MediaPlayer2 --method org.freedesktop.DBus.Properties.GetAll \"$PLAYER_IFACE\" 2>/dev/null)\n"
            + "  [ -n \"$OUT\" ] || return 1\n"
            + "  TITLE=$(gval_str 'xesam:title')\n"
            + "  [ -n \"$TITLE\" ] || return 1\n"
            + "  ARTIST=$(printf '%s' \"$OUT\" | sed -n \"s/.*'xesam:artist': <\\[\\(.*\\)\\]>.*/\\1/p\" | sed -n '1p' | sed -e \"s/'//g\")\n"
            + "  ALBUM=$(gval_str 'xesam:album')\n"
            + "  STATUS=$(gval_str 'PlaybackStatus')\n"
            + "  [ -n \"$STATUS\" ] || STATUS='Stopped'\n"
            + "  len_us=$(gval_num 'mpris:length')\n"
            + "  art_url=$(gval_str 'mpris:artUrl')\n"
            + "  pos_us=$(gdbus call --session --dest \"$dest\" --object-path /org/mpris/MediaPlayer2 --method org.freedesktop.DBus.Properties.Get \"$PLAYER_IFACE\" Position 2>/dev/null | sed -n \"s/.*<\\([a-z0-9]* \\)\\{0,1\\}\\([0-9-]\\{1,\\}\\)>.*/\\2/p\" | sed -n '1p')\n"
            + "  APP=$(printf '%s' \"$dest\" | sed -e \"s/^$PREFIX//\" -e 's/\\..*//')\n"
            + "  [ -n \"$APP\" ] || APP='unknown'\n"
            + "  POS_MS=$(awk -v v=\"$pos_us\" 'BEGIN { if (v == \"\") v = 0; printf \"%d\", v / 1000 }')\n"
            + "  LEN_MS=$(awk -v v=\"$len_us\" 'BEGIN { if (v == \"\") v = 0; printf \"%d\", v / 1000 }')\n"
            + "  if [ \"$STATUS\" = \"Playing\" ]; then PLAYING=true; else PLAYING=false; fi\n"
            + "  prepare_art \"$art_url\"\n"
            + "  emit_json\n"
            + "  return 0\n"
            + "}\n"
            + "while : ; do\n"
            + "  if read_playerctl; then\n"
            + "    :\n"
            + "  elif read_gdbus; then\n"
            + "    :\n"
            + "  else\n"
            + "    printf '{\"ok\":false,\"reason\":\"no playerctl and no MPRIS player answered\"}\\n'\n"
            + "  fi\n"
            + "  sleep \"$SLEEP\"\n"
            + "done\n"
            + "\n";
}
