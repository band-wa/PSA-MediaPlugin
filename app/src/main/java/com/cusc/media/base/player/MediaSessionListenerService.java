package com.cusc.media.base.player;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.v4.media.MediaMetadataCompat;
import android.util.Log;

import com.cusc.media.lyric.OnlineLyricsFetcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class MediaSessionListenerService extends NotificationListenerService {
    private static final String TAG = "MusicProgressListener";
    private static final long MAX_CACHE_SIZE = 10 * 1024 * 1024; // 10MB
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaController mMediaController;
    private String currentPlayingPackage;
    private MediaSessionManager sessionManager;
    private ComponentName listenerComponent;
    private static MediaSessionListenerService instance;
    private MediaInfoCallback mediaInfoCallback;
    private OnlineLyricsFetcher onlineLyricsFetcher;
    private String lastCoverFetchKey;

    // 缓存最后一次收到的媒体信息，供 MusicService 重新连接时立即恢复显示
    private String lastTitle;
    private String lastArtist;
    private long lastDuration;
    private String lastAlbumArtUri;
    private String lastPackageName;

    public static MediaSessionListenerService getInstance() {
        return instance;
    }

    public void setMediaInfoCallback(MediaInfoCallback callback) {
        this.mediaInfoCallback = callback;
        if (callback != null) {
            if (lastTitle != null) {
                callback.onMediaInfoUpdated(lastTitle, lastArtist, lastDuration, lastAlbumArtUri, null);
                Log.d(TAG, "Cached media info pushed to new callback: " + lastTitle + " - " + lastArtist);
            }
            // 同步推送当前 MediaController，确保 MusicService 重连后立即可以发送控制指令
            callback.onMediaControllerChanged(mMediaController);
            if (lastPackageName != null) {
                callback.onPackageChanged(lastPackageName);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        onlineLyricsFetcher = new OnlineLyricsFetcher(this);
        listenerComponent = new ComponentName(this, MediaSessionListenerService.class);

        // 若 MusicService 已在运行（比如本服务重启），主动让它重新注册回调，
        // 避免 mediaInfoCallback 为 null 导致媒体信息无法同步
        MusicService musicService = MusicService.getInstance();
        if (musicService != null) {
            Log.d(TAG, "MusicService already running, triggering callback re-register");
            musicService.reRegisterCallback();
        } else {
            // MusicService 尚未启动，正常拉起
            Intent serviceIntent = new Intent();
            serviceIntent.setClassName(
                    "com.cusc.media",
                    "com.cusc.media.base.player.MusicService"
            );
            startForegroundService(serviceIntent);
        }
    }

    @Override
    public void onDestroy() {
        // 先清理回调和引用，再关闭线程池
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mControllerCallback);
            mMediaController = null;
        }
        if (sessionManager != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(mSessionsChangedListener);
            } catch (Exception ignored) {
            }
            sessionManager = null;
        }
        ioExecutor.shutdown();
        instance = null;
        super.onDestroy();
    }

    public void onMusicStateChanged(PlaybackState state) {
        if (state != null) {
            long currentPosition = state.getPosition();
            Log.d(TAG, "[" + currentPlayingPackage + "] Current position: " + currentPosition + " ms");
            if (mediaInfoCallback != null) {
                mediaInfoCallback.onPlaybackStateChanged(state);
            }
        }
    }

    // 专辑图片URI的获取和传递，并缓存供回调重连时使用
    // 优先读取 ALBUM_ART_URI（QQ音乐等），URI 为空时回退到读取 Bitmap 并落盘（汽水音乐等）
    public void onMusicMetadataChanged(MediaMetadata metadata) {
        if (metadata != null) {
            applyMetadata(metadata, null);
        }
    }

    private void applyMetadata(MediaMetadata metadata, String coverUriOverride) {
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String filePath = extractFilePath(metadata);

        String albumArtUri = coverUriOverride;
        if (albumArtUri == null) {
            // 1. 优先读取 URI（QQ音乐、网易云等直接提供 URI 的播放器）
            albumArtUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);

            // 2. URI 为空时，回退到读取 Bitmap（汽水音乐等将封面直接嵌入 Bitmap 的播放器）
            if (albumArtUri == null) {
                Bitmap bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                // 部分播放器使用 METADATA_KEY_ART 字段
                if (bitmap == null) {
                    bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                }
                if (bitmap != null) {
                    Log.d(TAG, "[" + currentPlayingPackage + "] URI is null, falling back to Bitmap");
                    // 用封面位图内容哈希命名，保证 URI 随封面内容变化：
                    // 汽水音乐切歌时可能先推送"新标题+旧封面"，稍后再推送"新标题+正确封面"，
                    // 内容寻址能让后一帧的正确封面得到不同 URI，从而不被 unchanged 跳过、能被下游刷新。
                    albumArtUri = saveBitmapToCache(bitmap, computeBitmapHash(bitmap));
                }
            }
        }

        // 四个字段均与上次一致时，属于播放器重复推送，直接跳过
        boolean unchanged = duration == lastDuration
                && equals(title, lastTitle)
                && equals(artist, lastArtist)
                && equals(albumArtUri, lastAlbumArtUri);
        if (unchanged) {
            Log.d(TAG, "[" + currentPlayingPackage + "] Metadata unchanged, skip");
            return;
        }

        lastTitle = title;
        lastArtist = artist;
        lastDuration = duration;
        lastAlbumArtUri = albumArtUri;

        Log.d(TAG, "[" + currentPlayingPackage + "] Song: " + title + " - " + artist);
        Log.d(TAG, "[" + currentPlayingPackage + "] Total duration: " + duration + " ms");
        Log.d(TAG, "[" + currentPlayingPackage + "] Album art URI: " + (albumArtUri != null ? albumArtUri : "None"));

        if (mediaInfoCallback != null) {
            mediaInfoCallback.onMediaInfoUpdated(title, artist, duration, albumArtUri, filePath);
        }

        scheduleOnlineCoverFallback(metadata, title, artist);
    }

    private void scheduleOnlineCoverFallback(MediaMetadata metadata, String title, String artist) {
        if (lastAlbumArtUri != null || title == null || title.isEmpty()) {
            return;
        }
        String fetchKey = title + "|" + artist;
        if (fetchKey.equals(lastCoverFetchKey)) {
            return;
        }
        lastCoverFetchKey = fetchKey;
        final MediaMetadata metadataRef = metadata;
        try {
            ioExecutor.execute(() -> {
                Bitmap cover = onlineLyricsFetcher.fetchCover(title, artist);
                if (cover == null) {
                    return;
                }
                String uri = saveBitmapToCache(cover, computeBitmapHash(cover));
                mainHandler.post(() -> {
                    if (instance != null && mediaInfoCallback != null
                            && equals(title, lastTitle) && lastAlbumArtUri == null) {
                        Log.d(TAG, "Online cover fetched: " + uri);
                        applyMetadata(metadataRef, uri);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Could not schedule cover fetch: executor is shut down");
        }
    }

    private static String extractFilePath(MediaMetadata metadata) {
        try {
            if (metadata.getDescription() != null && metadata.getDescription().getMediaUri() != null) {
                String uri = metadata.getDescription().getMediaUri().toString();
                if (uri.startsWith("file://")) {
                    return uri.substring(7);
                }
                if (uri.startsWith("/")) {
                    return uri;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            String uri = metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI);
            if (uri != null && uri.startsWith("file://")) {
                return uri.substring(7);
            }
            if (uri != null && uri.startsWith("/")) {
                return uri;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 计算封面位图的内容签名：缩放到 16x16 后对像素做哈希。
     * 开销很小，且与封面内容一一对应——相同封面得到相同签名，不同封面得到不同签名。
     */
    private static int computeBitmapHash(Bitmap bitmap) {
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 16, 16, true);
        int[] pixels = new int[16 * 16];
        scaled.getPixels(pixels, 0, 16, 0, 0, 16, 16);
        if (scaled != bitmap) {
            scaled.recycle();
        }
        return Arrays.hashCode(pixels);
    }

    /**
     * 将 Bitmap 保存到应用缓存目录，返回 file:// URI 字符串。
     * 文件名由封面内容哈希决定（内容寻址）：同名 ⇒ 同内容，因此"文件已存在则跳过写入"是安全且正确的；
     * 不同封面必得不同文件名，从根本上消除"旧封面占用新歌文件名"的问题。
     * 实际的写盘和清理操作在后台线程执行，避免阻塞主线程。
     */
    private String saveBitmapToCache(Bitmap bitmap, int contentHash) {
        String fileName = "album_art_" + Math.abs(contentHash) + ".jpg";
        File cacheFile = new File(getCacheDir(), fileName);
        String uri = cacheFile.toURI().toString();

        if (ioExecutor.isShutdown()) {
            return uri;
        }

        try {
            if (!cacheFile.exists()) {
                // 复制一份 Bitmap 引用，确保在异步线程处理时安全
                ioExecutor.execute(() -> {
                    if (!cacheFile.exists()) {
                        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                            Log.d(TAG, "Album art saved to cache: " + cacheFile.getAbsolutePath());
                            cleanupCache();
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to save album art bitmap to cache", e);
                        }
                    }
                });
            } else {
                // 已存在则仅更新访问时间
                ioExecutor.execute(() -> cacheFile.setLastModified(System.currentTimeMillis()));
            }
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Could not schedule cache task: executor is shut down");
        }

        return uri;
    }

    /**
     * 清理缓存目录，确保总大小不超过 MAX_CACHE_SIZE (10MB)。
     * 采用 LRU 策略，优先删除最久未使用的文件。
     */
    private void cleanupCache() {
        File[] files = getCacheDir().listFiles((dir, name) -> name.startsWith("album_art_"));
        if (files == null || files.length == 0) return;

        long currentSize = 0;
        for (File file : files) {
            currentSize += file.length();
        }

        if (currentSize <= MAX_CACHE_SIZE) return;

        // 按最后修改时间排序（从旧到新）
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));

        for (File file : files) {
            long fileSize = file.length();
            if (file.delete()) {
                currentSize -= fileSize;
                Log.d(TAG, "Cache size limit exceeded, deleted: " + file.getName());
            }
            if (currentSize <= MAX_CACHE_SIZE) break;
        }
    }

    private static boolean equals(String a, String b) {
        return Objects.equals(a, b);
    }

    private final MediaController.Callback mControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            onMusicStateChanged(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            onMusicMetadataChanged(metadata);
        }

        @Override
        public void onSessionDestroyed() {
            Log.d(TAG, "Session destroyed: " + currentPlayingPackage);
            if (mMediaController != null) {
                mMediaController.unregisterCallback(mControllerCallback);
                mMediaController = null;
            }
            refreshSessions();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsChangedListener =
            controllers -> refreshSessions();

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "Notification listener service connected");
        sessionManager = (MediaSessionManager) getSystemService(MediaSessionManager.class);
        if (sessionManager != null) {
            try {
                sessionManager.addOnActiveSessionsChangedListener(mSessionsChangedListener, listenerComponent);
                Log.d(TAG, "OnActiveSessionsChangedListener registered");
            } catch (Exception e) {
                Log.w(TAG, "register sessions listener failed: " + e.getMessage());
            }
        }
        refreshSessions();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (mMediaController == null || !isPlaying(mMediaController)) {
            refreshSessions();
        }
        if (mMediaController == null) {
            handleFallbackNotification(sbn);
        }
    }

    private void refreshSessions() {
        if (sessionManager == null) {
            sessionManager = (MediaSessionManager) getSystemService(MediaSessionManager.class);
        }
        if (sessionManager == null) {
            return;
        }
        List<MediaController> controllers;
        try {
            controllers = sessionManager.getActiveSessions(listenerComponent);
        } catch (SecurityException e) {
            Log.w(TAG, "getActiveSessions SecurityException: " + e.getMessage());
            return;
        } catch (Exception e) {
            Log.w(TAG, "getActiveSessions failed: " + e.getMessage());
            return;
        }
        int size = controllers != null ? controllers.size() : 0;
        Log.d(TAG, "refreshSessions: " + size + " active session(s)");
        if (controllers == null || controllers.isEmpty()) {
            return;
        }
        MediaController selected = selectController(controllers);
        attachController(selected);
    }

    private MediaController selectController(List<MediaController> controllers) {
        if (mMediaController != null && isPlaying(mMediaController)) {
            for (MediaController controller : controllers) {
                if (controller.getSessionToken().equals(mMediaController.getSessionToken())) {
                    return mMediaController;
                }
            }
        }
        for (MediaController controller : controllers) {
            if (isPlaying(controller)) {
                return controller;
            }
        }
        return controllers.get(0);
    }

    private void attachController(MediaController controller) {
        if (controller == null) {
            return;
        }
        if (mMediaController != null
                && controller.getSessionToken().equals(mMediaController.getSessionToken())) {
            return;
        }
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mControllerCallback);
        }
        mMediaController = controller;
        currentPlayingPackage = controller.getPackageName();
        lastPackageName = currentPlayingPackage;
        if (mediaInfoCallback != null) {
            mediaInfoCallback.onMediaControllerChanged(mMediaController);
            mediaInfoCallback.onPackageChanged(currentPlayingPackage);
        }
        Log.d(TAG, "Connected to media session: " + currentPlayingPackage);
        try {
            mMediaController.registerCallback(mControllerCallback);
        } catch (Exception e) {
            Log.w(TAG, "registerCallback failed: " + e.getMessage());
        }
        PlaybackState state = mMediaController.getPlaybackState();
        MediaMetadata metadata = mMediaController.getMetadata();
        if (state != null) onMusicStateChanged(state);
        if (metadata != null) onMusicMetadataChanged(metadata);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (mMediaController != null && sbn.getPackageName().equals(currentPlayingPackage)
                && !isPlaying(mMediaController)) {
            Log.d(TAG, "Notification removed for idle session: " + sbn.getPackageName());
            refreshSessions();
        }
    }

    private void handleFallbackNotification(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) {
            return;
        }
        Bundle extras = notification.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null || title.toString().isEmpty()) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (!isMusicPackage(pkg)) {
            return;
        }
        String titleStr = title.toString();
        if (!isValidSongTitle(titleStr)) {
            Log.d(TAG, "Fallback title filtered: " + titleStr);
            return;
        }
        String artistStr = text != null ? text.toString() : "";
        Bitmap cover = extractNotificationCover(notification, extras);
        String coverUri = cover != null ? saveBitmapToCache(cover, computeBitmapHash(cover)) : null;

        lastTitle = titleStr;
        lastArtist = artistStr;
        lastDuration = 0;
        lastAlbumArtUri = coverUri;
        lastPackageName = pkg;
        if (mediaInfoCallback != null) {
            mediaInfoCallback.onMediaControllerChanged(null);
            mediaInfoCallback.onPackageChanged(pkg);
            mediaInfoCallback.onMediaInfoUpdated(titleStr, artistStr, 0, coverUri, null);
        }
        Log.d(TAG, "Fallback info from notification: " + titleStr + " - " + artistStr
                + " (pkg: " + pkg + ")");
    }

    private static boolean isPlaying(MediaController controller) {
        if (controller == null) return false;
        PlaybackState state = controller.getPlaybackState();
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private static boolean isMusicPackage(String pkg) {
        if (pkg == null) {
            return false;
        }
        return pkg.contains("qqmusic") || pkg.contains("luna") || pkg.contains("netease")
                || pkg.contains("cloudmusic") || pkg.contains("kugou") || pkg.contains("kuwo")
                || pkg.contains("music") || pkg.contains("player");
    }

    private static boolean isValidSongTitle(String title) {
        if (title == null || title.isEmpty() || title.length() > 50 || title.contains("\n")) {
            return false;
        }
        String lower = title.toLowerCase();
        String[] invalidKeywords = {"正在运行", "运行中", "后台运行", "服务运行", "点击进入", "点击查看",
                "查看详情", "点击管理", "后台播放", "正在后台", "通知栏", "状态栏", "已连接", "连接中",
                "正在连接", "下载中", "正在下载", "上传中", "正在更新", "更新中", "安装中", "正在扫描",
                "扫描中", "正在加载", "无可用", "暂无", "没有更多"};
        for (String keyword : invalidKeywords) {
            if (lower.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    private Bitmap extractNotificationCover(Notification notification, Bundle extras) {
        if (notification == null && extras == null) {
            return null;
        }
        Bitmap largeIcon = null;
        if (extras != null) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    largeIcon = extras.getParcelable(Notification.EXTRA_LARGE_ICON, Bitmap.class);
                } else {
                    largeIcon = extras.getParcelable(Notification.EXTRA_LARGE_ICON);
                }
            } catch (Exception ignored) {
            }
            if (largeIcon == null) {
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        largeIcon = extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap.class);
                    } else {
                        largeIcon = extras.getParcelable(Notification.EXTRA_PICTURE);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (largeIcon != null) {
            return largeIcon;
        }
        if (notification != null && notification.largeIcon != null) {
            try {
                Object iconObj = notification.largeIcon;
                if (iconObj instanceof Bitmap) {
                    return (Bitmap) iconObj;
                }
                if (iconObj instanceof Icon) {
                    Drawable drawable = ((Icon) iconObj).loadDrawable(this);
                    if (drawable != null && drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0) {
                        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                                drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(bitmap);
                        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                        drawable.draw(canvas);
                        return bitmap;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
