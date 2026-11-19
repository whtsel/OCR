package com.blackboxstream.mobile;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import java.io.File;
import java.io.IOException;

final class Downloads {
    private Downloads() {}

    static boolean canKeep(MediaEntry e) {
        return !e.bundled() && (e.fromPhone() || e.torrent() || e.lan() && LanSessions.available(e) || e.remote() && !MediaSources.hls(e.source) && !MediaSources.playlist(e.source));
    }

    static synchronized void enqueue(Context context, MediaEntry e) throws IOException {
        LibraryStore store = LibraryStore.get(context);
        if (!canKeep(e)) throw new IOException("Only direct files can be kept offline.");
        if (store.isOffline(e)) return;
        if (!store.prepareDirectory() || store.availableBytes() < OfflineTransfer.RESERVE_BYTES) {
            throw new IOException("Not enough free space for an offline copy.");
        }
        int id = store.reserveTransfer(e);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        try {
            PersistableBundle extras = new PersistableBundle();
            extras.putString("media", e.id);
            JobInfo.Builder job = new JobInfo.Builder(id, new ComponentName(context, OfflineJobService.class))
                    .setExtras(extras).setPersisted(true).setBackoffCriteria(15000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
            if (e.remote() || e.torrent()) job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            if (scheduler == null || scheduler.schedule(job.build()) != JobScheduler.RESULT_SUCCESS) {
                throw new IOException("Android could not queue the offline copy. Try again.");
            }
        } catch (IOException | RuntimeException err) {
            store.finishTransfer(e, id, "Could not queue the offline copy. Try again.");
            throw err;
        }
    }

    static synchronized void cancel(Context context, MediaEntry entry) {
        LibraryStore store = LibraryStore.get(context);
        MediaEntry e = store.get(entry.id);
        if (e == null || e.transfer < 0) return;
        int job = e.transfer;
        if (e.torrent()) TorrentJobs.cancel(e);
        store.finishTransfer(e, job, null);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(job);
        discard(store, e, job);
    }

    static long received(LibraryStore store, MediaEntry e) {
        return e.torrent() ? TorrentJobs.received(e) : store.partial(e, e.transfer).length();
    }

    static synchronized void discard(LibraryStore store, MediaEntry e, int job) {
        store.partial(e, job).delete();
        store.validator(e, job).delete();
    }

    static synchronized boolean commit(LibraryStore store, MediaEntry e, int job) throws IOException {
        MediaEntry current = store.get(e.id);
        if (current == null || current.transfer != job) { discard(store, e, job); return false; }
        File file = store.partial(e, job);
        if (!file.isFile() || file.length() == 0 || !file.renameTo(store.offline(e))) {
            throw new OfflineTransfer.Rejected("Could not finish the offline copy. Check your free space.");
        }
        store.validator(e, job).delete();
        store.finishTransfer(e, job, null);
        return true;
    }

    static synchronized void deleteCopy(Context context, MediaEntry e) throws IOException {
        LibraryStore store = LibraryStore.get(context);
        cancel(context, e);
        File target = store.offline(e);
        if (target.exists() && !target.delete()) throw new IOException("Android could not remove this copy.");
        store.clearTransferError(e);
    }

    static synchronized void failIfMissing(Context context, MediaEntry e) {
        // Account for interrupted scheduling or a queue reset without treating a live job as failed.
        LibraryStore store = LibraryStore.get(context);
        MediaEntry current = store.get(e.id);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (current != null && current.transfer >= 0 && scheduler != null
                && scheduler.getPendingJob(current.transfer) == null) {
            discard(store, current, current.transfer);
            store.finishTransfer(current, current.transfer, store.isOffline(current) ? null : "Download was interrupted. Try again.");
        }
    }
}
