package de.lazuli.features.steamcloudsync.api;

/**
 * A single world's local, never-Cloud-synced Group 6 sync preference
 * (FR6.1): whether <em>this device</em> currently syncs that world's save
 * folder to Steam Cloud.
 *
 * <p>Usage example:
 * <pre>{@code
 * WorldSyncPreference preference = new WorldSyncPreference("my_world_folder", true);
 * }</pre>
 *
 * @param worldSlug the world's on-disk save-folder name
 * @param enabled   whether Cloud sync is currently enabled for this world on
 *                  this device
 */
public record WorldSyncPreference(String worldSlug, boolean enabled) {
}
