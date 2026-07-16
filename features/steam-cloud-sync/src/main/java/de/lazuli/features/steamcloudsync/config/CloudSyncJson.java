package de.lazuli.features.steamcloudsync.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, shared, hand-rolled JSON value model and recursive-descent
 * parser/writer (objects/arrays/strings/numbers/booleans/null), used by
 * every {@code *IO} class in this feature instead of each one hand-rolling
 * its own bespoke single-schema parser from scratch (as
 * {@code HelloWorldMainMenuConfigIO} does for its own narrower, two-field
 * shape).
 *
 * <p>This feature needs the same small-JSON-object/array shape six times
 * (config, world-sync-preferences, bookmarks, notes, continue-pointer,
 * fingerprint file) -- generalized here once rather than duplicated six
 * times, while still adding no external dependency (this repo's existing
 * hand-roll-rather-than-add-a-library precedent).
 *
 * <p><strong>Deliberately conservative:</strong> every parsing method fails
 * closed -- any malformed input, unexpected token, trailing content, or
 * missing/wrong-typed field throws a {@link JsonParseException} or
 * {@link JsonSchemaException} rather than attempting any best-effort partial
 * recovery. Callers (this feature's {@code *IO} classes) are expected to
 * catch either and fall back to a schema's own defaults with a logged
 * warning, mirroring {@code HelloWorldMainMenuConfigIO.load(...)}'s own
 * discipline.
 *
 * <p>Usage example:
 * <pre>{@code
 * CloudSyncJson.JsonValue parsed = CloudSyncJson.parse(fileContent);
 * if (!(parsed instanceof CloudSyncJson.JsonObject root)) {
 *     throw new CloudSyncJson.JsonSchemaException("expected a JSON object at the top level");
 * }
 * int schemaVersion = root.getInt("schemaVersion");
 * boolean enabled = root.getBoolean("enabled");
 *
 * CloudSyncJson.JsonObject toWrite = new CloudSyncJson.JsonObject()
 *         .putNumber("schemaVersion", 1)
 *         .putBoolean("enabled", true);
 * String text = CloudSyncJson.write(toWrite);
 * }</pre>
 */
public final class CloudSyncJson {

    private CloudSyncJson() {
    }

    /**
     * The common supertype of every JSON value this model can represent.
     */
    public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {
    }

    /**
     * A JSON object: an ordered map of string keys to {@link JsonValue}s.
     * Insertion order is preserved for stable, human-readable serialization.
     */
    public static final class JsonObject implements JsonValue {
        private final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();

        public JsonObject put(String key, JsonValue value) {
            members.put(key, value == null ? JsonNull.INSTANCE : value);
            return this;
        }

        public JsonObject putString(String key, String value) {
            return put(key, value == null ? JsonNull.INSTANCE : new JsonString(value));
        }

        public JsonObject putBoolean(String key, boolean value) {
            return put(key, new JsonBoolean(value));
        }

        public JsonObject putNumber(String key, double value) {
            return put(key, new JsonNumber(value));
        }

        public boolean has(String key) {
            return members.containsKey(key);
        }

        public JsonValue get(String key) {
            return members.get(key);
        }

        public Map<String, JsonValue> members() {
            return members;
        }

        public String getString(String key) {
            JsonValue value = require(key);
            if (!(value instanceof JsonString s)) {
                throw new JsonSchemaException("expected \"" + key + "\" to be a string");
            }
            return s.value();
        }

        /**
         * Like {@link #getString(String)}, but tolerates the field being
         * absent or explicitly {@code null}, returning {@code null} in
         * either case -- for genuinely optional/nullable schema fields
         * (e.g. {@code Note.context}).
         */
        public String getStringOrNull(String key) {
            if (!members.containsKey(key)) {
                return null;
            }
            JsonValue value = members.get(key);
            if (value instanceof JsonNull) {
                return null;
            }
            if (!(value instanceof JsonString s)) {
                throw new JsonSchemaException("expected \"" + key + "\" to be a string or null");
            }
            return s.value();
        }

        public boolean getBoolean(String key) {
            JsonValue value = require(key);
            if (!(value instanceof JsonBoolean b)) {
                throw new JsonSchemaException("expected \"" + key + "\" to be a boolean");
            }
            return b.value();
        }

        public double getNumber(String key) {
            JsonValue value = require(key);
            if (!(value instanceof JsonNumber n)) {
                throw new JsonSchemaException("expected \"" + key + "\" to be a number");
            }
            return n.value();
        }

        /**
         * Like {@link #getNumber(String)}, but tolerates the field being
         * absent or explicitly {@code null}, returning {@code null} in
         * either case -- for genuinely optional/nullable numeric schema
         * fields (e.g. {@code Note.x}/{@code y}/{@code z}).
         */
        public Double getNumberOrNull(String key) {
            if (!members.containsKey(key)) {
                return null;
            }
            JsonValue value = members.get(key);
            if (value instanceof JsonNull) {
                return null;
            }
            if (!(value instanceof JsonNumber n)) {
                throw new JsonSchemaException("expected \"" + key + "\" to be a number or null");
            }
            return n.value();
        }

        public int getInt(String key) {
            return (int) getNumber(key);
        }

        public long getLong(String key) {
            return (long) getNumber(key);
        }

        public JsonArray getArray(String key) {
            JsonValue value = require(key);
            if (!(value instanceof JsonArray a)) {
                throw new JsonSchemaException("expected \"" + key + "\" to be an array");
            }
            return a;
        }

        public JsonObject getObject(String key) {
            JsonValue value = require(key);
            if (!(value instanceof JsonObject o)) {
                throw new JsonSchemaException("expected \"" + key + "\" to be an object");
            }
            return o;
        }

        private JsonValue require(String key) {
            if (!members.containsKey(key)) {
                throw new JsonSchemaException("missing required field \"" + key + "\"");
            }
            return members.get(key);
        }
    }

    /**
     * A JSON array: an ordered list of {@link JsonValue}s.
     */
    public static final class JsonArray implements JsonValue {
        private final List<JsonValue> elements = new ArrayList<>();

        public JsonArray add(JsonValue value) {
            elements.add(value == null ? JsonNull.INSTANCE : value);
            return this;
        }

        public List<JsonValue> elements() {
            return elements;
        }
    }

    public record JsonString(String value) implements JsonValue {
    }

    public record JsonNumber(double value) implements JsonValue {
    }

    public record JsonBoolean(boolean value) implements JsonValue {
    }

    /**
     * The single JSON {@code null} value. Use {@link #INSTANCE}, never
     * construct directly.
     */
    public static final class JsonNull implements JsonValue {
        public static final JsonNull INSTANCE = new JsonNull();

        private JsonNull() {
        }
    }

    /**
     * Thrown when the raw text itself is not well-formed JSON (unexpected
     * character, unterminated string, truncated input, trailing content
     * after the top-level value, etc).
     */
    public static final class JsonParseException extends RuntimeException {
        public JsonParseException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when the text parses as well-formed JSON but does not match the
     * shape a caller's {@code getXxx(key)} accessor expected (missing field,
     * wrong value type).
     */
    public static final class JsonSchemaException extends RuntimeException {
        public JsonSchemaException(String message) {
            super(message);
        }
    }

    /**
     * Parses {@code text} as a single JSON value. Never attempts partial
     * recovery: any malformed input throws {@link JsonParseException}.
     *
     * @param text the raw JSON text
     * @return the parsed top-level value
     * @throws JsonParseException if {@code text} is not well-formed JSON
     */
    public static JsonValue parse(String text) {
        if (text == null) {
            throw new JsonParseException("input was null");
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        JsonValue value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonParseException("trailing content after the top-level value at position " + parser.pos);
        }
        return value;
    }

    /**
     * Serializes {@code value} to pretty-printed JSON text (2-space indent),
     * terminated with a trailing newline, matching this repo's existing
     * hand-rolled-config convention.
     *
     * @param value the value to serialize
     * @return the serialized JSON text
     */
    public static String write(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(JsonValue value, StringBuilder sb, int indent) {
        switch (value) {
            case JsonObject o -> writeObject(o, sb, indent);
            case JsonArray a -> writeArray(a, sb, indent);
            case JsonString s -> writeQuotedString(s.value(), sb);
            case JsonNumber n -> writeNumber(n.value(), sb);
            case JsonBoolean b -> sb.append(b.value());
            case JsonNull ignored -> sb.append("null");
        }
    }

    private static void writeObject(JsonObject o, StringBuilder sb, int indent) {
        if (o.members.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        int size = o.members.size();
        for (Map.Entry<String, JsonValue> entry : o.members.entrySet()) {
            indent(sb, indent + 1);
            writeQuotedString(entry.getKey(), sb);
            sb.append(": ");
            writeValue(entry.getValue(), sb, indent + 1);
            if (++i < size) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(JsonArray a, StringBuilder sb, int indent) {
        if (a.elements.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        int size = a.elements.size();
        for (int i = 0; i < size; i++) {
            indent(sb, indent + 1);
            writeValue(a.elements.get(i), sb, indent + 1);
            if (i + 1 < size) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int level) {
        sb.append("  ".repeat(Math.max(0, level)));
    }

    private static void writeNumber(double value, StringBuilder sb) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1.0e15) {
            sb.append((long) value);
        } else {
            sb.append(value);
        }
    }

    private static void writeQuotedString(String value, StringBuilder sb) {
        String safe = value == null ? "" : value;
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
    }

    /**
     * A minimal recursive-descent parser accepting the full generic JSON
     * grammar (objects, arrays, strings with escapes, numbers, booleans,
     * null) -- deliberately not schema-aware itself; schema validation is
     * the caller's job via {@link JsonObject}'s {@code getXxx(key)}
     * accessors.
     */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        JsonValue parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new JsonParseException("unexpected end of input at position " + pos);
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> new JsonString(parseString());
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new JsonParseException("unexpected character '" + c + "' at position " + pos);
                }
            };
        }

        private JsonObject parseObject() {
            expect('{');
            JsonObject object = new JsonObject();
            skipWhitespace();
            if (peekIs('}')) {
                pos++;
                return object;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != '"') {
                    throw new JsonParseException("expected a string key at position " + pos);
                }
                String key = parseString();
                if (object.has(key)) {
                    throw new JsonParseException("duplicate key \"" + key + "\" at position " + pos);
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                JsonValue value = parseValue();
                object.put(key, value);
                skipWhitespace();
                char next = next("expected ',' or '}'");
                if (next == ',') {
                    continue;
                }
                if (next == '}') {
                    break;
                }
                throw new JsonParseException("expected ',' or '}' but found '" + next + "' at position " + (pos - 1));
            }
            return object;
        }

        private JsonArray parseArray() {
            expect('[');
            JsonArray array = new JsonArray();
            skipWhitespace();
            if (peekIs(']')) {
                pos++;
                return array;
            }
            while (true) {
                skipWhitespace();
                array.add(parseValue());
                skipWhitespace();
                char next = next("expected ',' or ']'");
                if (next == ',') {
                    continue;
                }
                if (next == ']') {
                    break;
                }
                throw new JsonParseException("expected ',' or ']' but found '" + next + "' at position " + (pos - 1));
            }
            return array;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next("unterminated string");
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char escaped = next("truncated escape sequence");
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
                        default -> throw new JsonParseException("invalid escape '\\" + escaped + "' at position " + (pos - 1));
                    }
                } else if (c < 0x20) {
                    throw new JsonParseException("unescaped control character in string at position " + (pos - 1));
                } else {
                    sb.append(c);
                }
            }
        }

        private char parseUnicodeEscape() {
            if (pos + 4 > s.length()) {
                throw new JsonParseException("truncated \\u escape at position " + pos);
            }
            String hex = s.substring(pos, pos + 4);
            pos += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new JsonParseException("invalid \\u escape '" + hex + "' at position " + (pos - 4));
            }
        }

        private JsonBoolean parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return new JsonBoolean(true);
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return new JsonBoolean(false);
            }
            throw new JsonParseException("expected boolean literal at position " + pos);
        }

        private JsonNull parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return JsonNull.INSTANCE;
            }
            throw new JsonParseException("expected null literal at position " + pos);
        }

        private JsonNumber parseNumber() {
            int start = pos;
            if (peekIs('-')) {
                pos++;
            }
            if (atEnd() || !Character.isDigit(s.charAt(pos))) {
                throw new JsonParseException("expected digit at position " + pos);
            }
            if (s.charAt(pos) == '0') {
                pos++;
            } else {
                while (!atEnd() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && s.charAt(pos) == '.') {
                pos++;
                if (atEnd() || !Character.isDigit(s.charAt(pos))) {
                    throw new JsonParseException("expected digit after decimal point at position " + pos);
                }
                while (!atEnd() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (!atEnd() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (atEnd() || !Character.isDigit(s.charAt(pos))) {
                    throw new JsonParseException("expected digit in exponent at position " + pos);
                }
                while (!atEnd() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String literal = s.substring(start, pos);
            try {
                return new JsonNumber(Double.parseDouble(literal));
            } catch (NumberFormatException e) {
                throw new JsonParseException("invalid number literal \"" + literal + "\" at position " + start);
            }
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private boolean peekIs(char expected) {
            return !atEnd() && s.charAt(pos) == expected;
        }

        private void expect(char expected) {
            char actual = next("expected '" + expected + "'");
            if (actual != expected) {
                throw new JsonParseException("expected '" + expected + "' but found '" + actual + "' at position " + (pos - 1));
            }
        }

        private char next(String errorContext) {
            if (atEnd()) {
                throw new JsonParseException(errorContext + " but reached end of input at position " + pos);
            }
            return s.charAt(pos++);
        }
    }
}
