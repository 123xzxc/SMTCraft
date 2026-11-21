package org.yinyun.smtcraft.media;

import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.smtc.TrackInfo;

/**
 * Reader for platforms the mod knows nothing about (macOS, BSD, a bare JVM in a
 * unit test). It keeps every caller alive and explains itself in the settings
 * page instead of leaving the HUD stuck on "reading".
 */
public final class NullBridge implements MediaBridge {

    private final String reason;
    private volatile SmtcConfig config;

    public NullBridge(String reason) {
        this.reason = reason == null || reason.isBlank() ? "no media reader on this platform" : reason;
    }

    @Override public void start() {}
    @Override public void setConfig(SmtcConfig config) { this.config = config; }
    public SmtcConfig config() { return config; }
    @Override public void reconfigure() {}
    @Override public TrackInfo latest() { return TrackInfo.EMPTY; }
    @Override public byte[] artwork() { return new byte[0]; }
    @Override public String artworkHash() { return ""; }
    @Override public boolean running() { return false; }
    @Override public String lastError() { return reason; }
    @Override public long sampleCount() { return 0L; }
    @Override public long millisSinceSample() { return -1L; }
    @Override public void close() {}

    @Override public void close(long budgetMs) {}
}
