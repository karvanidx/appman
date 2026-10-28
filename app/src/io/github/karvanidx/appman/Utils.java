package io.github.karvanidx.appman;

import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Utils {
    private Utils() {}

    public static String humanSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double d = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do {
            d /= 1024.0;
            i++;
        } while (d >= 1024.0 && i < units.length - 1);
        String fmt = d >= 100 ? "%.0f %s" : (d >= 10 ? "%.1f %s" : "%.2f %s");
        return String.format(Locale.US, fmt, d, units[i]);
    }

    public static String fmtDate(long millis) {
        if (millis <= 0) return "—";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }

    public static String humanDuration(long ms) {
        if (ms <= 0) return "0m";
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) return days + "d " + (hours % 24) + "h";
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m";
        return totalSeconds + "s";
    }

    public static String ago(long millis) {
        if (millis <= 0) return "—";
        long diff = System.currentTimeMillis() - millis;
        if (diff < 60_000L) return "just now";
        long minutes = diff / 60_000L;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days < 30) return days + "d ago";
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    public static String safeName(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    public static void copyText(Context c, String label, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            toast(c, "Copied: " + text);
        }
    }

    public static void openAppInfo(Context c, String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            toast(c, "Cannot open app info: " + e.getMessage());
        }
    }

    public static void startApp(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            toast(c, "This app has no launcher activity");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            toast(c, "Cannot open app: " + e.getMessage());
        }
    }

    public static boolean usageAccessGranted(Context c) {
        try {
            AppOpsManager aom = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static void openUsageAccessSettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception e) {
            toast(c, "Cannot open usage access settings");
        }
    }

    /** Returns {appBytes, dataBytes, cacheBytes} or null if not available. */
    public static long[] storageStats(Context c, String pkg) {
        if (Build.VERSION.SDK_INT < 26) return null;
        try {
            StorageStatsManager ssm = (StorageStatsManager) c.getSystemService(Context.STORAGE_STATS_SERVICE);
            if (ssm == null) return null;
            StorageStats s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg,
                    Process.myUserHandle());
            return new long[]{s.getAppBytes(), s.getDataBytes(), s.getCacheBytes()};
        } catch (Exception e) {
            return null;
        }
    }

    public static String installerOf(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                String s = pm.getInstallSourceInfo(pkg).getInstallingPackageName();
                return s == null ? "—" : s;
            } else {
                String s = pm.getInstallerPackageName(pkg);
                return s == null ? "—" : s;
            }
        } catch (Exception e) {
            return "—";
        }
    }

    public static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent.getAbsolutePath());
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    public static void zipApks(File dst, AppEntry e) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent.getAbsolutePath());
        }
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dst));
        try {
            addZip(zos, "base.apk", new File(e.sourceDir));
            if (e.splitDirs != null) {
                for (int i = 0; i < e.splitDirs.length; i++) {
                    addZip(zos, "split_" + (i + 1) + ".apk", new File(e.splitDirs[i]));
                }
            }
            zos.putNextEntry(new ZipEntry("info.txt"));
            zos.write(appInfoText(e).getBytes("UTF-8"));
            zos.closeEntry();
        } finally {
            closeQuietly(zos);
        }
    }

    private static void addZip(ZipOutputStream zos, String name, File f) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        } finally {
            closeQuietly(in);
        }
        zos.closeEntry();
    }

    public static String appInfoText(AppEntry e) {
        return "AppMan backup\n"
                + "name=" + e.label + "\n"
                + "package=" + e.pkg + "\n"
                + "versionName=" + e.versionName + "\n"
                + "versionCode=" + e.versionCode + "\n"
                + "type=" + (e.system ? "system" : "user") + "\n"
                + "minSdk=" + e.minSdk + "\n"
                + "targetSdk=" + e.targetSdk + "\n"
                + "updated=" + fmtDate(e.updated) + "\n"
                + "apkSizeBytes=" + e.apkSize + "\n"
                + "splitApks=" + (e.splitDirs == null ? 0 : e.splitDirs.length) + "\n"
                + "sourceDir=" + e.sourceDir + "\n";
    }

    public static File downloadsDir() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    public static boolean isExternalStorageWritable() {
        String s = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(s);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
