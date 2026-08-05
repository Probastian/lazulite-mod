package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.SteamCloudSyncConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Load/parse/serialize for {@code config/steam-cloud-sync.json} (this
 * feature's own master settings, see {@link SteamCloudSyncConfig}).
 *
 * <p>Malformed/missing content always falls back to
 * {@link SteamCloudSyncConfig#DEFAULT} with a logged warning, never by
 * throwing -- same discipline as {@code HelloWorldMainMenuConfigIO}.
 *
 * <p>Usage example:
 * <pre>{@code
 * SteamCloudSyncConfigIO configIO = new SteamCloudSyncConfigIO();
 * SteamCloudSyncConfigIO.ParseResult result = configIO.load(configPath);
 * if (result.warning() != null) {
 *     logger.warn(result.warning());
 * }
 * SteamCloudSyncConfig config = result.config();
 * }</pre>
 */
public final class SteamCloudSyncConfigIO {

    /**
     * @param config  the resolved configuration; never {@code null}
     * @param warning a human-readable warning, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(SteamCloudSyncConfig config, String warning) {
        private static ParseResult ok(SteamCloudSyncConfig config) {
            return new ParseResult(config, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(SteamCloudSyncConfig.DEFAULT, reason);
        }
    }

    /**
     * Loads the config from {@code path}, creating it with
     * {@link SteamCloudSyncConfig#DEFAULT} if absent. Never throws.
     *
     * @param path the config file's location
     * @return the resolved config, plus an optional warning
     */
    public ParseResult load(Path path) {
        try {
            if (Files.notExists(path)) {
                Path parent = path.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, serialize(SteamCloudSyncConfig.DEFAULT), StandardCharsets.UTF_8);
                return ParseResult.ok(SteamCloudSyncConfig.DEFAULT);
            }
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return ParseResult.fallback(
                    "Failed to load " + path + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Parses {@code content} as this feature's config schema. Never throws.
     *
     * @param content the raw JSON text
     * @return the resolved config, plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null) {
            return ParseResult.fallback("steam-cloud-sync config content was null; using defaults.");
        }
        try {
            CloudSyncJson.JsonValue value = CloudSyncJson.parse(content);
            if (!(value instanceof CloudSyncJson.JsonObject root)) {
                return ParseResult.fallback("steam-cloud-sync config root was not a JSON object; using defaults.");
            }
            SteamCloudSyncConfig config = new SteamCloudSyncConfig(
                    root.getInt("schemaVersion"),
                    root.getBoolean("enabled"),
                    root.getBoolean("syncSettings"),
                    root.getBoolean("syncAccessibility"),
                    root.getBoolean("syncBookmarkedServers"),
                    root.getBoolean("syncContinuePointer"),
                    root.getBoolean("syncNotes"));
            return ParseResult.ok(config);
        } catch (RuntimeException e) {
            return ParseResult.fallback(
                    "Malformed steam-cloud-sync config (" + e.getMessage() + "); using defaults.");
        }
    }

    /**
     * Serializes {@code config} back to this feature's JSON schema.
     *
     * @param config the config to serialize
     * @return the serialized JSON text
     */
    public String serialize(SteamCloudSyncConfig config) {
        CloudSyncJson.JsonObject root = new CloudSyncJson.JsonObject()
                .putNumber("schemaVersion", config.schemaVersion())
                .putBoolean("enabled", config.enabled())
                .putBoolean("syncSettings", config.syncSettings())
                .putBoolean("syncAccessibility", config.syncAccessibility())
                .putBoolean("syncBookmarkedServers", config.syncBookmarkedServers())
                .putBoolean("syncContinuePointer", config.syncContinuePointer())
                .putBoolean("syncNotes", config.syncNotes());
        return CloudSyncJson.write(root);
    }
}
