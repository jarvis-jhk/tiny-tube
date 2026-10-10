package org.tinytube.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * The safety net for {@link Screen}, without ever waking the phone. A one-off job with a minimum
 * latency and **no deadline**: JobScheduler runs it only while the phone is awake anyway. It asks
 * for {@link Screen} again, and if the screen is on it runs a pass itself -- because after the
 * system kills the process for memory, a sticky service can take over an hour to come back, and
 * {@code startService} does nothing while that restart is pending. With the screen off it does
 * nothing else; the feed is only checked while the screen is on. A periodic job would not do: every
 * period of one carries a deadline, and a missed deadline wakes the phone.
 */
public final class Refresh extends JobService {
    private static final int JOB_ID = 1;
    private static final long GAP_MS = 15 * 60 * 1000L;

    /** @param restart the job just ran, so the gap counts from now; otherwise a pending job is kept */
    static void schedule(Context ctx, boolean restart) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || (!restart && js.getPendingJob(JOB_ID) != null)) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, Refresh.class))
                .setMinimumLatency(GAP_MS)
                .setPersisted(true)
                .build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        final Context app = getApplicationContext();
        Screen.ensure(app);
        if (getSystemService(android.os.PowerManager.class).isInteractive()) Sync.background(app);
        // after this job has finished: rescheduling a running job cancels it
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { schedule(app, true); }
        });
        return false;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}
