package io.github.karvanidx.appman;

import android.graphics.drawable.Drawable;

public class AppEntry {
    public String label;
    public String pkg;
    public String versionName;
    public long versionCode;
    public long apkSize;
    public long updated;
    public int targetSdk;
    public int minSdk;
    public int uid;
    public boolean system;
    public String sourceDir;
    public String[] splitDirs;
    public String installer;
    public String searchText;
    public Drawable icon;

    // Usage statistics (last 30 days, requires usage access)
    public long lastUsed;
    public long totalForeground;
    public int launchCount;
}
