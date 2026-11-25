/*
 * Portions of this file are ported from Lyricify-Lyrics-Helper
 * (https://github.com/WXRIW/Lyricify-Lyrics-Helper),
 * Copyright 2023 XY Wang, WXRIW, licensed under the Apache License 2.0.
 * Modified for Java / Minecraft. See THIRD_PARTY_NOTICES.md.
 */
package org.yinyun.smtcraft.smtc;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.yinyun.smtcraft.util.Budgets;

/**
 * QQ Music provider: a keyword search over {@code musicu.fcg} and the QRC lyric
 * download endpoint.
 *
 * <p>The lyric payload arrives as hex-encoded 3DES (see {@link Crypto}); older
 * songs only have a plain LRC inside the {@code Lyric_1} field, which is used as
 * a fallback.</p>
 */
public final class QQMusicClient {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    // 1.8.9 的目标运行时是 Java 8：java.net.http.HttpClient（Java 11）不存在，改用
    // HttpURLConnection；超时照抄原文件的 connectTimeout(6s) 与 timeout(10s)
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /** One search hit; {@code id} addresses the download endpoint, {@code mid} the legacy one. */
    public static final class Song {
        private final String id;
        private final String mid;
        private final String name;
        private final String artists;
        private final long durationMs;

        public Song(String id, String mid, String name, String artists, long durationMs) {
            this.id = id;
            this.mid = mid;
            this.name = name;
            this.artists = artists;
            this.durationMs = durationMs;
        }

