package org.yinyun.smtcraft.smtc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.yinyun.smtcraft.util.Budgets;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * NetEase Cloud Music client: keyword search through the plain web API and
 * lyrics through the <b>eapi</b> endpoint the mobile client uses.
 *
 * <p>The old {@code /api/song/lyric} path only answers with line-level LRC and,
 * depending on the song, not even that — it is kept as a fallback. The eapi
 * endpoint asks with an encrypted {@code params} blob and returns {@code lrc},
 * {@code tlyric}, {@code romalrc} and — the part that matters for karaoke —
 * {@code yrc}, the per-syllable sheet.</p>
 *
 * <p>Scheme (same as the desktop/mobile client, documented by Lyricify):</p>
 * <pre>
 *   digest = md5("nobody" + path + "use" + json + "md5forencrypt")
 *   plain  = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest
 *   params = hex(AES-128-ECB(plain, "e82ckenh8dichen8"))
 * </pre>
 */
public final class NetEaseClient {
    private static final String UA = "Mozilla/5.0 (Linux; Android 9; PCT-AL10) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/70.0.3538.64 HuaweiBrowser/10.0.3.311 Mobile Safari/537.36";
    private static final String EAPI_HOST = "https://interface3.music.163.com";
    private static final String EAPI_KEY = "e82ckenh8dichen8";
    private static final String EAPI_SEPARATOR = "-36cd479b6b5-";

    // 1.8.9 的目标运行时是 Java 8：java.net.http.HttpClient（Java 11）不存在，改用
    // HttpURLConnection；超时照抄原文件的 connectTimeout(6s) 与 timeout(10s)
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /** One search hit. */
    public static final class Song {
        private final long id;
        private final String name;
        private final String artists;
        private final String album;
        private final long durationMs;

        public Song(long id, String name, String artists, String album, long durationMs) {
            this.id = id;
            this.name = name;
            this.artists = artists;
            this.album = album;
            this.durationMs = durationMs;
        }

