package com.cusc.media.base.player;

import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

import java.util.List;

public interface MediaInfoCallback {
    void onMediaInfoUpdated(String title, String artist, long duration, String albumArtUri, String filePath);
    void onPlaybackStateChanged(PlaybackState state);
    void onPackageChanged(String packageName);

    /**
     * 当监听到的目标媒体 App 切换时回调，传入新的 MediaController。
     * 置 null 表示当前无活跃媒体会话。
     */
    void onMediaControllerChanged(MediaController controller);

    void onQueueUpdated(CharSequence queueTitle, List<MediaSession.QueueItem> queue);
}