        public String id() { return id; }
        public String mid() { return mid; }
        public String name() { return name; }
        public String artists() { return artists; }
        public long durationMs() { return durationMs; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Song)) return false;
            Song that = (Song) other;
            return durationMs == that.durationMs && Objects.equals(id, that.id)
                    && Objects.equals(mid, that.mid) && Objects.equals(name, that.name)
                    && Objects.equals(artists, that.artists);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, mid, name, artists, durationMs);
        }

        @Override
        public String toString() {
            return "Song[id=" + id + ", mid=" + mid + ", name=" + name + ", artists=" + artists
                    + ", durationMs=" + durationMs + "]";
        }
    }

    /** {@code qrc} is syllable-synced, {@code lrc} is the plain fallback. */
    public static final class Lyrics {
        private final String qrc;
        private final String lrc;
        private final String translationQrc;

        public Lyrics(String qrc, String lrc, String translationQrc) {
            this.qrc = qrc;
            this.lrc = lrc;
            this.translationQrc = translationQrc;
        }

        public String qrc() { return qrc; }
        public String lrc() { return lrc; }
        public String translationQrc() { return translationQrc; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Lyrics)) return false;
            Lyrics that = (Lyrics) other;
            return Objects.equals(qrc, that.qrc) && Objects.equals(lrc, that.lrc)
                    && Objects.equals(translationQrc, that.translationQrc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(qrc, lrc, translationQrc);
        }

        @Override
        public String toString() {
            return "Lyrics[qrc=" + qrc.length() + " chars, lrc=" + lrc.length() + " chars]";
        }
    }

    public List<Song> search(String keyword, int limit) throws Exception {
        JsonObject param = new JsonObject();
        param.addProperty("query", keyword);
        param.addProperty("highlight", 1);
        param.addProperty("num_per_page", Math.max(1, limit));
        param.addProperty("page_num", 1);

        JsonObject call = new JsonObject();
        call.addProperty("module", "music.search.SearchCgiService");
        call.addProperty("method", "DoSearchForQQMusicMobile");
        call.add("param", param);

        JsonObject comm = new JsonObject();
        comm.addProperty("ct", "11");
        comm.addProperty("v", "20080508");
        comm.addProperty("tmeLoginType", "0");
        comm.addProperty("tmeLoginMethod", "0");

        JsonObject body = new JsonObject();
        body.add("comm", comm);
        body.add("music.search.SearchCgiService.DoSearchForQQMusicMobile", call);

        JsonObject root = postJson("https://u6.y.qq.com/cgi-bin/musicu.fcg", body.toString());
        JsonArray list = findSongList(root);
        List<Song> out = new ArrayList<Song>();
        if (list == null) return out;
        for (JsonElement el : list) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String mid = str(o, "mid");
            String id = str(o, "id");
            if (id.isEmpty()) id = str(o, "songid");
            String name = str(o, "name");
            if (name.isEmpty()) name = str(o, "title");
            if (name.isEmpty() || (id.isEmpty() && mid.isEmpty())) continue;
            StringBuilder artists = new StringBuilder();
            JsonArray singers = arr(o, "singer");
            if (singers != null) {
                for (JsonElement s : singers) {
                    if (!s.isJsonObject()) continue;
                    String n = str(s.getAsJsonObject(), "name");
                    if (n.isEmpty()) continue;
                    if (artists.length() > 0) artists.append('/');
                    artists.append(n);
                }
            }
            long duration = num(o, "interval") * 1000L;
            out.add(new Song(id, mid, name, artists.toString(), duration));
        }
        return out;
    }

    public Lyrics lyrics(Song song) throws Exception {
        String id = song.id();
        if (id.isEmpty()) return new Lyrics("", "", "");
        String form = "version=15&miniversion=82&lrctype=4&musicid=" + enc(id);
        String xml = postText("https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg", form,
                "https://c.y.qq.com/", "application/x-www-form-urlencoded");
        if (xml == null) throw new IOException("HTTP lyric download failed");

        xml = xml.replace("<!--", "").replace("-->", "");
        String qrc = decodeField(tag(xml, "content"));
        String translation = decodeField(tag(xml, "contentts"));
        String plain = decodeField(tag(xml, "Lyric_1"));
        if (!plain.contains("[")) plain = "";
        if (qrc.isEmpty()) {
            // newer payloads are encrypted with a key we cannot derive; the classic
            // endpoint still serves the same song as base64 LRC, so fall back to it
            String lrc = plainLrc(song.mid());
            if (plain.isEmpty()) plain = lrc;
        }
        return new Lyrics(qrc, plain, translation);
    }

    /**
     * Line-synced fallback from the legacy lyric endpoint, which answers with
     * base64-encoded LRC. Empty when the song has no lyric at all.
     */
    private String plainLrc(String mid) {
        if (mid == null || mid.isEmpty()) return "";
        String url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + enc(mid)
                + "&format=json&nobase64=0&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8"
                + "&outCharset=utf8&notice=0&platform=yqq&needNewCode=0&pcachetime=0";
        try {
            String text = getText(url, "https://y.qq.com/");
            if (text == null) return "";
            // JsonParser.parseString is Gson 2.8.6+; 1.8.9 ships Gson 2.2.4
            JsonElement parsed = new JsonParser().parse(text);
            if (!parsed.isJsonObject()) return "";
            JsonElement lyric = parsed.getAsJsonObject().get("lyric");
            if (lyric == null || lyric.isJsonNull()) return "";
            String b64 = lyric.getAsString();
            if (b64.isEmpty()) return "";
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** One XML field: hex QRC when encrypted, otherwise the plain text as-is. */
    private static String decodeField(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String text = unescape(raw.trim());
        if (Crypto.looksEncrypted(text)) {
            try {
                text = Crypto.qrc(text);
            } catch (Exception e) {
                return "";
            }
        }
        // the decrypted payload may still be wrapped in its own little XML
        int open = text.indexOf("<lyric>");
        if (open >= 0) {
            int close = text.indexOf("</lyric>", open);
            if (close > open) text = text.substring(open + 7, close);
        }
        return text.trim();
    }

    private JsonObject postJson(String url, String body) throws Exception {
        String text = postText(url, body, "https://c.y.qq.com/", "application/json");
        if (text == null) throw new IOException("HTTP request failed");
        JsonElement parsed = new JsonParser().parse(text);
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    /**
     * POSTs {@code body} and returns the response text, or {@code null} for a
     * non-200 answer — the same split the status check in the Java 11 version made.
     */
    private static String postText(String url, String body, String referer, String contentType)
            throws IOException {
        HttpURLConnection connection = open(url, "POST");
        connection.setRequestProperty("Referer", referer);
        connection.setRequestProperty("Content-Type", contentType);
        try {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
            } finally {
                out.close();
            }
            if (connection.getResponseCode() != 200) return null;
            return readText(connection);
        } finally {
            connection.disconnect();
        }
    }

    /** Plain GET returning the response text, or {@code null} for a non-200 answer. */
    private static String getText(String url, String referer) throws IOException {
        HttpURLConnection connection = open(url, "GET");
        connection.setRequestProperty("Referer", referer);
        try {
            if (connection.getResponseCode() != 200) return null;
            return readText(connection);
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

    /** The search payload nests the songs differently per endpoint version. */
    private static JsonArray findSongList(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
                if (entry.getValue().isJsonArray()) {
                    JsonArray array = entry.getValue().getAsJsonArray();
                    if (array.size() > 0 && array.get(0).isJsonObject()) {
                        JsonObject first = array.get(0).getAsJsonObject();
                        // the desktop endpoint says "list", the mobile one says
                        // "item_song"; both carry mid + singer + interval per song
                        if (first.has("mid") && (first.has("singer") || first.has("name"))) {
                            return array;
                        }
                    }
                }
                JsonArray found = findSongList(entry.getValue());
                if (found != null) return found;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                JsonArray found = findSongList(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Inner text of the first {@code <name …>} element. The payload uses
     * attributes ({@code <content type="file">}) and CDATA wrappers, so a plain
     * {@code "<name>"} search would miss it.
     */
    private static String tag(String xml, String name) {
        int open = indexOfOpen(xml, name);
        if (open < 0) return "";
        int gt = xml.indexOf('>', open);
        if (gt < 0) return "";
        if (gt > 0 && xml.charAt(gt - 1) == '/') return "";    // <content … /> is empty
        int close = xml.indexOf("</" + name + ">", gt);
        if (close < 0) return "";
        String inner = xml.substring(gt + 1, close).trim();
        if (inner.startsWith("<![CDATA[")) {
            int end = inner.indexOf("]]>");
            inner = end < 0 ? inner.substring(9) : inner.substring(9, end);
        }
        return inner;
    }

    /** First {@code <name>} / {@code <name …>} that is not a longer name like {@code <contentts>}. */
    private static int indexOfOpen(String xml, String name) {
        int from = 0;
        while (true) {
            int i = xml.indexOf("<" + name, from);
            if (i < 0) return -1;
            int after = i + 1 + name.length();
            if (after >= xml.length()) return -1;
            char c = xml.charAt(after);
            if (c == '>' || c == ' ' || c == '\t' || c == '\n' || c == '\r') return i;
            from = i + 1;
        }
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() || !el.isJsonPrimitive() ? "" : el.getAsString();
    }

    private static long num(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return 0L;
        try {
            return el.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    private static String enc(String s) {
        try {
            // the Charset overload of URLEncoder.encode is Java 10; the name overload is Java 1.4
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException never) {
            throw new IllegalStateException(never);
        }
    }
}
