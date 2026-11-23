package org.yinyun.smtcraft.render;

import com.mojang.blaze3d.platform.NativeImage;
import org.yinyun.smtcraft.SmtcCraftClient;
import org.yinyun.smtcraft.util.Errors;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LRU cache of album covers turned into GPU textures. The bridge only ships a
 * new PNG when the hash changes, so this map normally holds a handful of
 * entries; old ones are released to keep VRAM flat during long sessions.
 *
 * <p>Vinyl mode needs a circular crop, which is baked into a second texture per
 * cover ({@code covers/<hash>_vinyl}) instead of being done per frame.</p>
 *
 * <p>Decoding is deliberately <b>not</b> done on the render thread: a player
 * hands out thumbnails up to a few thousand pixels wide, and decoding, cropping
 * and uploading those cost long enough to be felt as a freeze at the start of
 * every song. A single background thread turns the bytes into a small (at most
 * {@value #MAX_DECODED_SIDE} px) {@link NativeImage}; the next frame only
 * copies it into a texture. Until then the HUD draws its placeholder note.</p>
 */
public final class ArtworkCache {
    private static final Logger LOG = LoggerFactory.getLogger("smtcraft");
    private static final int MAX_ENTRIES = 24;
    /** Covers are drawn at 44 px, so keeping more than this is pure waste. */
    private static final int MAX_DECODED_SIDE = 256;
    /** Cap on decoded-but-not-yet-uploaded images, so a song switch cannot pile up. */
    private static final int MAX_BUILT = 6;
    /** Cap on the worker -> render hand-off queue; the worker drops its own image past it. */
    private static final int MAX_PENDING = 16;

    static {
        // ImageIO otherwise writes a disk cache file per decode, which fails when
        // %TEMP% is not writable (Defender). This is a process-wide switch, so it
        // is set once here instead of inside the decode path (AUDIT-34).
        ImageIO.setUseCache(false);
    }

    private static final List<ArtworkCache> LIVE = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "smtcraft-cover");
        t.setDaemon(true);
        return t;
    });

    private final LinkedHashMap<String, Identifier> byHash =
            new LinkedHashMap<>(16, 0.75f, false);

    /** Hashes that failed to decode; a set, so A/B alternating bad covers do not thrash (N8). */
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    /** Keys currently being decoded by the worker. */
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();
    /**
     * A decoded cover handed over by the worker. Only the render thread ever
     * closes one of these: the worker publishes an image once and then forgets
     * it, so "close" has exactly one owner and no image can be freed twice or
     * uploaded after being freed (AUDIT-31).
     */
    private record Done(String key, NativeImage image, int generation) { }

    /**
     * Bumped by {@link #clear()}. A decode that started before the click is from an
     * older generation and gets thrown away, otherwise the cover the player just
     * cleared reappears on the next frame (AUDIT-45).
     */
    private volatile int generation;

    /** Worker output waiting to be collected; appended by the worker, drained by the render thread. */
    private final ConcurrentLinkedQueue<Done> handoff = new ConcurrentLinkedQueue<>();
    /** Decoded images waiting for an upload. Render thread only, hence the plain map (AUDIT-31). */
    private final LinkedHashMap<String, NativeImage> decoded = new LinkedHashMap<>(8, 0.75f, false);
    /** Textures to release one call later, so the frame that still holds them finishes (N9). */
    private final List<Identifier> pendingRelease = new ArrayList<>();

    public ArtworkCache() {
        LIVE.add(this);
    }

    /** Release every cached cover texture; used by the "clear cache" button. */
    public static int clearAll() {
        int released = 0;
        for (ArtworkCache cache : LIVE) {
            released += cache.byHash.size();
            cache.clear();
        }
        return released;
    }

    public Identifier texture(String hash, byte[] png) {
        return texture(hash, png, false);
    }

    /**
     * A square cover with rounded corners. The radius is baked into the pixels, so a
     * change of the slider is a different cache key rather than a redraw every frame.
     *
     * @param radiusDesign corner radius in HUD pixels, 0 for a plain square
     * @param drawnSize    the size the cover is drawn at, needed to scale the radius
     *                     from HUD pixels to texels
     */
    public Identifier textureRounded(String hash, byte[] png, int radiusDesign, int drawnSize) {
        return texture(hash, png, false, radiusDesign, drawnSize);
    }

    /**
     * @param circular crop the image to a disc (vinyl mode, spinning cover)
     */
    public Identifier texture(String hash, byte[] png, boolean circular) {
        return texture(hash, png, circular, 0, 0);
    }

    public Identifier texture(String hash, byte[] png, boolean circular, int radiusDesign,
                              int drawnSize) {
        if (hash == null || hash.isEmpty() || png == null || png.length == 0) {
            return null;
        }
        String key = circular ? hash + "@disc" : radiusDesign > 0 && drawnSize > 0 ? hash + "@r" + radiusDesign : hash;
        // Evicted textures are released every frame, not only when a lookup misses:
        // a song that keeps hitting the cache used to hold them until the next song
        // change (AUDIT-46).
        drainPending();
        Identifier cached = byHash.get(key);
        if (cached != null) {
            byHash.remove(key);
            byHash.put(key, cached);
            return cached;
        }
        drainDone();
        NativeImage ready = decoded.remove(key);
        if (ready != null) {
            try {
                Identifier id = upload(key, hash, ready, circular, radiusDesign);
                byHash.put(key, id);
                evict();
                return id;
            } catch (Throwable t) {
                // the image left the map above and never reached a texture, so this
                // is the only owner left; without the close it leaks native memory (AUDIT-42)
                ready.close();
                fail(key, t);
                return null;
            }
        }
        if (failed.contains(key)) {
            return null;
        }
        if (inflight.add(key)) {
            // the bridge reuses its buffer for the next sample, so hand the worker a copy
            byte[] copy = png.clone();
            final int gen = generation;
            try {
                DECODER.execute(() -> {
                    try {
                        NativeImage image = decode(copy);
                        if (circular) {
                            disc(image);
                        } else if (radiusDesign > 0 && drawnSize > 0) {
                            int side = Math.min(image.getWidth(), image.getHeight());
                            roundCorners(image, Math.round(radiusDesign * (float) side / drawnSize));
                        }
                        if (handoff.size() >= MAX_PENDING) {
                            // nobody is consuming covers right now (no frame drew the
                            // HUD); this image was never published, so the worker is
                            // still allowed to close it itself
                            image.close();
                            LOG.debug("dropping a decoded cover for {}, hand-off queue is full", key);
                            return;
                        }
                        handoff.add(new Done(key, image, gen));
                    } catch (Throwable t) {
                        fail(key, t);
                    } finally {
                        inflight.remove(key);
                    }
                });
            } catch (Throwable rejected) {
                inflight.remove(key);
                fail(key, rejected);
            }
        }
        return null;
    }

    private void fail(String key, Throwable t) {
        LOG.warn("cannot build a cover texture for {}: {}", key, Errors.describe(t));
        if (failed.size() > 64) failed.clear();
        failed.add(key);
    }

    /** Render thread only: hand the finished image to the texture manager. */
    private static Identifier upload(String key, String hash, NativeImage image, boolean circular,
                                     int radiusDesign) {
        Identifier id = Identifier.fromNamespaceAndPath(SmtcCraftClient.MOD_ID,
                "covers/" + sanitize(hash)
                        + (circular ? "_vinyl" : radiusDesign > 0 ? "_r" + radiusDesign : ""));
        if (!CoverTexture.register(id, image, "smtcraft cover " + hash)) {
            // the image never reached the GPU, so the caller still owns it and closes it
            throw new IllegalStateException("cannot publish the cover texture: " + CoverTexture.lastFailure());
        }
        LOG.debug("cover texture {} registered ({}x{}{}) through {}",
                id, image.getWidth(), image.getHeight(), circular ? ", disc" : "", CoverTexture.mode());
        return id;
    }

    /**
     * Turn the raw thumbnail bytes into a small {@link NativeImage}. Windows apps
     * mostly hand out PNG, which the built-in reader takes; everything else goes
     * through ImageIO. Both paths end at most {@value #MAX_DECODED_SIDE} px wide,
     * which is all a 44 px cover can show.
     */
    private static NativeImage decode(byte[] data) throws IOException {
        // Ask the decoder for a subsampled read first: a 4K cover then never exists
        // as a full-size bitmap in the heap (33 MB per song switch, R3-5).
        NativeImage sampled = decodeSubsampled(data);
        if (sampled != null) return sampled;
        try {
            return shrink(NativeImage.read(data));
        } catch (Throwable pngFailed) {
            throw new IOException("not a decodable image", pngFailed);
        }
    }

    /**
     * Reads the header, picks a step that lands at or under {@value #MAX_DECODED_SIDE}
     * px and lets the reader skip the rest of the pixels.
     *
     * @return {@code null} when no ImageIO reader understands the bytes
     */
    private static NativeImage decodeSubsampled(byte[] data) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (stream == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int sw = reader.getWidth(0);
                int sh = reader.getHeight(0);
                if (sw <= 0 || sh <= 0) return null;
                int step = Math.max(1, (int) Math.ceil((double) Math.max(sw, sh) / MAX_DECODED_SIDE));
                ImageReadParam param = reader.getDefaultReadParam();
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage src = reader.read(0, param);
                if (src == null) return null;
                int w = src.getWidth();
                int h = src.getHeight();
                if (w <= 0 || h <= 0) return null;
                int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
                NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
                for (int y = 0; y < h; y++) {
                    int row = y * w;
                    for (int x = 0; x < w; x++) {
                        image.setPixelABGR(x, y, abgr(pixels[row + x]));
                    }
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (Throwable unsupported) {
            return null;
        }
    }

    /** BufferedImage hands out 0xAARRGGBB; the native image stores 0xAABBGGRR. */
    private static int abgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >>> 16) & 0xFF);
    }

    /** Nearest-neighbour downscale of an oversized cover; the original is closed. */
    private static NativeImage shrink(NativeImage full) {
        int w = full.getWidth();
        int h = full.getHeight();
        int longest = Math.max(w, h);
        if (longest <= MAX_DECODED_SIDE) return full;
        float scale = (float) MAX_DECODED_SIDE / longest;
        int tw = Math.max(1, Math.round(w * scale));
        int th = Math.max(1, Math.round(h * scale));
        NativeImage small = new NativeImage(NativeImage.Format.RGBA, tw, th, false);
        for (int y = 0; y < th; y++) {
            int sy = Math.min(h - 1, (int) (y / scale));
            for (int x = 0; x < tw; x++) {
                small.setPixelABGR(x, y, full.getPixel(Math.min(w - 1, (int) (x / scale)), sy));
            }
        }
        full.close();
        return small;
    }

    /**
     * Clear the four corners of a square cover. Radius is in texels and clamped to half the
     * short side; the outermost ring fades out over one texel so the edge does not stair-step
     * when 44 HUD pixels are stretched over a 256 px thumbnail.
     */
    private static void roundCorners(NativeImage image, int radius) {
        int w = image.getWidth();
        int h = image.getHeight();
        int r = Math.max(0, Math.min(Math.min(w, h) / 2, radius));
        if (r == 0) return;
        float inner = r - 1.0f;
        for (int y = 0; y < h; y++) {
            boolean top = y < r;
            boolean bottom = y >= h - r;
            if (!top && !bottom) continue;
            float cy = top ? r - 0.5f : h - r + 0.5f;
            for (int x = 0; x < w; x++) {
                boolean left = x < r;
                boolean right = x >= w - r;
                if (!left && !right) continue;
                float cx = left ? r - 0.5f : w - r + 0.5f;
                float dx = x + 0.5f - cx;
                float dy = y + 0.5f - cy;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d <= inner) continue;
                float alpha = d >= r ? 0.0f : (r - d);
                int packed = image.getPixel(x, y);
                image.setPixelABGR(x, y, (Math.round(255.0f * alpha) << 24) | (packed & 0xFFFFFF));
            }
        }
    }

    /** Clip the image to a circle with a one-pixel antialiased rim. */
    private static void disc(NativeImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float outer = Math.min(cx, cy) - 0.5f;
        float inner = outer - 1.0f;
        for (int y = 0; y < h; y++) {
            float dy = y + 0.5f - cy;
            for (int x = 0; x < w; x++) {
                float dx = x + 0.5f - cx;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d <= inner) continue;
                float alpha = d >= outer ? 0.0f : (outer - d);
                int packed = image.getPixel(x, y);
                image.setPixelABGR(x, y, (Math.round(255.0f * alpha) << 24) | (packed & 0xFFFFFF));
            }
        }
    }

    private static String sanitize(String hash) {
        StringBuilder sb = new StringBuilder(hash.length());
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }

    private void evict() {
        Iterator<Map.Entry<String, Identifier>> it = byHash.entrySet().iterator();
        while (byHash.size() > MAX_ENTRIES && it.hasNext()) {
            Map.Entry<String, Identifier> eldest = it.next();
            // the HUD may still be holding this identifier from the current frame
            pendingRelease.add(eldest.getValue());
            it.remove();
        }
    }

    /**
     * Render thread only: collect what the worker finished, closing anything that
     * lost its turn (a newer song, or more images than {@link #MAX_BUILT}).
     */
    private void drainDone() {
        boolean dropped = false;
        Done item;
        while ((item = handoff.poll()) != null) {
            if (item.generation() != generation) {
                // decoded for a cache that has since been cleared (AUDIT-45)
                item.image().close();
                dropped = true;
                continue;
            }
            NativeImage previous = decoded.put(item.key(), item.image());
            if (previous != null) {
                // two decodes for the same key raced; keep the newer one and close
                // the loser, which nothing else references any more (AUDIT-43)
                previous.close();
                dropped = true;
            }
        }
        Iterator<Map.Entry<String, NativeImage>> it = decoded.entrySet().iterator();
        while (decoded.size() > MAX_BUILT && it.hasNext()) {
            it.next().getValue().close();
            it.remove();
            dropped = true;
        }
        if (dropped) LOG.debug("dropped decoded cover(s) nobody asked for");
    }

    /** Release textures evicted on an earlier frame, when nothing can reference them. */
    private void drainPending() {
        if (pendingRelease.isEmpty()) return;
        for (Identifier id : pendingRelease) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
        pendingRelease.clear();
    }

    public void clear() {
        // invalidate decodes that are running right now (AUDIT-45)
        generation++;
        for (Identifier id : byHash.values()) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
        byHash.clear();
        pendingRelease.clear();
        failed.clear();
        // both the collected images and anything still in flight belong to this
        // cache, and this runs on the client thread, which also drains the queue
        for (NativeImage image : decoded.values()) image.close();
        decoded.clear();
        Done pending;
        while ((pending = handoff.poll()) != null) pending.image().close();
    }
}
