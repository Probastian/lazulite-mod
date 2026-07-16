package de.lazuli.api.cloudsync;

/**
 * A plain, immutable snapshot of a world restore's progress (FR6.11 of
 * {@code steam-cloud-sync}'s specification), handed to a
 * {@link RestoreProgressListener} and polled by {@code WorldRestoreScreen}
 * once per render frame -- never a Minecraft-facing type itself.
 *
 * <p>Because Steam Cloud fully downloads all of a user's Cloud files to the
 * local machine automatically <em>before</em> the game launches, every read
 * reached during a restore is a fast local read -- there is no real
 * network-transfer-progress signal available. The progress this type reports
 * is therefore synthesized from two local phases (see {@link Phase}), not a
 * live network transfer; consumers must use "Restoring.../Extracting..."
 * framing, never "Downloading...".
 *
 * <p>Usage example (from {@code WorldRestoreScreen}, reading the latest
 * snapshot on the render thread):
 * <pre>{@code
 * RestoreProgress progress = latestProgress.get();
 * String status = switch (progress.phase()) {
 *     case READING_FROM_CLOUD -> "Restoring world from Steam Cloud...";
 *     case EXTRACTING -> "Extracting world files...";
 * };
 * float fraction = progress.totalBytes() <= 0 ? 0f
 *         : (float) progress.processedBytes() / progress.totalBytes();
 * }</pre>
 *
 * @param phase          which of the two local phases is currently running
 * @param processedBytes cumulative bytes processed so far within this phase
 * @param totalBytes     the total bytes expected for this phase
 */
public record RestoreProgress(Phase phase, long processedBytes, long totalBytes) {

    /**
     * The two local phases a restore synthesizes its progress bar from.
     */
    public enum Phase {
        /** Reading the archive's bytes from Steam Cloud's already-local cache. */
        READING_FROM_CLOUD,
        /** Decompressing/extracting the archive into the staging directory. */
        EXTRACTING
    }
}
