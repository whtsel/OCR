package com.blackboxstream.mobile;

/** Separate deadlines for finding audio, receiving it, and writing the playable file. */
final class MusicPreparationBudget {
    static final long LOOKUP_MS = 80_000;
    static final long OPEN_MS = 80_000;
    static final long TRANSFER_MS = 145_000;
    static final long IDLE_MS = 40_000;
    static final long FINALIZE_MS = 25_000;

    enum Phase { LOOKUP, TRANSFER, FINALIZE }

    enum Limit {
        LOOKUP("lookup-timeout", "Finding the exact recording took too long. Nothing played."),
        TRANSFER("transfer-timeout", "Downloading the full audio took too long. Nothing played."),
        IDLE("transfer-idle", "The audio download stopped receiving data. Nothing played."),
        FINALIZE("finalize-timeout", "Finishing the audio file took too long. Nothing played.");

        final String code, message;
        Limit(String code, String message) { this.code = code; this.message = message; }
    }

    private Phase phase = Phase.LOOKUP;
    private long phaseAt, bytesAt, highWater;
    private boolean opening;

    MusicPreparationBudget(long now) { phaseAt = bytesAt = now; }

    // Only the Offline resolver's selected-ID marker starts this window. A repeat marker
    // cannot extend it, nor can a selection that arrived after the lookup deadline.
    synchronized void openSelectedSource(long now) {
        if (phase != Phase.LOOKUP || opening || now - phaseAt >= LOOKUP_MS) return;
        opening = true;
        phaseAt = now;
    }

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

    synchronized Limit timeout(long now) {
        long age = now - phaseAt;
        if (phase == Phase.LOOKUP && age >= (opening ? OPEN_MS : LOOKUP_MS)) return Limit.LOOKUP;
        if (phase == Phase.TRANSFER) {
            if (age >= TRANSFER_MS) return Limit.TRANSFER;
            if (now - bytesAt >= IDLE_MS) return Limit.IDLE;
        }
        if (phase == Phase.FINALIZE && age >= FINALIZE_MS) return Limit.FINALIZE;
        return null;
    }
}
