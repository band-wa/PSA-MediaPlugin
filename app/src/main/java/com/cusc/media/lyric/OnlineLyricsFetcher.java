package com.cusc.media.lyric;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OnlineLyricsFetcher {
    private static final String TAG = "OnlineLyrics";
    private static final int SOURCE_KUGOU = 0;
    private static final int SOURCE_KUGOU_MOBILE = 1;
    private static final int SOURCE_QQMUSIC = 2;
    private static final int SOURCE_NETEASE = 3;
    private static final int SOURCE_KUWO = 4;
    private static final int SOURCE_LRCLIB = 5;
    private static final long TOTAL_TIMEOUT_MS = 20000;
    private static final int MIN_CANDIDATE_SCORE = 6;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String cacheDir;

    public OnlineLyricsFetcher(Context context) {
        this.cacheDir = context.getCacheDir().getAbsolutePath() + "/online_lyrics";
        File dir = new File(this.cacheDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private List<Integer> getPreferredSources(String playerPackage) {
        List<Integer> order = buildSourceOrder(playerPackage);
        order.add(SOURCE_LRCLIB);
        return order;
    }

    private List<Integer> buildSourceOrder(String playerPackage) {
        List<Integer> order = new ArrayList<>();
        if (playerPackage == null) {
            order.add(SOURCE_KUGOU);
            order.add(SOURCE_KUGOU_MOBILE);
            order.add(SOURCE_QQMUSIC);
            order.add(SOURCE_NETEASE);
            order.add(SOURCE_KUWO);
            return order;
        }
        if (playerPackage.contains("qqmusic")) {
            order.add(SOURCE_QQMUSIC);
            order.add(SOURCE_KUGOU);
            order.add(SOURCE_NETEASE);
            order.add(SOURCE_KUWO);
            order.add(SOURCE_KUGOU_MOBILE);
        } else if (playerPackage.contains("luna")) {
            order.add(SOURCE_KUWO);
            order.add(SOURCE_KUGOU);
            order.add(SOURCE_NETEASE);
            order.add(SOURCE_QQMUSIC);
            order.add(SOURCE_KUGOU_MOBILE);
        } else if (playerPackage.contains("netease") || playerPackage.contains("cloudmusic")) {
            order.add(SOURCE_NETEASE);
            order.add(SOURCE_QQMUSIC);
            order.add(SOURCE_KUGOU);
            order.add(SOURCE_KUWO);
            order.add(SOURCE_KUGOU_MOBILE);
        } else if (playerPackage.contains("kuwo")) {
            order.add(SOURCE_KUWO);
            order.add(SOURCE_KUGOU);
            order.add(SOURCE_KUGOU_MOBILE);
            order.add(SOURCE_NETEASE);
        } else if (playerPackage.contains("kugou")) {
            order.add(SOURCE_KUGOU);
            order.add(SOURCE_KUGOU_MOBILE);
            order.add(SOURCE_QQMUSIC);
            order.add(SOURCE_NETEASE);
            order.add(SOURCE_KUWO);
        } else {
            order.add(SOURCE_KUGOU);
            order.add(SOURCE_KUGOU_MOBILE);
            order.add(SOURCE_QQMUSIC);
            order.add(SOURCE_NETEASE);
            order.add(SOURCE_KUWO);
        }
        return order;
    }

    public String fetchLyrics(String title, String artist, long duration, String playerPackage) {
        if (title == null || title.isEmpty()) {
            return null;
        }
        String cacheKey = sanitizeFileName(LyricContentHelper.cacheKeyBase(title, artist));
        File cachedFile = new File(cacheDir, cacheKey + ".lrc");
        String cachedPath = LyricContentHelper.acceptOrClearCache(cachedFile, title);
        if (cachedPath != null) {
            Log.d(TAG, "命中缓存: " + cachedFile.getName());
            return LyricContentHelper.readFileDecoded(cachedPath);
        }
        List<Integer> sourceOrder = getPreferredSources(playerPackage);
        Log.d(TAG, "API源优先级: " + sourceOrder + " (播放器: " + playerPackage + ")");
        long deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS;
        for (int src : sourceOrder) {
            if (System.currentTimeMillis() > deadline) {
                Log.w(TAG, "总超时，停止尝试剩余源");
                break;
            }
            try {
                String lrcContent;
                switch (src) {
                    case SOURCE_KUGOU:
                        lrcContent = fetchFromKugou(title, artist, duration);
                        break;
                    case SOURCE_KUGOU_MOBILE:
                        lrcContent = fetchFromKugouMobile(title, artist, duration);
                        break;
                    case SOURCE_QQMUSIC:
                        lrcContent = fetchFromQQMusic(title, artist, duration);
                        break;
                    case SOURCE_NETEASE:
                        lrcContent = fetchFromNetEase(title, artist, duration);
                        break;
                    case SOURCE_KUWO:
                        lrcContent = fetchFromKuwo(title, artist, duration);
                        break;
                    case SOURCE_LRCLIB:
                        lrcContent = fetchFromLrcLib(title, artist, duration);
                        break;
                    default:
                        lrcContent = null;
                        break;
                }
                if (lrcContent != null && !lrcContent.isEmpty()) {
                    String path = cacheLyrics(cacheKey, lrcContent);
                    Log.d(TAG, "在线歌词匹配成功, source=" + src + ", cached=" + path);
                    return lrcContent;
                }
            } catch (Exception e) {
                Log.w(TAG, "源 " + src + " 失败: " + e.getMessage());
            }
        }
        Log.w(TAG, "所有源均未找到歌词");
        return null;
    }

    private String fetchFromKugou(String title, String artist, long duration) throws Exception {
        return downloadKugouLyric(title, artist, duration);
    }

    private String downloadKugouLyric(String title, String artist, long durationMs) throws Exception {
        String keyword = LyricContentHelper.buildSearchKeyword(title, artist);
        String keywordEnc = URLEncoder.encode(keyword, "UTF-8");
        String durMs = durationMs > 0 ? String.valueOf(durationMs) : "";
        String searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=mobi&keyword="
                + keywordEnc + "&duration=" + durMs;
        String searchResp = httpGet(searchUrl);
        if (searchResp == null || searchResp.isEmpty()) {
            return null;
        }
        JSONObject searchJson = new JSONObject(searchResp);
        JSONArray candidates = searchJson.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return null;
        }
        String bestId = null;
        String bestAccesskey = null;
        String bestDesc = "";
        int bestScore = -1;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject item = candidates.getJSONObject(i);
            String songName = item.optString("song", "");
            String singerName = item.optString("singer", "");
            long itemDurationMs = item.optLong("duration", 0L);
            int score = scoreCandidate(songName, singerName, title, artist, itemDurationMs, durationMs, i);
            if (score > bestScore) {
                bestScore = score;
                bestId = item.optString("id", "");
                bestAccesskey = item.optString("accesskey", "");
                bestDesc = songName + "-" + singerName;
            }
        }
        if (bestScore < MIN_CANDIDATE_SCORE) {
            Log.w(TAG, "kugou candidates irrelevant (best=" + bestScore + ", top=" + bestDesc + ")");
            return null;
        }
        if (bestId == null || bestId.isEmpty() || bestAccesskey == null || bestAccesskey.isEmpty()) {
            return null;
        }
        String downloadUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id="
                + URLEncoder.encode(bestId, "UTF-8") + "&accesskey="
                + URLEncoder.encode(bestAccesskey, "UTF-8") + "&fmt=lrc&charset=utf8";
        String downloadResp = httpGet(downloadUrl);
        if (downloadResp == null || downloadResp.isEmpty()) {
            return null;
        }
        JSONObject downloadJson = new JSONObject(downloadResp);
        String content = downloadJson.optString("content", "");
        if (content.isEmpty()) {
            Log.w(TAG, "酷狗歌词content为空");
            return null;
        }
        return LyricContentHelper.normalizeFetched(content);
    }

    private String fetchFromKugouMobile(String title, String artist, long duration) throws Exception {
        String keyword = LyricContentHelper.buildSearchKeyword(title, artist);
        String keywordEnc = URLEncoder.encode(keyword, "UTF-8");
        String searchUrl = "https://mobiles.kugou.com/api/v3/search/song?keyword="
                + keywordEnc + "&page=1&pagesize=10&showtype=1";
        String searchResp = httpGet(searchUrl);
        if (searchResp == null || searchResp.isEmpty()) {
            return null;
        }
        JSONObject searchJson = new JSONObject(searchResp);
        JSONObject data = searchJson.optJSONObject("data");
        if (data == null) {
            return null;
        }
        JSONArray info = data.optJSONArray("info");
        if (info == null || info.length() == 0) {
            return null;
        }
        String bestSong = null;
        String bestSinger = null;
        long bestDurationMs = 0;
        int bestScore = -1;
        for (int i = 0; i < info.length(); i++) {
            JSONObject item = info.getJSONObject(i);
            String songName = item.optString("songname", "");
            String singerName = item.optString("singername", "");
            String hash = item.optString("hash", "");
            long itemDurationMs = item.optLong("duration", 0L) * 1000L;
            int score = scoreCandidate(songName, singerName, title, artist, itemDurationMs, duration, i);
            if (score > bestScore && !hash.isEmpty()) {
                bestScore = score;
                bestSong = songName;
                bestSinger = singerName;
                bestDurationMs = itemDurationMs;
            }
        }
        if (bestScore < MIN_CANDIDATE_SCORE) {
            Log.w(TAG, "kugou mobile candidates irrelevant (best=" + bestScore + ")");
            return null;
        }
        if (bestSong == null || bestSong.isEmpty()) {
            return null;
        }
        return downloadKugouLyric(bestSong, bestSinger, bestDurationMs);
    }

    private String fetchFromQQMusic(String title, String artist, long duration) throws Exception {
        String keyword = artist != null && !artist.isEmpty() ? title + " " + artist : title;
        String keywordEnc = URLEncoder.encode(keyword, "UTF-8");
        String searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w="
                + keywordEnc + "&format=json&p=1&n=10";
        String searchResp = httpGet(searchUrl, "https://y.qq.com/");
        if (searchResp == null || searchResp.isEmpty()) {
            Log.w(TAG, "QQ音乐搜索返回空");
            return null;
        }
        JSONObject searchJson = new JSONObject(searchResp);
        JSONObject data = searchJson.optJSONObject("data");
        if (data == null) {
            return null;
        }
        JSONObject song = data.optJSONObject("song");
        if (song == null) {
            return null;
        }
        JSONArray list = song.optJSONArray("list");
        if (list == null || list.length() == 0) {
            return null;
        }
        String bestSongmid = null;
        String bestDesc = "";
        int bestScore = -1;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            String songName = item.optString("songname", "");
            JSONArray singers = item.optJSONArray("singer");
            String singerName = (singers == null || singers.length() <= 0)
                    ? "" : singers.getJSONObject(0).optString("name", "");
            long itemDurationMs = item.optLong("interval", 0L) * 1000L;
            int score = scoreCandidate(songName, singerName, title, artist, itemDurationMs, duration, i);
            if (score > bestScore) {
                bestScore = score;
                bestSongmid = item.optString("songmid", "");
                bestDesc = songName + "-" + singerName;
            }
        }
        if (bestScore < MIN_CANDIDATE_SCORE) {
            Log.w(TAG, "qq candidates irrelevant (best=" + bestScore + ", top=" + bestDesc + ")");
            return null;
        }
        if (bestSongmid == null || bestSongmid.isEmpty()) {
            return null;
        }
        String lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                + URLEncoder.encode(bestSongmid, "UTF-8") + "&g_tk=5381&format=json&nobase64=0";
        String lyricResp = httpGet(lyricUrl, "https://y.qq.com/");
        if (lyricResp == null || lyricResp.isEmpty()) {
            return null;
        }
        JSONObject lyricJson = new JSONObject(lyricResp);
        String lyric = lyricJson.optString("lyric", "");
        if (lyric.isEmpty()) {
            return null;
        }
        return LyricContentHelper.normalizeFetched(lyric);
    }

    private String fetchFromNetEase(String title, String artist, long duration) throws Exception {
        String keyword = LyricContentHelper.buildSearchKeyword(title, artist);
        String keywordEnc = URLEncoder.encode(keyword, "UTF-8");
        String searchUrl = "https://music.163.com/api/search/get/web?s="
                + keywordEnc + "&type=1&offset=0&total=true&limit=10";
        String searchResp = httpGet(searchUrl, "https://music.163.com");
        if (searchResp == null || searchResp.isEmpty()) {
            return null;
        }
        JSONObject searchJson = new JSONObject(searchResp);
        JSONObject result = searchJson.optJSONObject("result");
        if (result == null) {
            return null;
        }
        JSONArray songs = result.optJSONArray("songs");
        if (songs == null || songs.length() == 0) {
            return null;
        }
        String bestId = null;
        String bestDesc = "";
        int bestScore = -1;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject item = songs.getJSONObject(i);
            String songName = item.optString("name", "");
            JSONArray artists = item.optJSONArray("artists");
            String artistName = (artists == null || artists.length() <= 0)
                    ? "" : artists.getJSONObject(0).optString("name", "");
            long itemDurationMs = item.optLong("duration", 0L);
            int score = scoreCandidate(songName, artistName, title, artist, itemDurationMs, duration, i);
            if (score > bestScore) {
                bestScore = score;
                bestId = item.optString("id", "");
                bestDesc = songName + "-" + artistName;
            }
        }
        if (bestScore < MIN_CANDIDATE_SCORE) {
            Log.w(TAG, "netease candidates irrelevant (best=" + bestScore + ", top=" + bestDesc + ")");
            return null;
        }
        if (bestId == null || bestId.isEmpty()) {
            return null;
        }
        String lyricUrl = "https://music.163.com/api/song/lyric?id=" + bestId + "&lv=1&kv=1&tv=-1";
        String lyricResp = httpGet(lyricUrl, "https://music.163.com");
        if (lyricResp == null || lyricResp.isEmpty()) {
            return null;
        }
        JSONObject lyricJson = new JSONObject(lyricResp);
        JSONObject lrc = lyricJson.optJSONObject("lrc");
        if (lrc == null) {
            return null;
        }
        String lyric = lrc.optString("lyric", "");
        if (lyric.trim().isEmpty() || lyric.trim().equals("[]")) {
            JSONObject tlyric = lyricJson.optJSONObject("tlyric");
            if (tlyric != null) {
                String transLyric = tlyric.optString("lyric", "");
                if (!transLyric.trim().isEmpty()) {
                    return transLyric;
                }
            }
            return null;
        }
        return LyricContentHelper.normalizeFetched(lyric);
    }

    private String fetchFromKuwo(String title, String artist, long duration) throws Exception {
        String keyword = LyricContentHelper.buildSearchKeyword(title, artist);
        String keywordEnc = URLEncoder.encode(keyword, "UTF-8");
        String searchUrl = "https://search.kuwo.cn/r.s?all=" + keywordEnc
                + "&ft=music&itemset=web_2013&client=kt&pn=0&rn=10&rformat=json&encoding=utf8&uid=221260053&ver=kwplayer_ar_9.2.2.1_B_jiakong_vh.apk";
        String searchResp = null;
        try {
            searchResp = httpGet(searchUrl);
        } catch (Exception e) {
            Log.w(TAG, "酷我HTTPS搜索失败: " + e.getMessage());
        }
        if (searchResp == null || searchResp.isEmpty()) {
            try {
                searchResp = httpGet("http://search.kuwo.cn/r.s?all=" + keywordEnc
                        + "&ft=music&itemset=web_2013&client=kt&pn=0&rn=10&rformat=json&encoding=utf8&uid=221260053&ver=kwplayer_ar_9.2.2.1_B_jiakong_vh.apk");
            } catch (Exception e) {
                Log.w(TAG, "酷我HTTP搜索失败: " + e.getMessage());
                return null;
            }
        }
        if (searchResp == null || searchResp.isEmpty()) {
            Log.w(TAG, "酷我搜索返回空");
            return null;
        }
        String trimmed = searchResp.trim();
        if (!trimmed.startsWith("{") && trimmed.contains("{")) {
            trimmed = trimmed.substring(trimmed.indexOf('{'));
        }
        JSONObject searchJson = new JSONObject(trimmed);
        JSONArray abslist = searchJson.optJSONArray("abslist");
        if (abslist == null || abslist.length() == 0) {
            Log.w(TAG, "酷我搜索结果为空");
            return null;
        }
        String bestMusicId = null;
        String bestDesc = "";
        int bestScore = -1;
        for (int i = 0; i < abslist.length(); i++) {
            JSONObject item = abslist.getJSONObject(i);
            String songName = item.optString("SONGNAME", "").replaceAll("&nbsp;", " ");
            String singerName = item.optString("ARTIST", "").replaceAll("&nbsp;", " ");
            String musicId = item.optString("MUSICRID", "");
            if (musicId.startsWith("MUSIC_")) {
                musicId = musicId.substring(6);
            }
            long itemDurationMs = item.optLong("DURATION", 0L) * 1000L;
            int score = scoreCandidate(songName, singerName, title, artist, itemDurationMs, duration, i);
            if (score > bestScore && !musicId.isEmpty()) {
                bestScore = score;
                bestMusicId = musicId;
                bestDesc = songName + "-" + singerName;
            }
        }
        if (bestScore < MIN_CANDIDATE_SCORE) {
            Log.w(TAG, "kuwo candidates irrelevant (best=" + bestScore + ", top=" + bestDesc + ")");
            return null;
        }
        if (bestMusicId == null || bestMusicId.isEmpty()) {
            return null;
        }
        String lyricUrl = "https://www.kuwo.cn/openapi/v1/www/lyric/getlyric?musicId=" + bestMusicId;
        String lyricResp = httpGet(lyricUrl, "https://www.kuwo.cn/");
        if (lyricResp == null || lyricResp.isEmpty()) {
            return null;
        }
        JSONObject lyricJson = new JSONObject(lyricResp);
        JSONObject data = lyricJson.optJSONObject("data");
        if (data == null) {
            return null;
        }
        JSONArray lrclist = data.optJSONArray("lrclist");
        if (lrclist == null || lrclist.length() == 0) {
            return null;
        }
        StringBuilder lrcBuilder = new StringBuilder();
        for (int i = 0; i < lrclist.length(); i++) {
            JSONObject line = lrclist.getJSONObject(i);
            String time = line.optString("time", "0");
            String text = line.optString("lineLyric", "");
            if (text.isEmpty()) {
                continue;
            }
            try {
                float seconds = Float.parseFloat(time);
                int min = (int) (seconds / 60.0f);
                int sec = (int) (seconds % 60.0f);
                int ms = (int) ((seconds - (int) seconds) * 100.0f);
                lrcBuilder.append(String.format("[%02d:%02d.%02d]%s%n", min, sec, ms, text));
            } catch (Exception ignored) {
            }
        }
        String lrcContent = lrcBuilder.toString();
        if (lrcContent.isEmpty()) {
            return null;
        }
        return LyricContentHelper.normalizeFetched(lrcContent);
    }

    private String fetchFromLrcLib(String title, String artist, long durationMs) throws Exception {
        String trackEnc = URLEncoder.encode(title, "UTF-8");
        boolean hasArtist = artist != null && !artist.isEmpty();
        String artistEnc = hasArtist ? URLEncoder.encode(artist, "UTF-8") : "";
        if (hasArtist) {
            String getResp = httpGet("https://lrclib.net/api/get?track_name=" + trackEnc
                    + "&artist_name=" + artistEnc, null);
            String synced = extractSyncedLyrics(getResp);
            if (synced != null) {
                return synced;
            }
        }
        String searchUrl = hasArtist
                ? "https://lrclib.net/api/search?track_name=" + trackEnc + "&artist_name=" + artistEnc
                : "https://lrclib.net/api/search?q=" + trackEnc;
        String searchResp = httpGet(searchUrl, null);
        if (searchResp == null || searchResp.isEmpty() || searchResp.trim().startsWith("<")) {
            return null;
        }
        JSONArray results = new JSONArray(searchResp);
        if (results.length() == 0) {
            return null;
        }
        long durationSec = durationMs > 0 ? durationMs / 1000 : 0;
        String bestLyrics = null;
        int bestScore = -1;
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.getJSONObject(i);
            String lyrics = extractSyncedLyricsFromItem(item);
            if (lyrics == null) {
                continue;
            }
            int score = 0;
            String trackName = item.optString("trackName", "");
            if (fuzzyEquals(trackName, title)) {
                score += 10;
            } else if (fuzzyContains(trackName, title)) {
                score += 5;
            }
            String artistName = item.optString("artistName", "");
            if (hasArtist) {
                if (fuzzyEquals(artistName, artist)) {
                    score += 10;
                } else if (fuzzyContains(artistName, artist)) {
                    score += 5;
                }
            }
            double itemDuration = item.optDouble("duration", 0);
            if (durationSec > 0 && itemDuration > 0 && Math.abs(itemDuration - durationSec) <= 3) {
                score += 5;
            }
            if (i == 0) {
                score++;
            }
            if (score > bestScore) {
                bestScore = score;
                bestLyrics = lyrics;
            }
        }
        if (bestScore < MIN_CANDIDATE_SCORE) {
            Log.w(TAG, "lrclib candidates irrelevant (best=" + bestScore + ")");
            return null;
        }
        return bestLyrics;
    }

    private String extractSyncedLyrics(String resp) throws Exception {
        if (resp == null || resp.isEmpty() || resp.trim().startsWith("<")) {
            return null;
        }
        return extractSyncedLyricsFromItem(new JSONObject(resp));
    }

    private String extractSyncedLyricsFromItem(JSONObject item) {
        if (item == null || item.optBoolean("instrumental", false)) {
            return null;
        }
        String synced = item.optString("syncedLyrics", "");
        if (synced.trim().isEmpty()) {
            return null;
        }
        return LyricContentHelper.normalizeFetched(synced);
    }

    private String httpGet(String urlStr) throws Exception {
        return httpGet(urlStr, null);
    }

    private String httpGet(String urlStr, String referer) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (referer != null) {
            conn.setRequestProperty("Referer", referer);
        }
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            conn.disconnect();
            return null;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        while (true) {
            String line = reader.readLine();
            if (line != null) {
                sb.append(line);
            } else {
                reader.close();
                conn.disconnect();
                return sb.toString();
            }
        }
    }

    private String cacheLyrics(String cacheKey, String content) {
        String normalized = LyricContentHelper.normalizeFetched(content);
        if (normalized == null) {
            return null;
        }
        try {
            File file = new File(cacheDir, cacheKey + ".lrc");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(normalized.getBytes("UTF-8"));
            fos.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "缓存歌词失败: " + e.getMessage());
            return null;
        }
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "_").replaceAll("_+", "_");
    }

    private int scoreCandidate(String songName, String singerName, String title, String artist,
                               long itemDurationMs, long durationMs, int index) {
        int score = 0;
        if (fuzzyEquals(songName, title)) {
            score += 10;
        } else if (fuzzyContains(songName, title)) {
            score += 5;
        }
        if (artist != null && !artist.isEmpty()) {
            if (fuzzyEquals(singerName, artist)) {
                score += 10;
            } else if (fuzzyContains(singerName, artist)) {
                score += 5;
            }
        }
        if (durationMs > 0 && itemDurationMs > 0) {
            long diff = Math.abs(itemDurationMs - durationMs);
            if (diff < 3000) {
                score += 8;
            } else if (diff < 5000) {
                score += 5;
            } else if (diff < 10000) {
                score += 2;
            }
        }
        if (index == 0) {
            score++;
        }
        return score;
    }

    private String normalizeString(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase()
                .replaceAll("[\\s\\-_()（）【】\\[\\].,，。!！?？'\"`~@#$%^&*+=/\\\\|<>]", "")
                .trim();
    }

    private boolean fuzzyContains(String a, String b) {
        String na = normalizeString(a);
        String nb = normalizeString(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        return na.contains(nb) || nb.contains(na);
    }

    private boolean fuzzyEquals(String a, String b) {
        return normalizeString(a).equals(normalizeString(b));
    }

    public Bitmap fetchCover(String title, String artist) {
        if (title == null || title.isEmpty()) {
            return null;
        }
        try {
            String keyword = artist != null && !artist.isEmpty() ? title + " " + artist : title;
            String keywordEnc = URLEncoder.encode(keyword, "UTF-8");
            String searchUrl = "https://music.163.com/api/search/get/web?s="
                    + keywordEnc + "&type=1&offset=0&total=true&limit=5";
            String searchResp = httpGet(searchUrl, "https://music.163.com");
            if (searchResp == null || searchResp.isEmpty()) {
                return null;
            }
            JSONObject searchJson = new JSONObject(searchResp);
            JSONObject result = searchJson.optJSONObject("result");
            if (result == null) {
                return null;
            }
            JSONArray songs = result.optJSONArray("songs");
            if (songs == null || songs.length() == 0) {
                return null;
            }
            String bestCoverUrl = null;
            int bestScore = -1;
            for (int i = 0; i < songs.length(); i++) {
                JSONObject item = songs.getJSONObject(i);
                String songName = item.optString("name", "");
                JSONArray artists = item.optJSONArray("artists");
                String artistName = (artists == null || artists.length() <= 0)
                        ? "" : artists.getJSONObject(0).optString("name", "");
                JSONObject album = item.optJSONObject("album");
                String picUrl = album != null ? album.optString("picUrl", "") : "";
                int score = 0;
                if (songName.equalsIgnoreCase(title)) {
                    score += 10;
                } else if (songName.contains(title) || title.contains(songName)) {
                    score += 5;
                }
                if (artist != null && !artist.isEmpty()) {
                    if (artistName.equalsIgnoreCase(artist)) {
                        score += 10;
                    } else if (artistName.contains(artist) || artist.contains(artistName)) {
                        score += 5;
                    }
                }
                if (i == 0) {
                    score++;
                }
                if (score > bestScore && !picUrl.isEmpty()) {
                    bestScore = score;
                    bestCoverUrl = picUrl;
                }
            }
            if (bestCoverUrl == null || bestCoverUrl.isEmpty()) {
                return null;
            }
            Log.d(TAG, "在线搜索封面成功: " + bestCoverUrl + " (score: " + bestScore + ")");
            URL url = new URL(bestCoverUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            if (conn.getResponseCode() == 200) {
                Bitmap bitmap = BitmapFactory.decodeStream(conn.getInputStream());
                conn.disconnect();
                return bitmap;
            }
            conn.disconnect();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "在线搜索封面失败: " + e.getMessage());
            return null;
        }
    }

    public void clearCache() {
        try {
            File dir = new File(cacheDir);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".lrc")) {
                        f.delete();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
