package de.lazuli.api.mainmenu;

/**
 * Stable, dependency-free abstraction over a platform's ability to show or
 * hide a single decorative text label on the game's title/main menu screen.
 *
 * <p>This interface is the "Platform API" half of the
 * {@code Feature -> Platform API -> Version Adapter -> Minecraft} pattern
 * described in {@code architecture.md}. It intentionally exposes only
 * {@link String} in its signatures so that no {@code net.minecraft.*} or
 * {@code net.fabricmc.*} type ever needs to appear in a feature module. Each
 * {@code platform/fabric-<version>} module supplies its own implementation
 * (a "Version Adapter") using that version's real Minecraft/Fabric GUI
 * classes.
 *
 * <p>Implementations are expected to be idempotent and cheap to call
 * repeatedly (e.g. once per title screen {@code init()}), and to only ever
 * be exercised on the client.
 *
 * <p>Usage example (from a feature's business logic, holding a
 * constructor-injected {@code MainMenuHook}):
 * <pre>{@code
 * MainMenuHook hook = ...; // supplied by the platform composition root
 * if (config.shouldDisplayLabel()) {
 *     hook.showLabel(config.text());
 * } else {
 *     hook.hideLabel();
 * }
 * }</pre>
 */
public interface MainMenuHook {

    /**
     * Shows (or updates) the label on the title screen with the given text.
     *
     * @param text the text to display; implementations should tolerate any
     *             non-null string, including unusually long values
     */
    void showLabel(String text);

    /**
     * Hides the label from the title screen, if currently shown.
     */
    void hideLabel();
}


