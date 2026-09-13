package com.cusc.media.base.player;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.media.session.MediaController;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.annotation.RequiresApi;
import androidx.media.MediaBrowserServiceCompat;
import androidx.core.app.NotificationCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.content.Context;

import com.cusc.media.lyric.LyricsManager;

import java.util.List;

public class MusicService extends MediaBrowserServiceCompat implements MediaInfoCallback {
    private static final String TAG = "SimpleMusicService";
    private static final String MY_MEDIA_ROOT_ID = "media_root_id";
    private static final String CHANNEL_ID = "channel_1";
    private static final String COMMAND_GET_AUDIO_LRC = "com.cusc.media.command.getAudioLrc";
    private static final String KEY_MUSIC_ID_L = "MUSIC_ID_L";

    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder stateBuilder;
    private QueueManager mQueueManager;
    private AlbumArtServer mAlbumArtServer;
    private LyricsManager lyricsManager;

    /** 当前目标媒体 App 的 MediaController，由 MediaSessionListenerService 回调注入 */
    private MediaController mMediaController;

    /** 单例，供 MediaSessionListenerService 在启动后主动触发回调注册 */
    private static MusicService instance;

    private MusicServiceCallback mMusicServiceCallback;

    public interface MusicServiceCallback {
        void onPackageChanged(String packageName);
    }

    public void setMusicServiceCallback(MusicServiceCallback callback) {
        this.mMusicServiceCallback = callback;
        if (callback != null && lastPackageName != null) {
            callback.onPackageChanged(lastPackageName);
        }
    }

    public static MusicService getInstance() {
        return instance;
    }

    // 存储从MediaSessionListenerService获取的最新媒体信息
    private String latestTitle = "默认歌曲";
    private String latestArtist = "默认歌手";
    private long latestDuration = 180000; // 默认3分钟
    private String latestAlbumArtUri = null;
    private String lastPackageName = null;
    private String currentFilePath = null;
    // 用于生成唯一的mediaId（默认值避免桌面读取时为 null）
    private String currentMediaId = "0";
    // 上次已持久化的歌曲键（title+artist），用于判断是否为真正的切歌，避免同歌封面纠正时多余写盘
    private String lastPersistedSongKey = null;

