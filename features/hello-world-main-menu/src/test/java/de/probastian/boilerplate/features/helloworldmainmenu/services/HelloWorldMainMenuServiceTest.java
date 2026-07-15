package de.probastian.boilerplate.features.helloworldmainmenu.services;

import de.probastian.boilerplate.api.mainmenu.MainMenuHook;
import de.probastian.boilerplate.features.helloworldmainmenu.config.HelloWorldMainMenuConfigIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelloWorldMainMenuServiceTest {

    /**
     * Hand-written fake {@link MainMenuHook} (per the implementation plan's
     * Test Strategy: the interface's surface is tiny enough that no mocking
     * framework is needed) that records what was called.
     */
    private static final class FakeMainMenuHook implements MainMenuHook {
        String shownText;
        boolean hideCalled;
        int showCallCount;
        int hideCallCount;

        @Override
        public void showLabel(String text) {
            shownText = text;
            showCallCount++;
        }

        @Override
        public void hideLabel() {
            hideCalled = true;
            hideCallCount++;
        }
    }

    @Test
    void enabledWithNonBlankTextShowsLabel(@TempDir Path tempDir) {
        Path configPath = tempDir.resolve("hello-world-main-menu.json");
        writeConfig(configPath, "{\"enabled\": true, \"text\": \"Hello World\"}");

        FakeMainMenuHook hook = new FakeMainMenuHook();
        List<String> warnings = new ArrayList<>();
        HelloWorldMainMenuService service =
                new HelloWorldMainMenuService(hook, new HelloWorldMainMenuConfigIO(), configPath, warnings::add);

        service.applyToMainMenu();

        assertEquals("Hello World", hook.shownText);
        assertEquals(1, hook.showCallCount);
        assertFalse(hook.hideCalled);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void disabledHidesLabelAndNeverShows(@TempDir Path tempDir) {
        Path configPath = tempDir.resolve("hello-world-main-menu.json");
        writeConfig(configPath, "{\"enabled\": false, \"text\": \"Hello World\"}");

        FakeMainMenuHook hook = new FakeMainMenuHook();
        HelloWorldMainMenuService service =
                new HelloWorldMainMenuService(hook, new HelloWorldMainMenuConfigIO(), configPath, warning -> { });

        service.applyToMainMenu();

        assertTrue(hook.hideCalled);
        assertEquals(1, hook.hideCallCount);
        assertNull(hook.shownText);
        assertEquals(0, hook.showCallCount);
    }

    @Test
    void malformedConfigReportsWarningAndFallsBackToDefaultBehavior(@TempDir Path tempDir) {
        Path configPath = tempDir.resolve("hello-world-main-menu.json");
        writeConfig(configPath, "this is not valid json");

        FakeMainMenuHook hook = new FakeMainMenuHook();
        List<String> warnings = new ArrayList<>();
        HelloWorldMainMenuService service =
                new HelloWorldMainMenuService(hook, new HelloWorldMainMenuConfigIO(), configPath, warnings::add);

        service.applyToMainMenu();

        assertEquals(1, warnings.size());
        assertFalse(warnings.get(0).isBlank());
        // DEFAULT is enabled=true, text="Hello World" -> falls back to showing the label.
        assertEquals("Hello World", hook.shownText);
        assertFalse(hook.hideCalled);
    }

    private static void writeConfig(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
