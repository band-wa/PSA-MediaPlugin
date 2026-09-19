package com.cusc.media.base.player;

import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;

import com.cusc.bean.media.AudioInfo;

import java.util.ArrayList;
import java.util.List;

public class QueueManager {
    private static final String TAG = "QueueManager";
    private static final int MAX_PLAYLIST_SIZE = 500;
    private static final int MAX_HISTORY = 50;
    private static final String KEY_AUDIO_INFO_LIST = "AudioInfoList";
    private static final String KEY_AUDIO_INFO = "AudioInfo";
    private static final String KEY_AUDIO_INFO_TYPE = "AudioInfoType";
    private static final int AUDIO_TYPE_MUSIC = AudioInfo.AUDIO_TYPE_MUSIC;

    private final MusicService mMusicService;
    private boolean playlistMirrored;
    private int playlistSource = SOURCE_NONE;
    private String lastPlaylistSignature = "";
    private final List<AudioInfo> playHistory = new ArrayList<>();

    private static final int SOURCE_NONE = 0;
    private static final int SOURCE_APP = 1;
    private static final int SOURCE_HISTORY = 2;

    public QueueManager(MusicService musicService) {
        this.mMusicService = musicService;
    }

    public void updateCurrentSong(MediaBrowserCompat.MediaItem mediaItem) {
        if (mediaItem == null) {
            Log.w(TAG, "updateCurrentSong: media item is null");
            return;
        }
        if (playlistMirrored) {
            Log.d(TAG, "updateCurrentSong: playlist active, skip single-item queue");
            return;
        }

        List<MediaSessionCompat.QueueItem> queueItems = new ArrayList<>();

        String mediaId = mediaItem.getDescription().getMediaId();
        long queueId = 0;
        try {
            queueId = Long.parseLong(mediaId);
        } catch (NumberFormatException e) {
            Log.e(TAG, "mediaId is not a valid timestamp format: " + mediaId, e);
        }
        queueItems.add(new MediaSessionCompat.QueueItem(mediaItem.getDescription(), queueId));

        setQueue(queueItems);

        Log.d(TAG, "updateCurrentSong: Update current song to - " + mediaItem.getDescription().getTitle());
    }

