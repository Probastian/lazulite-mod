package de.lazuli.features.mainmenu.config;

import de.lazuli.api.mainmenu.StoreItem;
import de.lazuli.api.mainmenu.WardrobeSlot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Hand-rolled JSON reader/writer for the Store/Wardrobe item catalog (spec
 * FR5.1/Configuration), updated for the implementation plan's Decision 1
 * schema (both {@code inventoryItemDefId} and {@code steamDlcAppId}):
 * <pre>{@code
 * {
 *   "items": [
 *     {
 *       "id": "moss-cloak", "displayName": "Moss Cloak",
 *       "description": "A traveler's cloak, dyed in forest tones.",
 *       "category": "TORSO", "priceCents": 499, "originalPriceCents": 799,
 *       "inventoryItemDefId": null, "steamDlcAppId": null, "featured": true
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Same narrow, no-external-JSON-library, fail-closed-to-defaults
 * convention {@code SteamWorldHostingConfigIO} already establishes: malformed
 * input, missing/unknown keys, or wrong value types fail closed to a small
 * built-in default catalog with a human-readable warning, never by throwing.
 * If the file is missing, it is created with that same default catalog on
 * first run (Configuration) -- a handful of placeholder items covering all
 * four {@link WardrobeSlot}s plus one featured Store item, every one shipping
 * with {@code null} {@code inventoryItemDefId}/{@code steamDlcAppId} (the
 * expected dev-time default, spec Configuration/plan Decision 1).
 */
public final class StoreCatalogConfigIO {

    /**
     * The small built-in default catalog created on first run/malformed
     * content: one placeholder item per {@link WardrobeSlot}, plus one
     * additional featured Store item with no wardrobe slot -- all with
     * {@code null} {@code inventoryItemDefId}/{@code steamDlcAppId} (not yet
     * purchasable via Steam, spec FR5.2's honest dev-time default).
     */
    public static final List<StoreItem> DEFAULT_CATALOG = List.of(
            new StoreItem("moss-cloak", "Moss Cloak",
                    "A traveler's cloak, dyed in forest tones.", "TORSO",
                    499, OptionalInt.of(799), OptionalInt.empty(), OptionalInt.empty(), true),
            new StoreItem("wanderer-hood", "Wanderer's Hood",
                    "A weathered hood for long roads.", "HEAD",
                    299, OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), false),
            new StoreItem("trailblazer-trousers", "Trailblazer Trousers",
                    "Reinforced trousers for rough terrain.", "LEGS",
                    349, OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), false),
            new StoreItem("sturdy-boots", "Sturdy Boots",
                    "Boots built to last.", "FEET",
                    249, OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), false)
    );

    /**
     * The outcome of a parse/load attempt: always resolves to a usable
     * catalog, plus an optional human-readable warning.
     *
     * @param items   the resolved catalog; never {@code null}
     * @param warning a human-readable warning message, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(List<StoreItem> items, String warning) {

        private static ParseResult ok(List<StoreItem> items) {
            return new ParseResult(items, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(DEFAULT_CATALOG, reason);
        }
    }

    /**
     * Loads the catalog from {@code path}. If the file does not exist, it is
     * created with {@link #DEFAULT_CATALOG} serialized to it and that default
     * is returned with no warning. If the file exists but cannot be read or
     * parsed, the default catalog is returned with a warning. Never throws.
     *
     * @param path the config file's location
     * @return the resolved catalog, plus an optional warning
     */
    public ParseResult load(Path path) {
        try {
            if (Files.notExists(path)) {
                Path parent = path.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, serialize(DEFAULT_CATALOG), StandardCharsets.UTF_8);
                return ParseResult.ok(DEFAULT_CATALOG);
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parse(content);
        } catch (IOException | RuntimeException e) {
            return ParseResult.fallback(
                    "Failed to load " + path + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Parses {@code content} as an instance of this feature's catalog schema.
     * Never throws: any malformed input falls back to
     * {@link #DEFAULT_CATALOG} with a warning.
     *
     * @param content the raw JSON text
     * @return the resolved catalog, plus an optional warning
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
            MainMenuJson.JsonArray itemsArray = rootObject.getArray("items");

            List<StoreItem> items = new ArrayList<>();
            for (MainMenuJson.JsonValue element : itemsArray.elements()) {
                if (!(element instanceof MainMenuJson.JsonObject itemObject)) {
                    throw new MainMenuJson.JsonSchemaException("expected each catalog entry to be an object");
                }
                items.add(parseItem(itemObject));
            }
            return ParseResult.ok(List.copyOf(items));
        } catch (MainMenuJson.JsonParseException | MainMenuJson.JsonSchemaException e) {
            return ParseResult.fallback("Malformed main-menu-store-catalog config (" + e.getMessage() + "); using defaults.");
        }
    }

    private StoreItem parseItem(MainMenuJson.JsonObject itemObject) {
        String id = itemObject.getString("id");
        String displayName = itemObject.getString("displayName");
        String description = itemObject.getString("description");
        String category = itemObject.getString("category");
        int priceCents = itemObject.getInt("priceCents");
        Integer originalPriceCents = itemObject.getIntOrNull("originalPriceCents");
        Integer inventoryItemDefId = itemObject.getIntOrNull("inventoryItemDefId");
        Integer steamDlcAppId = itemObject.getIntOrNull("steamDlcAppId");
        boolean featured = itemObject.getBoolean("featured");

        return new StoreItem(id, displayName, description, category, priceCents,
                originalPriceCents == null ? OptionalInt.empty() : OptionalInt.of(originalPriceCents),
                inventoryItemDefId == null ? OptionalInt.empty() : OptionalInt.of(inventoryItemDefId),
                steamDlcAppId == null ? OptionalInt.empty() : OptionalInt.of(steamDlcAppId),
                featured);
    }

    /**
     * Serializes {@code items} back to this feature's JSON schema.
     *
     * @param items the catalog to serialize
     * @return the serialized JSON text, terminated with a trailing newline
     */
    public String serialize(List<StoreItem> items) {
        MainMenuJson.JsonArray itemsArray = new MainMenuJson.JsonArray();
        for (StoreItem item : items) {
            MainMenuJson.JsonObject itemObject = new MainMenuJson.JsonObject()
                    .putString("id", item.id())
                    .putString("displayName", item.displayName())
                    .putString("description", item.description())
                    .putString("category", item.category())
                    .putNumber("priceCents", item.priceCents())
                    .putNumberOrNull("originalPriceCents", item.originalPriceCents().isPresent() ? item.originalPriceCents().getAsInt() : null)
                    .putNumberOrNull("inventoryItemDefId", item.inventoryItemDefId().isPresent() ? item.inventoryItemDefId().getAsInt() : null)
                    .putNumberOrNull("steamDlcAppId", item.steamDlcAppId().isPresent() ? item.steamDlcAppId().getAsInt() : null)
                    .putBoolean("featured", item.featured());
            itemsArray.add(itemObject);
        }
        MainMenuJson.JsonObject root = new MainMenuJson.JsonObject().putArray("items", itemsArray);
        return MainMenuJson.write(root);
    }
}
