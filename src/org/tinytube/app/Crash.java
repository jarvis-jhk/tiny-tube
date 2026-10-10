package org.tinytube.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

/** Optional crash reporting for private builds. */
final class Crash {
    private Crash() {}

    /** Empty in public builds. */
    private static final String REPORT_URL =
            "";

    private static final int JOIN_MS = 4000;
    private static final int MAX_TRACE = 4000;

    static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    post(ctx, thread, error);
                } catch (Throwable ignored) {
                    // a failure to report a crash is not worth a second crash
                }
                if (next != null) next.uncaughtException(thread, error);
            }
        });
    }

    private static void post(Context ctx, Thread thread, Throwable error) {
        if (REPORT_URL.isEmpty()) return;
        // Exception messages may contain video URLs, titles, or request data.
        // Class names and stack frames identify the failure without sending them.
        StringBuilder trace = new StringBuilder(error.getClass().getName());
        for (StackTraceElement frame : error.getStackTrace()) {
            trace.append("\n at ").append(frame);
            if (trace.length() >= MAX_TRACE) break;
        }
        String detail = "thread " + thread.getId() + "\n" + trace;
        if (detail.length() > MAX_TRACE) detail = detail.substring(0, MAX_TRACE);

        final String body = "{\"lane\":\"tiny-tube\",\"kind\":\"bug\""
                + ",\"title\":" + json("Crash: " + error.getClass().getName())
                + ",\"detail\":" + json(detail)
                + ",\"source\":" + json("Tiny Tube " + version(ctx) + " on " + Build.MODEL
                        + " (Android " + Build.VERSION.RELEASE + ")")
                + "}";

        Thread sender = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Net.post(REPORT_URL, body.getBytes("UTF-8"), Net.UA_WEB,
                            "Content-Type", "application/json");
                } catch (Throwable ignored) {
                }
            }
        });
        sender.setDaemon(true);
        sender.start();
        try {
            sender.join(JOIN_MS);
        } catch (InterruptedException ignored) {
        }
    }

    /** Background jobs can fail without crashing; report only a scrubbed failure category. */
    static void reportDownload(Context ctx, String title, Exception error) {
        if (REPORT_URL.isEmpty()) return;
        String message = error.getMessage();
        String category = "other";
        if (message != null) {
            if (message.contains("HTTP 403")) category = "HTTP 403";
            else if (message.contains("HTTP 429")) category = "HTTP 429";
            else if (message.contains("not playable")) category = "not playable";
            else if (message.contains("stalled")) category = "stalled";
            else if (message.contains("no usable video/audio pair")) category = "unsupported format";
            else if (message.contains("finalise")) category = "file finalisation";
        }
        final String body = "{\"lane\":\"tiny-tube\",\"kind\":\"bug\""
                + ",\"title\":" + json(title + ": " + category)
                + ",\"detail\":" + json(error.getClass().getName() + ", " + category)
                + ",\"source\":" + json("Tiny Tube " + version(ctx) + " on " + Build.MODEL
                        + " (Android " + Build.VERSION.RELEASE + ")") + "}";
        Thread sender = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Net.post(REPORT_URL, body.getBytes("UTF-8"), Net.UA_WEB,
                            "Content-Type", "application/json");
                } catch (Throwable ignored) { }
            }
        }, "download-report");
        sender.setDaemon(true);
        sender.start();
    }

    private static String version(Context ctx) {
        try {
            PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return p.versionName + " (" + p.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
