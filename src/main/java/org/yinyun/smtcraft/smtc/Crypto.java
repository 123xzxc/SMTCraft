/*
 * Portions of this file are ported from Lyricify-Lyrics-Helper
 * (https://github.com/WXRIW/Lyricify-Lyrics-Helper),
 * Copyright 2023 XY Wang, WXRIW, licensed under the Apache License 2.0.
 * Modified for Java / Minecraft. See THIRD_PARTY_NOTICES.md.
 */
package org.yinyun.smtcraft.smtc;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Inflater;

/**
 * Payload decryption for the syllable-synced lyric formats.
 *
 * <p>QRC (QQ Music) is a hex string holding a 3DES-ECB ciphertext of a zlib
 * stream; KRC (Kugou) is base64 holding four header bytes, an XOR mask and a
 * zlib stream. Both end up as UTF-8 text after inflating.</p>
 */
public final class Crypto {
    /** "!@#)(*$%123ZXC!@!@#)(NHL" - the fixed QQ Music triple-DES key. */
    private static final byte[] QQ_KEY =
            "!@#)(*$%123ZXC!@!@#)(NHL".getBytes(StandardCharsets.US_ASCII);

    /** Fixed Kugou XOR mask, cycled over the payload. */
    private static final byte[] KRC_KEY = {
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, (byte) 0xce, (byte) 0xd2, 0x6e, 0x69
    };

    private Crypto() {}

    /** Hex -> 3DES -> zlib -> UTF-8, BOM removed. */
    public static String qrc(String hex) throws Exception {
        byte[] raw = hexToBytes(hex);
        if (raw.length == 0 || raw.length % 8 != 0) {
            throw new IllegalArgumentException("qrc length " + raw.length);
        }
        Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(QQ_KEY, "DESede"));
        return stripBom(new String(inflate(cipher.doFinal(raw)), StandardCharsets.UTF_8));
    }

    /** Base64 -> skip 4 bytes -> XOR -> zlib -> UTF-8, leading length byte removed. */
    public static String krc(String base64) throws Exception {
        byte[] data = Base64.getDecoder().decode(base64.trim());
        if (data.length < 6) throw new IllegalArgumentException("krc length " + data.length);
        byte[] body = new byte[data.length - 4];
        for (int i = 4; i < data.length; i++) {
            body[i - 4] = (byte) (data[i] ^ KRC_KEY[(i - 4) % KRC_KEY.length]);
        }
        String text = new String(inflate(body), StandardCharsets.UTF_8);
        // the payload starts with a length/BOM byte on some songs, but on others
        // it starts straight at '['; only drop a genuine non-printable prefix
        if (!text.isEmpty() && text.charAt(0) != '[' && text.charAt(0) < 0x21) {
            return text.substring(1);
        }
        return text;
    }

    /** True when the string looks like a hex-encoded QRC blob rather than plain lyrics. */
    public static boolean looksEncrypted(String s) {
        if (s == null || s.length() < 32 || s.length() % 2 != 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private static byte[] hexToBytes(String hex) {
        String s = hex.trim();
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i + 1 < s.length(); i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    | Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }

    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    /** Hard cap for one lyric payload: a 40 KB zlib stream must never expand to 4 GB. */
    private static final int MAX_INFLATED_BYTES = 8 * 1024 * 1024;
    private static final int MAX_EXPANSION_RATIO = 200;

    private static byte[] inflate(byte[] data) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        int limit = Math.min(MAX_INFLATED_BYTES,
                Math.max(64, Math.max(data.length * MAX_EXPANSION_RATIO, data.length * 4)));
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, Math.max(64, data.length * 4)));
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                } else {
                    if (out.size() + n > limit) {
                        // decompression bomb guard: keep what fits and stop
                        out.write(buffer, 0, Math.max(0, limit - out.size()));
                        break;
                    }
                    out.write(buffer, 0, n);
                }
            }
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
