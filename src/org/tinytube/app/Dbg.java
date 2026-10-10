package org.tinytube.app;

import android.util.Log;

/**
 * Diagnostics for the playback path, kept deliberately.
 *
 * <p>Everything interesting about this app happens against a server that pushes back in
 * undocumented ways, so being able to see the request pattern and the frame budget on the real
 * phone is worth its (tiny) size. Read it with:
 *
 * <pre>adb logcat -d -v brief | grep TinyTube</pre>
 *
 * <p>Set {@link #VERBOSE} to false to silence the per-request chatter while keeping errors and
 * the playback heartbeat.
 */
final class Dbg {
    private Dbg() {}

    static final String TAG = "TinyTube";
    static final boolean VERBOSE = true;

    /** Per-request / per-stage chatter. */
    static void v(String message) {
        if (VERBOSE) Log.i(TAG, message);
    }

    /** Always logged: milestones and failures. */
    static void i(String message) {
        Log.i(TAG, message);
    }

    static void e(String message, Throwable t) {
        Log.e(TAG, message, t);
    }
}
