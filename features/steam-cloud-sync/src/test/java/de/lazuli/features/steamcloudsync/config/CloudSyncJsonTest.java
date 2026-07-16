package de.lazuli.features.steamcloudsync.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The most exhaustive test class in this feature (per the implementation
 * plan's Test Strategy): every one of the six {@code *IO} classes depends on
 * {@link CloudSyncJson}'s correctness, and it must fail closed on anything
 * not confidently recognized, never attempt best-effort partial parsing.
 */
class CloudSyncJsonTest {

    @Test
    void parsesEmptyObjectAndArray() {
        assertThat(CloudSyncJson.parse("{}")).isInstanceOf(CloudSyncJson.JsonObject.class);
        assertThat(CloudSyncJson.parse("[]")).isInstanceOf(CloudSyncJson.JsonArray.class);
    }

    @Test
    void parsesFlatObjectOfEveryPrimitiveType() {
        CloudSyncJson.JsonValue value = CloudSyncJson.parse(
                "{\"str\": \"hello\", \"num\": 42, \"neg\": -3.5, \"bool\": true, \"nil\": null}");

        assertThat(value).isInstanceOf(CloudSyncJson.JsonObject.class);
        CloudSyncJson.JsonObject object = (CloudSyncJson.JsonObject) value;
        assertThat(object.getString("str")).isEqualTo("hello");
        assertThat(object.getNumber("num")).isEqualTo(42.0);
        assertThat(object.getNumber("neg")).isEqualTo(-3.5);
        assertThat(object.getBoolean("bool")).isTrue();
        assertThat(object.get("nil")).isInstanceOf(CloudSyncJson.JsonNull.class);
    }

    @Test
    void parsesNestedObjectsAndArrays() {
        CloudSyncJson.JsonObject root = (CloudSyncJson.JsonObject) CloudSyncJson.parse(
                "{\"outer\": {\"inner\": [1, 2, {\"deep\": true}]}}");

        CloudSyncJson.JsonObject outer = root.getObject("outer");
        CloudSyncJson.JsonArray inner = outer.getArray("inner");
        assertThat(inner.elements()).hasSize(3);
        assertThat(((CloudSyncJson.JsonNumber) inner.elements().get(0)).value()).isEqualTo(1.0);
        CloudSyncJson.JsonObject deepObject = (CloudSyncJson.JsonObject) inner.elements().get(2);
        assertThat(deepObject.getBoolean("deep")).isTrue();
    }

    @Test
    void parsesEscapedStrings() {
        CloudSyncJson.JsonObject root = (CloudSyncJson.JsonObject) CloudSyncJson.parse(
                "{\"text\": \"Say \\\"hi\\\"\\tnewline:\\nbackslash:\\\\ done\"}");

        assertThat(root.getString("text")).isEqualTo("Say \"hi\"\tnewline:\nbackslash:\\ done");
    }

    @Test
    void parsesUnicodeEscape() {
        CloudSyncJson.JsonObject root = (CloudSyncJson.JsonObject) CloudSyncJson.parse("{\"text\": \"\\u0041\\u0042\"}");

        assertThat(root.getString("text")).isEqualTo("AB");
    }

    @Test
    void parsesNumbersWithExponents() {
        CloudSyncJson.JsonObject root = (CloudSyncJson.JsonObject) CloudSyncJson.parse(
                "{\"a\": 1e3, \"b\": 1.5E-2, \"c\": 0, \"d\": -0.5}");

        assertThat(root.getNumber("a")).isEqualTo(1000.0);
        assertThat(root.getNumber("b")).isEqualTo(0.015);
        assertThat(root.getNumber("c")).isEqualTo(0.0);
        assertThat(root.getNumber("d")).isEqualTo(-0.5);
    }

    @Test
    void writeThenParseRoundTrips() {
        CloudSyncJson.JsonObject original = new CloudSyncJson.JsonObject()
                .putString("name", "test \"quoted\"")
                .putNumber("count", 7)
                .putBoolean("flag", false)
                .put("nested", new CloudSyncJson.JsonArray()
                        .add(new CloudSyncJson.JsonString("a"))
                        .add(new CloudSyncJson.JsonNumber(2.5)));

        String written = CloudSyncJson.write(original);
        CloudSyncJson.JsonObject reparsed = (CloudSyncJson.JsonObject) CloudSyncJson.parse(written);

        assertThat(reparsed.getString("name")).isEqualTo("test \"quoted\"");
        assertThat(reparsed.getNumber("count")).isEqualTo(7.0);
        assertThat(reparsed.getBoolean("flag")).isFalse();
        assertThat(reparsed.getArray("nested").elements()).hasSize(2);
    }

    @Test
    void getStringOrNullTreatsMissingAndNullAsNull() {
        CloudSyncJson.JsonObject root = (CloudSyncJson.JsonObject) CloudSyncJson.parse("{\"present\": null}");

        assertThat(root.getStringOrNull("present")).isNull();
        assertThat(root.getStringOrNull("absent")).isNull();
    }

    @Test
    void getNumberOrNullTreatsMissingAndNullAsNull() {
        CloudSyncJson.JsonObject root = (CloudSyncJson.JsonObject) CloudSyncJson.parse("{\"present\": null}");

        assertThat(root.getNumberOrNull("present")).isNull();
        assertThat(root.getNumberOrNull("absent")).isNull();
    }

    @Test
    void malformedInputThrowsJsonParseException() {
        assertThatThrownBy(() -> CloudSyncJson.parse("not json at all"))
                .isInstanceOf(CloudSyncJson.JsonParseException.class);
        assertThatThrownBy(() -> CloudSyncJson.parse("{"))
                .isInstanceOf(CloudSyncJson.JsonParseException.class);
        assertThatThrownBy(() -> CloudSyncJson.parse("{\"a\": 1,}"))
                .isInstanceOf(CloudSyncJson.JsonParseException.class);
        assertThatThrownBy(() -> CloudSyncJson.parse("{\"a\" 1}"))
                .isInstanceOf(CloudSyncJson.JsonParseException.class);
        assertThatThrownBy(() -> CloudSyncJson.parse("[1, 2"))
                .isInstanceOf(CloudSyncJson.JsonParseException.class);
        assertThatThrownBy(() -> CloudSyncJson.parse(""))
                .isInstanceOf(CloudSyncJson.JsonParseException.class);
    }

    @Test
    void trailingContentAfterTopLevelValueThrows() {
        assertThatThrownBy(() -> CloudSyncJson.parse("{} extra"))
                .isInstanceOf(CloudSyncJson.JsonParseException.class);
    }

    @Test
    void duplicateKeysThrow() {
        assertThatThrownBy(() -> CloudSyncJson.parse("{\"a\": 1, \"a\": 2}"))
                .isInstanceOf(CloudSyncJson.JsonParseException.class);
    }

    @Test
    void nullInputThrows() {
        assertThatThrownBy(() -> CloudSyncJson.parse(null))
                .isInstanceOf(CloudSyncJson.JsonParseException.class);
    }

    @Test
    void unterminatedStringThrows() {
        assertThatThrownBy(() -> CloudSyncJson.parse("{\"a\": \"unterminated"))
                .isInstanceOf(CloudSyncJson.JsonParseException.class);
    }

    @Test
    void invalidEscapeThrows() {
        assertThatThrownBy(() -> CloudSyncJson.parse("{\"a\": \"bad \\q escape\"}"))
                .isInstanceOf(CloudSyncJson.JsonParseException.class);
    }

    @Test
    void missingFieldThrowsJsonSchemaException() {
        CloudSyncJson.JsonObject root = (CloudSyncJson.JsonObject) CloudSyncJson.parse("{}");

        assertThatThrownBy(() -> root.getString("missing"))
                .isInstanceOf(CloudSyncJson.JsonSchemaException.class);
    }

    @Test
    void wrongTypeThrowsJsonSchemaException() {
        CloudSyncJson.JsonObject root = (CloudSyncJson.JsonObject) CloudSyncJson.parse("{\"a\": \"not a number\"}");

        assertThatThrownBy(() -> root.getNumber("a"))
                .isInstanceOf(CloudSyncJson.JsonSchemaException.class);
    }

    @Test
    void writeProducesPrettyPrintedTrailingNewline() {
        String written = CloudSyncJson.write(new CloudSyncJson.JsonObject().putBoolean("x", true));

        assertThat(written).endsWith("\n");
        assertThat(written).contains("\"x\": true");
    }
}
