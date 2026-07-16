package de.lazuli.api.cloudsync;

import java.util.List;

/**
 * Stable, Minecraft-free abstraction for discovering which worlds exist in
 * this device's Steam Cloud fingerprint metadata (FR6.6) but have no matching
 * local save folder yet (FR6.8) -- "cloud-only" worlds, rendered as distinct
 * synthetic rows on the vanilla Singleplayer world-select screen (FR6.9).
 *
 * <p>Implemented by {@code features/steam-cloud-sync}'s own
 * {@code CloudOnlyWorldsFacade} (a thin wrapper around the pure
 * {@code CloudOnlyWorldDetector} plus the already-pulled fingerprint file);
 * consumed by a platform Version Adapter that locates the Singleplayer
 * screen's list widget and appends one synthetic entry per summary returned
 * here (a Pattern 2 UI injection, per {@code ui-guidelines.md} -- needs a
 * {@code @Mixin}, unlike this feature's other, Pattern 1 UI hooks).
 *
 * <p>Usage example (from a platform Version Adapter holding a
 * constructor-injected {@code CloudOnlyWorldsHook}):
 * <pre>{@code
 * CloudOnlyWorldsHook hook = ...; // supplied by the platform composition root
 * List<String> localFolders = listLocalSaveFolderNames(); // Minecraft-specific, one line
 * for (CloudOnlyWorldSummary summary : hook.listCloudOnlyWorlds(localFolders)) {
 *     list.addEntry(new CloudOnlyWorldListEntry(summary));
 * }
 * }</pre>
 */
public interface CloudOnlyWorldsHook {

    /**
     * @param localWorldFolderNames every world save-folder name currently
     *                              present on this device's local saves
     *                              directory; computed on the platform side
     *                              (a one-line, Minecraft-specific directory
     *                              listing) and passed in as plain strings so
     *                              no Minecraft type crosses this boundary
     * @return every world present in this device's Cloud fingerprint metadata
     *         with no matching entry in {@code localWorldFolderNames}, in no
     *         particular order; never {@code null}, empty if none
     */
    List<CloudOnlyWorldSummary> listCloudOnlyWorlds(List<String> localWorldFolderNames);
}
