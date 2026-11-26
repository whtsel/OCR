package com.blackboxstream.mobile;

/** Deterministic regression for the slow open after lookup seen on the phone. */
public final class MusicBudgetChecks {
    private static void require(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }

    public static void main(String[] args) {
        long launch = 16, selected = 36_118;
        MusicPreparationBudget attempt = new MusicPreparationBudget(launch);
        require(attempt.timeout(selected - 1) == null, "Search expired before a source was selected");
        attempt.openSelectedSource(selected);
        require(attempt.currentPhase() == MusicPreparationBudget.Phase.LOOKUP && attempt.timeout(90_000) == null,
                "An already-selected source still used the search deadline");
        attempt.openSelectedSource(95_000); // A duplicate notice must not extend the deadline.
        long openDeadline = selected + MusicPreparationBudget.OPEN_MS;
        require(attempt.timeout(openDeadline - 1) == null && attempt.timeout(openDeadline) == MusicPreparationBudget.Limit.LOOKUP,
                "Opening did not retain one bounded window or its normal retry reason");

        MusicPreparationBudget unselected = new MusicPreparationBudget(launch);
        long lookupDeadline = launch + MusicPreparationBudget.LOOKUP_MS;
        require(unselected.timeout(lookupDeadline - 1) == null
                        && unselected.timeout(lookupDeadline) == MusicPreparationBudget.Limit.LOOKUP,
                "A stalled search or direct open lost its existing deadline");
        unselected.openSelectedSource(lookupDeadline);
        require(unselected.timeout(lookupDeadline) == MusicPreparationBudget.Limit.LOOKUP,
                "A late selection revived an expired lookup");

        MusicPreparationBudget successful = new MusicPreparationBudget(launch);
        successful.openSelectedSource(selected);
        long transfer = 90_000;
        require(successful.marker("BBX_DOWNLOAD_START m4a", transfer) == MusicPreparationBudget.Phase.TRANSFER,
                "Opening prevented the original transfer transition");
        successful.openSelectedSource(transfer + 100);
        require(successful.timeout(transfer + MusicPreparationBudget.IDLE_MS) == MusicPreparationBudget.Limit.IDLE,
                "Opening extended the transfer idle guard");
        System.out.println("PASS selected Offline source gets one bounded opening window, without changing transfer deadlines");
    }
}
