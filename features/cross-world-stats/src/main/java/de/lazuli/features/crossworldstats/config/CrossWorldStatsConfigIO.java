package de.lazuli.features.crossworldstats.config;

import de.lazuli.api.crossworldstats.TrackedStat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand-rolled JSON read/write for this feature's single persisted file,
 * {@code config/cross-world-stats.json}:
 * <pre>{@code
 * {
 *   "accounts": {
 *     "76561197960287930": {
 *       "totals": { "BLOCKS_MINED": 48213, "...": "..." },
 *       "worldBaselines": {
 *         "a1b2c3d4-world-fingerprint-or-path": { "BLOCKS_MINED": 1200, "...": "..." }
 *       }
 *     },
 *     "offline": { "totals": { "...": "..." }, "worldBaselines": { "...": "..." } }
 *   }
 * }
 * }</pre>
 *
 * <p>Same fail-closed-to-defaults convention as every other feature's own
 * {@code *ConfigIO} in this repo ({@code ServerJoinPresenceConfigIO},
 * {@code SteamWorldHostingConfigIO}): malformed content, an unknown top-level
 * key, or a wrong-typed field falls back to an empty aggregate (spec's own
 * {@code {"accounts": {}}}) with a logged warning, never throws.
 */
public final class CrossWorldStatsConfigIO {

    /**
     * The outcome of a parse/load attempt: always resolves to a usable
     * accounts map, plus an optional human-readable warning.
     *
     * @param accounts the resolved per-account map; never {@code null}, keyed
     *                 by {@code localSteamId64()} as a string or the
     *                 {@code "offline"} sentinel (FR1.2)
     * @param warning  a human-readable warning message, or {@code null} if no
     *                 fallback occurred
     */
    public record ParseResult(Map<String, AccountStats> accounts, String warning) {

        private static ParseResult ok(Map<String, AccountStats> accounts) {
            return new ParseResult(accounts, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(Map.of(), reason);
        }
    }

