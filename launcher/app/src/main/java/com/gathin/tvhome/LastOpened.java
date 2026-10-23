package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateUtils;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * When an app was last opened from Home, for the cinematic shelf's "Last opened" line and grid sorting.
 * App usage stats are queried asynchronously in the background and cached in memory to ensure UI thread
 * operations (such as list sorting) never block on IPC or disk I/O.
 */
final class LastOpened {
    private static final String PREFS = "last_opened";
    private static final Map<String, Long> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService BG_EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile boolean seeded = false;

    private LastOpened() {
    }

    /**
     * Seeds in-memory cache and asynchronously fetches UsageStatsManager history once in the background.
     */
    static void seed(Context ctx) {
        if (ctx == null) return;
        Context appCtx = ctx.getApplicationContext();
        if (!seeded) {
            synchronized (CACHE) {
                if (!seeded) {
                    seeded = true;
                    try {
                        Map<String, ?> all = prefs(appCtx).getAll();
                        for (Map.Entry<String, ?> e : all.entrySet()) {
                            if (e.getValue() instanceof Long) {
                                CACHE.put(e.getKey(), (Long) e.getValue());
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        BG_EXECUTOR.execute(() -> {
            try {
                android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                        appCtx.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    long now = System.currentTimeMillis();
                    java.util.List<android.app.usage.UsageStats> stats = usm.queryUsageStats(
                            android.app.usage.UsageStatsManager.INTERVAL_BEST,
                            now - 1000L * 60 * 60 * 24 * 180, now);
                    if (stats != null && !stats.isEmpty()) {
                        SharedPreferences.Editor ed = prefs(appCtx).edit();
                        boolean changed = false;
                        for (android.app.usage.UsageStats s : stats) {
                            String pkg = s.getPackageName();
                            long t = s.getLastTimeUsed();
                            if (pkg != null && t > 0) {
                                Long current = CACHE.get(pkg);
                                if (current == null || current < t) {
                                    CACHE.put(pkg, t);
                                    ed.putLong(pkg, t);
                                    changed = true;
                                }
                            }
                        }
                        if (changed) {
                            ed.apply();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** Notes that Home just opened this app. */
    static void record(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return;
        long now = System.currentTimeMillis();
        CACHE.put(pkg, now);
        prefs(ctx.getApplicationContext()).edit().putLong(pkg, now).apply();
    }

    /** Milliseconds since the epoch, or 0 when Home never opened the app. */
    static long get(Context ctx, String pkg) {
        if (pkg == null) return 0L;
        if (!seeded && ctx != null) {
            seed(ctx);
        }
        Long val = CACHE.get(pkg);
        return val != null ? val : 0L;
    }

    /**
     * "5 minutes ago" within the hour, then the day and the time: "today, 11:40 am", "yesterday, 9:40 pm",
     * "3 days ago, 9:40 pm", "Oct 12, 9:40 pm". The parts are Android's own wording in the TV's language.
     */
    static String describe(Context ctx, long when) {
        long now = System.currentTimeMillis();
        // Under a minute Android says "0 minutes ago"; one minute reads better.
        when = Math.min(when, now - DateUtils.MINUTE_IN_MILLIS);
        if (now - when < DateUtils.HOUR_IN_MILLIS) {
            return DateUtils.getRelativeTimeSpanString(when, now, DateUtils.MINUTE_IN_MILLIS).toString();
        }
        String day = DateUtils.getRelativeTimeSpanString(when, now, DateUtils.DAY_IN_MILLIS).toString();
        // It follows "Last opened" mid-sentence: "yesterday", not "Yesterday". Month names keep their capital.
        if (Locale.getDefault().getLanguage().equals("en") && (day.startsWith("Yesterday") || day.startsWith("Today"))) {
            day = Character.toLowerCase(day.charAt(0)) + day.substring(1);
        }
        return day + ", " + DateUtils.formatDateTime(ctx, when, DateUtils.FORMAT_SHOW_TIME);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
