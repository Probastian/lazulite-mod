package de.lazuli.features.crossworldstats.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small, hand-rolled, generic JSON value model and recursive-descent
 * parser/writer (objects/strings/numbers/booleans), scoped to this feature's
 * own module -- mirrors {@code features/steam-cloud-sync}'s own
 * {@code CloudSyncJson} shape/discipline (this feature's nested
 * {@code accounts -> account -> totals/worldBaselines} shape needs real
 * nested-object support, not a single flat-schema hand parser like
 * {@code ServerJoinPresenceConfigIO}'s), without adding a compile-time
 * dependency on that other feature's module or any external JSON library.
 *
 * <p>Deliberately conservative: {@link #parse(String)} fails closed -- any
 * malformed input throws {@link JsonParseException}/{@link JsonSchemaException}
 * rather than attempting partial recovery; callers fall back to defaults with
 * a logged warning (same discipline as every sibling config-file convention
 * in this repo).
 */
final class CrossWorldStatsJson {

    private CrossWorldStatsJson() {
    }

    sealed interface JsonValue permits JsonObject, JsonString, JsonNumber, JsonBoolean {
    }

    static final class JsonObject implements JsonValue {
        private final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();

        JsonObject put(String key, JsonValue value) {
            members.put(key, value);
            return this;
        }

        JsonObject putString(String key, String value) {
            return put(key, new JsonString(value));
        }

        JsonObject putBoolean(String key, boolean value) {
            return put(key, new JsonBoolean(value));
        }

        JsonObject putNumber(String key, long value) {
            return put(key, new JsonNumber(value));
        }

        boolean has(String key) {
            return members.containsKey(key);
        }

        Map<String, JsonValue> members() {
            return members;
        }

        boolean getBoolean(String key) {
            JsonValue value = require(key);
            if (!(value instanceof JsonBoolean b)) {
                throw new JsonSchemaException("expected \"" + key + "\" to be a boolean");
            }
            return b.value();
        }

        boolean getBooleanOrDefault(String key, boolean fallback) {
            return members.containsKey(key) ? getBoolean(key) : fallback;
        }

        long getLong(String key) {
            JsonValue value = require(key);
            if (!(value instanceof JsonNumber n)) {
                throw new JsonSchemaException("expected \"" + key + "\" to be a number");
            }
            return n.value();
        }

        JsonObject getObject(String key) {
            JsonValue value = require(key);
            if (!(value instanceof JsonObject o)) {
                throw new JsonSchemaException("expected \"" + key + "\" to be an object");
            }
            return o;
        }

        JsonObject getObjectOrEmpty(String key) {
            return members.containsKey(key) ? getObject(key) : new JsonObject();
        }

        private JsonValue require(String key) {
            if (!members.containsKey(key)) {
                throw new JsonSchemaException("missing required field \"" + key + "\"");
            }
            return members.get(key);
        }
    }

    record JsonString(String value) implements JsonValue {
    }

    record JsonNumber(long value) implements JsonValue {
    }

    record JsonBoolean(boolean value) implements JsonValue {
    }

    static final class JsonParseException extends RuntimeException {
        JsonParseException(String message) {
            super(message);
        }
    }

    static final class JsonSchemaException extends RuntimeException {
        JsonSchemaException(String message) {
            super(message);
        }
    }

    static JsonValue parse(String text) {
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

    static String write(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(JsonValue value, StringBuilder sb, int indent) {
        switch (value) {
            case JsonObject o -> writeObject(o, sb, indent);
            case JsonString s -> writeQuotedString(s.value(), sb);
            case JsonNumber n -> sb.append(n.value());
            case JsonBoolean b -> sb.append(b.value());
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

    private static void indent(StringBuilder sb, int level) {
        sb.append("  ".repeat(Math.max(0, level)));
    }

    private static void writeQuotedString(String value, StringBuilder sb) {
        String safe = value == null ? "" : value;
        sb.append('"');
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

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
                case '"' -> new JsonString(parseString());
                case 't', 'f' -> parseBoolean();
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
                        default -> throw new JsonParseException("invalid escape '\\" + escaped + "' at position " + (pos - 1));
                    }
                } else {
                    sb.append(c);
                }
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

        private JsonNumber parseNumber() {
            int start = pos;
            if (peekIs('-')) {
                pos++;
            }
            if (atEnd() || !Character.isDigit(s.charAt(pos))) {
                throw new JsonParseException("expected digit at position " + pos);
            }
            while (!atEnd() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            String literal = s.substring(start, pos);
            try {
                return new JsonNumber(Long.parseLong(literal));
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
