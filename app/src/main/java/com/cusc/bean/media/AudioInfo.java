package com.cusc.bean.media;

import android.os.Parcel;
import android.os.Parcelable;

public class AudioInfo implements Parcelable, Comparable<AudioInfo> {
    public static final int AUDIO_TYPE_MUSIC = 10;
    public static final int AUDIO_TYPE_WEB_RADIO = 20;
    public static final int AUDIO_TYPE_RADIO = 30;
    public static final int AUDIO_TYPE_BT_MUSIC = 40;
    public static final int AUDIO_TYPE_LOCAL_USB1 = 50;
    public static final int AUDIO_TYPE_LOCAL_USB2 = 51;
    public static final int AUDIO_TYPE_LOCAL_FAVORITE = 52;

    public static final Parcelable.Creator<AudioInfo> CREATOR = new Parcelable.Creator<AudioInfo>() {
        @Override
        public AudioInfo createFromParcel(Parcel source) {
            return new AudioInfo(source);
        }

        @Override
        public AudioInfo[] newArray(int size) {
            return new AudioInfo[size];
        }
    };

    public long uid;
    public long audioId;
    public String audioName;
    public String audioSubName;
    public String audioDes;
    public long singerId;
    public String singerName;
    public String singerCoverUrl;
    public boolean favourite;
    public boolean free;
    public long duration;
    public String audioCoverUrl;
    public String audioPlayUrl;
    public String kind;
    public int categoryId;
    public long updateTime;
    public long orderNum;
    public long audioSize;
    public long playCount;
    public long albumId;
    public String albumName;
    public String albumCoverUrl;
    public long albumPlayCount;
    public int audioType;
    public long playedSecs;
    public String kwDataSource;
    public int radioBandIndex;
    public int radioListType;
    public int hasAccompany;
    public long mvId;
    public int tryPlayable;
    public String buyUrl;
    public int playableCode;
    public String extra1;
    public String extra2;
    public String extra3;
    public String extra4;
    public String extra5;

    public AudioInfo() {
        this.mvId = -1L;
    }

    protected AudioInfo(Parcel in) {
        this.mvId = -1L;
        this.uid = in.readLong();
        this.audioId = in.readLong();
        this.audioName = in.readString();
        this.audioSubName = in.readString();
        this.audioDes = in.readString();
        this.singerId = in.readLong();
        this.singerName = in.readString();
        this.singerCoverUrl = in.readString();
        this.favourite = in.readByte() != 0;
        this.free = in.readByte() != 0;
        this.duration = in.readLong();
        this.audioCoverUrl = in.readString();
        this.audioPlayUrl = in.readString();
        this.kind = in.readString();
        this.categoryId = in.readInt();
        this.updateTime = in.readLong();
        this.orderNum = in.readLong();
        this.audioSize = in.readLong();
        this.playCount = in.readLong();
        this.albumId = in.readLong();
        this.albumName = in.readString();
        this.albumCoverUrl = in.readString();
        this.albumPlayCount = in.readLong();
        this.audioType = in.readInt();
        this.playedSecs = in.readLong();
        this.kwDataSource = in.readString();
        this.radioBandIndex = in.readInt();
        this.radioListType = in.readInt();
        this.hasAccompany = in.readInt();
        this.mvId = in.readLong();
        this.tryPlayable = in.readInt();
        this.buyUrl = in.readString();
        this.playableCode = in.readInt();
        this.extra1 = in.readString();
        this.extra2 = in.readString();
        this.extra3 = in.readString();
        this.extra4 = in.readString();
        this.extra5 = in.readString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeLong(this.uid);
        parcel.writeLong(this.audioId);
        parcel.writeString(this.audioName);
        parcel.writeString(this.audioSubName);
        parcel.writeString(this.audioDes);
        parcel.writeLong(this.singerId);
        parcel.writeString(this.singerName);
        parcel.writeString(this.singerCoverUrl);
        parcel.writeByte(this.favourite ? (byte) 1 : (byte) 0);
        parcel.writeByte(this.free ? (byte) 1 : (byte) 0);
        parcel.writeLong(this.duration);
        parcel.writeString(this.audioCoverUrl);
        parcel.writeString(this.audioPlayUrl);
        parcel.writeString(this.kind);
        parcel.writeInt(this.categoryId);
        parcel.writeLong(this.updateTime);
        parcel.writeLong(this.orderNum);
        parcel.writeLong(this.audioSize);
        parcel.writeLong(this.playCount);
        parcel.writeLong(this.albumId);
        parcel.writeString(this.albumName);
        parcel.writeString(this.albumCoverUrl);
        parcel.writeLong(this.albumPlayCount);
        parcel.writeInt(this.audioType);
        parcel.writeLong(this.playedSecs);
        parcel.writeString(this.kwDataSource);
        parcel.writeInt(this.radioBandIndex);
        parcel.writeInt(this.radioListType);
        parcel.writeInt(this.hasAccompany);
        parcel.writeLong(this.mvId);
        parcel.writeInt(this.tryPlayable);
        parcel.writeString(this.buyUrl);
        parcel.writeInt(this.playableCode);
        parcel.writeString(this.extra1);
        parcel.writeString(this.extra2);
        parcel.writeString(this.extra3);
        parcel.writeString(this.extra4);
        parcel.writeString(this.extra5);
    }

    @Override
    public int compareTo(AudioInfo o) {
        return (int) (this.audioId - o.audioId);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AudioInfo)) {
            return false;
        }
        AudioInfo other = (AudioInfo) o;
        return this.audioId == other.audioId
                && this.audioType == other.audioType
                && stringEquals(this.audioName, other.audioName);
    }

    @Override
    public int hashCode() {
        int result = (int) (this.audioId ^ (this.audioId >>> 32));
        result = 31 * result + this.audioType;
        result = 31 * result + (this.audioName != null ? this.audioName.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "AudioInfo@" + hashCode()
                + "{audioId=" + this.audioId
                + ", audioType=" + this.audioType
                + ", audioName='" + this.audioName
                + "', singerName='" + this.singerName
                + "', duration=" + this.duration
                + ", albumId=" + this.albumId
                + ", albumName='" + this.albumName
                + "', audioCoverUrl=" + this.audioCoverUrl + "}";
    }

    public boolean isLocalMusic() {
        int type = this.audioType;
        return type == AUDIO_TYPE_LOCAL_USB1 || type == AUDIO_TYPE_LOCAL_USB2 || type == AUDIO_TYPE_LOCAL_FAVORITE;
    }

    public boolean isWebRadio() {
        int type = this.audioType;
        return type == AUDIO_TYPE_WEB_RADIO;
    }

    private static boolean stringEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }
}
