package com.blackboxstream.mobile;

/** Separate deadlines for finding audio, receiving it, and writing the playable file. */
final class MusicPreparationBudget {
    static final long LOOKUP_MS = 80_000;
    static final long TRANSFER_MS = 145_000;
    static final long IDLE_MS = 40_000;
    static final long FINALIZE_MS = 25_000;

    enum Phase { LOOKUP, TRANSFER, FINALIZE }

    private Phase phase = Phase.LOOKUP;
    private long phaseAt, bytesAt, highWater;

    MusicPreparationBudget(long now) { phaseAt = bytesAt = now; }

    // Markers are produced by the bundled CLI, not taken from the upload title or description.
    synchronized Phase marker(String line, long now) {
        Phase next = phase;
        if (phase == Phase.LOOKUP && (line.startsWith("BBX_DOWNLOAD_START ") || "BBX_MEDIA:downloading".equals(line))) {
            next = Phase.TRANSFER;
        } else if (phase != Phase.FINALIZE && "BBX_MEDIA:finished".equals(line)) {
            next = Phase.FINALIZE;
        }
        if (next == phase) return null; // Repeated progress lines must not reset a deadline.
        phase = next;
        phaseAt = bytesAt = now;
        return next;
    }

    synchronized Phase currentPhase() { return phase; }

    synchronized void sawBytes(long size, long now) {
        if (size <= highWater) return;
        highWater = size;
        bytesAt = now;
    }

    synchronized String timeout(long now) {
        long age = now - phaseAt;
        if (phase == Phase.LOOKUP && age >= LOOKUP_MS) return "Finding the exact recording took too long. Nothing played.";
        if (phase == Phase.TRANSFER) {
            if (age >= TRANSFER_MS) return "Downloading the full audio took too long. Nothing played.";
            if (now - bytesAt >= IDLE_MS) return "The audio download stopped receiving data. Nothing played.";
        }
        if (phase == Phase.FINALIZE && age >= FINALIZE_MS) return "Finishing the audio file took too long. Nothing played.";
        return null;
    }
}
