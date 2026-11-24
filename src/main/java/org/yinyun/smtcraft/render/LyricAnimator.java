package org.yinyun.smtcraft.render;

/**
 * 歌词带的连续纵向滚动：顶端行是一个浮点行号，缓动到该在屏幕上的行；
 * 每行都从这一个偏移摆位置，换行就是一行的滚动而不是跳变（AUDIT-26-5）。
 */
public final class LyricAnimator {
    /** 一行交接花的时间，秒。 */
    private static final float SECONDS = 0.30f;

    private float scroll;
    private float from;
    private float progress = 1.0f;
    private int target = Integer.MIN_VALUE;

    /** 推进滚动并返回当前顶端行；跳超过一行（拖进度、换歌）直接落位。 */
    public float update(float dt, int base) {
        if (target == Integer.MIN_VALUE) {
            snap(base);
            return scroll;
        }
        if (base != target) {
            if (Math.abs(base - scroll) > 1.6f) {
                snap(base);
                return scroll;
            }
            from = scroll;
            target = base;
            progress = 0.0f;
        }
        if (progress < 1.0f) {
            progress = Math.min(1.0f, progress + (dt <= 0.0f ? 0.05f : dt / SECONDS));
            scroll = from + (target - from) * LyricsRenderer.smooth(progress);
        }
        return scroll;
    }

    /** 交接还在进行。 */
    public boolean moving() {
        return progress < 1.0f;
    }

    /** 当前交接的缓动进度；没在动时是 1。 */
    public float eased() {
        return progress >= 1.0f ? 1.0f : LyricsRenderer.smooth(progress);
    }

    /** 立刻落位：面板隐藏时滚动钟没走，恢复时不该从半路继续。 */
    public void finish() {
        if (target == Integer.MIN_VALUE) return;
        scroll = target;
        from = target;
        progress = 1.0f;
    }

    /** 不做动画直接跳到 {@code base}。 */
    public void snap(int base) {
        target = base;
        scroll = base;
        from = base;
        progress = 1.0f;
    }
}
