package org.yinyun.smtcraft.mpris;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Resolves {@code mpris:artUrl} to bytes.
 *
 * <p>MPRIS hands out a URL, not a picture: either a percent-encoded
 * {@code file://} path (Spotify, local players) or an {@code http(s)} link
 * (web players). Both are read here, capped so a hostile or broken link cannot
 * pull megabytes into the sampler: {@link #MAX_BYTES} is refused outright for a
 * local file, and an HTTP body is both length-checked up front and abandoned
 * once the cap or the read deadline is reached.</p>
 */
public final class MprisArt {

    /** Refuse anything above this; covers are tens of kilobytes in practice. */
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 1_500;
    private static final int READ_TIMEOUT_MS = 3_000;
    /** Whole-body deadline; a trickling server must not hold the sampler (AUDIT-55). */
    private static final long READ_DEADLINE_MS = 5_000L;

    private MprisArt() {}

    /** Bytes of the cover, or an empty array when the URL cannot be read. */
    public static byte[] fetch(String url) {
        if (url == null) return new byte[0];
        String s = url.trim();
        if (s.isEmpty()) return new byte[0];
        try {
            if (s.regionMatches(true, 0, "file:", 0, 5)) {
                Path path = filePath(s);
                return path == null ? new byte[0] : readFile(path);
            }
            if (s.regionMatches(true, 0, "http:", 0, 5) || s.regionMatches(true, 0, "https:", 0, 5)) {
                return readHttp(s);
            }
            // players that hand out a bare absolute path
            if (s.startsWith("/")) return readFile(Path.of(s));
        } catch (Throwable ignored) {
            // a missing cover is not an error worth a stack trace per poll
        }
        return new byte[0];
    }

    /** {@code file:///home/me/a%20b.jpg} becomes a path; null when malformed. */
    public static Path filePath(String url) {
        try {
            String rest = url.substring(5);
            if (rest.startsWith("//")) {
                // drop the authority, which is empty for every player seen so far
                rest = rest.substring(2);
                int slash = rest.indexOf('/');
                rest = slash >= 0 ? rest.substring(slash) : "/" + rest;
            }
            if (!rest.startsWith("/")) return null;
            String decoded = percentDecode(rest);
            return decoded.isBlank() ? null : Path.of(decoded);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Percent-decoding for paths: {@code URLDecoder} also turns {@code +} into a
     * space, which is legitimate in a file name and would break the lookup.
     */
    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        // escapes are collected as bytes and decoded as one run: decoding each byte
        // separately turns every multi-byte character into replacement characters,
        // which is why a cover under a Chinese path was never found (AUDIT-53)
        java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream(8);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    pending.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            if (pending.size() > 0) {
                out.append(new String(pending.toByteArray(), StandardCharsets.UTF_8));
                pending.reset();
            }
            out.append(c);
            i++;
        }
        if (pending.size() > 0) out.append(new String(pending.toByteArray(), StandardCharsets.UTF_8));
        return out.toString();
    }

    private static byte[] readFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return new byte[0];
        long size = Files.size(path);
        if (size <= 0L) return new byte[0];
        if (size > MAX_BYTES) return new byte[0];
        return Files.readAllBytes(path);
    }

    private static byte[] readHttp(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "SMTCraft/1.0 (MPRIS artwork)");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return new byte[0];
            long declared = conn.getContentLengthLong();
            if (declared > MAX_BYTES) return new byte[0];
            try (InputStream in = conn.getInputStream()) {
                return readCapped(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        long deadline = System.nanoTime() + READ_DEADLINE_MS * 1_000_000L;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > MAX_BYTES) return new byte[0];
            // a slow server otherwise resets the socket timeout on every chunk and
            // can hold the sampling thread for the full cap
            if (System.nanoTime() - deadline > 0L) return new byte[0];
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Stable short hash, the same shape the Windows path produces. */
    public static String hash(byte[] data) {
        if (data == null || data.length == 0) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            return HexFormat.of().formatHex(Arrays.copyOf(digest, 8));
        } catch (Throwable t) {
            // same 16 hex characters the SHA-1 path produces, so artworkHash does
            // not change shape when the digest is unavailable (AUDIT-51-2)
            long mixed = ((long) Arrays.hashCode(data) << 32) ^ data.length;
            return String.format("%016x", mixed);
        }
    }
}
