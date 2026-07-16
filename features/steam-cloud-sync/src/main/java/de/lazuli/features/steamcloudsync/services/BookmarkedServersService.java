package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.BookmarkSyncHook;
import de.lazuli.features.steamcloudsync.api.BookmarkedServer;
import de.lazuli.features.steamcloudsync.config.BookmarkedServersIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Pure CRUD (add/remove/rename) plus Cloud sync for Group 3 (bookmarked
 * servers, FR3.1-FR3.4), independent of and never reading/writing vanilla's
 * own {@code servers.dat}.
 *
 * <p>The local file is always written first (source of truth for a
 * single-device session, FR0.5); a Cloud push follows immediately after
 * every mutation (the group's own natural data-change checkpoint, FR0.3),
 * gated on this service's {@code cloudSyncEnabled} flag (this feature's
 * master switch AND'd with {@code syncBookmarkedServers}, resolved once at
 * construction by the caller).
 *
 * <p>Implements {@link BookmarkSyncHook} so a platform Version Adapter (the
 * Multiplayer-screen bookmark-toggle widget, FR3.3) can hold this service
 * directly through that {@code api}-layer contract.
 *
 * <p>Usage example:
 * <pre>{@code
 * BookmarkedServersService service = new BookmarkedServersService(
 *         cloudFileStore, localFilePath, true, LazuliMod.LOGGER::warn);
 * service.reconcileAtStartup();
 * service.add("My Server", "play.example.com:25565");
 * }</pre>
 */
public final class BookmarkedServersService implements BookmarkSyncHook {

    private static final String CLOUD_FILE_NAME = "lazuli-bookmarked-servers.json";

    private final CloudFileStore cloudFileStore;
    private final BookmarkedServersIO io = new BookmarkedServersIO();
    private final Path localFilePath;
    private final boolean cloudSyncEnabled;
    private final Consumer<String> warningLogger;
    private final List<BookmarkedServer> entries = new ArrayList<>();

    /**
     * @param cloudFileStore   the Cloud seam to sync through (real or no-op)
     * @param localFilePath    this device's own local bookmarks file
     * @param cloudSyncEnabled whether Cloud sync is currently enabled for
     *                         this group (master switch AND'd with
     *                         {@code syncBookmarkedServers})
     * @param warningLogger    receives a human-readable message for any I/O
     *                         failure; never invoked with a thrown exception
     */
    public BookmarkedServersService(
            CloudFileStore cloudFileStore, Path localFilePath, boolean cloudSyncEnabled, Consumer<String> warningLogger) {
        this.cloudFileStore = Objects.requireNonNull(cloudFileStore, "cloudFileStore");
        this.localFilePath = Objects.requireNonNull(localFilePath, "localFilePath");
        this.cloudSyncEnabled = cloudSyncEnabled;
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
    }

    /** @return every currently-known bookmark, in insertion order */
    public synchronized List<BookmarkedServer> list() {
        return List.copyOf(entries);
    }

    /**
     * Adds a new bookmark and persists it (local, then Cloud).
     *
     * @param label   a human-readable label
     * @param address the server address (host:port)
     * @return the newly-created bookmark
     */
    public synchronized BookmarkedServer add(String label, String address) {
        BookmarkedServer entry = new BookmarkedServer(UUID.randomUUID().toString(), label, address, System.currentTimeMillis());
        entries.add(entry);
        persistAndSync();
        return entry;
    }

    /**
     * Removes a bookmark by id and persists the change.
     *
     * @param id the bookmark's id
     * @return {@code true} if a bookmark was found and removed
     */
    public synchronized boolean remove(String id) {
        boolean removed = entries.removeIf(entry -> entry.id().equals(id));
        if (removed) {
            persistAndSync();
        }
        return removed;
    }

    /**
     * Renames an existing bookmark and persists the change.
     *
     * @param id       the bookmark's id
     * @param newLabel the new label
     * @return {@code true} if a bookmark was found and renamed
     */
    public synchronized boolean rename(String id, String newLabel) {
        for (int i = 0; i < entries.size(); i++) {
            BookmarkedServer entry = entries.get(i);
            if (entry.id().equals(id)) {
                entries.set(i, new BookmarkedServer(entry.id(), newLabel, entry.address(), entry.addedAt()));
                persistAndSync();
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized boolean isBookmarked(String address) {
        return entries.stream().anyMatch(entry -> entry.address().equals(address));
    }

    @Override
    public synchronized void toggleBookmark(String address, String label) {
        Optional<BookmarkedServer> existing = entries.stream().filter(entry -> entry.address().equals(address)).findFirst();
        if (existing.isPresent()) {
            remove(existing.get().id());
        } else {
            add(label, address);
        }
    }

    /**
     * Reconciles the local file against the Cloud copy (FR0.4), then loads
     * the winning side into memory. Call once at the client-startup
     * checkpoint (FR0.3).
     */
    public synchronized void reconcileAtStartup() {
        LocalCloudFileReconciler.reconcile(cloudFileStore, CLOUD_FILE_NAME, localFilePath, cloudSyncEnabled, warningLogger);
        entries.clear();
        entries.addAll(readLocalFile());
    }

    /**
     * Pushes the current in-memory state to Cloud. Call once at the
     * client-shutdown checkpoint (FR0.3) to capture in-session edits
     * (FR1.4-equivalent for this group).
     */
    public synchronized void syncOnShutdown() {
        if (cloudSyncEnabled) {
            cloudFileStore.write(CLOUD_FILE_NAME, serializeCurrent());
        }
    }

    private List<BookmarkedServer> readLocalFile() {
        try {
            if (Files.notExists(localFilePath)) {
                return List.of();
            }
            BookmarkedServersIO.ParseResult result = io.parse(Files.readString(localFilePath, StandardCharsets.UTF_8));
            if (result.warning() != null) {
                warningLogger.accept(result.warning());
            }
            return result.entries();
        } catch (IOException e) {
            warningLogger.accept("Failed to read " + localFilePath + ": " + e);
            return List.of();
        }
    }

    private void persistAndSync() {
        try {
            Path parent = localFilePath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(localFilePath, io.serialize(entries), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warningLogger.accept("Failed to write " + localFilePath + ": " + e);
        }
        if (cloudSyncEnabled) {
            cloudFileStore.write(CLOUD_FILE_NAME, serializeCurrent());
        }
    }

    private byte[] serializeCurrent() {
        return io.serialize(entries).getBytes(StandardCharsets.UTF_8);
    }
}
