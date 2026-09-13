package com.cusc.bean.media;

import android.os.Parcel;
import android.os.Parcelable;

public class Lrc implements Parcelable {
    private long time;
    private String text;

    public Lrc() {
    }

    public Lrc(long time, String text) {
        this.time = time;
        this.text = text;
    }

    protected Lrc(Parcel in) {
        this.time = in.readLong();
        this.text = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(this.time);
        dest.writeString(this.text);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public long getTime() {
        return this.time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public static final Creator<Lrc> CREATOR = new Creator<Lrc>() {
        @Override
        public Lrc createFromParcel(Parcel source) {
            return new Lrc(source);
        }

        @Override
        public Lrc[] newArray(int size) {
            return new Lrc[size];
        }
    };
}
