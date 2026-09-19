package com.cusc.media.base.player;

import android.os.Parcel;
import android.os.Parcelable;

import com.cusc.bean.media.AudioInfo;

import java.util.List;

public class AudioInfoList implements Parcelable {
    public static final Parcelable.Creator<AudioInfoList> CREATOR = new Parcelable.Creator<AudioInfoList>() {
        @Override
        public AudioInfoList createFromParcel(Parcel source) {
            return new AudioInfoList(source);
        }

        @Override
        public AudioInfoList[] newArray(int size) {
            return new AudioInfoList[size];
        }
    };

    public int audioListType;
    public long clickAudioId;
    public long albumId;
    public String title;
    public List<AudioInfo> audioInfoList;
    public boolean autoPlay;
    public boolean updateMetadata;
    public int total;
    public int offset;
    public int limit;

    public AudioInfoList() {
        this.autoPlay = true;
        this.updateMetadata = false;
    }

    protected AudioInfoList(Parcel in) {
        this.autoPlay = true;
        this.updateMetadata = false;
        this.audioListType = in.readInt();
        this.clickAudioId = in.readLong();
        this.albumId = in.readLong();
        this.title = in.readString();
        this.audioInfoList = in.createTypedArrayList(AudioInfo.CREATOR);
        this.autoPlay = in.readByte() != 0;
        this.updateMetadata = in.readByte() != 0;
        this.total = in.readInt();
        this.offset = in.readInt();
        this.limit = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(this.audioListType);
        parcel.writeLong(this.clickAudioId);
        parcel.writeLong(this.albumId);
        parcel.writeString(this.title);
        parcel.writeTypedList(this.audioInfoList);
        parcel.writeByte(this.autoPlay ? (byte) 1 : (byte) 0);
        parcel.writeByte(this.updateMetadata ? (byte) 1 : (byte) 0);
        parcel.writeInt(this.total);
        parcel.writeInt(this.offset);
        parcel.writeInt(this.limit);
    }

    @Override
    public String toString() {
        return "AudioInfoList{audioListType=" + this.audioListType
                + ", clickAudioId=" + this.clickAudioId
                + ", albumId=" + this.albumId
                + ", title='" + this.title
                + "', autoPlay=" + this.autoPlay
                + ", updateMetadata=" + this.updateMetadata
                + ", total=" + this.total
                + ", offset=" + this.offset
                + ", limit=" + this.limit + "}";
    }
}
