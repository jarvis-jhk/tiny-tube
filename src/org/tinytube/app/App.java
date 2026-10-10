package org.tinytube.app;

import android.app.Application;

/** Exists only to install the crash reporter before anything else runs ({@link Crash}). */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Crash.install(this);
        DataSync.init(this);
    }
}
