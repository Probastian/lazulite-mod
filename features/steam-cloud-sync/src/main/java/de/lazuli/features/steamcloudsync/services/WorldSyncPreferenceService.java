package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.WorldSyncToggleHook;
import de.lazuli.features.steamcloudsync.api.WorldSyncPreference;
import de.lazuli.features.steamcloudsync.config.WorldSyncPreferencesIO;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Pure, local-only CRUD over the per-world Group 6 sync preference list
 * (FR6.1). Never touches Cloud itself -- this list is deliberately never
 * synced (Non-goals): the toggle only has meaning for a world that already
 * exists on this specific device.
 *
 * <p>Implements {@link WorldSyncToggleHook} so a platform Version Adapter
 * (the per-world sync-toggle icon, FR6.1) can hold this service directly.
 *
 * <p>Usage example:
 * <pre>{@code
 * WorldSyncPreferenceService service = new WorldSyncPreferenceService(preferencesFilePath, LazuliMod.LOGGER::warn);
 * service.load();
 * boolean syncing = service.isSyncEnabled("my_world_folder");
 * service.toggleSync("my_world_folder");
 * }</pre>
 */
public final class WorldSyncPreferenceService implements WorldSyncToggleHook {

    private final WorldSyncPreferencesIO io = new WorldSyncPreferencesIO();
    private final Path preferencesFilePath;
    private final Consumer<String> warningLogger;
    private final Consumer<String> infoLogger;
    private final Map<String, Boolean> preferences = new LinkedHashMap<>();
    private volatile Consumer<String> onSyncEnabledListener = worldSlug -> { };
    private volatile Consumer<String> onSyncDisabledListener = worldSlug -> { };

    /**
     * @param preferencesFilePath the local-only preferences file's location
     * @param warningLogger       receives a human-readable message for any
     *                            I/O failure; never invoked with a thrown
     *                            exception
     */
    public WorldSyncPreferenceService(Path preferencesFilePath, Consumer<String> warningLogger) {
        this(preferencesFilePath, warningLogger, message -> { });
    }

    /**
     * @param preferencesFilePath the local-only preferences file's location
     * @param warningLogger       receives a human-readable message for any
     *                            I/O failure; never invoked with a thrown
     *                            exception
     * @param infoLogger          receives a human-readable message whenever a
     *                            world's sync preference is toggled/persisted
     */
    public WorldSyncPreferenceService(Path preferencesFilePath, Consumer<String> warningLogger, Consumer<String> infoLogger) {
        this.preferencesFilePath = Objects.requireNonNull(preferencesFilePath, "preferencesFilePath");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.infoLogger = Objects.requireNonNull(infoLogger, "infoLogger");
    }

    /**
     * Loads the preference list from disk. Call once, before this service is
     * used (typically at the client-startup checkpoint, FR0.3).
     */
    public synchronized void load() {
        WorldSyncPreferencesIO.ParseResult result = io.load(preferencesFilePath);
        if (result.warning() != null) {
            warningLogger.accept(result.warning());
        }
        preferences.clear();
        for (WorldSyncPreference preference : result.preferences()) {
            preferences.put(preference.worldSlug(), preference.enabled());
        }
    }

    @Override
    public synchronized boolean isSyncEnabled(String worldSlug) {
        return preferences.getOrDefault(worldSlug, Boolean.FALSE);
    }

    @Override
    public synchronized void toggleSync(String worldSlug) {
        boolean newValue = !isSyncEnabled(worldSlug);
        preferences.put(worldSlug, newValue);
        infoLogger.accept("Cloud sync " + (newValue ? "enabled" : "disabled") + " for world \"" + worldSlug + "\".");
        persist();
        if (newValue) {
            // Gap 2 (sync-conflict-coverage-gaps spec): only the
            // disabled->enabled transition needs the strict conflict gate --
            // fired after persist() so the preference is already durable
            // before any async check/upload can observe it.
            onSyncEnabledListener.accept(worldSlug);
        } else {
            // Request 3 (cloud-sync-threshold-and-full-sync-only): the
            // enabled->disabled transition triggers un-sync Cloud deletion.
            onSyncDisabledListener.accept(worldSlug);
        }
    }

