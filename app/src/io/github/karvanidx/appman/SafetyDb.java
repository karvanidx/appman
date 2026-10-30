package io.github.karvanidx.appman;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** AppMan's own curated HyperOS safety list (advisory, not copied from UAD/Canta). */
public final class SafetyDb {
    public static final String RECOMMENDED = "recommended";
    public static final String ADVANCED = "advanced";
    public static final String EXPERT = "expert";
    public static final String UNSAFE = "unsafe";

    public static final class Entry {
        public final String pkg;
        public final String category;
        public final String title;
        public final String note;

        Entry(String pkg, String category, String title, String note) {
            this.pkg = pkg;
            this.category = category;
            this.title = title;
            this.note = note;
        }
    }

    private static Map<String, Entry> entries;
    private static Set<String> blocked;

    private SafetyDb() {}

    private static synchronized void ensure(Context c) {
        if (entries != null) return;
        entries = new LinkedHashMap<>();
        blocked = new HashSet<>();
        try {
            InputStream in = c.getAssets().open("debloat.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(bos.toString("UTF-8"));
            JSONArray arr = root.optJSONArray("blocked");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) blocked.add(arr.getString(i));
            }
            JSONObject obj = root.optJSONObject("entries");
            if (obj != null) {
                Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String pkg = it.next();
                    JSONObject o = obj.optJSONObject(pkg);
                    if (o == null) continue;
                    entries.put(pkg, new Entry(pkg,
                            o.optString("category", ADVANCED),
                            o.optString("title", pkg),
                            o.optString("note", "")));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static Entry get(Context c, String pkg) {
        ensure(c);
        return entries.get(pkg);
    }

    public static boolean isBlocked(Context c, String pkg) {
        ensure(c);
        return blocked.contains(pkg);
    }

    public static boolean isDebloatCandidate(Context c, String pkg) {
        ensure(c);
        Entry e = entries.get(pkg);
        return e != null && !UNSAFE.equals(e.category) && !blocked.contains(pkg);
    }

    public static int count(Context c) {
        ensure(c);
        return entries.size();
    }

    public static String label(String category) {
        if (RECOMMENDED.equals(category)) return "Recommended";
        if (ADVANCED.equals(category)) return "Advanced";
        if (EXPERT.equals(category)) return "Expert";
        if (UNSAFE.equals(category)) return "Unsafe";
        return "Unknown";
    }

    public static int color(String category) {
        if (RECOMMENDED.equals(category)) return 0xFF047857;
        if (ADVANCED.equals(category)) return 0xFFB45309;
        if (EXPERT.equals(category)) return 0xFFC2410C;
        if (UNSAFE.equals(category)) return 0xFFB91C1C;
        return 0xFF6B7280;
    }

    public static int badge(String category) {
        if (RECOMMENDED.equals(category)) return R.drawable.bg_badge_green;
        if (ADVANCED.equals(category)) return R.drawable.bg_badge_amber;
        if (EXPERT.equals(category)) return R.drawable.bg_badge_amber;
        if (UNSAFE.equals(category)) return R.drawable.bg_badge_red;
        return R.drawable.bg_badge_gray;
    }
}
