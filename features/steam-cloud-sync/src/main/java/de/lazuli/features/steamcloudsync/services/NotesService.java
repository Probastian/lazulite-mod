package de.lazuli.features.steamcloudsync.services;

import de.lazuli.features.steamcloudsync.api.Note;
import de.lazuli.features.steamcloudsync.config.NotesIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Pure CRUD plus Cloud sync for Group 5 (personal notes/waypoints,
 * FR5.1-FR5.3). Ships with no in-game HUD/waypoint-rendering or management
 * screen in v1 (Future Extension) -- this service is the entirety of Group
 * 5's implementation.
 *
 * <p>Same local-write-first, push-on-mutation discipline as
 * {@code BookmarkedServersService}; see that class's JavaDoc for the
 * shared rationale.
 *
 * <p>Usage example:
 * <pre>{@code
 * NotesService service = new NotesService(cloudFileStore, localFilePath, true, LazuliMod.LOGGER::warn);
 * service.reconcileAtStartup();
 * service.add("Diamond vein", "my_world_folder", 120.0, 12.0, -45.0);
 * }</pre>
 */
public final class NotesService {

    private static final String CLOUD_FILE_NAME = "lazuli-notes.json";

    private final CloudFileStore cloudFileStore;
    private final NotesIO io = new NotesIO();
    private final Path localFilePath;
    private final boolean cloudSyncEnabled;
    private final Consumer<String> warningLogger;
    private final List<Note> entries = new ArrayList<>();

    /**
     * @param cloudFileStore   the Cloud seam to sync through (real or no-op)
     * @param localFilePath    this device's own local notes file
     * @param cloudSyncEnabled whether Cloud sync is currently enabled for
     *                         this group (master switch AND'd with
     *                         {@code syncNotes})
     * @param warningLogger    receives a human-readable message for any I/O
     *                         failure; never invoked with a thrown exception
     */
    public NotesService(CloudFileStore cloudFileStore, Path localFilePath, boolean cloudSyncEnabled, Consumer<String> warningLogger) {
        this.cloudFileStore = Objects.requireNonNull(cloudFileStore, "cloudFileStore");
        this.localFilePath = Objects.requireNonNull(localFilePath, "localFilePath");
        this.cloudSyncEnabled = cloudSyncEnabled;
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
    }

    /** @return every currently-known note, in insertion order */
    public synchronized List<Note> list() {
        return List.copyOf(entries);
    }

    /**
     * Adds a pure text reminder with no attached location (FR5.1).
     *
     * @param text the note's text content
     * @return the newly-created note
     */
    public synchronized Note add(String text) {
        return add(text, null, null, null, null);
    }

    /**
     * Adds a location-bound waypoint (FR5.1).
     *
     * @param text    the note's text content
     * @param context the world folder name or server address this note is
     *                attached to, or {@code null} for none
     * @param x       x coordinate, or {@code null} for none
     * @param y       y coordinate, or {@code null} for none
     * @param z       z coordinate, or {@code null} for none
     * @return the newly-created note
     */
    public synchronized Note add(String text, String context, Double x, Double y, Double z) {
        Note entry = new Note(UUID.randomUUID().toString(), text, context, x, y, z, System.currentTimeMillis());
        entries.add(entry);
        persistAndSync();
        return entry;
    }

    /**
     * Removes a note by id and persists the change.
     *
     * @param id the note's id
     * @return {@code true} if a note was found and removed
     */
    public synchronized boolean remove(String id) {
        boolean removed = entries.removeIf(entry -> entry.id().equals(id));
        if (removed) {
            persistAndSync();
        }
        return removed;
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
     * client-shutdown checkpoint (FR0.3).
     */
    public synchronized void syncOnShutdown() {
        if (cloudSyncEnabled) {
            cloudFileStore.write(CLOUD_FILE_NAME, serializeCurrent());
        }
    }

    private List<Note> readLocalFile() {
        try {
            if (Files.notExists(localFilePath)) {
                return List.of();
            }
            NotesIO.ParseResult result = io.parse(Files.readString(localFilePath, StandardCharsets.UTF_8));
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
