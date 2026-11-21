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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Kugou provider: the lyric search endpoint returns candidates with an
 * {@code id}/{@code accesskey} pair, the download endpoint returns a base64 KRC
 * payload that {@link Crypto} turns into text.
 */
public final class KugouClient {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Set once the catalogue host has proven it cannot do TLS; see {@link #searchSongs}. */
    private static volatile boolean catalogueTlsBroken;

    public record Candidate(String id, String accessKey, String song, String singer,
                            long durationMs, int score) {}

    /** A catalogue hit; {@code hash} is what the lyric search needs. */
    public record Song(String hash, String name, String singer, long durationMs) {}

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
        List<Song> out = new ArrayList<>();
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
        List<Candidate> out = new ArrayList<>();
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

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        JsonElement parsed = JsonParser.parseString(response.body());
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
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
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
