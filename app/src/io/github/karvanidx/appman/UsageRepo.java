package io.github.karvanidx.appman;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** UsageStatsManager access (requires the PACKAGE_USAGE_STATS app-op, same as storage stats). */
public final class UsageRepo {
    private UsageRepo() {}

    public static Map<String, UsageStats> query(Context ctx, long begin, long end) {
        if (!Utils.usageAccessGranted(ctx)) return Collections.emptyMap();
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return Collections.emptyMap();
            Map<String, UsageStats> map = usm.queryAndAggregateUsageStats(begin, end);
            return map == null ? Collections.<String, UsageStats>emptyMap() : map;
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }

    public static final class Session {
        public final long start;
        public final long end;
        public final boolean ongoing;

        Session(long start, long end, boolean ongoing) {
            this.start = start;
            this.end = end;
            this.ongoing = ongoing;
        }

        public long duration() {
            return Math.max(0, end - start);
        }
    }

    public static final class Analysis {
        public final Map<String, Integer> launches = new HashMap<>();
        public final Map<String, List<Session>> sessions = new HashMap<>();
    }

    /**
     * One UsageEvents pass: launch counts and foreground sessions per package.
     * Session = ACTIVITY_RESUMED .. ACTIVITY_PAUSED (MOVE_TO_FOREGROUND/BACKGROUND).
     */
    public static Analysis analyze(Context ctx, long begin, long end) {
        Analysis out = new Analysis();
        if (!Utils.usageAccessGranted(ctx)) return out;
        Map<String, Long> open = new HashMap<>();
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return out;
            UsageEvents events = usm.queryEvents(begin, end);
            if (events == null) return out;
            UsageEvents.Event ev = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                String pkg = ev.getPackageName();
                if (pkg == null) continue;
                int type = ev.getEventType();
                long ts = ev.getTimeStamp();
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    Integer c = out.launches.get(pkg);
                    out.launches.put(pkg, c == null ? 1 : c + 1);
                    Long started = open.get(pkg);
                    if (started != null) addSession(out, pkg, started, ts, false);
                    open.put(pkg, ts);
                } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    Long started = open.remove(pkg);
                    if (started != null) addSession(out, pkg, started, ts, false);
                }
            }
        } catch (Throwable ignored) {
        }
        long now = Math.min(System.currentTimeMillis(), end);
        for (Map.Entry<String, Long> e : open.entrySet()) {
            addSession(out, e.getKey(), e.getValue(), now, true);
        }
        for (List<Session> list : out.sessions.values()) {
            Collections.sort(list, new Comparator<Session>() {
                @Override
                public int compare(Session a, Session b) {
                    return Long.compare(b.start, a.start);
                }
            });
        }
        return out;
    }

    private static void addSession(Analysis out, String pkg, long start, long end,
                                   boolean ongoing) {
        if (end < start) end = start;
        List<Session> list = out.sessions.get(pkg);
        if (list == null) {
            list = new ArrayList<>();
            out.sessions.put(pkg, list);
        }
        list.add(new Session(start, end, ongoing));
    }

    public static Map<String, Integer> launchCounts(Context ctx, long begin, long end) {
        return analyze(ctx, begin, end).launches;
    }

    public static long startOfToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
