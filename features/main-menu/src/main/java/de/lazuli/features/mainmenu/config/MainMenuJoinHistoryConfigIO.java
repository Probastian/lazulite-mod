package de.lazuli.features.mainmenu.config;

import de.lazuli.common.config.MainMenuJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled JSON reader/writer for {@link MainMenuJoinHistoryConfig}
 * (batch-3-fixes Item BF4):
 * <pre>{@code
 * {
 *   "servers": [ { "ip": "...", "name": "...", "lastJoinedEpochMillis": 0 } ],
 *   "friends": [ { "steamId64": 0, "lastPlayedTogetherEpochMillis": 0 } ]
 * }
 * }</pre>
 *
 * <p>Same fail-closed-to-defaults convention as {@code WardrobeConfigIO}:
 * malformed input falls back to {@link MainMenuJoinHistoryConfig#EMPTY} with
 * a human-readable warning, never by throwing.
 */
public final class MainMenuJoinHistoryConfigIO {

    /**
     * @param config  the resolved configuration; never {@code null}
     * @param warning a human-readable warning message, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(MainMenuJoinHistoryConfig config, String warning) {

        private static ParseResult ok(MainMenuJoinHistoryConfig config) {
            return new ParseResult(config, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(MainMenuJoinHistoryConfig.EMPTY, reason);
        }
    }

    public ParseResult load(Path path) {
        try {
            if (Files.notExists(path)) {
                Path parent = path.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, serialize(MainMenuJoinHistoryConfig.EMPTY), StandardCharsets.UTF_8);
                return ParseResult.ok(MainMenuJoinHistoryConfig.EMPTY);
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parse(content);
        } catch (IOException | RuntimeException e) {
            return ParseResult.fallback(
                    "Failed to load " + path + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public ParseResult parse(String content) {
        if (content == null) {
            return ParseResult.fallback("Config content was null; using defaults.");
        }
        try {
            MainMenuJson.JsonValue root = MainMenuJson.parse(content);
            if (!(root instanceof MainMenuJson.JsonObject rootObject)) {
                throw new MainMenuJson.JsonSchemaException("expected a JSON object at the top level");
            }
            List<MainMenuJoinHistoryConfig.ServerJoinEntry> servers = new ArrayList<>();
            if (rootObject.has("servers")) {
                for (MainMenuJson.JsonValue element : rootObject.getArray("servers").elements()) {
                    if (!(element instanceof MainMenuJson.JsonObject obj)) {
                        throw new MainMenuJson.JsonSchemaException("expected a JSON object in \"servers\"");
                    }
                    servers.add(new MainMenuJoinHistoryConfig.ServerJoinEntry(
                            obj.getString("ip"), obj.getString("name"), (long) obj.getNumber("lastJoinedEpochMillis")));
                }
            }
            List<MainMenuJoinHistoryConfig.FriendJoinEntry> friends = new ArrayList<>();
            if (rootObject.has("friends")) {
                for (MainMenuJson.JsonValue element : rootObject.getArray("friends").elements()) {
                    if (!(element instanceof MainMenuJson.JsonObject obj)) {
                        throw new MainMenuJson.JsonSchemaException("expected a JSON object in \"friends\"");
                    }
                    friends.add(new MainMenuJoinHistoryConfig.FriendJoinEntry(
                            (long) obj.getNumber("steamId64"), (long) obj.getNumber("lastPlayedTogetherEpochMillis")));
                }
            }
            return ParseResult.ok(new MainMenuJoinHistoryConfig(servers, friends));
        } catch (MainMenuJson.JsonParseException | MainMenuJson.JsonSchemaException e) {
            return ParseResult.fallback("Malformed main-menu-join-history config (" + e.getMessage() + "); using defaults.");
        }
    }

    public String serialize(MainMenuJoinHistoryConfig config) {
        MainMenuJson.JsonArray serversArray = new MainMenuJson.JsonArray();
        for (MainMenuJoinHistoryConfig.ServerJoinEntry entry : config.servers()) {
            serversArray.add(new MainMenuJson.JsonObject()
                    .putString("ip", entry.ip())
                    .putString("name", entry.name())
                    .putNumber("lastJoinedEpochMillis", entry.lastJoinedEpochMillis()));
        }
        MainMenuJson.JsonArray friendsArray = new MainMenuJson.JsonArray();
        for (MainMenuJoinHistoryConfig.FriendJoinEntry entry : config.friends()) {
            friendsArray.add(new MainMenuJson.JsonObject()
                    .putNumber("steamId64", entry.steamId64())
                    .putNumber("lastPlayedTogetherEpochMillis", entry.lastPlayedTogetherEpochMillis()));
        }
        MainMenuJson.JsonObject root = new MainMenuJson.JsonObject()
                .putArray("servers", serversArray)
                .putArray("friends", friendsArray);
        return MainMenuJson.write(root);
    }

    /**
     * Saves {@code config} to {@code path}; returns a human-readable warning
     * on failure (never throws), or {@code null} on success.
     */
    public String save(Path path, MainMenuJoinHistoryConfig config) {
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, serialize(config), StandardCharsets.UTF_8);
            return null;
        } catch (IOException | RuntimeException e) {
            return "Failed to save " + path + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
