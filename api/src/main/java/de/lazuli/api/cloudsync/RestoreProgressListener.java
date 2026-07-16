package de.lazuli.api.cloudsync;

/**
 * Receives progress/completion/failure callbacks for one in-flight world
 * restore attempt (FR6.10-FR6.12 of {@code steam-cloud-sync}'s
 * specification). Implementations may be called from a background thread
 * (see {@code CloudSyncWorker}); they must never block or perform Minecraft
 * rendering calls directly -- typically an implementation simply publishes
 * the latest {@link RestoreProgress} to a {@code volatile}/
 * {@code AtomicReference} field for the render thread to poll.
 *
 * <p>Usage example:
 * <pre>{@code
 * RestoreProgressListener listener = new RestoreProgressListener() {
 *     public void onProgress(RestoreProgress progress) {
 *         latestProgress.set(progress);
 *     }
 *     public void onComplete(String worldSlug) {
 *         completed.set(true);
 *     }
 *     public void onFailed(String worldSlug, String reason) {
 *         failureReason.set(reason);
 *     }
 * };
 * }</pre>
 */
public interface RestoreProgressListener {

    /**
     * Invoked repeatedly as a restore makes progress through either phase.
     *
     * @param progress the latest progress snapshot; never {@code null}
     */
    void onProgress(RestoreProgress progress);

    /**
     * Invoked exactly once, when the restore has fully completed and the
     * world is now a normal, playable local world.
     *
     * @param worldSlug the restored world's slug
     */
    void onComplete(String worldSlug);

    /**
     * Invoked exactly once, when the restore has failed or been cancelled.
     * The staging directory has already been fully cleaned up by the time
     * this is invoked; the world remains cloud-only.
     *
     * @param worldSlug the world that failed to restore
     * @param reason    a human-readable, player-visible reason
     */
    void onFailed(String worldSlug, String reason);
}
