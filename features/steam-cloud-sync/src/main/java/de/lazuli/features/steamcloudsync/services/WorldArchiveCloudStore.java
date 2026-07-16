package de.lazuli.features.steamcloudsync.services;

import java.util.OptionalLong;

/**
 * The streamed-write, async-read, quota, and {@code fileForget} surface
 * Group 6 (world save sync) alone needs -- kept separate from
 * {@link CloudFileStore} because quota/{@code fileForget} bookkeeping is
 * scoped to world archives only (FR0.6, FR6.7), never the small Groups
 * 1/3/4/5 files.
 *
 * <p>This is one of exactly two seams in this feature that a real Steam
 * implementation ({@code SteamRemoteStorageWorldArchiveStore}) sits behind --
 * {@code WorldSaveSyncService}/{@code WorldRestoreService} depend only on
 * this interface (or a hand-written fake of it in tests), never on
 * {@code com.codedisaster.steamworks.*} directly.
 *
 * <p>Usage example (upload path, from {@code WorldSaveSyncService}):
 * <pre>{@code
 * long[] total = new long[1];
 * long[] available = new long[1];
 * store.getQuota(total, available);
 * if (available[0] >= archiveBytes.length) {
 *     store.streamWrite("lazuli-world-my_world.zip", archiveBytes);
 * }
 * }</pre>
 *
 * <p>Usage example (restore path, from {@code WorldRestoreService}):
 * <pre>{@code
 * store.beginAsyncRead("lazuli-world-my_world.zip", new WorldArchiveCloudStore.AsyncReadListener() {
 *     public void onChunk(byte[] chunk) { archiveBuffer.write(chunk); }
 *     public void onComplete() { extractArchive(archiveBuffer.toByteArray()); }
 *     public void onFailed(String reason) { listener.onFailed(worldSlug, reason); }
 * });
 * }</pre>
 */
public interface WorldArchiveCloudStore {

    /**
     * @return {@code true} if this store is backed by a real, available
     *         Steam Cloud session; used only for logging/diagnostics
     */
    boolean isAvailable();

    /**
     * Writes {@code data} as the full contents of {@code fileName}, chunked
     * internally under Valve's documented 100MB-per-call cap regardless of
     * {@code data}'s total size (FR6.3) -- steamworks4j's streamed-write
     * trio ({@code fileWriteStreamOpen}/{@code WriteChunk}/{@code Close}).
     *
     * @param fileName the flat, lowercase Cloud file name
     * @param data     the already-compressed archive bytes to write
     * @return {@code true} if the entire streamed write succeeded
     */
    boolean streamWrite(String fileName, byte[] data);

    /**
     * Begins an asynchronous, chunked read of {@code fileName}'s full
     * contents, off the render/client thread. Because Steam Cloud fully
     * downloads all Cloud files to the local machine before the game
     * launches, this is always effectively a fast local read (FR6.11).
     *
     * @param fileName the flat, lowercase Cloud file name
     * @param listener receives the read chunks/completion/failure; may be
     *                 called from a background thread
     */
    void beginAsyncRead(String fileName, AsyncReadListener listener);

    /**
     * @param fileName the flat, lowercase Cloud file name
     * @return the file's size in bytes, or {@code -1} if it does not exist or
     *         Steam is unavailable (steamworks4j's own {@code getFileSize}
     *         returns {@code int} -- a hard ~2 GiB ceiling per file, see
     *         {@code minecraft.md}-equivalent note in this feature's
     *         implementation plan, Decision 6)
     */
    int fileSize(String fileName);

    /**
     * @param fileName the flat, lowercase Cloud file name
     * @return the file's last-write timestamp on Steam Cloud, or empty if it
     *         does not exist or Steam is unavailable
     */
    OptionalLong fileTimestamp(String fileName);

    /**
     * @param totalBytes     out-parameter (length-1 array), receives this
     *                       user's total configured Cloud quota in bytes
     * @param availableBytes out-parameter (length-1 array), receives this
     *                       user's currently-available Cloud quota in bytes
     * @return {@code true} if the quota query succeeded
     */
    boolean getQuota(long[] totalBytes, long[] availableBytes);

    /**
     * Removes {@code fileName} from Steam Cloud only, leaving any local copy
     * untouched (Valve's own documented {@code fileForget} behavior) -- used
     * for quota housekeeping (FR6.7); {@code fileDelete} (which also
     * propagates a delete) is never used for this purpose (FR0.6).
     *
     * @param fileName the flat, lowercase Cloud file name to forget
     * @return {@code true} if the operation succeeded
     */
    boolean forget(String fileName);

    /**
     * Receives the result of one {@link #beginAsyncRead(String, AsyncReadListener)}
     * call.
     */
    interface AsyncReadListener {
        /**
         * Invoked repeatedly, once per chunk read, in order.
         *
         * @param chunk the next chunk of the file's bytes
         */
        void onChunk(byte[] chunk);

        /**
         * Invoked exactly once, after every chunk has been delivered
         * successfully.
         */
        void onComplete();

        /**
         * Invoked exactly once, if the read failed at any point; no further
         * {@link #onChunk(byte[])} calls follow.
         *
         * @param reason a human-readable failure reason
         */
        void onFailed(String reason);
    }
}
