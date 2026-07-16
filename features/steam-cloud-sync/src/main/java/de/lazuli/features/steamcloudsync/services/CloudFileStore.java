package de.lazuli.features.steamcloudsync.services;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * The minimal Cloud-file seam shared by Groups 1/3/4/5 (this feature's own
 * settings-aggregation reconciliation loop, bookmarked servers, the
 * continue-pointer, and notes/waypoints) -- small, whole-file
 * read/write/timestamp operations only. Deliberately does not expose
 * quota/{@code fileForget} bookkeeping, which is scoped to Group 6's world
 * archives only (see {@link WorldArchiveCloudStore}); FR0.6/FR6.7 of this
 * feature's specification confirm those concerns never apply to these small
 * files.
 *
 * <p>This is one of exactly two seams in this feature that a real Steam
 * implementation ({@code SteamRemoteStorageCloudFileStore}) sits behind --
 * every business-logic class in this feature depends only on this interface
 * (or a hand-written fake of it in tests), never on
 * {@code com.codedisaster.steamworks.*} directly, keeping NFR1's
 * plain-JVM-testability guarantee structural rather than a matter of
 * discipline.
 *
 * <p>Usage example (from a service under test, using a hand-written fake):
 * <pre>{@code
 * CloudFileStore store = new CloudFileStore() {
 *     private final Map<String, byte[]> files = new HashMap<>();
 *     public boolean isAvailable() { return true; }
 *     public Optional<byte[]> read(String fileName) { return Optional.ofNullable(files.get(fileName)); }
 *     public boolean write(String fileName, byte[] data) { files.put(fileName, data); return true; }
 *     public OptionalLong fileTimestamp(String fileName) { return files.containsKey(fileName) ? OptionalLong.of(0L) : OptionalLong.empty(); }
 * };
 * }</pre>
 */
public interface CloudFileStore {

    /**
     * @return {@code true} if this store is backed by a real, available
     *         Steam Cloud session; {@code false} for a {@code Noop}
     *         implementation (Steam unavailable) -- callers use this only
     *         for logging/diagnostics, never to branch business logic (every
     *         call is already safe to make unconditionally on either
     *         implementation)
     */
    boolean isAvailable();

    /**
     * Reads the full contents of {@code fileName} from Steam Cloud.
     *
     * @param fileName the flat, lowercase Cloud file name
     * @return the file's bytes, or empty if the file does not exist, Steam
     *         is unavailable, or the read failed; never throws
     */
    Optional<byte[]> read(String fileName);

    /**
     * Writes {@code data} as the full contents of {@code fileName} to Steam
     * Cloud, overwriting any previous contents (Valve's own
     * "creates a new file, writes the bytes, then closes the file"
     * semantics -- no merge).
     *
     * @param fileName the flat, lowercase Cloud file name
     * @param data     the bytes to write
     * @return {@code true} if the write succeeded; {@code false} if Steam is
     *         unavailable or the write failed; never throws
     */
    boolean write(String fileName, byte[] data);

    /**
     * @param fileName the flat, lowercase Cloud file name
     * @return the file's last-write timestamp on Steam Cloud, or empty if
     *         the file does not exist or Steam is unavailable
     */
    OptionalLong fileTimestamp(String fileName);
}
