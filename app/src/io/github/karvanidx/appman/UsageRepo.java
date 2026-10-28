package io.github.karvanidx.appman;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.Collections;
import java.util.HashMap;
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

    /** Launch counts from UsageEvents (getAppLaunchCount is a hidden API). */
    public static Map<String, Integer> launchCounts(Context ctx, long begin, long end) {
        Map<String, Integer> out = new HashMap<>();
        if (!Utils.usageAccessGranted(ctx)) return out;
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return out;
            UsageEvents events = usm.queryEvents(begin, end);
            if (events == null) return out;
            UsageEvents.Event ev = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    String pkg = ev.getPackageName();
                    if (pkg == null) continue;
                    Integer c = out.get(pkg);
                    out.put(pkg, c == null ? 1 : c + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
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
