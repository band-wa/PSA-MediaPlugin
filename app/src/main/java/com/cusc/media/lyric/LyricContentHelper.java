package com.cusc.media.lyric;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LyricContentHelper {
    private static final String TAG = "LyricContent";
    private static final Pattern PAREN = Pattern.compile("[\\(（][^\\)）]*[\\)）]");
    private static final Pattern BASE64_CHARS = Pattern.compile("^[A-Za-z0-9+/\\r\\n]+=*$");
    private static final Pattern TI_TAG = Pattern.compile("\\[ti:([^\\]]*)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMED = Pattern.compile("^\\[\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?](.*)$");

    private LyricContentHelper() {
    }

    static String cleanTitle(String str) {
        if (str == null) {
            return "";
        }
        String trimmed = str.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String cleaned = PAREN.matcher(trimmed).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? str.trim() : cleaned;
    }

    static String buildSearchKeyword(String title, String artist) {
        String keyword = cleanTitle(title);
        if (keyword.isEmpty() && title != null) {
            keyword = title.trim();
        }
        if (artist != null) {
            String artistTrim = artist.trim();
            int index = artistTrim.indexOf(" - ");
            if (index > 0) {
                artistTrim = artistTrim.substring(0, index).trim();
            }
            if (!artistTrim.isEmpty() && !"unknown".equalsIgnoreCase(artistTrim)
                    && !"未知".equals(artistTrim) && !"unknown artist".equalsIgnoreCase(artistTrim)) {
                return keyword + " " + artistTrim;
            }
        }
        return keyword;
    }

    static String cacheKeyBase(String title, String artist) {
        String cleaned = cleanTitle(title);
        if (cleaned.isEmpty() && title != null) {
            cleaned = title.trim();
        }
        String artistPart = "";
        if (artist != null) {
            String artistTrim = artist.trim();
            int index = artistTrim.indexOf(" - ");
            if (index > 0) {
                artistTrim = artistTrim.substring(0, index).trim();
            }
            if (!artistTrim.isEmpty() && !"unknown".equalsIgnoreCase(artistTrim)
                    && !"未知".equals(artistTrim) && !"unknown artist".equalsIgnoreCase(artistTrim)) {
                artistPart = artistTrim;
            }
        }
        return artistPart.isEmpty() ? cleaned + "_k3" : cleaned + "_" + artistPart + "_k3";
    }

    static String decodeIfNeeded(String str) {
        if (str == null) {
            return null;
        }
        String trimmed = str.trim();
        if (trimmed.isEmpty()) {
            return str;
        }
        if (trimmed.indexOf('[') >= 0 && trimmed.indexOf(']') > trimmed.indexOf('[')) {
            return str;
        }
        String compact = trimmed.replace("\r", "").replace("\n", "").trim();
        if (compact.length() < 32 || compact.length() % 4 != 0 || !BASE64_CHARS.matcher(compact).matches()) {
            return str;
        }
        try {
            byte[] decoded = Base64.decode(compact, Base64.DEFAULT);
            if (decoded != null && decoded.length != 0) {
                String result = new String(decoded, StandardCharsets.UTF_8).trim();
                if (looksLikeLrc(result)) {
                    Log.d(TAG, "decoded base64 lrc, len=" + result.length());
                    return result;
                }
            }
            return str;
        } catch (Throwable th) {
            Log.w(TAG, "base64 decode failed", th);
            return str;
        }
    }

    static boolean looksLikeLrc(String str) {
        if (str == null) {
            return false;
        }
        String trimmed = str.trim();
        if (trimmed.length() < 8) {
            return false;
        }
        int index = trimmed.indexOf('[');
        return index >= 0 && trimmed.indexOf(']') > index && trimmed.indexOf(':') > index;
    }

    static boolean matchesSong(String lrcContent, String title) {
        String decoded = decodeIfNeeded(lrcContent);
        if (decoded == null || !looksLikeLrc(decoded)) {
            return false;
        }
        String needle = cleanTitle(title);
        if (needle.length() < 2) {
            return true;
        }
        String lowerLrc = decoded.toLowerCase();
        String lowerNeedle = needle.toLowerCase();
        if (lowerLrc.contains(lowerNeedle)) {
            return true;
        }
        String hint = extractMetaTitle(decoded);
        if (hint == null || hint.length() < 2) {
            return true;
        }
        String lowerHint = cleanTitle(hint).toLowerCase();
        if (lowerHint.contains(lowerNeedle) || lowerNeedle.contains(lowerHint)) {
            return true;
        }
        Log.d(TAG, "title mismatch needle=" + needle + " hint=" + hint);
        return sharedRatio(lowerNeedle, lowerHint) >= 0.5f;
    }

    private static String extractMetaTitle(String str) {
        Matcher ti = TI_TAG.matcher(str);
        if (ti.find()) {
            String group = ti.group(1);
            if (group != null) {
                String trimmed = group.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        for (String line : str.split("\\r?\\n")) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher timed = TIMED.matcher(trimmed);
            if (timed.find()) {
                String text = timed.group(1);
                if (text == null) {
                    return null;
                }
                String first = text.trim();
                if (first.isEmpty() || first.startsWith("词：") || first.startsWith("曲：")
                        || first.startsWith("作词") || first.startsWith("作曲")) {
                    return null;
                }
                if (!first.contains(" - ") && !first.contains(" – ")
                        && first.indexOf('(') < 0 && first.indexOf('（') < 0) {
                    return null;
                }
                int index = first.indexOf(" - ");
                if (index < 0) {
                    index = first.indexOf(" – ");
                }
                return index > 0 ? first.substring(0, index).trim() : first;
            }
        }
        return null;
    }

    private static float sharedRatio(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0f;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        int count = 0;
        int matched = 0;
        for (int i = 0; i < shorter.length(); i++) {
            char c = shorter.charAt(i);
            if (c > ' ') {
                count++;
                if (longer.indexOf(c) >= 0) {
                    matched++;
                }
            }
        }
        if (count == 0) {
            return 0.0f;
        }
        return (float) matched / count;
    }

    static String normalizeFetched(String str) {
        String decoded = decodeIfNeeded(str);
        if (decoded == null || !looksLikeLrc(decoded)) {
            return null;
        }
        int timed = countTimedLines(decoded);
        if (timed < 8) {
            Log.w(TAG, "reject fetched lrc (too short, timed=" + timed + ")");
            return null;
        }
        return decoded;
    }

    static String readFileDecoded(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            return decodeIfNeeded(readAll(new File(path)));
        } catch (Throwable th) {
            Log.w(TAG, "readFileDecoded", th);
            return null;
        }
    }

    static String acceptOrClearCache(File file, String title) {
        if (file == null || !file.exists() || file.length() <= 0) {
            return null;
        }
        try {
            String all = readAll(file);
            String decoded = decodeIfNeeded(all);
            if (!matchesSong(decoded, title)) {
                Log.w(TAG, "reject cache (title mismatch): " + file.getName() + " title=" + title);
                file.delete();
                return null;
            }
            if (decoded != null && !decoded.equals(all)) {
                writeAll(file, decoded);
            }
            int timed = countTimedLines(decoded);
            if (timed > 0 && timed < 8) {
                Log.w(TAG, "reject cache (too short, lines=" + timed + "): " + file.getName());
                file.delete();
                return null;
            }
            return file.getAbsolutePath();
        } catch (Throwable th) {
            Log.w(TAG, "cache check failed", th);
            return null;
        }
    }

    static int countTimedLines(String str) {
        if (str == null) {
            return 0;
        }
        int count = 0;
        for (String line : str.split("\\r?\\n")) {
            if (line != null && TIMED.matcher(line.trim()).matches()) {
                count++;
            }
        }
        return count;
    }

    private static String readAll(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            char[] buffer = new char[4096];
            while (true) {
                int read = reader.read(buffer);
                if (read >= 0) {
                    sb.append(buffer, 0, read);
                } else {
                    return sb.toString();
                }
            }
        } finally {
            try {
                reader.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void writeAll(File file, String str) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(str.getBytes(StandardCharsets.UTF_8));
        } finally {
            try {
                fos.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
