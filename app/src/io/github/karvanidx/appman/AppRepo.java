package io.github.karvanidx.appman;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.app.usage.UsageStats;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AppRepo {
    public interface Progress {
        void onProgress(int count);
    }

    private AppRepo() {}

    public static List<AppEntry> load(Context ctx, Progress progress) {
        PackageManager pm = ctx.getPackageManager();
        List<AppEntry> out = new ArrayList<>();
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(0);
        } catch (Exception e) {
            return out;
        }
        long now = System.currentTimeMillis();
        java.util.Map<String, UsageStats> usage =
                UsageRepo.query(ctx, now - 30L * 24 * 60 * 60 * 1000, now);
        java.util.Set<String> active = UsageRepo.activePackages(ctx);

        int n = 0;
        for (PackageInfo pi : packages) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) continue;
            if (ai.sourceDir == null) continue;

            AppEntry e = new AppEntry();
            e.pkg = pi.packageName;
            CharSequence label = ai.loadLabel(pm);
            e.label = label == null ? pi.packageName : label.toString().trim();
            if (e.label.isEmpty()) e.label = pi.packageName;
            e.versionName = pi.versionName == null ? "?" : pi.versionName;
            if (Build.VERSION.SDK_INT >= 28) {
                e.versionCode = pi.getLongVersionCode();
            } else {
                e.versionCode = pi.versionCode;
            }
            e.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            e.running = active.contains(pi.packageName);
            e.sourceDir = ai.sourceDir;
            e.splitDirs = ai.splitSourceDirs;
            e.updated = pi.lastUpdateTime;
            e.targetSdk = ai.targetSdkVersion;
            e.minSdk = ai.minSdkVersion;
            e.uid = ai.uid;
            e.installer = Utils.installerOf(ctx, pi.packageName);

            long size = new File(ai.sourceDir).length();
            if (e.splitDirs != null) {
                for (String s : e.splitDirs) {
                    if (s != null) size += new File(s).length();
                }
            }
            e.apkSize = size;

            try {
                e.icon = ai.loadIcon(pm);
            } catch (Exception ignored) {
            }
            UsageStats us = usage.get(pi.packageName);
            if (us != null) {
                e.lastUsed = us.getLastTimeUsed();
                e.totalForeground = us.getTotalTimeInForeground();
            }
            e.searchText = (e.label + " " + e.pkg).toLowerCase(Locale.ROOT);
            out.add(e);

            n++;
            if (progress != null && (n % 15 == 0)) progress.onProgress(n);
        }
        if (progress != null) progress.onProgress(out.size());
        return out;
    }
}
