package org.yinyun.smtcraft.audio;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.settings.GameSettings;
import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.smtc.TrackInfo;

/**
 * 系统里有歌在放的时候把游戏自带音乐压到 0，放完了再把玩家原来的音量写回去。
 *
 * <p>只碰“音乐”这一路音量：唱片机、方块的音效和 UI 音量都不动。1.8.9 没有 Fabric 版
 * 那个 {@code OptionInstance} 抽象——音乐音量走 {@code GameSettings} 的
 * {@link GameSettings#getSoundLevel(SoundCategory)} / {@code setSoundLevel}，键是
 * {@link SoundCategory#MUSIC}；声音设置界面的滑条读的也是同一路值，所以写完立刻同步。</p>
 */
public final class MusicMuter {

    private static final Logger LOG = Logger.getLogger("smtcraft");
    private boolean muted;
    /** 进入静音时抓下来的音量，原样写回（包括 0）。 */
    private double saved = 1.0d;
    /** 直到第一次真的抓到过值才为真，release() 因此永远不会写一个猜出来的音量。 */
    private boolean captured;
    /** 最近一次 tick 拿到的配置，release() 用它清掉崩溃恢复标记。 */
    private SmtcConfig lastConfig;

    /**
     * 保持“在播”多久才静音、保持安静多久才恢复。SMTC 播放器在缓冲、切歌、拖进度时会
     * 抖几下 paused/stopped，逐帧跟着变会把音量一秒开关好几次，听起来像卡顿。
     */
    private static final long MUTE_DELAY_MS = 400L;
    private static final long RELEASE_DELAY_MS = 1_500L;
    /** 音量被别人改回去时，最多这么频繁地重新压一次。 */
    private static final long REASSERT_GAP_MS = 1_000L;

    private boolean previousWant;
    private long wantChangedAt;
    private boolean seeded;
    private long lastWriteAt;

    public boolean muted() {
        return muted;
    }

    /** 每个客户端 tick 调一次；没有变化时只做两次字段比较。 */
    public void tick(SmtcConfig cfg, TrackInfo track) {
        GameSettings settings = settings();
        if (settings == null) return;
        lastConfig = cfg;
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
        float present = music(settings);

        if (want && !muted) {
            if (now - wantChangedAt < MUTE_DELAY_MS) return;
            if (present <= 0.0f) {
                // 玩家自己就把音乐关了：没有需要恢复的值，也不该把 0 记成“欠着”
                muted = true;
                captured = false;
                saved = 0.0d;
                return;
            }
            saved = present;
            captured = true;
            muted = true;
            write(settings, 0.0f, now);
            remember(cfg, saved);
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("muting vanilla music (was " + saved + ")");
            }
        } else if (want) {
            // 玩家可能在我们压着的时候动了滑条，或者别的东西把音量放回来了：
            // 记住新值、再压一次，但不逐 tick 硬抢
            if (present > 0.0f && now - lastWriteAt >= REASSERT_GAP_MS) {
                saved = present;
                captured = true;
                write(settings, 0.0f, now);
            }
        } else if (muted) {
            if (now - wantChangedAt < RELEASE_DELAY_MS) return;
            muted = false;
            remember(cfg, -1.0d);
            // 存的这个值就是玩家的选择，包括 0；写死 1.0 会把“故意关掉音乐”的人
            // 在歌放完的瞬间变成满音量
            if (captured) write(settings, (float) saved, now);
            captured = false;
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("restoring vanilla music to " + saved);
            }
        }
    }

    /** 只在真的变化时写，并记下时间，避免两个方向的互相拉扯。 */
    private void write(GameSettings settings, float value, long now) {
        float present = music(settings);
        if (Math.abs(present - value) < 1.0e-4f) return;
        settings.setSoundLevel(SoundCategory.MUSIC, clamp(value));
        lastWriteAt = now;
    }

    /** 音乐音量：1.8.9 把各项音量放在按 {@link SoundCategory} 索引的映射里，不是字段。 */
    private static float music(GameSettings settings) {
        return settings.getSoundLevel(SoundCategory.MUSIC);
    }

    private static float clamp(float value) {
        if (value < 0.0f) return 0.0f;
        return Math.min(value, 1.0f);
    }

    /** 1.8.9 只有一个全局设置对象，取不到就别动音量。 */
    private static GameSettings settings() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null ? null : mc.gameSettings;
    }

    /**
     * 把我们压着的音量写进配置，这样在静音中途崩溃也能在下次启动时修回来（N11）。
     */
    private static void remember(SmtcConfig cfg, double value) {
        if (cfg == null) return;
        cfg.mutedVolume = value;
        cfg.save();
    }

    /** 撤销一次因为游戏死在歌里而没来得及恢复的静音，客户端启动时调一次。 */
    public void restorePending(SmtcConfig cfg) {
        if (cfg == null || cfg.mutedVolume < 0.0d) return;
        GameSettings settings = settings();
        if (settings != null) {
            settings.setSoundLevel(SoundCategory.MUSIC, clamp((float) cfg.mutedVolume));
            settings.saveOptions();
            LOG.info("restored the music volume to " + cfg.mutedVolume + " after an unclean shutdown");
        }
        cfg.mutedVolume = -1.0d;
        cfg.save();
    }

    /** 关服/退游戏时把音量放回去，别让一个没撤掉的静音写进 options.txt。 */
    public void release() {
        if (!muted) return;
        muted = false;
        GameSettings settings = settings();
        if (captured && settings != null) {
            settings.setSoundLevel(SoundCategory.MUSIC, clamp((float) saved));
            // 关停时选项可能还没落盘：显式存一次，否则下一个会话还是 0
            settings.saveOptions();
        }
        captured = false;
        // 待恢复标记只为崩溃准备，正常退出就该清掉
        if (lastConfig != null) remember(lastConfig, -1.0d);
    }
}
