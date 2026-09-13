package com.cusc.media.lyric;

import android.util.Log;

import com.cusc.bean.media.Lrc;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LyricParser {
    private static final String TAG = "LyricParser";
    private static final Pattern TIME_PATTERN = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern TIME_TAG_STRIP = Pattern.compile("\\[\\d{1,2}:\\d{1,2}(?:[.:]\\d{1,3})?\\]");
    private static final String[] USB_MOUNT_POINTS = {
            "/mnt/usb", "/mnt/usb0", "/mnt/usb1", "/mnt/usbdisk", "/storage/usb",
            "/storage/usbdisk", "/storage/udisk", "/mnt/udisk", "/storage/external_storage",
            "/mnt/external_storage", "/storage/emulated/0", "/sdcard"
    };
    private static final int MAX_SEARCH_DEPTH = 4;

    private LyricParser() {
    }

    public static List<Lrc> parseFile(String filePath) {
        List<Lrc> lyrics = new ArrayList<>();
        if (filePath == null) {
            return lyrics;
        }
        try {
            String content = LyricContentHelper.readFileDecoded(filePath);
            if (content != null && !content.isEmpty()) {
                return parseContent(content);
            }
        } catch (Exception e) {
            Log.w(TAG, "parseFile failed: " + filePath, e);
        }
        return lyrics;
    }

    public static List<Lrc> parseContent(String content) {
        String decoded = LyricContentHelper.decodeIfNeeded(content);
        List<Lrc> lyrics = new ArrayList<>();
        if (decoded == null || decoded.isEmpty()) {
            return lyrics;
        }
        try {
            String[] lines = decoded.split("\\r?\\n");
            for (String line : lines) {
                parseLine(line, lyrics);
            }
            Collections.sort(lyrics, (a, b) -> Long.compare(a.getTime(), b.getTime()));
        } catch (Exception e) {
            Log.w(TAG, "parseContent failed", e);
        }
        return lyrics;
    }

    private static void parseLine(String line, List<Lrc> lyrics) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        Matcher matcher = TIME_PATTERN.matcher(line);
        String text = TIME_TAG_STRIP.matcher(line).replaceAll("").trim();
        if (text.isEmpty()) {
            return;
        }
        while (matcher.find()) {
            int min = Integer.parseInt(matcher.group(1));
            int sec = Integer.parseInt(matcher.group(2));
            String msStr = matcher.group(3);
            int ms = 0;
            if (msStr != null) {
                ms = Integer.parseInt(msStr);
                if (msStr.length() == 2) {
                    ms *= 10;
                } else if (msStr.length() == 1) {
                    ms *= 100;
                }
            }
            long time = min * 60000L + sec * 1000L + ms;
            lyrics.add(new Lrc(time, text));
        }
    }

    public static String findLyricFile(String songPath) {
        if (songPath == null || songPath.isEmpty()) {
            return null;
        }
        int lastDot = songPath.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String lrcPath = songPath.substring(0, lastDot) + ".lrc";
        if (new File(lrcPath).exists()) {
            return lrcPath;
        }
        File dir = new File(songPath).getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        String target = normalizeFileName(new File(songPath).getName());
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".lrc")
                    && normalizeFileName(f.getName()).contains(target)) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    public static String searchLyricBySongName(String songTitle) {
        String target = normalizeFileName(songTitle);
        if (target.isEmpty()) {
            return null;
        }
        for (String root : USB_MOUNT_POINTS) {
            File rootDir = new File(root);
            if (rootDir.exists() && rootDir.isDirectory()) {
                String result = searchLyricInDirectory(rootDir, target, 0);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static String searchLyricInDirectory(File dir, String target, int depth) {
        if (depth > MAX_SEARCH_DEPTH || !dir.canRead()) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && !"Android".equals(f.getName())) {
                    String result = searchLyricInDirectory(f, target, depth + 1);
                    if (result != null) {
                        return result;
                    }
                }
            } else if (f.getName().toLowerCase().endsWith(".lrc")
                    && normalizeFileName(f.getName()).contains(target)) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static String normalizeFileName(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot > 0) {
            String ext = lower.substring(dot + 1);
            if (ext.equals("lrc") || ext.equals("mp3") || ext.equals("flac") || ext.equals("wav")
                    || ext.equals("ape") || ext.equals("wma") || ext.equals("ogg") || ext.equals("m4a")
                    || ext.equals("aac") || ext.equals("dsf") || ext.equals("dff")) {
                lower = lower.substring(0, dot);
            }
        }
        return lower.replaceAll("[\\s\\-_()（）\\[\\]【】.~,，!！?？'\"@#$%^&+=]", "");
    }
}
