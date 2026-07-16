package de.lazuli.api.cloudsync;

/**
 * Stable, Minecraft-free abstraction over restoring a cloud-only world
 * (FR6.10-FR6.13 of {@code steam-cloud-sync}'s specification) onto this
 * device, consumed by the platform's {@code WorldRestoreScreen} and
 * implemented by {@code features/steam-cloud-sync}'s own
 * {@code WorldRestoreService}.
 *
 * <p>Usage example (from {@code WorldRestoreScreen}, a thin platform
 * {@code Screen} subclass holding a constructor-injected
 * {@code WorldRestoreHook}):
 * <pre>{@code
 * WorldRestoreHook hook = ...; // supplied by the platform composition root
 * RestoreHandle handle = hook.beginRestore(worldSlug, new RestoreProgressListener() {
 *     public void onProgress(RestoreProgress progress) { latestProgress.set(progress); }
 *     public void onComplete(String worldSlug) { returnToWorldSelectScreen(); }
 *     public void onFailed(String worldSlug, String reason) { showError(reason); }
 * });
 * cancelButton.onClick(() -> hook.cancelRestore(handle));
 * }</pre>
 */
public interface WorldRestoreHook {

    /**
     * Begins restoring {@code worldSlug} from this device's Steam Cloud
     * archive into a new local world folder, off the render/client thread.
     * Aborts immediately (via {@link RestoreProgressListener#onFailed}, no
     * extraction ever begun) if a local world folder already exists for this
     * slug (FR6.13).
     *
     * @param worldSlug the cloud-only world's slug (its intended save-folder
     *                  name), as reported by {@link CloudOnlyWorldsHook}
     * @param listener  receives progress/completion/failure callbacks; may be
     *                  called from a background thread, never the render
     *                  thread directly
     * @return an opaque handle identifying this restore attempt, usable with
     *         {@link #cancelRestore(RestoreHandle)}
     */
    RestoreHandle beginRestore(String worldSlug, RestoreProgressListener listener);

    /**
     * Cancels an in-progress restore, if still running, and cleans up its
     * staging directory in full (FR6.12) -- a no-op if the restore already
     * completed, failed, or does not correspond to a currently-running
     * attempt.
     *
     * @param handle the handle previously returned by
     *               {@link #beginRestore(String, RestoreProgressListener)}
     */
    void cancelRestore(RestoreHandle handle);
}