    private static final String PREFS_NAME = "MusicServicePrefs";
    private static final String PREF_KEY_TITLE = "last_title";
    private static final String PREF_KEY_ARTIST = "last_artist";
    private static final String PREF_KEY_DURATION = "last_duration";
    private static final String PREF_KEY_ALBUM_ART = "last_album_art";

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "onCreate");

        // 初始化并启动 HTTP 服务
        mAlbumArtServer = new AlbumArtServer(this);
        mAlbumArtServer.start();

        // 步骤0：恢复上次播放的元数据
        lyricsManager = new LyricsManager(this);
        restoreLastMediaInfo();

        // 步骤1：初始化MediaSession
        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS |
                        MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                        MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS
        );
        setSessionToken(mediaSession.getSessionToken());

        // 步骤2：初始化播放状态
        stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_STOP |
                                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                PlaybackStateCompat.ACTION_SEEK_TO |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());

        // 步骤3：初始化QueueManager
        mQueueManager = new QueueManager(this);

        // 步骤4：设置MediaSession回调
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                super.onPlay();
                Log.d(TAG, "onPlay");
                if (mMediaController != null) {
                    mMediaController.getTransportControls().play();
                }
            }

            @Override
            public void onPause() {
                super.onPause();
                Log.d(TAG, "onPause");
                if (mMediaController != null) {
                    mMediaController.getTransportControls().pause();
                }
            }

            @Override
            public void onStop() {
                super.onStop();
                Log.d(TAG, "onStop");
            }

            @Override
            public void onSkipToNext() {
                super.onSkipToNext();
                Log.d(TAG, "Next");
                if (mMediaController != null) {
                    mMediaController.getTransportControls().skipToNext();
                }
            }

            @Override
            public void onSkipToPrevious() {
                super.onSkipToPrevious();
                Log.d(TAG, "Previous");
                if (mMediaController != null) {
                    mMediaController.getTransportControls().skipToPrevious();
                }
            }

            @Override
            public void onSeekTo(long pos) {
                super.onSeekTo(pos);
                Log.d(TAG, "SeekTo: " + pos);
                if (mMediaController != null) {
                    mMediaController.getTransportControls().seekTo(pos);
                }
            }

            @Override
            public void onCommand(String command, Bundle args, ResultReceiver cb) {
                super.onCommand(command, args, cb);
                if (!COMMAND_GET_AUDIO_LRC.equals(command) || cb == null) {
                    return;
                }
                long musicId = args != null ? args.getLong(KEY_MUSIC_ID_L) : 0;
                String requestMediaId = String.valueOf(musicId);
                Log.d(TAG, "onCommand getAudioLrc, mediaId=" + requestMediaId);
                lyricsManager.request(requestMediaId, cb::send);
            }
        });

        // 步骤5：注册MediaSessionListenerService的回调
        registerMediaInfoCallback();

        // 步骤6：推送元数据
        updateMediaMetadata();

        // 步骤7：前台通知
        initNotification();
    }

    /**
     * 由 MediaSessionListenerService 在其 onCreate 完成后主动调用，
     * 确保 MusicService 重启后能及时重新注册回调，避免 callback 为 null 导致数据丢失。
     */
    public void reRegisterCallback() {
        Log.d(TAG, "reRegisterCallback called by MediaSessionListenerService");
        registerMediaInfoCallback();
    }

    // 注册媒体信息回调
    private void registerMediaInfoCallback() {
        MediaSessionListenerService listenerService = MediaSessionListenerService.getInstance();
        if (listenerService != null) {
            listenerService.setMediaInfoCallback(this);
            Log.d(TAG, "Registered media info callback");
        } else {
            // MediaSessionListenerService 尚未启动，尝试启动它；
            // 启动完成后它的 onCreate 会反向调用 reRegisterCallback() 完成注册
            Log.w(TAG, "MediaSessionListenerService not started, starting it...");
            startService(new Intent(this, MediaSessionListenerService.class));
        }
    }

    private void restoreLastMediaInfo() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        latestTitle = prefs.getString(PREF_KEY_TITLE, "默认歌曲");
        latestArtist = prefs.getString(PREF_KEY_ARTIST, "默认歌手");
        latestDuration = prefs.getLong(PREF_KEY_DURATION, 180000);
        latestAlbumArtUri = prefs.getString(PREF_KEY_ALBUM_ART, null);
        
        // 恢复 currentMediaId，计算方式需与 onMediaInfoUpdated 一致（纳入封面标识）
        String artKey = (latestAlbumArtUri != null) ? latestAlbumArtUri : "";
        String uniqueKey = latestTitle + latestArtist + artKey;
        currentMediaId = String.valueOf(Math.abs(uniqueKey.hashCode()));
        lastPersistedSongKey = latestTitle + latestArtist;
        
        Log.d(TAG, "Restored media info: " + latestTitle + " - " + latestArtist);

        if (lyricsManager != null) {
            lyricsManager.setCurrent(currentMediaId, latestTitle, latestArtist, latestDuration,
                    lastPackageName, null);
        }
    }

    private void saveLastMediaInfo() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_KEY_TITLE, latestTitle)
                .putString(PREF_KEY_ARTIST, latestArtist)
                .putLong(PREF_KEY_DURATION, latestDuration)
                .putString(PREF_KEY_ALBUM_ART, latestAlbumArtUri)
                .apply();
        Log.d(TAG, "Saved media info to prefs");
    }

    @Override
    public void onMediaInfoUpdated(String title, String artist, long duration, String albumArtUri, String filePath) {
        Log.d(TAG, "Received latest media info: " + title + "-" + artist + ", duration: " + duration);

        // 更新本地存储的最新媒体信息
        if (title != null) this.latestTitle = title;
        if (artist != null) this.latestArtist = artist;
        if (duration > 0) this.latestDuration = duration;
        this.latestAlbumArtUri = albumArtUri;

        // 使用 title + artist + 封面标识 的哈希值作为 mediaId。
        // 纳入封面标识（file://album_art_<内容哈希>.jpg 或 content:// URI，均为稳定、不含端口的标识）：
        // 当封面被纠正（同一首歌但封面 URI 变化）时 mediaId 随之改变，
        // 才能让原厂桌面卡片的 isSameMedia(mediaId) 去重判为不同、从而刷新封面。
        // 注意：不要用带随机端口的 http URL，避免服务重启导致 mediaId 抖动。
        String artKey = (latestAlbumArtUri != null) ? latestAlbumArtUri : "";
        String uniqueKey = latestTitle + latestArtist + artKey;
        String newMediaId = String.valueOf(Math.abs(uniqueKey.hashCode()));
        currentMediaId = newMediaId;
        currentFilePath = filePath;

        // 持久化判定仅看 title+artist 是否变化，避免同一首歌封面纠正时产生多余磁盘写入
        String songKey = latestTitle + latestArtist;
        boolean songMetaChanged = !songKey.equals(lastPersistedSongKey);

        // 如果是 file:// URI，转换为 HTTP URL 供 Launcher 读取
        String displayUri = mAlbumArtServer.getHttpUrl(latestAlbumArtUri);

        // 创建媒体项并更新队列
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(currentMediaId)
                .setTitle(latestTitle)
                .setSubtitle(latestArtist)
                .setIconUri(displayUri != null ? android.net.Uri.parse(displayUri) : null)
                .build();

        MediaBrowserCompat.MediaItem mediaItem = new MediaBrowserCompat.MediaItem(
                description,
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        );

        // 更新队列（只包含当前播放的歌曲）
        mQueueManager.updateCurrentSong(mediaItem);

        // 更新MediaSession元数据
        updateMediaMetadata();

        lyricsManager.setCurrent(currentMediaId, latestTitle, latestArtist, latestDuration,
                lastPackageName, currentFilePath);
        
        // 仅在歌曲切换时持久化，避免同一首歌的重复元数据回调触发多余磁盘写入
        if (songMetaChanged) {
            lastPersistedSongKey = songKey;
            saveLastMediaInfo();
        }
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
        if (state == null) return;
        
        // 使用带 updateTime 的 setState 方法，确保进度条同步准确
        // 直接透传原始 PlaybackState 的最后更新时间
        stateBuilder.setState(state.getState(), state.getPosition(), state.getPlaybackSpeed(), state.getLastPositionUpdateTime());
        mediaSession.setPlaybackState(stateBuilder.build());
        Log.d(TAG, "Sync playback state: state=" + state.getState() + ", pos=" + state.getPosition() + ", lastUpdateTime=" + state.getLastPositionUpdateTime());
    }

    @Override
    public void onPackageChanged(String packageName) {
        this.lastPackageName = packageName;
        if (mMusicServiceCallback != null) {
            mMusicServiceCallback.onPackageChanged(packageName);
        }
    }

    @Override
    public void onMediaControllerChanged(MediaController controller) {
        mMediaController = controller;
        Log.d(TAG, "MediaController updated: " + (controller != null ? controller.getPackageName() : "null"));
    }

    @SuppressLint("ForegroundServiceType")
    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_ID,
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID).build();
        startForeground(1, notification);
    }

    private void updatePlaybackState(int state) {
        stateBuilder.setState(state, 0, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void updateMediaMetadata() {
        String mediaId = (currentMediaId != null && !currentMediaId.isEmpty()) ? currentMediaId : "0";
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, latestTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, latestArtist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, latestDuration);

        if (latestAlbumArtUri != null) {
            // 转换为 HTTP URL 供 Launcher 读取
            String httpUrl = mAlbumArtServer.getHttpUrl(latestAlbumArtUri);
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, httpUrl);
        }

        MediaMetadataCompat metadata = metadataBuilder.build();
        mediaSession.setMetadata(metadata);
        Log.d(TAG, "Update MediaSession metadata: " + latestTitle + "-" + latestArtist + ", album art: " + mAlbumArtServer.getHttpUrl(latestAlbumArtUri));
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        Log.d(TAG, "onGetRoot: clientPackageName=" + clientPackageName);
        return new BrowserRoot(MY_MEDIA_ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowserCompat.MediaItem>> result) {
        Log.d(TAG, "onLoadChildren");
        result.sendResult(null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        return super.onBind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mAlbumArtServer != null) {
            mAlbumArtServer.stop();
        }
        instance = null;
        mMediaController = null;
        MediaSessionListenerService listenerService = MediaSessionListenerService.getInstance();
        if (listenerService != null) {
            listenerService.setMediaInfoCallback(null);
        }
        mediaSession.release();
    }

    public MediaSessionCompat getMediaSession() {
        return mediaSession;
    }
}
