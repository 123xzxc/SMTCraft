package org.yinyun.smtcraft.render;

/** 滚动歌词行的横向位置：有逐字时间的行由渲染器自己跟随，没有的行由这里给目标（AUDIT-25-5）。 */
public final class LyricScroll {
    /** 卡拉OK播放头在一个滚动框里的位置，按框宽的比例。 */
    public static final float HEAD_ANCHOR = 0.68f;

    /** 把已唱边缘钉在 {@link #HEAD_ANCHOR} 处需要的偏移，钳到尾巴不出框。 */
    public static float followOffset(float sungPx, float drawnW, float maxWidth) {
        if (maxWidth <= 0.0f || drawnW <= maxWidth) return 0.0f;
        return Math.max(0.0f, Math.min(drawnW - maxWidth, sungPx - maxWidth * HEAD_ANCHOR));
    }

    private LyricScroll() {
    }
}
