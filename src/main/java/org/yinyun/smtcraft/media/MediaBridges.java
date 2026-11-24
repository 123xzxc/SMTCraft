package org.yinyun.smtcraft.media;

import java.io.File;
import java.util.Locale;
import org.yinyun.smtcraft.SmtcConfig;

/**
 * Picks the reader that matches the running operating system.
 *
 * <p>Since Java 8 has no {@code java.lang.foreign}, both platforms are read by
 * an external process that prints one JSON line per snapshot: PowerShell talks
 * to WinRT on Windows, {@code playerctl} or {@code gdbus} talks to MPRIS2 on
 * Linux. Keeping the picker in one place means the HUD, the lyric service and
 * the settings page still never learn which OS they run on.</p>
 */
public final class MediaBridges {

    private MediaBridges() {}

    /** Creates the bridge for this OS, or a stub that explains why none exists. */
    public static MediaBridge create(SmtcConfig config) {
        return create(config, null);
    }

    /**
     * 同上，但显式给出配置根，让桥脚本落在 {@code <configDir>/smtcraft/bridge}。
     *
     * @param configDir 游戏目录下的 {@code config}；null 表示没有游戏实例（探针）
     */
    public static MediaBridge create(SmtcConfig config, File configDir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // startsWith, not contains: "Darwin" has no business matching Windows (AUDIT-51-3)
        if (os.startsWith("windows")) return new ProcessBridge(config, configDir);
        if (os.contains("linux")) return new ProcessBridge(config, configDir);
        return new NullBridge("no media reader for " + System.getProperty("os.name", "this platform")
                + " (SMTC needs Windows, MPRIS needs Linux)");
    }

    /** Short platform label for logs and the settings page. */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) return "smtc";
        if (os.contains("linux")) return "mpris";
        return "none";
    }

    /**
     * Tool the bridge will actually drive, for the diagnostics row.
     *
     * <p>The settings page shows this so a missing {@code playerctl} reads as
     * "needs playerctl" instead of "no song playing".</p>
     */
    public static String tool() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) return "powershell (WinRT SMTC)";
        if (os.contains("linux")) return "playerctl / gdbus (MPRIS2)";
        return "none";
    }
}
