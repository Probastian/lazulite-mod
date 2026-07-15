package de.probastian.boilerplate.features.helloworldmainmenu.config;

import de.probastian.boilerplate.features.helloworldmainmenu.api.HelloWorldMainMenuConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hand-rolled, minimal JSON reader/writer for {@link HelloWorldMainMenuConfig}'s
 * exact two-field schema:
 * <pre>{@code
 * {
 *   "enabled": true,
 *   "text": "Hello World"
 * }
 * }</pre>
 *
 * <p>This deliberately narrow parser exists so the feature needs no new
 * external JSON library dependency (see the feature's implementation plan,
 * Decision 6). It only needs to correctly accept well-formed instances of
 * its own tiny schema; anything else -- malformed JSON, wrong value types,
 * missing keys, unknown keys -- fails closed to
 * {@link HelloWorldMainMenuConfig#DEFAULT} with a human-readable warning,
 * never by throwing.
 *
 * <p>Usage example:
 * <pre>{@code
 * HelloWorldMainMenuConfigIO configIO = new HelloWorldMainMenuConfigIO();
 * HelloWorldMainMenuConfigIO.ParseResult result = configIO.load(configPath);
 * if (result.warning() != null) {
 *     logger.warn(result.warning());
 * }
 * HelloWorldMainMenuConfig config = result.config();
 * }</pre>
 */
public final class HelloWorldMainMenuConfigIO {

    /**
     * The outcome of a parse/load attempt: always resolves to a usable
     * {@link HelloWorldMainMenuConfig}, plus an optional human-readable
     * warning describing why {@link HelloWorldMainMenuConfig#DEFAULT} had to
     * be substituted (or {@code null} if parsing succeeded).
     *
     * @param config  the resolved configuration; never {@code null}
     * @param warning a human-readable warning message, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(HelloWorldMainMenuConfig config, String warning) {

        private static ParseResult ok(HelloWorldMainMenuConfig config) {
            return new ParseResult(config, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(HelloWorldMainMenuConfig.DEFAULT, reason);
        }
    }

    /**
     * Loads the config from {@code path}. If the file does not exist, it is
     * created with {@link HelloWorldMainMenuConfig#DEFAULT} serialized to it
     * (per FR5) and {@code DEFAULT} is returned with no warning. If the file
     * exists but cannot be read or parsed, {@code DEFAULT} is returned with
     * a warning describing why. This method never throws.
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
                Files.writeString(path, serialize(HelloWorldMainMenuConfig.DEFAULT), StandardCharsets.UTF_8);
                return ParseResult.ok(HelloWorldMainMenuConfig.DEFAULT);
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
     * {@link HelloWorldMainMenuConfig#DEFAULT} with a warning.
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
            return ParseResult.fallback("Malformed hello-world-main-menu config (" + e.getMessage() + "); using defaults.");
        }
    }

    /**
     * Serializes {@code config} back to this feature's JSON schema.
     *
     * @param config the config to serialize
     * @return the serialized JSON text, terminated with a trailing newline
     */
    public String serialize(HelloWorldMainMenuConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"enabled\": ").append(config.enabled()).append(",\n");
        sb.append("  \"text\": ").append(quote(config.text())).append('\n');
        sb.append("}\n");
        return sb.toString();
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        StringBuilder sb = new StringBuilder(safe.length() + 2);
        sb.append('"');
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Thrown internally to bail out of parsing on the first sign of
     * malformed input; always caught within {@link #parse(String)} and never
     * propagated to callers.
     */
    private static final class MalformedConfigException extends RuntimeException {
        MalformedConfigException(String message) {
            super(message);
        }
    }

    /**
     * A minimal recursive-descent scanner for exactly this schema's shape: a
     * flat JSON object with a boolean {@code enabled} field and a string
     * {@code text} field, in either order, both required, no extra keys.
     */
    private static final class JsonObjectParser {
        private final String s;
        private int pos;

        JsonObjectParser(String s) {
            this.s = s;
        }

        HelloWorldMainMenuConfig parseConfig() {
            Boolean enabled = null;
            String text = null;

            skipWhitespace();
            expect('{');
            skipWhitespace();

            if (peek() == '}') {
                throw new MalformedConfigException("empty object, missing \"enabled\"/\"text\"");
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();

                switch (key) {
                    case "enabled" -> {
                        if (enabled != null) {
                            throw new MalformedConfigException("duplicate key \"enabled\"");
                        }
                        enabled = parseBoolean();
                    }
                    case "text" -> {
                        if (text != null) {
                            throw new MalformedConfigException("duplicate key \"text\"");
                        }
                        text = parseString();
                    }
                    default -> throw new MalformedConfigException("unknown key \"" + key + "\"");
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
            if (enabled == null || text == null) {
                throw new MalformedConfigException("missing required key(s)");
            }

            return new HelloWorldMainMenuConfig(enabled, text);
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
                        case 'u' -> sb.append(parseUnicodeEscape());
                        default -> throw new MalformedConfigException("invalid escape '\\" + escaped + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private char parseUnicodeEscape() {
            if (pos + 4 > s.length()) {
                throw new MalformedConfigException("truncated \\u escape");
            }
            String hex = s.substring(pos, pos + 4);
            pos += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new MalformedConfigException("invalid \\u escape '" + hex + "'");
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
