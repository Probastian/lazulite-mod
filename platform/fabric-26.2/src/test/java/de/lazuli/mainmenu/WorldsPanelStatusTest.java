package de.lazuli.mainmenu;

import de.lazuli.api.cloudsync.WorldConflictHook.ConflictStatus;
import de.lazuli.api.cloudsync.WorldFreshnessHook.UpToDateStatus;
import de.lazuli.mainmenu.WorldsPanel.ConsolidatedStatus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cloud-sync-status-ui-simplify's first-ever automated test for any {@code
 * WorldsPanel.java}: covers FR-1's full precedence table (Conflict &gt;
 * Syncing &gt; Synced &gt; Unsynced) via {@link
 * WorldsPanel#computeConsolidatedStatus}, a plain, package-private static
 * function of five primitive/enum arguments -- no Minecraft-client
 * bootstrapping needed.
 *
 * <p>Per the implementation plan's Decision 3, this is deliberately the sole
 * automated test of this precedence logic across the three platform modules
 * ({@code fabric-1.21.11}/{@code fabric-26.1}/{@code fabric-26.2} each carry
 * their own identical copy of {@code computeConsolidatedStatus}); drift
 * between the copies is caught by the required end-of-implementation
 * three-way diff pass instead.
 */
class WorldsPanelStatusTest {

    @Test
    void conflictWinsOverUploadInProgress() {
        assertThat(WorldsPanel.computeConsolidatedStatus(
                ConflictStatus.CONFLICT, true, false, true, UpToDateStatus.UP_TO_DATE))
                .isEqualTo(ConsolidatedStatus.CONFLICT);
    }

    @Test
    void conflictWinsWithNoUploadOrDownload() {
        assertThat(WorldsPanel.computeConsolidatedStatus(
                ConflictStatus.CONFLICT, false, false, true, UpToDateStatus.UP_TO_DATE))
                .isEqualTo(ConsolidatedStatus.CONFLICT);
    }

    @Test
    void noConflictUploadInProgressIsSyncing() {
        assertThat(WorldsPanel.computeConsolidatedStatus(
                ConflictStatus.NONE, true, false, true, UpToDateStatus.STALE))
                .isEqualTo(ConsolidatedStatus.SYNCING);
    }

    @Test
    void noConflictDownloadInProgressIsSyncing() {
        assertThat(WorldsPanel.computeConsolidatedStatus(
                ConflictStatus.NONE, false, true, true, UpToDateStatus.STALE))
                .isEqualTo(ConsolidatedStatus.SYNCING);
    }

    @Test
    void noConflictBothUploadAndDownloadInProgressIsSyncing() {
        assertThat(WorldsPanel.computeConsolidatedStatus(
                ConflictStatus.NONE, true, true, true, UpToDateStatus.STALE))
                .isEqualTo(ConsolidatedStatus.SYNCING);
    }

    @Test
    void syncEnabledUpToDateIsSynced() {
        assertThat(WorldsPanel.computeConsolidatedStatus(
                ConflictStatus.NONE, false, false, true, UpToDateStatus.UP_TO_DATE))
                .isEqualTo(ConsolidatedStatus.SYNCED);
    }

    @Test
    void syncEnabledStaleIsUnsynced() {
        assertThat(WorldsPanel.computeConsolidatedStatus(
                ConflictStatus.NONE, false, false, true, UpToDateStatus.STALE))
                .isEqualTo(ConsolidatedStatus.UNSYNCED);
    }

    @Test
    void syncEnabledUnknownIsUnsynced() {
        assertThat(WorldsPanel.computeConsolidatedStatus(
                ConflictStatus.NONE, false, false, true, UpToDateStatus.UNKNOWN))
                .isEqualTo(ConsolidatedStatus.UNSYNCED);
    }

    @Test
    void syncDisabledIsUnsyncedRegardlessOfFreshness() {
        assertThat(WorldsPanel.computeConsolidatedStatus(
                ConflictStatus.NONE, false, false, false, UpToDateStatus.UP_TO_DATE))
                .isEqualTo(ConsolidatedStatus.UNSYNCED);
    }

    // -- sync-conflict-coverage-gaps Gaps 1-3: computeBlocked/computeShowResolveButton --

    @Test
    void syncEnabledUnknownFreshnessIsBlocked() {
        assertThat(WorldsPanel.computeBlocked(false, false, false, true, UpToDateStatus.UNKNOWN)).isTrue();
    }

    @Test
    void syncDisabledUnknownFreshnessIsNotBlocked() {
        assertThat(WorldsPanel.computeBlocked(false, false, false, false, UpToDateStatus.UNKNOWN)).isFalse();
    }

    @Test
    void unknownFreshnessCombinedWithRowSyncingIsStillBlocked() {
        assertThat(WorldsPanel.computeBlocked(true, false, false, true, UpToDateStatus.UNKNOWN)).isTrue();
    }

    @Test
    void unknownFreshnessCombinedWithConflictIsStillBlocked() {
        assertThat(WorldsPanel.computeBlocked(false, true, false, true, UpToDateStatus.UNKNOWN)).isTrue();
    }

    @Test
    void syncEnabledStaleFreshnessIsBlocked() {
        assertThat(WorldsPanel.computeBlocked(false, false, false, true, UpToDateStatus.STALE)).isTrue();
    }

    @Test
    void syncDisabledStaleFreshnessIsNotBlocked() {
        assertThat(WorldsPanel.computeBlocked(false, false, false, false, UpToDateStatus.STALE)).isFalse();
    }

    @Test
    void staleFreshnessCombinedWithConflictIsStillBlocked() {
        assertThat(WorldsPanel.computeBlocked(false, true, false, true, UpToDateStatus.STALE)).isTrue();
    }

    @Test
    void syncEnabledUpToDateIsNotBlockedByItself() {
        assertThat(WorldsPanel.computeBlocked(false, false, false, true, UpToDateStatus.UP_TO_DATE)).isFalse();
    }

    @Test
    void checkingConflictBlocksPlayEditExactlyLikeRowSyncing() {
        assertThat(WorldsPanel.computeBlocked(false, false, true, false, UpToDateStatus.UP_TO_DATE)).isTrue();
    }

    @Test
    void notCheckingNotSyncingNotConflictedNotStaleNotUnknownIsNotBlocked() {
        assertThat(WorldsPanel.computeBlocked(false, false, false, true, UpToDateStatus.UP_TO_DATE)).isFalse();
    }

    @Test
    void resolveButtonVisibleForTrueConflict() {
        assertThat(WorldsPanel.computeShowResolveButton(true, false, UpToDateStatus.UNKNOWN)).isTrue();
    }

    @Test
    void resolveButtonVisibleForSyncEnabledStale() {
        assertThat(WorldsPanel.computeShowResolveButton(false, true, UpToDateStatus.STALE)).isTrue();
    }

    @Test
    void resolveButtonHiddenForSyncDisabledStale() {
        assertThat(WorldsPanel.computeShowResolveButton(false, false, UpToDateStatus.STALE)).isFalse();
    }

    @Test
    void resolveButtonHiddenForNonStaleNonConflict() {
        assertThat(WorldsPanel.computeShowResolveButton(false, true, UpToDateStatus.UP_TO_DATE)).isFalse();
        assertThat(WorldsPanel.computeShowResolveButton(false, true, UpToDateStatus.UNKNOWN)).isFalse();
    }

    // -- Request 2 (cloud-sync-threshold-and-full-sync-only): computeShowStatusIndicator --

    @Test
    void statusIndicatorVisibleWhenSyncEnabledAndNotConflicted() {
        assertThat(WorldsPanel.computeShowStatusIndicator(true, false)).isTrue();
    }

    @Test
    void statusIndicatorHiddenWhenSyncDisabledAndNotConflicted() {
        assertThat(WorldsPanel.computeShowStatusIndicator(false, false)).isFalse();
    }

    @Test
    void statusIndicatorVisibleWhenSyncDisabledButConflicted() {
        assertThat(WorldsPanel.computeShowStatusIndicator(false, true)).isTrue();
    }

    @Test
    void statusIndicatorVisibleWhenSyncEnabledAndConflicted() {
        assertThat(WorldsPanel.computeShowStatusIndicator(true, true)).isTrue();
    }
}
