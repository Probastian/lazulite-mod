package de.lazuli.features.mainmenu.config;

import de.lazuli.api.mainmenu.WardrobeSlot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Hand-rolled JSON reader/writer for {@link WardrobeConfig}'s equip-map
 * schema (spec Configuration):
 * <pre>{@code
 * {
 *   "equipped": {
 *     "HEAD": null,
 *     "TORSO": "moss-cloak",
 *     "LEGS": null,
 *     "FEET": null
 *   }
 * }
 * }</pre>
 *
 * <p>Same narrow, no-external-JSON-library, fail-closed-to-defaults
 * convention {@code SteamWorldHostingConfigIO} already establishes: malformed
 * input, missing/unknown keys, or wrong value types fail closed to
 * {@link WardrobeConfig#DEFAULT} with a human-readable warning, never by
 * throwing. An unknown slot name inside {@code equipped} is treated as
 * malformed content (fails closed), rather than silently ignored, so a typo'd
 * config file doesn't silently discard a player's equip choice.
 */
public final class WardrobeConfigIO {

    /**
     * The outcome of a parse/load attempt: always resolves to a usable
     * {@link WardrobeConfig}, plus an optional human-readable warning.
     *
     * @param config  the resolved configuration; never {@code null}
     * @param warning a human-readable warning message, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(WardrobeConfig config, String warning) {

        private static ParseResult ok(WardrobeConfig config) {
            return new ParseResult(config, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(WardrobeConfig.DEFAULT, reason);
        }
    }

    /**
     * Loads the config from {@code path}. If the file does not exist, it is
     * created with {@link WardrobeConfig#DEFAULT} serialized to it and
     * {@code DEFAULT} is returned with no warning. If the file exists but
     * cannot be read or parsed, {@code DEFAULT} is returned with a warning.
     * Never throws.
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
                Files.writeString(path, serialize(WardrobeConfig.DEFAULT), StandardCharsets.UTF_8);
                return ParseResult.ok(WardrobeConfig.DEFAULT);
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parse(content);
        } catch (IOException | RuntimeException e) {
            return ParseResult.fallback(
                    "Failed to load " + path + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Parses {@code content} as an instance of this feature's wardrobe-config
     * schema. Never throws: any malformed input falls back to
     * {@link WardrobeConfig#DEFAULT} with a warning.
     *
     * @param content the raw JSON text
     * @return the resolved config, plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null) {
            return ParseResult.fallback("Config content was null; using defaults.");
        }
        try {
            MainMenuJson.JsonValue root = MainMenuJson.parse(content);
            if (!(root instanceof MainMenuJson.JsonObject rootObject)) {
                throw new MainMenuJson.JsonSchemaException("expected a JSON object at the top level");
            }
            MainMenuJson.JsonObject equippedObject = rootObject.getObject("equipped");

            Map<WardrobeSlot, String> equipped = new EnumMap<>(WardrobeSlot.class);
            Map<String, MainMenuJson.JsonValue> members = new HashMap<>(equippedObject.members());
            for (Map.Entry<String, MainMenuJson.JsonValue> entry : members.entrySet()) {
                WardrobeSlot slot;
                try {
                    slot = WardrobeSlot.valueOf(entry.getKey());
                } catch (IllegalArgumentException e) {
                    throw new MainMenuJson.JsonSchemaException("unknown wardrobe slot \"" + entry.getKey() + "\"");
                }
                String itemId = equippedObject.getStringOrNull(entry.getKey());
                if (itemId != null) {
                    equipped.put(slot, itemId);
                }
            }
            return ParseResult.ok(new WardrobeConfig(equipped));
        } catch (MainMenuJson.JsonParseException | MainMenuJson.JsonSchemaException e) {
            return ParseResult.fallback("Malformed main-menu-wardrobe config (" + e.getMessage() + "); using defaults.");
        }
    }

    /**
     * Serializes {@code config} back to this feature's JSON schema.
     *
     * @param config the config to serialize
     * @return the serialized JSON text, terminated with a trailing newline
     */
    public String serialize(WardrobeConfig config) {
        MainMenuJson.JsonObject equippedObject = new MainMenuJson.JsonObject();
        for (WardrobeSlot slot : WardrobeSlot.values()) {
            equippedObject.putString(slot.name(), config.equipped().get(slot));
        }
        MainMenuJson.JsonObject root = new MainMenuJson.JsonObject().put("equipped", equippedObject);
        return MainMenuJson.write(root);
    }
}