    public void mirrorQueue(CharSequence queueTitle, List<MediaSession.QueueItem> queue) {
        if (queue == null || queue.size() <= 1) {
            return;
        }
        int size = Math.min(queue.size(), MAX_PLAYLIST_SIZE);
        List<MediaSessionCompat.QueueItem> items = new ArrayList<>(size);
        List<AudioInfo> infos = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            MediaSession.QueueItem src = queue.get(i);
            if (src == null || src.getDescription() == null) {
                continue;
            }
            MediaDescriptionCompat desc = MediaDescriptionCompat.fromMediaDescription(src.getDescription());
            AudioInfo info = audioInfoFrom(desc, src.getQueueId(), i);
            items.add(buildQueueItem(desc, info, src.getQueueId()));
            infos.add(info);
        }
        if (items.isEmpty()) {
            return;
        }
        String signature = queueTitle + "#" + items.size() + "#" + items.get(0).getQueueId();
        if (signature.equals(lastPlaylistSignature) && playlistMirrored) {
            return;
        }
        lastPlaylistSignature = signature;
        playlistMirrored = true;
        playlistSource = SOURCE_APP;
        applyPlaylist(queueTitle, items, buildAudioInfoList(queueTitle, infos,
                mMusicService.getActiveQueueItemId()));
    }

    public void resetPlaylist() {
        playHistory.clear();
        clearAppliedPlaylist();
    }

    public void addHistoryTrack(String mediaId, String title, String artist, long duration) {
        long audioId;
        try {
            audioId = Long.parseLong(mediaId);
        } catch (NumberFormatException e) {
            Log.w(TAG, "addHistory: bad mediaId " + mediaId);
            return;
        }
        for (int i = 0; i < playHistory.size(); i++) {
            if (playHistory.get(i).audioId == audioId) {
                return;
            }
        }
        Log.d(TAG, "addHistory: new " + audioId + " " + title);
        AudioInfo info = new AudioInfo();
        info.audioId = audioId;
        info.audioName = title != null ? title : "";
        info.singerName = artist != null ? artist : "";
        info.audioSubName = info.singerName;
        info.duration = duration;
        info.audioType = AUDIO_TYPE_MUSIC;
        playHistory.add(0, info);
        while (playHistory.size() > MAX_HISTORY) {
            playHistory.remove(playHistory.size() - 1);
        }
        if (playlistSource == SOURCE_APP) {
            return;
        }
        applyHistory();
    }

    private void applyHistory() {
        List<MediaSessionCompat.QueueItem> items = new ArrayList<>();
        for (int i = playHistory.size() - 1; i >= 0; i--) {
            AudioInfo info = playHistory.get(i);
            info.orderNum = playHistory.size() - 1 - i;
            MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                    .setMediaId(String.valueOf(info.audioId))
                    .setTitle(info.audioName)
                    .setSubtitle(info.singerName)
                    .build();
            items.add(buildQueueItem(desc, info, info.audioId));
        }
        long currentId = playHistory.isEmpty() ? -1 : playHistory.get(0).audioId;
        playlistMirrored = true;
        playlistSource = SOURCE_HISTORY;
        applyPlaylist(null, items, buildAudioInfoList(null, playHistory, currentId));
    }

    public boolean isHistoryPlaylist() {
        return playlistSource == SOURCE_HISTORY;
    }

    public AudioInfo findHistoryItem(long audioId) {
        for (AudioInfo info : playHistory) {
            if (info.audioId == audioId) {
                return info;
            }
        }
        return null;
    }

    public void handleOemAudioList(AudioInfoList audioInfoList) {
        if (audioInfoList == null || audioInfoList.audioInfoList == null
                || audioInfoList.audioInfoList.isEmpty()) {
            return;
        }
        int size = Math.min(audioInfoList.audioInfoList.size(), MAX_PLAYLIST_SIZE);
        List<MediaSessionCompat.QueueItem> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            AudioInfo info = audioInfoList.audioInfoList.get(i);
            MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                    .setMediaId(String.valueOf(info.audioId))
                    .setTitle(info.audioName)
                    .setSubtitle(info.singerName)
                    .setDescription(info.audioDes)
                    .setIconUri(info.audioCoverUrl != null ? Uri.parse(info.audioCoverUrl) : null)
                    .setMediaUri(info.audioPlayUrl != null ? Uri.parse(info.audioPlayUrl) : null)
                    .build();
            items.add(buildQueueItem(desc, info, info.audioId));
        }
        String signature = audioInfoList.title + "#" + items.size() + "#" + audioInfoList.clickAudioId;
        if (signature.equals(lastPlaylistSignature) && playlistMirrored) {
            return;
        }
        lastPlaylistSignature = signature;
        playlistMirrored = true;
        playlistSource = SOURCE_APP;
        applyPlaylist(audioInfoList.title, items, audioInfoList);
    }

    public void clearPlaylistQueue() {
        clearAppliedPlaylist();
        playHistory.clear();
        Log.d(TAG, "playlist queue cleared");
    }

    private void clearAppliedPlaylist() {
        MediaSessionCompat session = mMusicService.getMediaSession();
        if (session == null) {
            return;
        }
        lastPlaylistSignature = "";
        playlistMirrored = false;
        playlistSource = SOURCE_NONE;
        session.setQueue(new ArrayList<>());
        session.setQueueTitle("");
        session.setExtras(null);
        Log.d(TAG, "playlist applied queue cleared");
    }

    private void applyPlaylist(CharSequence queueTitle, List<MediaSessionCompat.QueueItem> items,
                               AudioInfoList audioInfoList) {
        MediaSessionCompat session = mMusicService.getMediaSession();
        if (session == null || items.isEmpty()) {
            return;
        }
        session.setQueueTitle(queueTitle);
        session.setQueue(items);
        Bundle extras = new Bundle();
        extras.putParcelable(KEY_AUDIO_INFO_LIST, audioInfoList);
        session.setExtras(extras);
        Log.d(TAG, "playlist applied, size=" + items.size() + ", title=" + queueTitle);
    }

    private AudioInfoList buildAudioInfoList(CharSequence queueTitle, List<AudioInfo> infos,
                                             long clickAudioId) {
        AudioInfoList list = new AudioInfoList();
        list.audioListType = AUDIO_TYPE_MUSIC;
        list.title = textOf(queueTitle);
        list.audioInfoList = infos;
        list.autoPlay = false;
        list.updateMetadata = false;
        list.total = infos.size();
        list.offset = 0;
        list.limit = infos.size();
        if (clickAudioId >= 0) {
            list.clickAudioId = clickAudioId;
        }
        return list;
    }

    private MediaSessionCompat.QueueItem buildQueueItem(MediaDescriptionCompat desc, AudioInfo info,
                                                        long queueId) {
        Bundle extra = new Bundle();
        extra.putParcelable(KEY_AUDIO_INFO, info);
        extra.putInt(KEY_AUDIO_INFO_TYPE, info.audioType);
        MediaDescriptionCompat.Builder builder = new MediaDescriptionCompat.Builder()
                .setMediaId(String.valueOf(queueId))
                .setTitle(desc.getTitle())
                .setSubtitle(desc.getSubtitle())
                .setDescription(desc.getDescription())
                .setIconUri(desc.getIconUri())
                .setMediaUri(desc.getMediaUri())
                .setExtras(extra);
        return new MediaSessionCompat.QueueItem(builder.build(), queueId);
    }

    private AudioInfo audioInfoFrom(MediaDescriptionCompat desc, long queueId, int order) {
        AudioInfo info = new AudioInfo();
        info.audioId = queueId;
        info.audioName = textOf(desc.getTitle());
        info.audioSubName = textOf(desc.getSubtitle());
        info.singerName = info.audioSubName;
        info.audioDes = textOf(desc.getDescription());
        info.audioCoverUrl = desc.getIconUri() != null ? desc.getIconUri().toString() : null;
        info.audioType = AUDIO_TYPE_MUSIC;
        info.orderNum = order;
        return info;
    }

    private static String textOf(CharSequence text) {
        return text != null ? text.toString() : "";
    }

    private void setQueue(List<MediaSessionCompat.QueueItem> queueItems) {
        MediaSessionCompat mediaSession = mMusicService.getMediaSession();
        if (mediaSession != null) {
            mediaSession.setQueue(queueItems);
            Log.d(TAG, "setQueue: current song count=" + queueItems.size());
        } else {
            Log.e(TAG, "setQueue: mediaSession is null, cannot update queue");
        }
    }
}
