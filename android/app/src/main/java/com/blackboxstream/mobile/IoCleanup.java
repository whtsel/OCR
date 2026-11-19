package com.blackboxstream.mobile;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Resource teardown must stay off the UI thread, even after a service is destroyed. */
final class IoCleanup {
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, action -> {
        Thread thread = new Thread(action, "BlackBox-close");
        thread.setDaemon(true);
        return thread;
    });
    private IoCleanup() { }
    static void run(Runnable action) { WORKER.execute(action); }
}
