package de.lazuli.features.tweaks.config;

import de.lazuli.api.tweaks.TweakId;
import de.lazuli.api.tweaks.TweakState;
import de.lazuli.common.config.MainMenuJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled JSON reader/writer for {@code tweaks.json} (spec Configuration),
 * shaped exactly like {@code WardrobeConfigIO}: read-or-create-with-defaults
 * on load, fail-closed to {@link TweaksConfig#DEFAULT} with a human-readable
 * warning on malformed content (spec F5), write-through is the caller's job
 * (this class never writes except on first-create).
 *
 * <pre>{@code
 * {
 *   "tweaks": {
 *     "ANTI_DROP": { "enabled": false, "configurables": { "whitelist": [], "shiftQForceDrop": true } },
 *     ...
 *   }
 * }
 * }</pre>
 *
 * <p>An unknown {@link TweakId} name inside {@code tweaks} is ignored (not
 * treated as malformed) -- forward-compatible with a config written by a
 * newer build that added tweaks this build doesn't know about yet. A missing
 * {@link TweakId} entry is backfilled with that tweak's default state (net-new
 * tweak added since the file was last written).
 */
public final class TweaksConfigIO {

    public record ParseResult(TweaksConfig config, String warning) {

        private static ParseResult ok(TweaksConfig config) {
            return new ParseResult(config, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(TweaksConfig.DEFAULT, reason);
        }
    }

    public ParseResult load(Path path) {
        try {
            if (Files.notExists(path)) {
                Path parent = path.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, serialize(TweaksConfig.DEFAULT), StandardCharsets.UTF_8);
                return ParseResult.ok(TweaksConfig.DEFAULT);
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
            MainMenuJson.JsonObject tweaksObject = rootObject.getObject("tweaks");

            Map<TweakId, TweakState> tweaks = new EnumMap<>(TweakId.class);
            for (TweakId id : TweakId.values()) {
                tweaks.put(id, TweaksConfig.DEFAULT.stateOf(id));
            }
            for (Map.Entry<String, MainMenuJson.JsonValue> entry : tweaksObject.members().entrySet()) {
                TweakId id;
                try {
                    id = TweakId.valueOf(entry.getKey());
                } catch (IllegalArgumentException e) {
                    continue; // forward-compatible: ignore unknown tweak ids.
                }
                if (!(entry.getValue() instanceof MainMenuJson.JsonObject tweakObject)) {
                    throw new MainMenuJson.JsonSchemaException("expected \"" + entry.getKey() + "\" to be an object");
                }
                boolean enabled = tweakObject.getBoolean("enabled");
                MainMenuJson.JsonObject configurablesObject = tweakObject.getObject("configurables");
                // Start from this tweak's *default* configurables and overlay whatever the
                // saved file has, rather than replacing the map outright -- otherwise a
                // configurable key added after this file was first written (e.g. Zoom's
                // "scrollToAdjust") silently reads as missing/null forever for any save that
                // predates it, even though TweakDefinitions declares a real default for it.
                Map<String, Object> configurables =
                        new LinkedHashMap<>(TweaksConfig.DEFAULT.stateOf(id).configurables());
                for (Map.Entry<String, MainMenuJson.JsonValue> confEntry : configurablesObject.members().entrySet()) {
                    configurables.put(confEntry.getKey(), toJavaValue(confEntry.getValue()));
                }
                if (id == TweakId.FREECAM) {
                    migrateFreecamMoveSpeed(configurablesObject, configurables);
                }
                tweaks.put(id, new TweakState(enabled, configurables));
            }
            return ParseResult.ok(new TweaksConfig(tweaks));
        } catch (MainMenuJson.JsonParseException | MainMenuJson.JsonSchemaException e) {
            return ParseResult.fallback("Malformed tweaks config (" + e.getMessage() + "); using defaults.");
        }
    }

    /**
     * Addendum AD-3: a small, {@code FREECAM}-only migration, not a general
     * schema-version bump. An old (pre-corrective-release) {@code tweaks.json}
     * stored {@code moveSpeed} on the 0.1-10.0 scale; the new scale is
     * 0.25-5.0 with a compensating {@code MOVE_SPEED_RUNTIME_SCALE} factor
     * applied at runtime (see {@code FreecamTicker}). A file is migrated
     * (divided by 10) exactly once, signalled by the presence/absence of the
     * {@code moveSpeedRescaled} marker key in the raw saved JSON -- once
     * migrated, that marker is always written back on next save, making this
     * an idempotent, one-time conversion per save file.
     */
    private static void migrateFreecamMoveSpeed(MainMenuJson.JsonObject configurablesObject, Map<String, Object> configurables) {
        if (configurablesObject.has("moveSpeed") && !configurablesObject.has("moveSpeedRescaled")) {
            Object raw = configurables.get("moveSpeed");
            if (raw instanceof Number n) {
                configurables.put("moveSpeed", n.doubleValue() / 10.0);
            }
        }
        configurables.put("moveSpeedRescaled", true);

        Object raw = configurables.get("moveSpeed");
        if (raw instanceof Number n) {
            double clamped = Math.max(0.25, Math.min(5.0, n.doubleValue()));
            configurables.put("moveSpeed", clamped);
        }
    }

    public String serialize(TweaksConfig config) {
        MainMenuJson.JsonObject tweaksObject = new MainMenuJson.JsonObject();
        for (TweakId id : TweakId.values()) {
            TweakState state = config.stateOf(id);
            MainMenuJson.JsonObject configurablesObject = new MainMenuJson.JsonObject();
            for (Map.Entry<String, Object> entry : state.configurables().entrySet()) {
                configurablesObject.put(entry.getKey(), toJsonValue(entry.getValue()));
            }
            MainMenuJson.JsonObject tweakObject = new MainMenuJson.JsonObject()
                    .putBoolean("enabled", state.enabled())
                    .put("configurables", configurablesObject);
            tweaksObject.put(id.name(), tweakObject);
        }
        MainMenuJson.JsonObject root = new MainMenuJson.JsonObject().put("tweaks", tweaksObject);
        return MainMenuJson.write(root);
    }

    @SuppressWarnings("unchecked")
    private static MainMenuJson.JsonValue toJsonValue(Object value) {
        if (value == null) {
            return MainMenuJson.JsonNull.INSTANCE;
        }
        if (value instanceof Boolean b) {
            return new MainMenuJson.JsonBoolean(b);
        }
        if (value instanceof Number n) {
            return new MainMenuJson.JsonNumber(n.doubleValue());
        }
        if (value instanceof String s) {
            return new MainMenuJson.JsonString(s);
        }
        if (value instanceof List<?> list) {
            MainMenuJson.JsonArray array = new MainMenuJson.JsonArray();
            for (Object element : list) {
                array.add(toJsonValue(element));
            }
            return array;
        }
        throw new MainMenuJson.JsonSchemaException("unsupported configurable value type " + value.getClass());
    }

    private static Object toJavaValue(MainMenuJson.JsonValue value) {
        return switch (value) {
            case MainMenuJson.JsonBoolean b -> b.value();
            case MainMenuJson.JsonNumber n -> n.value();
            case MainMenuJson.JsonString s -> s.value();
            case MainMenuJson.JsonNull ignored -> null;
            case MainMenuJson.JsonArray a -> {
                List<Object> list = new java.util.ArrayList<>();
                for (MainMenuJson.JsonValue element : a.elements()) {
                    list.add(toJavaValue(element));
                }
                yield list;
            }
            case MainMenuJson.JsonObject o -> throw new MainMenuJson.JsonSchemaException("nested objects are not supported in configurables");
        };
    }
}
