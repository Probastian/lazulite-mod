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
    private final Map<String, Boolean> preferences = new LinkedHashMap<>();

    /**
     * @param preferencesFilePath the local-only preferences file's location
     * @param warningLogger       receives a human-readable message for any
     *                            I/O failure; never invoked with a thrown
     *                            exception
     */
    public WorldSyncPreferenceService(Path preferencesFilePath, Consumer<String> warningLogger) {
        this.preferencesFilePath = Objects.requireNonNull(preferencesFilePath, "preferencesFilePath");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
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
        persist();
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
