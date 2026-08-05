package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.WorldSyncStatusHook.SyncStatus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorldSyncStatusTrackerTest {

    @Test
    void defaultsToNotSyncedForAnUnseenWorld() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        assertThat(tracker.statusFor("unknown_world")).isEqualTo(SyncStatus.NOT_SYNCED);
        assertThat(tracker.lastErrorFor("unknown_world")).isNull();
    }

    @Test
    void markSyncedRecordsSyncedStatus() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markSynced("my_world");

        assertThat(tracker.statusFor("my_world")).isEqualTo(SyncStatus.SYNCED);
        assertThat(tracker.lastErrorFor("my_world")).isNull();
    }

    @Test
    void markErrorRecordsErrorStatusAndMessage() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markError("my_world", "quota exceeded");

        assertThat(tracker.statusFor("my_world")).isEqualTo(SyncStatus.SYNC_ERROR);
        assertThat(tracker.lastErrorFor("my_world")).isEqualTo("quota exceeded");
    }

    @Test
    void markSkippedTooLargeRecordsSkippedStatus() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markSkippedTooLarge("big_world");

        assertThat(tracker.statusFor("big_world")).isEqualTo(SyncStatus.SKIPPED_TOO_LARGE);
        assertThat(tracker.lastErrorFor("big_world")).isNull();
    }

    @Test
    void laterCallOverwritesEarlierStatusForTheSameSlug() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markError("my_world", "first failure");
        tracker.markSynced("my_world");

        assertThat(tracker.statusFor("my_world")).isEqualTo(SyncStatus.SYNCED);
        assertThat(tracker.lastErrorFor("my_world")).isNull();
    }

    @Test
    void freshWorldDefaultsToNotInProgress() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        assertThat(tracker.isUploadInProgress("unknown_world")).isFalse();
    }

    @Test
    void markUploadPendingSetsInProgressImmediately() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markUploadPending("my_world");

        assertThat(tracker.isUploadInProgress("my_world")).isTrue();
    }

    @Test
    void markUploadFinishedClearsInProgress() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markUploadPending("my_world");
        tracker.markUploadFinished("my_world");

        assertThat(tracker.isUploadInProgress("my_world")).isFalse();
    }

    @Test
    void markUploadFinishedOnAWorldNeverMarkedPendingIsANoOp() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markUploadFinished("never_pending_world");

        assertThat(tracker.isUploadInProgress("never_pending_world")).isFalse();
    }

    @Test
    void inProgressFlagIsIndependentPerWorldSlug() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markUploadPending("world_a");

        assertThat(tracker.isUploadInProgress("world_a")).isTrue();
        assertThat(tracker.isUploadInProgress("world_b")).isFalse();
    }

    @Test
    void freshWorldDefaultsToNoPendingConflict() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        assertThat(tracker.hasPendingConflict("unknown_world")).isFalse();
    }

    @Test
    void markConflictPendingSetsPendingConflict() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markConflictPending("my_world");

        assertThat(tracker.hasPendingConflict("my_world")).isTrue();
    }

    @Test
    void clearPendingConflictClearsPendingConflict() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markConflictPending("my_world");
        tracker.clearPendingConflict("my_world");

        assertThat(tracker.hasPendingConflict("my_world")).isFalse();
    }

    // -- Gap 2 (sync-conflict-coverage-gaps): transient conflict-check-pending state --

    @Test
    void freshWorldDefaultsToNoConflictCheckPending() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        assertThat(tracker.isConflictCheckPending("unknown_world")).isFalse();
    }

    @Test
    void markConflictCheckPendingSetsPendingImmediately() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markConflictCheckPending("my_world");

        assertThat(tracker.isConflictCheckPending("my_world")).isTrue();
    }

    @Test
    void clearConflictCheckPendingClearsPending() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markConflictCheckPending("my_world");
        tracker.clearConflictCheckPending("my_world");

        assertThat(tracker.isConflictCheckPending("my_world")).isFalse();
    }

    @Test
    void clearConflictCheckPendingCalledTwiceDoesNotLeak() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markConflictCheckPending("my_world");
        tracker.clearConflictCheckPending("my_world");
        tracker.clearConflictCheckPending("my_world");

        assertThat(tracker.isConflictCheckPending("my_world")).isFalse();
    }

    @Test
    void conflictCheckPendingFlagIsIndependentPerWorldSlug() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markConflictCheckPending("world_a");

        assertThat(tracker.isConflictCheckPending("world_a")).isTrue();
        assertThat(tracker.isConflictCheckPending("world_b")).isFalse();
    }
}
