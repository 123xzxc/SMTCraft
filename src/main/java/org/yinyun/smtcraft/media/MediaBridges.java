package org.yinyun.smtcraft.media;

import java.util.Locale;
import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.mpris.MprisBridge;
import org.yinyun.smtcraft.smtc.SmtcBridge;

/** Picks the reader that matches the running operating system. */
public final class MediaBridges {

    private MediaBridges() {}

    public static MediaBridge create(SmtcConfig config) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // startsWith, not contains: "Darwin" has no business matching Windows (AUDIT-51-3)
        if (os.startsWith("windows")) return new SmtcBridge(config);
        if (os.contains("linux")) return new MprisBridge(config);
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
}
