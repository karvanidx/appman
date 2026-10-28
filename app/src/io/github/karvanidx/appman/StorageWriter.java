package io.github.karvanidx.appman;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Writes files to Download/AppMan using MediaStore on Android 10+. */
public final class StorageWriter {
    private StorageWriter() {}

    public static final String ROOT = "Download/AppMan";

    private static String stripDownload(String relDir) {
        if (relDir.startsWith("Download/")) return relDir.substring("Download/".length());
        return relDir;
    }

    public static Uri writeFile(Context ctx, String relDir, String name, String mime, File src)
            throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            return writeMediaStore(ctx, relDir, name, mime, src, null);
        } else {
            File dir = new File(Utils.downloadsDir(), stripDownload(relDir));
            return writeLegacy(dir, name, src, null);
        }
    }

    public static Uri writeText(Context ctx, String relDir, String name, String mime, String text)
            throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            return writeMediaStore(ctx, relDir, name, mime, null, text.getBytes("UTF-8"));
        } else {
            File dir = new File(Utils.downloadsDir(), stripDownload(relDir));
            return writeLegacy(dir, name, null, text.getBytes("UTF-8"));
        }
    }

    private static Uri writeMediaStore(Context ctx, String relDir, String name, String mime,
                                       File src, byte[] bytes) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String path = relDir.endsWith("/") ? relDir : relDir + "/";
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("MediaStore refused to create the file");
        boolean ok = false;
        OutputStream os = null;
        try {
            os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("Cannot open output stream");
            if (src != null) {
                InputStream in = new java.io.FileInputStream(src);
                try {
                    copy(in, os);
                } finally {
                    in.close();
                }
            } else {
                os.write(bytes);
            }
            os.flush();
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            ok = true;
            return uri;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
            if (!ok) {
                try {
                    cr.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Uri writeLegacy(File dir, String name, File src, byte[] bytes) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create folder " + dir.getAbsolutePath());
        }
        File out = new File(dir, name);
        OutputStream os = new FileOutputStream(out);
        try {
            if (src != null) {
                InputStream in = new java.io.FileInputStream(src);
                try {
                    copy(in, os);
                } finally {
                    in.close();
                }
            } else {
                os.write(bytes);
            }
            os.flush();
        } finally {
            try {
                os.close();
            } catch (Exception ignored) {
            }
        }
        return Uri.fromFile(out);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
