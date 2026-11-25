package org.yinyun.smtcraft.smtc;

import org.yinyun.smtcraft.SmtcConfig;

/**
 * Decides whether a config handed to a media bridge has to cut the sampling
 * interval short. Both {@code SmtcBridge} and {@code MprisBridge} ask here, so
 * the answer has to be about the fields the samplers read, nothing else.
 *
 * <p>The client passes its config object on every tick. Waking the worker for
 * each of those turned the 150 ms sampler into a 20 Hz one, and the WinRT churn
 * of that (three async calls per round) is audible in the game audio. Only a
 * value the sampler actually reads may justify an immediate round.</p>
 */
public final class SamplerGate {
    private SamplerGate() {
    }

    public static boolean readsDifferently(SmtcConfig a, SmtcConfig b) {
        if (a == null || b == null) return true;
        return a.showArtwork != b.showArtwork
                || a.pollIntervalMs != b.pollIntervalMs
                || a.ignoreBrowserSessions != b.ignoreBrowserSessions
                || a.progressSmooth != b.progressSmooth
                || a.swapGraceMs != b.swapGraceMs;
    }
}
