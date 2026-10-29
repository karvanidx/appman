package io.github.karvanidx.appman;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Lists native .so libraries inside an app's APK(s). */
public final class NativeLibs {
    public static class Lib {
        public String name;
        public String abi;
        public long size;
    }

    private NativeLibs() {}

    public static List<Lib> list(AppEntry e) {
        List<Lib> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        scan(new File(e.sourceDir), out, seen);
        if (e.splitDirs != null) {
            for (String s : e.splitDirs) {
                if (s != null) scan(new File(s), out, seen);
            }
        }
        Collections.sort(out, new Comparator<Lib>() {
            @Override
            public int compare(Lib a, Lib b) {
                int c = a.name.compareToIgnoreCase(b.name);
                return c != 0 ? c : a.abi.compareToIgnoreCase(b.abi);
            }
        });
        return out;
    }

    public static long totalSize(List<Lib> libs) {
        long n = 0;
        for (Lib l : libs) n += l.size;
        return n;
    }

    private static void scan(File apk, List<Lib> out, Set<String> seen) {
        if (apk == null || !apk.isFile()) return;
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String path = ze.getName();
                if (!path.startsWith("lib/") || !path.endsWith(".so")) continue;
                String[] parts = path.split("/");
                if (parts.length < 3) continue;
                String abi = parts[1];
                String name = parts[parts.length - 1];
                String key = abi + "/" + name;
                if (!seen.add(key)) continue;
                Lib l = new Lib();
                l.abi = abi;
                l.name = name;
                l.size = ze.getSize() >= 0 ? ze.getSize() : ze.getCompressedSize();
                out.add(l);
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
