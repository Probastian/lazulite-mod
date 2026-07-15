package de.probastian.boilerplate.features.helloworldmainmenu.config;

import de.probastian.boilerplate.features.helloworldmainmenu.api.HelloWorldMainMenuConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class HelloWorldMainMenuConfigIOTest {

    private final HelloWorldMainMenuConfigIO configIO = new HelloWorldMainMenuConfigIO();

    @Test
    void loadCreatesFileWithDefaultsWhenMissing(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("hello-world-main-menu.json");

        HelloWorldMainMenuConfigIO.ParseResult result = configIO.load(path);

        assertTrue(Files.exists(path));
        assertEquals(HelloWorldMainMenuConfig.DEFAULT, result.config());
        assertNull(result.warning());

        String written = Files.readString(path);
        HelloWorldMainMenuConfigIO.ParseResult reparsed = configIO.parse(written);
        assertEquals(HelloWorldMainMenuConfig.DEFAULT, reparsed.config());
        assertNull(reparsed.warning());
    }

    @Test
    void parsesWellFormedFile() {
        HelloWorldMainMenuConfigIO.ParseResult result = configIO.parse("{\"enabled\": false, \"text\": \"Hi\"}");

        assertEquals(new HelloWorldMainMenuConfig(false, "Hi"), result.config());
        assertNull(result.warning());
    }

    @Test
    void parsesWellFormedFileWithKeysInOppositeOrder() {
        HelloWorldMainMenuConfigIO.ParseResult result = configIO.parse("{\"text\": \"Hi\", \"enabled\": true}");

        assertEquals(new HelloWorldMainMenuConfig(true, "Hi"), result.config());
        assertNull(result.warning());
    }

    @Test
    void malformedJsonFallsBackToDefaults() {
        HelloWorldMainMenuConfigIO.ParseResult result = configIO.parse("not json at all");

        assertEquals(HelloWorldMainMenuConfig.DEFAULT, result.config());
        assertNotNull(result.warning());
        assertFalse(result.warning().isBlank());
    }

    @Test
    void wrongTypesFallBackToDefaults() {
        HelloWorldMainMenuConfigIO.ParseResult result = configIO.parse("{\"enabled\": \"yes\", \"text\": \"Hi\"}");

        assertEquals(HelloWorldMainMenuConfig.DEFAULT, result.config());
        assertNotNull(result.warning());
    }

    @Test
    void missingKeysFallBackToDefaults() {
        HelloWorldMainMenuConfigIO.ParseResult result = configIO.parse("{\"enabled\": true}");

        assertEquals(HelloWorldMainMenuConfig.DEFAULT, result.config());
        assertNotNull(result.warning());
    }

    @Test
    void malformedFileNeverThrows(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("hello-world-main-menu.json");
        Files.writeString(path, "{ this is not valid json");

        assertDoesNotThrow(() -> {
            HelloWorldMainMenuConfigIO.ParseResult result = configIO.load(path);
            assertEquals(HelloWorldMainMenuConfig.DEFAULT, result.config());
            assertNotNull(result.warning());
        });
    }

    @Test
    void excessivelyLongTextIsToleratedAsIs() {
        String longText = "x".repeat(10_000);
        HelloWorldMainMenuConfigIO.ParseResult result = configIO.parse("{\"enabled\": true, \"text\": \"" + longText + "\"}");

        assertEquals(longText, result.config().text());
        assertNull(result.warning());
    }

    @Test
    void roundTripsEscapedCharacters() {
        HelloWorldMainMenuConfig original = new HelloWorldMainMenuConfig(true, "Say \"hi\"\tnewline:\nbackslash:\\ done");

        String serialized = configIO.serialize(original);
        HelloWorldMainMenuConfigIO.ParseResult reparsed = configIO.parse(serialized);

        assertEquals(original, reparsed.config());
        assertNull(reparsed.warning());
    }

    @Test
    void roundTripsRepresentativeValues() {
        for (HelloWorldMainMenuConfig config : new HelloWorldMainMenuConfig[] {
                HelloWorldMainMenuConfig.DEFAULT,
                new HelloWorldMainMenuConfig(false, ""),
                new HelloWorldMainMenuConfig(true, "   "),
                new HelloWorldMainMenuConfig(false, "Custom Text 123!"),
        }) {
            String serialized = configIO.serialize(config);
            HelloWorldMainMenuConfigIO.ParseResult reparsed = configIO.parse(serialized);
            assertEquals(config, reparsed.config());
            assertNull(reparsed.warning());
        }
    }
}