        public long id() { return id; }
        public String name() { return name; }
        public String artists() { return artists; }
        public String album() { return album; }
        public long durationMs() { return durationMs; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Song)) return false;
            Song that = (Song) other;
            return id == that.id && durationMs == that.durationMs && Objects.equals(name, that.name)
                    && Objects.equals(artists, that.artists) && Objects.equals(album, that.album);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, artists, album, durationMs);
        }

        @Override
        public String toString() {
            return "Song[id=" + id + ", name=" + name + ", artists=" + artists + ", album=" + album
                    + ", durationMs=" + durationMs + "]";
        }
    }

    /** One song's sheets: {@code yrc} carries per-syllable timings, {@code lrc} is the line fallback. */
    public static final class Lyrics {
        private final long id;
        private final String lrc;
        private final String translation;
        private final String yrc;

        public Lyrics(long id, String lrc, String translation, String yrc) {
            this.id = id;
            this.lrc = lrc;
            this.translation = translation;
            this.yrc = yrc;
        }

        public long id() { return id; }
        public String lrc() { return lrc; }
        public String translation() { return translation; }
        public String yrc() { return yrc; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Lyrics)) return false;
            Lyrics that = (Lyrics) other;
            return id == that.id && Objects.equals(lrc, that.lrc)
                    && Objects.equals(translation, that.translation) && Objects.equals(yrc, that.yrc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, lrc, translation, yrc);
        }

        @Override
        public String toString() {
            return "Lyrics[id=" + id + ", lrc=" + lrc.length() + " chars, translation="
                    + translation + ", yrc=" + yrc + "]";
        }
    }

    public List<Song> search(String keyword, int limit) throws IOException {
        int size = Math.max(1, limit);
        String query = enc(keyword);
        JsonObject root;
        try {
            // the pc cloud search API; /api/search/get/web (the mobile web variant) is
            // retired and answers {"code":405,"message":"该视频不存在或已删除"}
            root = get("https://music.163.com/api/cloudsearch/pc?s=" + query
                    + "&type=1&limit=" + size + "&offset=0");
        } catch (IOException cloudSearchFailed) {
            root = get("https://music.163.com/api/search/get?type=1&s=" + query
                    + "&limit=" + size + "&offset=0");
        }
        List<Song> out = new ArrayList<Song>();
        JsonObject result = obj(root, "result");
        if (result == null) return out;
        JsonArray songs = arr(result, "songs");
        if (songs == null) return out;
        for (JsonElement el : songs) {
            if (!el.isJsonObject()) continue;
            JsonObject s = el.getAsJsonObject();
            long id = asLong(s, "id");
            if (id <= 0L) continue;
            // two schemas: /api/search/get uses artists/album/duration, the newer
            // /api/cloudsearch/pc uses ar/al/dt
            JsonArray artistsJson = arr(s, "artists");
            if (artistsJson == null) artistsJson = arr(s, "ar");
            StringBuilder artists = new StringBuilder();
            if (artistsJson != null) {
                for (JsonElement a : artistsJson) {
                    if (!a.isJsonObject()) continue;
                    String name = asString(a.getAsJsonObject(), "name");
                    if (name.isEmpty()) continue;
                    if (artists.length() > 0) artists.append('/');
                    artists.append(name);
                }
            }
            JsonObject album = obj(s, "album");
            if (album == null) album = obj(s, "al");
            long duration = asLong(s, "duration");
            if (duration <= 0L) duration = asLong(s, "dt");
            out.add(new Song(id, asString(s, "name"), artists.toString(),
                    album == null ? "" : asString(album, "name"), duration));
        }
        return out;
    }

    /** Lyrics of one song: syllable sheet from eapi, line-level API as a fallback. */
    public Lyrics lyrics(long id) throws IOException {
        Lyrics modern = null;
        try {
            modern = lyricsEapi(id);
        } catch (Exception e) {
            // network hiccup or a changed blob format: the plain endpoint may still answer
        }
        if (modern != null && (!modern.lrc().isEmpty() || !modern.yrc().isEmpty())) return modern;
        return lyricsPlain(id);
    }

    private Lyrics lyricsEapi(long id) throws Exception {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("id", Long.toString(id));
        data.put("cp", "false");
        data.put("lv", "-1");
        data.put("kv", "-1");
        data.put("tv", "-1");
        data.put("rv", "-1");
        data.put("yv", "-1");
        data.put("ytv", "-1");
        data.put("yrv", "-1");
        data.put("csrf_token", "");
        String cookie = eapiHeader();
        data.put("header", cookie);
        String json = new Gson().toJson(data);

        // the digest is computed over the weapi path; only the request target switches
        // to /eapi/, which is what the client derives with a \w*api replacement
        String params = eapiParams("/api/song/lyric/v1", json);
        String body = "params=" + enc(params) + "&header=" + enc(cookie);
        HttpURLConnection connection = open(EAPI_HOST + "/eapi/song/lyric/v1", "POST");
        connection.setRequestProperty("Referer", "https://music.163.com/");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("Cookie", eapiCookie());
        JsonObject root = send(connection, body);
        String lrc = toLrc(nested(root, "lrc"));
        String yrc = nested(root, "yrc");
        String trans = nested(root, "tlyric");
        if (trans.isEmpty()) trans = nested(root, "ytlyric");
        if (lrc.isEmpty() && yrc.isEmpty()) {
            throw new IOException("empty eapi sheet");
        }
        return new Lyrics(id, lrc, trans, yrc);
    }

    private Lyrics lyricsPlain(long id) throws IOException {
        String url = "https://music.163.com/api/song/lyric?os=pc&id=" + id
                + "&lv=-1&kv=-1&tv=-1&yv=-1&rv=-1";
        JsonObject root = get(url);
        return new Lyrics(id, toLrc(nested(root, "lrc")), nested(root, "tlyric"),
                nested(root, "yrc"));
    }

    /**
     * The mobile client serves lyrics in a mixed dialect: ordinary LRC lines next to
     * JSON lines such as {@code {"t":1370,"c":[{"tx":"夜"},{"tx":"空"}]}}. The JSON
     * form is the only place the line's own timestamp lives, so every such line is
     * rewritten into a plain {@code [mm:ss.mmm]text} line before parsing.
     */
    static String toLrc(String lyric) {
        if (lyric == null || lyric.isEmpty()) return "";
        StringBuilder out = new StringBuilder(lyric.length() + 64);
        for (String raw : lyric.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.charAt(0) != '{') {
                out.append(line).append('\n');
                continue;
            }
            try {
                // JsonParser.parseString is Gson 2.8.6+; 1.8.9 ships Gson 2.2.4
                JsonObject o = new JsonParser().parse(line).getAsJsonObject();
                long start = asLong(o, "t");
                StringBuilder text = new StringBuilder();
                JsonArray chunks = arr(o, "c");
                if (chunks != null) {
                    for (JsonElement c : chunks) {
                        if (c.isJsonObject()) text.append(asString(c.getAsJsonObject(), "tx"));
                    }
                }
                if (text.length() == 0) continue;
                out.append(stamp(start)).append(text).append('\n');
            } catch (RuntimeException e) {
                // not a lyric line after all; leave it to the parser to ignore
            }
        }
        return out.toString();
    }

    private static String stamp(long ms) {
        long total = Math.max(0L, ms);
        long minutes = total / 60_000L;
        long seconds = (total / 1000L) % 60L;
        long millis = total % 1000L;
        return String.format("[%02d:%02d.%03d]", minutes, seconds, millis);
    }

    /** The {@code header} the mobile client sends along with the encrypted blob. */
    private static String eapiHeader() {
        long seconds = System.currentTimeMillis() / 1000L;
        long millis = System.currentTimeMillis();
        Map<String, String> header = new LinkedHashMap<String, String>();
        header.put("__csrf", "");
        header.put("appver", "8.0.0");
        header.put("buildver", Long.toString(seconds));
        header.put("channel", "");
        header.put("deviceId", "");
        header.put("mobilename", "");
        header.put("resolution", "1920x1080");
        header.put("os", "android");
        header.put("osver", "");
        header.put("requestId", millis + "_0000");
        header.put("versioncode", "140");
        header.put("MUSIC_U", "");
        return new Gson().toJson(header);
    }

    private static String eapiCookie() {
        long seconds = System.currentTimeMillis() / 1000L;
        long millis = System.currentTimeMillis();
        return "__csrf=; appver=8.0.0; buildver=" + seconds + "; channel=; deviceId=; mobilename=;"
                + " resolution=1920x1080; os=android; osver=; requestId=" + millis + "_0000;"
                + " versioncode=140; MUSIC_U=";
    }

    static String eapiParams(String path, String json) throws Exception {
        String digest = md5Hex("nobody" + path + "use" + json + "md5forencrypt");
        String plain = path + EAPI_SEPARATOR + json + EAPI_SEPARATOR + digest;
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(EAPI_KEY.getBytes(StandardCharsets.US_ASCII), "AES"));
        byte[] out = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (byte b : out) sb.append(Character.toUpperCase(Character.forDigit((b >>> 4) & 0xF, 16)))
                .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
        return sb.toString();
    }

    private static String md5Hex(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(Character.forDigit((b >>> 4) & 0xF, 16))
                .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    /** {@code {"lyric": "..."}} inside an optional object. */
    private static String nested(JsonObject root, String key) {
        JsonObject o = obj(root, key);
        return o == null ? "" : asString(o, "lyric");
    }

    private JsonObject get(String url) throws IOException {
        HttpURLConnection connection = open(url, "GET");
        connection.setRequestProperty("Referer", "https://music.163.com/");
        connection.setRequestProperty("Cookie", "appver=2.9.7; os=pc");
        return send(connection, null);
    }

    /** Sends the request (with {@code body} when it is a POST) and parses the JSON answer. */
    private JsonObject send(HttpURLConnection connection, String body) throws IOException {
        try {
            if (body != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status);
            JsonElement parsed = new JsonParser().parse(readText(connection));
            if (!parsed.isJsonObject()) throw new IOException("unexpected response");
            return parsed.getAsJsonObject();
        } finally {
            connection.disconnect();
        }
    }

    /** The request the Java 11 client built: HTTP/1.1, 6 s connect, 10 s round, redirects on. */
    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        return connection;
    }

    /** Response body as UTF-8 text, exactly what BodyHandlers.ofString(UTF_8) produced. */
    private static String readText(HttpURLConnection connection) throws IOException {
        InputStream raw = connection.getInputStream();
        String encoding = connection.getContentEncoding();
        InputStream in = encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")
                ? new GZIPInputStream(raw) : raw;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int read;
            // InputStream.readAllBytes is Java 9; the loop is the Java 8 form
            while ((read = in.read(chunk)) != -1) {
                if (buffer.size() + read > Budgets.RESPONSE_BYTES_MAX) {
                    throw new IOException("response body over " + Budgets.RESPONSE_BYTES_MAX + " bytes");
                }
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // the body is already read; a failing close changes nothing
            }
            if (in != raw) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // same
                }
            }
        }
    }

    private static String enc(String s) {
        try {
            // the Charset overload of URLEncoder.encode is Java 10; the name overload is Java 1.4
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException never) {
            throw new IllegalStateException(never);
        }
    }

    private static JsonObject obj(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement el = parent.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement el = parent.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    private static String asString(JsonObject o, String key) {
        JsonElement el = o == null ? null : o.get(key);
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }

    private static long asLong(JsonObject o, String key) {
        JsonElement el = o == null ? null : o.get(key);
        if (el == null || el.isJsonNull()) return 0L;
        try {
            return el.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
