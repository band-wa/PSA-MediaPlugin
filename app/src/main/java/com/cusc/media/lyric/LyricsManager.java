package com.cusc.media.lyric;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.util.LruCache;

import com.cusc.bean.media.Lrc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LyricsManager {
    private static final String TAG = "LyricsManager";
    public static final String KEY_MUSIC_LRC_LIST = "MUSIC_LRC_LIST";
    public static final int RESULT_OK = 200;
    public static final int RESULT_EMPTY = 0;
    private static final long FAILED_TTL_MS = 60000;

    public interface ResultSender {
        void send(int resultCode, Bundle data);
    }

    private static class SongMeta {
        final String mediaId;
        final String title;
        final String artist;
        final long duration;
        final String playerPackage;
        final String filePath;

        SongMeta(String mediaId, String title, String artist, long duration, String playerPackage, String filePath) {
            this.mediaId = mediaId;
            this.title = title;
            this.artist = artist;
            this.duration = duration;
            this.playerPackage = playerPackage;
            this.filePath = filePath;
        }
    }

    private final OnlineLyricsFetcher fetcher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LruCache<String, List<Lrc>> cache = new LruCache<>(8);
    private final Map<String, Long> failedAt = new HashMap<>();
    private SongMeta current;

    private static volatile LyricsManager sInstance;

    public static LyricsManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (LyricsManager.class) {
                if (sInstance == null) {
                    sInstance = new LyricsManager(context);
                }
            }
        }
        return sInstance;
    }

    private LyricsManager(Context context) {
        fetcher = OnlineLyricsFetcher.getInstance(context.getApplicationContext());
    }

    public void setCurrent(String mediaId, String title, String artist, long duration,
                           String playerPackage, String filePath) {
        if (mediaId == null || mediaId.isEmpty() || title == null || title.isEmpty()) {
            return;
        }
        SongMeta meta = new SongMeta(mediaId, title, artist, duration, playerPackage, filePath);
        boolean songChanged = current == null || !meta.mediaId.equals(current.mediaId);
        current = meta;
        if (songChanged || cache.get(meta.mediaId) == null) {
            enqueuePrefetch(meta);
        }
    }

    public void request(final String mediaId, final ResultSender sender) {
        executor.execute(() -> {
            if (mediaId == null || mediaId.isEmpty()) {
                sender.send(RESULT_EMPTY, new Bundle());
                return;
            }
            if (current == null || !mediaId.equals(current.mediaId)) {
                Log.d(TAG, "request for stale/unknown mediaId=" + mediaId);
                sender.send(RESULT_EMPTY, new Bundle());
                return;
            }
            List<Lrc> cached = cache.get(mediaId);
            if (cached != null) {
                Log.d(TAG, "request cache hit mediaId=" + mediaId + " lines=" + cached.size());
                sender.send(RESULT_OK, buildData(cached));
                return;
            }
            if (isRecentlyFailed(mediaId)) {
                sender.send(RESULT_EMPTY, new Bundle());
                return;
            }
            List<Lrc> list = resolve(current);
            putResult(current, list);
            Log.d(TAG, "request resolved mediaId=" + mediaId + " title=" + current.title
                    + " lines=" + (list == null ? 0 : list.size()));
            sender.send(RESULT_OK, buildData(list));
        });
    }

    private void enqueuePrefetch(final SongMeta meta) {
        executor.execute(() -> {
            if (cache.get(meta.mediaId) != null || isRecentlyFailed(meta.mediaId)) {
                return;
            }
            List<Lrc> list = resolve(meta);
            putResult(meta, list);
            Log.d(TAG, "prefetch mediaId=" + meta.mediaId + " title=" + meta.title
                    + " lines=" + (list == null ? 0 : list.size()));
        });
    }

    private Bundle buildData(List<Lrc> list) {
        Bundle data = new Bundle();
        if (list != null && !list.isEmpty()) {
            data.putParcelableArrayList(KEY_MUSIC_LRC_LIST, new ArrayList<>(list));
        }
        return data;
    }

    private void putResult(SongMeta meta, List<Lrc> list) {
        if (list != null && !list.isEmpty()) {
            cache.put(meta.mediaId, list);
            failedAt.remove(meta.mediaId);
        } else {
            failedAt.put(meta.mediaId, System.currentTimeMillis());
        }
    }

    private boolean isRecentlyFailed(String mediaId) {
        Long at = failedAt.get(mediaId);
        return at != null && System.currentTimeMillis() - at < FAILED_TTL_MS;
    }

    private List<Lrc> resolve(SongMeta meta) {
        List<Lrc> list = null;
        boolean streaming = isStreamingPkg(meta.playerPackage);
        if (!streaming && meta.filePath != null && !meta.filePath.isEmpty()) {
            String lrcPath = LyricParser.findLyricFile(meta.filePath);
            if (lrcPath != null) {
                Log.d(TAG, "本地歌词: " + lrcPath);
                list = LyricParser.parseFile(lrcPath);
            }
        }
        if ((list == null || list.isEmpty()) && !streaming) {
            String lrcPath = LyricParser.searchLyricBySongName(meta.title);
            if (lrcPath != null) {
                Log.d(TAG, "按歌名找到本地歌词: " + lrcPath);
                list = LyricParser.parseFile(lrcPath);
            }
        }
        if (list == null || list.isEmpty()) {
            String lrc = fetcher.fetchLyrics(meta.title, meta.artist, meta.duration, meta.playerPackage);
            if (lrc != null) {
                list = LyricParser.parseContent(lrc);
            }
        }
        return list != null && !list.isEmpty() ? list : null;
    }

    private static boolean isStreamingPkg(String pkg) {
        if (pkg == null) {
            return false;
        }
        String lower = pkg.toLowerCase();
        return lower.contains("kuwo") || lower.contains("kugou") || lower.contains("netease")
                || lower.contains("cloudmusic") || lower.contains("qqmusic")
                || lower.contains("tencent.qqmusic") || lower.contains("spotify")
                || lower.contains("apple.android.music") || lower.contains("luna");
    }
}
