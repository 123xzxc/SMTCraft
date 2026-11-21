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
 * QQ Music provider: a keyword search over {@code musicu.fcg} and the QRC lyric
 * download endpoint.
 *
 * <p>The lyric payload arrives as hex-encoded 3DES (see {@link Crypto}); older
 * songs only have a plain LRC inside the {@code Lyric_1} field, which is used as
 * a fallback.</p>
 */
public final class QQMusicClient {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Song(String id, String mid, String name, String artists, long durationMs) {}

    /** {@code qrc} is syllable-synced, {@code lrc} is the plain fallback. */
    public record Lyrics(String qrc, String lrc, String translationQrc) {}

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
        List<Song> out = new ArrayList<>();
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
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg"))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .header("Referer", "https://c.y.qq.com/")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new java.io.IOException("HTTP " + response.statusCode());

        String xml = response.body().replace("<!--", "").replace("-->", "");
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
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return "";
            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) return "";
            JsonElement lyric = parsed.getAsJsonObject().get("lyric");
            if (lyric == null || lyric.isJsonNull()) return "";
            String b64 = lyric.getAsString();
            if (b64.isEmpty()) return "";
            return new String(java.util.Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** One XML field: hex QRC when encrypted, otherwise the plain text as-is. */
    private static String decodeField(String raw) {
        if (raw == null || raw.isBlank()) return "";
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .header("Referer", "https://c.y.qq.com/")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new java.io.IOException("HTTP " + response.statusCode());
        JsonElement parsed = JsonParser.parseString(response.body());
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    /** The search payload nests the songs differently per endpoint version. */
    private static JsonArray findSongList(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            for (var entry : o.entrySet()) {
                if (entry.getValue().isJsonArray()) {
                    JsonArray array = entry.getValue().getAsJsonArray();
                    if (!array.isEmpty() && array.get(0).isJsonObject()) {
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
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
