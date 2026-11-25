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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.yinyun.smtcraft.util.Budgets;

/**
 * Kugou provider: the lyric search endpoint returns candidates with an
 * {@code id}/{@code accesskey} pair, the download endpoint returns a base64 KRC
 * payload that {@link Crypto} turns into text.
 */
public final class KugouClient {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    // 1.8.9 的目标运行时是 Java 8，java.net.http.HttpClient（Java 11）不存在，
    // 这里换成 HttpURLConnection；两个数值照抄原文件的 connectTimeout(6s) 与 timeout(10s)
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /** Set once the catalogue host has proven it cannot do TLS; see {@link #searchSongs}. */
    private static volatile boolean catalogueTlsBroken;

    /** A lyric candidate of one catalogue hit: {@code id} + {@code accessKey} unlock the download. */
    public static final class Candidate {
        private final String id;
        private final String accessKey;
        private final String song;
        private final String singer;
        private final long durationMs;
        private final int score;

        public Candidate(String id, String accessKey, String song, String singer,
                         long durationMs, int score) {
            this.id = id;
            this.accessKey = accessKey;
            this.song = song;
            this.singer = singer;
            this.durationMs = durationMs;
            this.score = score;
        }

        public String id() { return id; }
        public String accessKey() { return accessKey; }
        public String song() { return song; }
        public String singer() { return singer; }
        public long durationMs() { return durationMs; }
        public int score() { return score; }

        // 原文件是 record，LyricService 用 contains 对候选去重，按值相等必须原样保留
        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Candidate)) return false;
            Candidate that = (Candidate) other;
            return durationMs == that.durationMs && score == that.score
                    && Objects.equals(id, that.id) && Objects.equals(accessKey, that.accessKey)
                    && Objects.equals(song, that.song) && Objects.equals(singer, that.singer);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, accessKey, song, singer, durationMs, score);
        }

        @Override
        public String toString() {
            return "Candidate[id=" + id + ", accessKey=" + accessKey + ", song=" + song
                    + ", singer=" + singer + ", durationMs=" + durationMs + ", score=" + score + "]";
        }
    }

    /** A catalogue hit; {@code hash} is what the lyric search needs. */
    public static final class Song {
        private final String hash;
        private final String name;
        private final String singer;
        private final long durationMs;

        public Song(String hash, String name, String singer, long durationMs) {
            this.hash = hash;
            this.name = name;
            this.singer = singer;
            this.durationMs = durationMs;
        }

        public String hash() { return hash; }
        public String name() { return name; }
        public String singer() { return singer; }
        public long durationMs() { return durationMs; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Song)) return false;
            Song that = (Song) other;
            return durationMs == that.durationMs && Objects.equals(hash, that.hash)
                    && Objects.equals(name, that.name) && Objects.equals(singer, that.singer);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hash, name, singer, durationMs);
        }

        @Override
        public String toString() {
            return "Song[hash=" + hash + ", name=" + name + ", singer=" + singer
                    + ", durationMs=" + durationMs + "]";
        }
    }

    /**
     * Catalogue search. The lyric endpoint only answers when it is given the song
     * hash, and a bare keyword search is not enough for many Japanese or
     * non-mainland titles, so the catalogue is the reliable first step.
     */
    public List<Song> searchSongs(String keyword) throws Exception {
        String path = "/api/v3/search/song?format=json&keyword=" + enc(keyword)
                + "&page=1&pagesize=20&showtype=1";
        // mobilecdn.kugou.com serves valid TLS only for other names: an https request
        // dies with "No subject alternative DNS name matching mobilecdn.kugou.com
        // found", which used to take the whole provider down. Try TLS first (in case
        // Kugou fixes the certificate) and fall back to plain HTTP, which carries no
        // credentials and no personal data.
        JsonObject root;
        if (catalogueTlsBroken) {
            root = getJson("http://mobilecdn.kugou.com" + path);
        } else {
            try {
                root = getJson("https://mobilecdn.kugou.com" + path);
            } catch (Exception tlsFailed) {
                // remember it: a failed handshake costs over a second, and the lyric
                // service issues several queries per song
                catalogueTlsBroken = true;
                root = getJson("http://mobilecdn.kugou.com" + path);
            }
        }
        JsonArray info = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data").has("info")
                        ? root.getAsJsonObject("data").getAsJsonArray("info") : null
                : null;
        List<Song> out = new ArrayList<Song>();
        if (info == null) return out;
        for (JsonElement el : info) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String hash = str(o, "hash");
            String name = str(o, "songname");
            if (hash.isEmpty() || name.isEmpty()) continue;
            out.add(new Song(hash, name, str(o, "singername"),
                    num(o, "duration") * 1000L));
        }
        return out;
    }

    /** Lyric candidates for one catalogue hit. */
    public List<Candidate> searchByHash(String keyword, long durationMs, String hash) throws Exception {
        StringBuilder url = new StringBuilder("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=")
                .append(enc(keyword));
        if (durationMs > 0L) url.append("&duration=").append(durationMs / 1000L);
        url.append("&hash=").append(enc(hash));
        return candidates(getJson(url.toString()));
    }

    public List<Candidate> search(String keyword, long durationMs) throws Exception {
        StringBuilder url = new StringBuilder("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=")
                .append(enc(keyword));
        if (durationMs > 0L) url.append("&duration=").append(durationMs / 1000L);
        url.append("&hash=");
        return candidates(getJson(url.toString()));
    }

    private static List<Candidate> candidates(JsonObject root) {
        List<Candidate> out = new ArrayList<Candidate>();
        JsonArray candidates = root.has("candidates") && root.get("candidates").isJsonArray()
                ? root.getAsJsonArray("candidates") : null;
        if (candidates == null) return out;
        for (JsonElement el : candidates) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "id");
            String key = str(o, "accesskey");
            if (id.isEmpty() || key.isEmpty()) continue;
            out.add(new Candidate(id, key, str(o, "song"), str(o, "singer"),
                    num(o, "duration"), (int) num(o, "score")));
        }
        return out;
    }

    /** Decrypted KRC text, or "" when the candidate has none. */
    public String krc(Candidate candidate) throws Exception {
        String url = "https://lyrics.kugou.com/download?ver=1&client=pc&id=" + enc(candidate.id())
                + "&accesskey=" + enc(candidate.accessKey()) + "&fmt=krc&charset=utf8";
        JsonObject root = getJson(url);
        String content = str(root, "content");
        if (content.isEmpty()) return "";
        return Crypto.krc(content);
    }

    private JsonObject getJson(String url) throws IOException {
        HttpURLConnection connection = open(url, "GET");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status);
            // JsonParser.parseString is Gson 2.8.6+; 1.8.9 ships Gson 2.2.4, whose entry point is the instance parse()
            JsonElement parsed = new JsonParser().parse(readText(connection));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
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
        // Redirect.NORMAL follows everything except https -> http, which HttpURLConnection
        // refuses by protocol rule anyway
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

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return "";
        return el.getAsString();
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

    private static String enc(String s) {
        try {
            // the Charset overload of URLEncoder.encode is Java 10; the name overload is Java 1.4
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException never) {
            throw new IllegalStateException(never);
        }
    }
}
