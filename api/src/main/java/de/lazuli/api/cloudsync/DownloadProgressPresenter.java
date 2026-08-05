package de.lazuli.api.cloudsync;

/**
 * Single, non-duplicated, Minecraft-free presentation-layer helper for the
 * "Play Cloud-Only World" download screen (cloud-world-download spec, FR3-FR5):
 * combines the two sequential {@link RestoreProgress.Phase}s into one 0-100%
 * scale (FR3), formats bytes into a human-readable binary-unit string (FR4),
 * and computes a once-per-second ETA string (FR5).
 *
 * <p>Instantiated once per screen instance (one per in-flight download the
 * player is currently watching), fed every {@link RestoreProgress} snapshot
 * the screen polls on the render thread via {@link #onProgress(RestoreProgress)},
 * and queried every render frame via {@link #currentStats(long)} (which
 * internally gates its own ETA recomputation to at most once per second, so
 * it is cheap to call unconditionally every frame).
 *
 * <p>Because the extracting phase's total byte count is only known once the
 * archive has been fully read (see {@code WorldRestoreService.extractAndFinish}),
 * this presenter's {@code extractingTotalBytes} constructor argument is only a
 * best-effort placeholder; both phases' totals are refined in place by
 * {@link #onProgress(RestoreProgress)} whenever a snapshot reports a positive
 * {@code totalBytes()} for its phase, so callers should simply pass the
 * best value known at construction time (e.g. the reading-phase's already-known
 * total for both arguments) and let this class self-correct as real totals
 * arrive.
 *
 * <p>Not thread-safe by design: per the governing Threading rule (this
 * project's existing {@code WorldRestoreScreen} javadoc), this class is only
 * ever touched from the render thread, which is also the only thread that
 * ever reads the screen's {@code AtomicReference<RestoreProgress>} snapshot.
 */
public final class DownloadProgressPresenter {

    private static final long ETA_UPDATE_INTERVAL_MILLIS = 1000L;
    private static final long ETA_MIN_ELAPSED_MILLIS = 2000L;

    private long readingProcessedBytes;
    private long readingTotalBytes;
    private long extractingProcessedBytes;
    private long extractingTotalBytes;
    private RestoreProgress.Phase currentPhase;

    private long startMillis;
    private long lastEtaUpdateMillis = -1L;
    private String lastEtaText = "Calculating...";

    /**
     * @param readingTotalBytes             best-known total for the
     *                                       {@code READING_FROM_CLOUD} phase
     *                                       (the compressed archive size,
     *                                       normally already known up front)
     * @param extractingEstimatedTotalBytes best-known/placeholder total for
     *                                       the {@code EXTRACTING} phase;
     *                                       refined in place by the first
     *                                       {@code EXTRACTING} snapshot's own
     *                                       {@code totalBytes()}
     */
    public DownloadProgressPresenter(long readingTotalBytes, long extractingEstimatedTotalBytes) {
        this.readingTotalBytes = Math.max(0L, readingTotalBytes);
        this.extractingTotalBytes = Math.max(0L, extractingEstimatedTotalBytes);
    }

    /**
     * Records the latest known snapshot for its phase. Safe to call every
     * render frame with the same snapshot repeatedly (idempotent).
     */
    public void onProgress(RestoreProgress progress) {
        currentPhase = progress.phase();
        switch (progress.phase()) {
            case READING_FROM_CLOUD -> {
                readingProcessedBytes = progress.processedBytes();
                if (progress.totalBytes() > 0) {
                    readingTotalBytes = progress.totalBytes();
                }
            }
            case EXTRACTING -> {
                extractingProcessedBytes = progress.processedBytes();
                if (progress.totalBytes() > 0) {
                    extractingTotalBytes = progress.totalBytes();
                }
            }
        }
    }

    /**
     * @param nowMillis the caller's current time (e.g.
     *                  {@code System.currentTimeMillis()}); the ETA text is
     *                  only recomputed once at least
     *                  {@value #ETA_UPDATE_INTERVAL_MILLIS}ms have elapsed
     *                  since the previous call recomputed it (FR5.2)
     */
    public DownloadDisplayStats currentStats(long nowMillis) {
        if (startMillis == 0L) {
            startMillis = nowMillis;
        }
        float overallFraction = combinedFraction(
                readingProcessedBytes, readingTotalBytes, extractingProcessedBytes, extractingTotalBytes, currentPhase);
        int percentage = Math.round(overallFraction * 100f);
        percentage = Math.max(0, Math.min(100, percentage));

        boolean extracting = currentPhase == RestoreProgress.Phase.EXTRACTING;
        long phaseProcessed = extracting ? extractingProcessedBytes : readingProcessedBytes;
        long phaseTotal = extracting ? extractingTotalBytes : readingTotalBytes;
        String currentSizeText = formatBytes(phaseProcessed);
        String totalSizeText = formatBytes(phaseTotal);

        String etaText;
        if (lastEtaUpdateMillis < 0 || nowMillis - lastEtaUpdateMillis >= ETA_UPDATE_INTERVAL_MILLIS) {
            etaText = computeEta(nowMillis, overallFraction);
            lastEtaText = etaText;
            lastEtaUpdateMillis = nowMillis;
        } else {
            etaText = lastEtaText;
        }

        return new DownloadDisplayStats(overallFraction, percentage, currentSizeText, totalSizeText, etaText);
    }

