package org.yinyun.smtcraft.render;

/**
 * 歌词带的播放位置，挡住短暂回退：同一首歌回退不到 {@link #SEEK_BACK_MS} 时先挂
 * {@link #HOLD_MS}，多半是缓冲或会话重建造成的一帧抖动；更大的回退是玩家拖进度条，立刻跟随。
 */
public final class LyricClock {
    /** 回退到这个量级就算拖动，不是抖动。 */
    public static final long SEEK_BACK_MS = 1200L;
    /** 更小的回退先挂这么久再接受。 */
    public static final long HOLD_MS = 400L;

    private String key = "";
    private long shown = Long.MIN_VALUE;
    private long heldSince = 0L;

    /** 这一帧能画的位置：只前进，换歌就丢掉守卫。 */
    public long advance(long raw, String trackKey, long now) {
        String k = trackKey == null ? "" : trackKey;
        if (shown == Long.MIN_VALUE || !k.equals(key)) {
            key = k;
            shown = raw;
            heldSince = 0L;
            return raw;
        }
        if (raw >= shown) {
            shown = raw;
            heldSince = 0L;
            return raw;
        }
        if (shown - raw >= SEEK_BACK_MS) {
            shown = raw;
            heldSince = 0L;
            return raw;
        }
        if (heldSince == 0L) heldSince = now;
        if (now - heldSince < HOLD_MS) return shown;
        // 数据源没回来，这就是小拖动或改了偏移
        shown = raw;
        heldSince = 0L;
        return raw;
    }

    /** 丢掉守卫，下一帧从原始值开始。 */
    public void reset() {
        key = "";
        shown = Long.MIN_VALUE;
        heldSince = 0L;
    }
}
