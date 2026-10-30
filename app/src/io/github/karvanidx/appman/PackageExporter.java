package io.github.karvanidx.appman;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Packs base + split APKs and every native .so into one portable zip. */
public final class PackageExporter {
    private PackageExporter() {}

    public static File build(Context c, AppEntry e) throws IOException {
        File dir = new File(c.getCacheDir(), "exports");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File out = new File(dir, Utils.safeName(e.pkg) + "-" + e.versionName + ".zip");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
        try {
            List<File> apks = new ArrayList<>();
            File base = new File(e.sourceDir);
            apks.add(base);
            addFile(zos, "apk/base.apk", base);
            if (e.splitDirs != null) {
                for (int i = 0; i < e.splitDirs.length; i++) {
                    if (e.splitDirs[i] == null) continue;
                    File split = new File(e.splitDirs[i]);
                    apks.add(split);
                    addFile(zos, "apk/split_" + (i + 1) + ".apk", split);
                }
            }

            Set<String> seen = new HashSet<>();
            StringBuilder libsCsv = new StringBuilder("abi,name,bytes\n");
            int libCount = 0;
            for (File apk : apks) {
                ZipFile zf = null;
                try {
                    zf = new ZipFile(apk);
                    java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry ze = en.nextElement();
                        String path = ze.getName();
                        if (!path.startsWith("lib/") || !path.endsWith(".so")) continue;
                        if (!seen.add(path)) continue;
                        String[] parts = path.split("/");
                        if (parts.length < 3) continue;
                        String abi = parts[1];
                        String name = parts[parts.length - 1];
                        zos.putNextEntry(new ZipEntry("lib/" + abi + "/" + name));
                        InputStream in = zf.getInputStream(ze);
                        try {
                            copy(in, zos);
                        } finally {
                            in.close();
                        }
                        zos.closeEntry();
                        libCount++;
                        libsCsv.append(abi).append(',').append(name).append(',')
                                .append(ze.getSize()).append('\n');
                    }
                } finally {
                    if (zf != null) {
                        try {
                            zf.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            zos.putNextEntry(new ZipEntry("info.txt"));
            zos.write((Utils.appInfoText(e) + "nativeLibraries=" + libCount + "\n").getBytes("UTF-8"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("libs.csv"));
            zos.write(libsCsv.toString().getBytes("UTF-8"));
            zos.closeEntry();
        } finally {
            try {
                zos.close();
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void addFile(ZipOutputStream zos, String name, File src) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        InputStream in = new FileInputStream(src);
        try {
            copy(in, zos);
        } finally {
            in.close();
        }
        zos.closeEntry();
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
