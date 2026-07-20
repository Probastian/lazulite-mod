package de.lazuli.features.worldhosting.config;

import de.lazuli.features.worldhosting.api.SteamWorldHostingConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hand-rolled, minimal JSON reader/writer for {@link SteamWorldHostingConfig}'s
 * exact single-field schema:
 * <pre>{@code
 * {
 *   "enabled": true
 * }
 * }</pre>
 *
 * <p>Same narrow, no-external-JSON-library convention already established by
 * {@code HelloWorldMainMenuConfigIO}/{@code FriendsSidebarConfigIO}: malformed
 * input, missing/unknown keys, or wrong value types fail closed to
 * {@link SteamWorldHostingConfig#DEFAULT} with a human-readable warning, never
 * by throwing.
 */
public final class SteamWorldHostingConfigIO {

    /**
     * The outcome of a parse/load attempt: always resolves to a usable
     * {@link SteamWorldHostingConfig}, plus an optional human-readable warning.
     *
     * @param config  the resolved configuration; never {@code null}
     * @param warning a human-readable warning message, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(SteamWorldHostingConfig config, String warning) {

        private static ParseResult ok(SteamWorldHostingConfig config) {
            return new ParseResult(config, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(SteamWorldHostingConfig.DEFAULT, reason);
        }
    }

    /**
     * Loads the config from {@code path}. If the file does not exist, it is
     * created with {@link SteamWorldHostingConfig#DEFAULT} serialized to it and
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
                Files.writeString(path, serialize(SteamWorldHostingConfig.DEFAULT), StandardCharsets.UTF_8);
                return ParseResult.ok(SteamWorldHostingConfig.DEFAULT);
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parse(content);
        } catch (IOException | RuntimeException e) {
            return ParseResult.fallback(
                    "Failed to load " + path + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Parses {@code content} as an instance of this feature's config schema.
     * Never throws: any malformed input falls back to
     * {@link SteamWorldHostingConfig#DEFAULT} with a warning.
     *
     * @param content the raw JSON text
     * @return the resolved config, plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null) {
            return ParseResult.fallback("Config content was null; using defaults.");
        }
        try {
            return ParseResult.ok(new JsonObjectParser(content).parseConfig());
        } catch (MalformedConfigException e) {
            return ParseResult.fallback("Malformed steam-world-hosting config (" + e.getMessage() + "); using defaults.");
        }
    }

    /**
     * Serializes {@code config} back to this feature's JSON schema.
     *
     * @param config the config to serialize
     * @return the serialized JSON text, terminated with a trailing newline
     */
    public String serialize(SteamWorldHostingConfig config) {
        return "{\n  \"enabled\": " + config.enabled() + "\n}\n";
    }

    /**
     * Thrown internally to bail out of parsing on the first sign of malformed
     * input; always caught within {@link #parse(String)}.
     */
    private static final class MalformedConfigException extends RuntimeException {
        MalformedConfigException(String message) {
            super(message);
        }
    }

    /**
     * A minimal recursive-descent scanner for exactly this schema's shape: a
     * flat JSON object with a single required boolean {@code enabled} field, no
     * extra keys.
     */
    private static final class JsonObjectParser {
        private final String s;
        private int pos;

        JsonObjectParser(String s) {
            this.s = s;
        }

        SteamWorldHostingConfig parseConfig() {
            Boolean enabled = null;

            skipWhitespace();
            expect('{');
            skipWhitespace();

            if (peek() == '}') {
                throw new MalformedConfigException("empty object, missing \"enabled\"");
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();

                if ("enabled".equals(key)) {
                    if (enabled != null) {
                        throw new MalformedConfigException("duplicate key \"enabled\"");
                    }
                    enabled = parseBoolean();
                } else {
                    throw new MalformedConfigException("unknown key \"" + key + "\"");
                }

                skipWhitespace();
                char next = next();
                if (next == ',') {
                    continue;
                }
                if (next == '}') {
                    break;
                }
                throw new MalformedConfigException("expected ',' or '}' but found '" + next + "'");
            }

            skipWhitespace();
            if (pos != s.length()) {
                throw new MalformedConfigException("trailing content after closing '}'");
            }
            if (enabled == null) {
                throw new MalformedConfigException("missing required key \"enabled\"");
            }

            return new SteamWorldHostingConfig(enabled);
        }

        private boolean parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return true;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return false;
            }
            throw new MalformedConfigException("expected boolean literal at position " + pos);
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char escaped = next();
                    switch (escaped) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        default -> throw new MalformedConfigException("invalid escape '\\" + escaped + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= s.length()) {
                throw new MalformedConfigException("unexpected end of input");
            }
            return s.charAt(pos);
        }

        private char next() {
            if (pos >= s.length()) {
                throw new MalformedConfigException("unexpected end of input");
            }
            return s.charAt(pos++);
        }

        private void expect(char expected) {
            char actual = next();
            if (actual != expected) {
                throw new MalformedConfigException("expected '" + expected + "' but found '" + actual + "'");
            }
        }
    }
}
