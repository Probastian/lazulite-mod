package de.probastian.boilerplate.features.helloworldmainmenu.api;

/**
 * Immutable configuration for the "Hello World Main Menu" feature.
 *
 * <p>Backed by a small JSON file (see
 * {@code de.probastian.boilerplate.features.helloworldmainmenu.config.HelloWorldMainMenuConfigIO}):
 * <pre>{@code
 * {
 *   "enabled": true,
 *   "text": "Hello World"
 * }
 * }</pre>
 *
 * <p>Usage example:
 * <pre>{@code
 * HelloWorldMainMenuConfig config = HelloWorldMainMenuConfig.DEFAULT;
 * if (config.shouldDisplayLabel()) {
 *     hook.showLabel(config.text());
 * }
 * }</pre>
 *
 * @param enabled whether the label should be considered on at all
 * @param text    the text to display; an empty/blank value is treated as
 *                equivalent to {@code enabled = false} by
 *                {@link #shouldDisplayLabel()}
 */
public record HelloWorldMainMenuConfig(boolean enabled, String text) {

    /**
     * The default configuration used when no config file exists yet, or when
     * an existing file fails to parse.
     */
    public static final HelloWorldMainMenuConfig DEFAULT = new HelloWorldMainMenuConfig(true, "Hello World");

    /**
     * Whether the label should actually be shown on the title screen.
     *
     * <p>A blank or whitespace-only {@link #text()} is treated as
     * equivalent to {@code enabled = false} even if {@link #enabled()} is
     * {@code true}, since rendering a zero-width label is never useful and
     * is more likely a configuration mistake than an intentional state.
     *
     * @return {@code true} if, and only if, {@link #enabled()} is
     *         {@code true} and {@link #text()} is non-null and non-blank
     */
    public boolean shouldDisplayLabel() {
        return enabled && text != null && !text.isBlank();
    }
}
