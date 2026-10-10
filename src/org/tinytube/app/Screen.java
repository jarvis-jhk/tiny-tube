package org.tinytube.app;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * The feed refresh for when the app is not on screen (the maintainer, 2026-09-13): while the phone's screen is
 * on -- whatever app is in front -- a silent pass ({@link Sync#background}) every
 * {@link #PASS_EVERY_MS}, and none at all while it is off. The screen being on is what keeps the
 * phone awake here, so nothing takes a wake lock and nothing is ever scheduled to wake it. Screen
 * broadcasts only reach receivers registered at runtime, hence a long-lived started service; the app
 * is on the Doze allowlist, which is what keeps the system from stopping it as an idle
 * background service. A killed process comes back through START_STICKY, the feed's launch and
 * {@link Refresh}.
 */
public final class Screen extends Service {
    /** How often a pass looks for due work while the screen stays on; channels are due hourly. */
    private static final long PASS_EVERY_MS = 5 * 60 * 1000L;

    static void ensure(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, Screen.class));
        } catch (RuntimeException e) {
            Dbg.i("screen: cannot start: " + e);
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    private final Runnable pass = new Runnable() {
        @Override public void run() {
            if (!getSystemService(PowerManager.class).isInteractive()) return;
            Sync.background(Screen.this);
            main.postDelayed(this, PASS_EVERY_MS);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            main.removeCallbacks(pass);
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) main.post(pass);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        main.post(pass);    // started with the screen on: no broadcast is coming for that
        Refresh.schedule(this, false);  // ...and this service comes back if the process is killed
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        unregisterReceiver(screen);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
