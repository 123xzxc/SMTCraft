package org.yinyun.smtcraft.audio;

import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.SmtcCraftClient;
import org.yinyun.smtcraft.smtc.TrackInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Silences vanilla background music while a SMTC track is playing, and puts the
 * player's volume back the moment playback stops.
 *
 * <p>Only {@link SoundSource#MUSIC} is touched: records, blocks and UI keep
 * their volume. The saved value is restored through the same
 * {@link OptionInstance} the sound options screen uses, so the slider in the
 * vanilla options screen stays in sync.</p>
 */
public final class MusicMuter {
    private static final Logger LOG = LoggerFactory.getLogger("smtcraft");
    private boolean muted;
    /** Volume captured when the mute began; restored unchanged, 0 included. */
    private double saved = 1.0d;
    /** False until the first mute captured a real value, so release() never writes a guess. */
    private boolean captured;

    /**
     * How long playback has to stay "playing" before the game music is silenced,
     * and how long it has to stay quiet before the volume comes back. SMTC
     * players flicker through paused/stopped while buffering, switching tracks
     * or reporting a seek; acting on every single sample flipped the music
     * volume on and off several times a second, which is audible as stuttering.
     */
    private static final long MUTE_DELAY_MS = 400L;
    private static final long RELEASE_DELAY_MS = 1_500L;
    /** A conflicting volume write is re-asserted at most this often. */
    private static final long REASSERT_GAP_MS = 1_000L;

    private boolean previousWant;
    private long wantChangedAt;
    private boolean seeded;
    private long lastWriteAt;

    public boolean muted() {
        return muted;
    }

    /** Called once per client tick; cheap when nothing changes. */
    public void tick(SmtcConfig cfg, TrackInfo track) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        OptionInstance<Double> option = mc.options.getSoundSourceOptionInstance(SoundSource.MUSIC);
        if (option == null) return;

        boolean want = cfg != null && cfg.muteGameMusic && track != null && track.hasTrack()
                && track.playing();
        long now = System.currentTimeMillis();
        if (!seeded) {
            seeded = true;
            previousWant = want;
            wantChangedAt = want ? now - MUTE_DELAY_MS : now - RELEASE_DELAY_MS;
        } else if (want != previousWant) {
            previousWant = want;
            wantChangedAt = now;
        }
        double current = option.get() == null ? 0.0d : option.get();

        if (want && !muted) {
            if (now - wantChangedAt < MUTE_DELAY_MS) return;
            if (current <= 0.0d) {
                // already silent by the player's own choice: nothing to restore later
                muted = true;
                captured = false;
                saved = 0.0d;
                return;
            }
            saved = current;
            captured = true;
            muted = true;
            write(option, 0.0d, now);
            remember(cfg, saved);
            if (LOG.isDebugEnabled()) {
                LOG.debug("muting vanilla music (was {})", saved);
            }
        } else if (want) {
            // The player may have moved the music slider while we were muted, or
            // something else put the volume back: remember the new value, silence
            // again, but never fight it tick after tick.
            if (current > 0.0d && now - lastWriteAt >= REASSERT_GAP_MS) {
                saved = current;
                captured = true;
                write(option, 0.0d, now);
            }
        } else if (muted) {
            if (now - wantChangedAt < RELEASE_DELAY_MS) return;
            muted = false;
            remember(cfg, -1.0d);
            // The saved value is the player's choice, including 0. Substituting
            // 1.0 here turned a deliberately muted music slider into full volume
            // the moment playback stopped.
            if (captured) write(option, saved, now);
            captured = false;
            if (LOG.isDebugEnabled()) {
                LOG.debug("restoring vanilla music to {}", saved);
            }
        }
    }

    /** Write only on a real change, and remember when, so nothing loops. */
    private void write(OptionInstance<Double> option, double value, long now) {
        Double present = option.get();
        if (present != null && Math.abs(present - value) < 1.0e-4d) return;
        option.set(value);
        lastWriteAt = now;
    }

    /**
     * Writes the volume we are holding into the config, so a crash while muted can
     * be repaired on the next start instead of leaving the game silent forever (N11).
     */
    private static void remember(SmtcConfig cfg, double value) {
        if (cfg == null) return;
        cfg.mutedVolume = value;
        cfg.save();
    }

    /**
     * Undo a mute that never got released because the game died mid-song. Called
     * once from client start-up.
     */
    public void restorePending(SmtcConfig cfg) {
        if (cfg == null || cfg.mutedVolume < 0.0d) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null) {
            OptionInstance<Double> option = mc.options.getSoundSourceOptionInstance(SoundSource.MUSIC);
            if (option != null) {
                option.set(cfg.mutedVolume);
                LOG.info("restored the music volume to {} after an unclean shutdown", cfg.mutedVolume);
            }
        }
        cfg.mutedVolume = -1.0d;
        cfg.save();
    }

    /** Put the volume back on shutdown, so a stale mute never reaches options.txt. */
    public void release() {
        if (!muted) return;
        muted = false;
        if (!captured) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        OptionInstance<Double> option = mc.options.getSoundSourceOptionInstance(SoundSource.MUSIC);
        if (option != null) option.set(saved);
        // the pending marker only exists to survive a crash; a clean exit clears it
        if (SmtcCraftClient.instance() != null) remember(SmtcCraftClient.instance().config(), -1.0d);
    }
}
