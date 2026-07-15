package de.probastian.boilerplate.features.helloworldmainmenu.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelloWorldMainMenuConfigTest {

    @Test
    void defaultIsEnabledWithHelloWorldText() {
        assertTrue(HelloWorldMainMenuConfig.DEFAULT.enabled());
        assertEquals("Hello World", HelloWorldMainMenuConfig.DEFAULT.text());
    }

    @Test
    void shouldDisplayLabelTrueWhenEnabledWithNonBlankText() {
        HelloWorldMainMenuConfig config = new HelloWorldMainMenuConfig(true, "Hello World");
        assertTrue(config.shouldDisplayLabel());
    }

    @Test
    void shouldDisplayLabelFalseWhenDisabled() {
        HelloWorldMainMenuConfig config = new HelloWorldMainMenuConfig(false, "Hello World");
        assertFalse(config.shouldDisplayLabel());
    }

    @Test
    void shouldDisplayLabelFalseWhenTextIsEmpty() {
        HelloWorldMainMenuConfig config = new HelloWorldMainMenuConfig(true, "");
        assertFalse(config.shouldDisplayLabel());
    }

    @Test
    void shouldDisplayLabelFalseWhenTextIsWhitespaceOnly() {
        HelloWorldMainMenuConfig config = new HelloWorldMainMenuConfig(true, "   ");
        assertFalse(config.shouldDisplayLabel());
    }
}
