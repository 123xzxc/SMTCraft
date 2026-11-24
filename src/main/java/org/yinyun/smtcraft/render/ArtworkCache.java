package org.yinyun.smtcraft.render;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 专辑封面：后台解码 PNG，渲染线程只做一次 GL 上传。
 *
 * <p>26.x 有 {@code NativeImage} 与贴图管理器，1.7.10 只有 {@code glGenTextures}。
 * 解码放在自己的线程上，因为一张 640×640 的封面解出来要十几毫秒——放在渲染线程
 * 上就是一次可见的卡顿；渲染线程每帧只问"有没有新结果"，有就上传。</p>
 *
 * <p>圆形（黑胶唱片）与圆角两种遮罩都在解码时烘进像素里，1.7.10 的固定管线没有
 * 片元着色器可用，逐像素做遮罩是唯一不会在低端显卡上塌掉的做法。</p>
 */
public final class ArtworkCache {

    /** 默认上传尺寸：99% 的封面画在 44 像素的框里，两倍留作缩放余量。 */
    private static final int TEX = 88;
    /** 上限：再大就是白烧显存，HUD 面板本身撑不到这个边长。 */
    private static final int MAX_TEX = 256;
    /** 同时留几张封面：当前曲 + 前后各一张足够拖动与切歌。 */
    private static final int MAX_ENTRIES = 6;

    private static final class Entry {
        int texture;
        boolean disc;
        int radius;
        boolean ready;
    }

    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>(8, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            if (size() <= MAX_ENTRIES) return false;
            if (eldest.getValue().texture > 0 && eldest.getValue().ready) {
                GL11.glDeleteTextures(eldest.getValue().texture);
            }
            return true;
        }
    };

    private final Executor decoder;
    /** 解码结果，等渲染线程来取。同一时刻只有一个在飞。 */
    private final AtomicReference<Pending> pending = new AtomicReference<Pending>();
    private String inflightKey = "";

    private static final class Pending {
        final String key;
        final int[] pixels;
        final int size;
        final boolean disc;
        final int radius;
        final boolean failed;

        Pending(String key, int[] pixels, int size, boolean disc, int radius, boolean failed) {
            this.key = key;
            this.pixels = pixels;
            this.size = size;
            this.disc = disc;
            this.radius = radius;
            this.failed = failed;
        }
    }

    public ArtworkCache() {
        this.decoder = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "smtcraft-artwork");
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** 方形封面，{@code radius} 为 0 表示直角，{@code size} 是上传边长。返回 0 表示还在解码。 */
    public int texture(String hash, byte[] png, int radius, int size) {
        return obtain(hash, png, false, radius, size);
    }

    /** 圆形封面（黑胶），整张图裁成圆并带透明边缘。 */
    public int disc(String hash, byte[] png) {
        return obtain(hash, png, true, 0, TEX);
    }

    private int obtain(String hash, byte[] png, boolean disc, int radius, int size) {
        if (hash == null || hash.isEmpty() || png == null || png.length == 0) return 0;
        // 边长进 key：同一张封面按不同尺寸各留一份，否则放大面板会一直拿小图
        int px = size <= 0 ? TEX : Math.max(16, Math.min(MAX_TEX, size));
        String key = hash + (disc ? "|d" : "|r" + radius) + "|" + px;
        Entry hit = entries.get(key);
        if (hit != null && hit.ready) return hit.texture;
        Entry placeholder = hit == null ? new Entry() : hit;
        placeholder.disc = disc;
        placeholder.radius = radius;
        entries.put(key, placeholder);
        if (!key.equals(inflightKey) && pending.get() == null) request(key, png, disc, radius, px);
        // 解码完成的图片由 {@link #tick()} 上传，这一帧先不画，避免在渲染线程里解码
        return hit != null && hit.ready ? hit.texture : 0;
    }

    private void request(final String key, final byte[] png, final boolean disc, final int radius,
                         final int size) {
        inflightKey = key;
        decoder.execute(new Runnable() {
            @Override public void run() {
                Pending p;
                try {
                    p = decode(key, png, disc, radius, size);
                } catch (Throwable t) {
                    p = new Pending(key, null, size, disc, radius, true);
                }
                pending.set(p);
            }
        });
    }

    /** 渲染线程每帧调用，把已解码的像素上传成 GL 纹理。 */
    public void tick() {
        Pending p = pending.getAndSet(null);
        if (p == null) return;
        inflightKey = "";
        Entry e = entries.get(p.key);
        if (e == null) {
            // 切歌太快，这张已经没人要了
            return;
        }
        if (p.failed || p.pixels == null) {
            e.ready = true;
            e.texture = 0;
            return;
        }
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        // 内存里的行是自上而下的，GL 的 v=0 在底部，所以这里从最后一行开始上传，
        // 画的时候才能按"左上角是 (0,0)"用
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(p.size * p.size * 4);
        for (int y = p.size - 1; y >= 0; y--) {
            for (int x = 0; x < p.size; x++) {
                int argb = p.pixels[y * p.size + x];
                buf.put((byte) ((argb >> 16) & 0xFF));
                buf.put((byte) ((argb >> 8) & 0xFF));
                buf.put((byte) (argb & 0xFF));
                buf.put((byte) ((argb >>> 24) & 0xFF));
            }
        }
        buf.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, p.size, p.size, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, buf);
        e.texture = id;
        e.ready = true;
    }

    private static Pending decode(String key, byte[] png, boolean disc, int radius, int size)
            throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) return new Pending(key, null, size, disc, radius, true);
        int[] px = new int[size * size];
        float cx = size / 2.0f - 0.5f;
        float cy = size / 2.0f - 0.5f;
        float r = size / 2.0f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // 最近邻取样就够：源图远大于目标尺寸，插值的收益看不见
                int sx = (int) ((x + 0.5f) / size * img.getWidth());
                int sy = (int) ((y + 0.5f) / size * img.getHeight());
                int argb = img.getRGB(Math.min(sx, img.getWidth() - 1), Math.min(sy, img.getHeight() - 1));
                int a = (argb >>> 24) & 0xFF;
                if (disc) {
                    float dx = x - cx;
                    float dy = y - cy;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    // 边缘一像素做软过渡，否则黑胶在缩放时会有一圈锯齿
                    a = (int) Math.round(a * clamp01((r - d) / 1.0f));
                } else if (radius > 0) {
                    a = (int) Math.round(a * cornerAlpha(x, y, radius, size));
                }
                px[y * size + x] = (a << 24) | (argb & 0xFFFFFF);
            }
        }
        return new Pending(key, px, size, disc, radius, false);
    }

    private static float cornerAlpha(int x, int y, int radius, int size) {
        int r = radius;
        float cx;
        float cy;
        if (x < r) cx = r - 0.5f; else if (x >= size - r) cx = size - r - 0.5f; else return 1.0f;
        if (y < r) cy = r - 0.5f; else if (y >= size - r) cy = size - r - 0.5f; else return 1.0f;
        float dx = x - cx;
        float dy = y - cy;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        return clamp01(r - d);
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    /** 删掉所有已上传的纹理（关客户端、资源重载时调用）。 */
    public void clear() {
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.texture > 0) GL11.glDeleteTextures(e.texture);
            it.remove();
        }
        inflightKey = "";
    }

    /** 供 {@code util/ShutdownPlan} 的封面步骤调用；1.7.10 不需要关闭守候线程。 */
    public void close() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) clear();
    }
}