    private String computeEta(long nowMillis, float overallFraction) {
        if (startMillis == 0L) {
            return "Calculating...";
        }
        long elapsedMillis = nowMillis - startMillis;
        if (elapsedMillis < ETA_MIN_ELAPSED_MILLIS || overallFraction <= 0f) {
            return "Calculating...";
        }
        double elapsedSeconds = elapsedMillis / 1000.0;
        double rate = overallFraction / elapsedSeconds; // fraction of the whole download, per second
        if (rate <= 0.0) {
            return "Calculating...";
        }
        double remainingFraction = Math.max(0.0, 1.0 - overallFraction);
        double etaSeconds = remainingFraction / rate;
        return formatEta(etaSeconds);
    }

    private static String formatEta(double etaSeconds) {
        if (etaSeconds < 60.0) {
            long seconds = Math.round(etaSeconds);
            return "About " + seconds + "s remaining";
        }
        long minutes = Math.round(etaSeconds / 60.0);
        if (minutes < 1) {
            minutes = 1;
        }
        return "About " + minutes + "m remaining";
    }

    /**
     * FR3.2's weighted combination of the two sequential phases into one
     * 0.0-1.0 fraction, exposed as a static, stateless helper so
     * {@code WorldRestoreService} (FR6.2's milestone logging) can reuse the
     * exact same math without duplicating it (Goal 3).
     *
     * <p>Weights are each phase's own total bytes relative to the combined
     * total; a phase with a zero/unknown total contributes zero weight (and
     * is treated as not-yet-started). Once {@code currentPhase} has advanced
     * past {@code READING_FROM_CLOUD}, that phase's own fraction is assumed
     * {@code 1} (finished) regardless of its last-known processed/total
     * numbers, per FR3.2, which keeps the combined result from ever moving
     * backwards as the phases transition.
     */
    public static float combinedFraction(
            long readingProcessedBytes,
            long readingTotalBytes,
            long extractingProcessedBytes,
            long extractingTotalBytes,
            RestoreProgress.Phase currentPhase) {
        long combinedTotal = Math.max(0L, readingTotalBytes) + Math.max(0L, extractingTotalBytes);
        if (combinedTotal <= 0L) {
            return 0f;
        }
        float readingWeight = (float) readingTotalBytes / combinedTotal;
        float extractingWeight = 1f - readingWeight;

        boolean extractingStartedOrDone = currentPhase == RestoreProgress.Phase.EXTRACTING;

        float readingFraction;
        if (extractingStartedOrDone) {
            readingFraction = 1f; // reading phase is finished once extraction has begun
        } else if (readingTotalBytes > 0) {
            readingFraction = clamp01((float) readingProcessedBytes / readingTotalBytes);
        } else {
            readingFraction = 0f;
        }

        float extractingFraction;
        if (extractingStartedOrDone && extractingTotalBytes > 0) {
            extractingFraction = clamp01((float) extractingProcessedBytes / extractingTotalBytes);
        } else {
            extractingFraction = 0f;
        }

        return clamp01(readingWeight * readingFraction + extractingWeight * extractingFraction);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * FR4.1/FR4.2: renders a byte count as a human-readable binary-unit
     * string -- {@code "B"} for values below 1024, {@code "KB"}/{@code "MB"}/
     * {@code "GB"}/{@code "TB"} above that (1024-based), one decimal place at
     * {@code KB} and above (e.g. {@code "512 B"}, {@code "4.2 KB"},
     * {@code "118.0 MB"}, {@code "1.3 GB"}). Exposed as a public static
     * method so both this presenter and {@code WorldRestoreService}'s FR6.2
     * milestone logging share the exact same formatting (Goal 3).
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        if (gb < 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.1f GB", gb);
        }
        double tb = gb / 1024.0;
        return String.format(java.util.Locale.ROOT, "%.1f TB", tb);
    }

    /**
     * FR1-FR5's four display values for one render frame.
     *
     * @param overallFraction FR3.2, clamped [0,1]
     * @param percentage      FR1.3, 0-100
     * @param currentSizeText FR1.4/FR4, phase-scoped
     * @param totalSizeText   FR1.4/FR4, phase-scoped
     * @param etaText         FR1.5/FR5
     */
    public record DownloadDisplayStats(
            float overallFraction, int percentage, String currentSizeText, String totalSizeText, String etaText) {
    }
}