    /**
     * Loads the config from {@code path}. If the file does not exist, it is
     * created with defaults (no accounts) and those defaults are returned
     * with no warning. If the file exists but cannot be read or parsed,
     * defaults are returned with a warning. Never throws.
     *
     * @param path the config file's location
     * @return the resolved accounts, plus an optional warning
     */
    public ParseResult load(Path path) {
        try {
            if (Files.notExists(path)) {
                Path parent = path.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, serialize(Map.of()), StandardCharsets.UTF_8);
                return ParseResult.ok(Map.of());
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parse(content);
        } catch (IOException | RuntimeException e) {
            return ParseResult.fallback(
                    "Failed to load " + path + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Persists {@code accounts} to {@code path}, overwriting any existing
     * content. Never throws; returns a human-readable warning on failure (the
     * caller decides whether/how to log it), or {@code null} on success.
     *
     * @param path     the config file's location
     * @param accounts the per-account map to persist
     * @return a warning message on failure, or {@code null} on success
     */
    public String save(Path path, Map<String, AccountStats> accounts) {
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, serialize(accounts), StandardCharsets.UTF_8);
            return null;
        } catch (IOException | RuntimeException e) {
            return "Failed to save " + path + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Parses {@code content} as this feature's config schema. Never throws:
     * any malformed input falls back to empty defaults with a warning. A
     * stray legacy {@code "enabled"} key (BF-4-2: pre-batch-4-fixes schema)
     * is silently ignored if present, not treated as a schema error.
     *
     * @param content the raw JSON text
     * @return the resolved accounts, plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null) {
            return ParseResult.fallback("Config content was null; using defaults.");
        }
        try {
            CrossWorldStatsJson.JsonValue parsed = CrossWorldStatsJson.parse(content);
            if (!(parsed instanceof CrossWorldStatsJson.JsonObject root)) {
                throw new CrossWorldStatsJson.JsonSchemaException("expected a JSON object at the top level");
            }

            CrossWorldStatsJson.JsonObject accountsObj = root.getObjectOrEmpty("accounts");

            Map<String, AccountStats> accounts = new LinkedHashMap<>();
            for (Map.Entry<String, CrossWorldStatsJson.JsonValue> accountEntry : accountsObj.members().entrySet()) {
                String accountKey = accountEntry.getKey();
                if (!(accountEntry.getValue() instanceof CrossWorldStatsJson.JsonObject accountObj)) {
                    throw new CrossWorldStatsJson.JsonSchemaException(
                            "expected account \"" + accountKey + "\" to be an object");
                }
                accounts.put(accountKey, parseAccount(accountObj));
            }

            return ParseResult.ok(Map.copyOf(accounts));
        } catch (CrossWorldStatsJson.JsonParseException | CrossWorldStatsJson.JsonSchemaException e) {
            return ParseResult.fallback("Malformed cross-world-stats config (" + e.getMessage() + "); using defaults.");
        }
    }

    private AccountStats parseAccount(CrossWorldStatsJson.JsonObject accountObj) {
        Map<TrackedStat, Long> totals = parseStatMap(accountObj.getObjectOrEmpty("totals"));

        Map<String, Map<TrackedStat, Long>> worldBaselines = new LinkedHashMap<>();
        CrossWorldStatsJson.JsonObject baselinesObj = accountObj.getObjectOrEmpty("worldBaselines");
        for (Map.Entry<String, CrossWorldStatsJson.JsonValue> worldEntry : baselinesObj.members().entrySet()) {
            if (!(worldEntry.getValue() instanceof CrossWorldStatsJson.JsonObject worldObj)) {
                throw new CrossWorldStatsJson.JsonSchemaException(
                        "expected worldBaselines entry \"" + worldEntry.getKey() + "\" to be an object");
            }
            worldBaselines.put(worldEntry.getKey(), parseStatMap(worldObj));
        }

        return new AccountStats(totals, worldBaselines);
    }

    private Map<TrackedStat, Long> parseStatMap(CrossWorldStatsJson.JsonObject statsObj) {
        Map<TrackedStat, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, CrossWorldStatsJson.JsonValue> entry : statsObj.members().entrySet()) {
            TrackedStat stat;
            try {
                stat = TrackedStat.valueOf(entry.getKey());
            } catch (IllegalArgumentException e) {
                // Unknown/retired TrackedStat key (e.g. from a future
                // downgrade, or a key an older build no longer tracks) --
                // simply dropped, not a malformed-file error (FR4.2's own
                // "adding a new tracked stat" forward-compat convention
                // implies the reverse direction should also tolerate it).
                continue;
            }
            if (!(entry.getValue() instanceof CrossWorldStatsJson.JsonNumber n)) {
                throw new CrossWorldStatsJson.JsonSchemaException(
                        "expected stat \"" + entry.getKey() + "\" to be a number");
            }
            result.put(stat, n.value());
        }
        return result;
    }

    /**
     * Serializes {@code accounts} back to this feature's JSON schema.
     *
     * @param accounts the per-account map to serialize
     * @return the serialized JSON text, terminated with a trailing newline
     */
    public String serialize(Map<String, AccountStats> accounts) {
        CrossWorldStatsJson.JsonObject root = new CrossWorldStatsJson.JsonObject();

        CrossWorldStatsJson.JsonObject accountsObj = new CrossWorldStatsJson.JsonObject();
        for (Map.Entry<String, AccountStats> accountEntry : accounts.entrySet()) {
            CrossWorldStatsJson.JsonObject accountObj = new CrossWorldStatsJson.JsonObject();
            accountObj.put("totals", toStatMapJson(accountEntry.getValue().totals()));

            CrossWorldStatsJson.JsonObject baselinesObj = new CrossWorldStatsJson.JsonObject();
            for (Map.Entry<String, Map<TrackedStat, Long>> worldEntry : accountEntry.getValue().worldBaselines().entrySet()) {
                baselinesObj.put(worldEntry.getKey(), toStatMapJson(worldEntry.getValue()));
            }
            accountObj.put("worldBaselines", baselinesObj);

            accountsObj.put(accountEntry.getKey(), accountObj);
        }
        root.put("accounts", accountsObj);

        return CrossWorldStatsJson.write(root);
    }

    private CrossWorldStatsJson.JsonObject toStatMapJson(Map<TrackedStat, Long> stats) {
        CrossWorldStatsJson.JsonObject obj = new CrossWorldStatsJson.JsonObject();
        stats.forEach((stat, value) -> obj.putNumber(stat.name(), value));
        return obj;
    }
}