    /**
     * Registers the listener invoked, with {@code worldSlug}, immediately
     * after {@link #toggleSync(String)} transitions a world from disabled to
     * enabled (Gap 2 of sync-conflict-coverage-gaps). Wired by the platform
     * composition root ({@code CloudSyncCoordinator}) to
     * {@code WorldSaveSyncService.handleSyncReenabled}, after both services
     * are constructed. Not a constructor parameter because
     * {@code WorldSaveSyncService} itself depends on this service, creating
     * an unavoidable construction-order cycle otherwise.
     *
     * @param listener receives the re-enabled world's slug; never invoked
     *                 with a thrown exception's caller left unguarded --
     *                 callers are responsible for their own exception safety
     */
    public void setOnSyncEnabledListener(Consumer<String> listener) {
        this.onSyncEnabledListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Registers the listener invoked, with {@code worldSlug}, immediately
     * after {@link #toggleSync(String)} transitions a world from enabled to
     * disabled (Request 3 of cloud-sync-threshold-and-full-sync-only).
     * Wired by the platform composition root ({@code CloudSyncCoordinator})
     * to {@code WorldSaveSyncService.handleSyncDisabled}, after both
     * services are constructed -- mirrors
     * {@link #setOnSyncEnabledListener(Consumer)}'s same construction-order-
     * cycle rationale.
     *
     * @param listener receives the disabled world's slug; never invoked with
     *                 a thrown exception's caller left unguarded -- callers
     *                 are responsible for their own exception safety
     */
    public void setOnSyncDisabledListener(Consumer<String> listener) {
        this.onSyncDisabledListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Sets {@code worldSlug}'s preference directly, without toggling.
     *
     * @param worldSlug the world's on-disk save-folder name
     * @param enabled   the new preference value
     */
    public synchronized void setSyncEnabled(String worldSlug, boolean enabled) {
        preferences.put(worldSlug, enabled);
        persist();
    }

    /**
     * Marks {@code worldSlug} as sync-enabled -- the one deliberate exception
     * to FR6.1's otherwise-conservative default, invoked immediately after a
     * successful restore (FR6.10): the world's mere presence in the Cloud
     * fingerprint file is itself proof a previous device already opted it
     * in, so this device does the same the moment it restores it.
     *
     * @param worldSlug the just-restored world's slug
     */
    public synchronized void markEnabledAfterRestore(String worldSlug) {
        setSyncEnabled(worldSlug, true);
    }

    /**
     * FR3.4/FR2.2 step 1 of the cloud-sync-uuid-identity spec: moves the
     * preference entry (if any) from {@code oldFolderName} to
     * {@code newFolderName}, preserving its enabled/disabled value, and
     * persists the change. A no-op if no entry exists under
     * {@code oldFolderName}. Called only from
     * {@code WorldCloudMigrationService}'s Phase B, immediately after a
     * successful physical folder rename.
     *
     * @param oldFolderName the folder's name before the rename
     * @param newFolderName the folder's name after the rename (its {@code cloudWorldId})
     */
    public synchronized void renameKey(String oldFolderName, String newFolderName) {
        if (!preferences.containsKey(oldFolderName)) {
            return;
        }
        boolean enabled = preferences.remove(oldFolderName);
        preferences.put(newFolderName, enabled);
        persist();
    }

    /** @return every currently-known preference entry */
    public synchronized List<WorldSyncPreference> list() {
        List<WorldSyncPreference> result = new ArrayList<>();
        preferences.forEach((slug, enabled) -> result.add(new WorldSyncPreference(slug, enabled)));
        return List.copyOf(result);
    }

    private void persist() {
        try {
            io.save(preferencesFilePath, list());
        } catch (IOException e) {
            warningLogger.accept("Failed to write " + preferencesFilePath + ": " + e);
        }
    }
}
