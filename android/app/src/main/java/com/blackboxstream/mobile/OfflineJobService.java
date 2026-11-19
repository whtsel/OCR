package com.blackboxstream.mobile;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.net.Uri;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android owns the schedule (including network availability and reboot); no open UI is required. */
public final class OfflineJobService extends JobService {
    private static final Object[] FILE_LOCKS = new Object[32];
    static { for (int i = 0; i < FILE_LOCKS.length; i++) FILE_LOCKS[i] = new Object(); }
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Task> running = new HashMap<>();

    private static final class Task {
        final JobParameters parameters;
        volatile boolean stopped;
        Task(JobParameters parameters) { this.parameters = parameters; }
    }

    @Override public boolean onStartJob(JobParameters info) {
        Task task = new Task(info);
        running.put(info.getJobId(), task);
        workers.execute(() -> {
            // A job stopped by Android can be rescheduled before its old socket unwinds.
            synchronized (FILE_LOCKS[info.getJobId() % FILE_LOCKS.length]) { work(task); }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters info) {
        Task task = running.remove(info.getJobId());
        if (task != null) task.stopped = true;
        // The assignment remains current until a user cancels or the job succeeds/fails permanently.
        return true;
    }

    @Override public void onDestroy() {
        for (Task task : running.values()) task.stopped = true;
        running.clear();
        workers.shutdownNow();
        super.onDestroy();
    }

    private void work(Task task) {
        JobParameters info = task.parameters;
        int job = info.getJobId();
        LibraryStore store = LibraryStore.get(this);
        MediaEntry e = store.get(info.getExtras().getString("media"));
        boolean retry = false;
        String error = null;
        if (e != null && e.transfer == job && !task.stopped) {
            OfflineTransfer.Monitor monitor = new OfflineTransfer.Monitor() {
                @Override public boolean stopped() { return task.stopped; }
                @Override public void total(long bytes) throws IOException {
                    long present = store.partial(e, job).length();
                    if (store.availableBytes() < Math.max(0, bytes - present) + OfflineTransfer.RESERVE_BYTES) {
                        throw new OfflineTransfer.Rejected("Not enough free space for this download.");
                    }
                    store.transferTotal(e, job, bytes);
                }
            };
            try {
                if (!store.prepareDirectory()) throw new OfflineTransfer.Rejected("Could not open the offline folder.");
                if (store.isOffline(e)) store.finishTransfer(e, job, null);
                else {
                    if (e.remote()) OfflineTransfer.download(e.source, store.partial(e, job), store.validator(e, job), monitor);
                    else if (e.torrent()) TorrentJobs.run(this, e, store.partial(e, job), monitor);
                    else if (e.lan()) LanSessions.copy(e, store.partial(e, job), monitor);
                    else if (e.fromPhone()) OfflineTransfer.copy(getContentResolver().openInputStream(Uri.parse(e.source)),
                            store.partial(e, job), monitor);
                    else throw new OfflineTransfer.Rejected("This item cannot be copied.");
                    if (!task.stopped) {
                        validateContainer(store.partial(e, job).getAbsolutePath());
                        if (!task.stopped) Downloads.commit(store, e, job);
                    }
                }
            } catch (OfflineTransfer.Rejected rejection) {
                error = rejection.getMessage();
            } catch (javax.net.ssl.SSLHandshakeException tls) {
                error = "The file server's HTTPS certificate could not be verified.";
            } catch (SecurityException access) {
                error = "Android no longer grants access to this file. Open it from your phone again.";
            } catch (IOException interrupted) {
                retry = !task.stopped && e.remote() && store.retryTransfer(e, job);
                error = e.lan() ? "Local Share ended or the sender changed networks. Reconnect with a fresh invitation."
                        : "Transfer interrupted. Check the file and your connection, then retry.";
            } catch (RuntimeException failed) {
                error = "Android could not keep an offline copy of this file.";
            }
        }
        final boolean reschedule = retry;
        final String reason = error;
        main.post(() -> {
            if (running.get(job) != task) {
                if (e != null) {
                    MediaEntry current = store.get(e.id);
                    if (current == null || current.transfer != job) Downloads.discard(store, e, job);
                }
                return;
            }
            running.remove(job);
            if (e != null && reason != null && !reschedule && !task.stopped) {
                Downloads.discard(store, e, job);
                store.finishTransfer(e, job, reason);
            }
            jobFinished(info, reschedule);
        });
    }

    private static void validateContainer(String path) throws OfflineTransfer.Rejected {
        MediaExtractor extractor = new MediaExtractor();
        try (FileInputStream file = new FileInputStream(path)) {
            extractor.setDataSource(file.getFD());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("audio/") || mime.startsWith("video/"))) return;
            }
            throw new IOException("No media tracks");
        } catch (IOException | RuntimeException invalid) {
            throw new OfflineTransfer.Rejected("Android could not read this media file. Try another format or link.");
        } finally { extractor.release(); }
    }
}
