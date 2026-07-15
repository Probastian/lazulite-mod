package de.lazuli.features.helloworldmainmenu.services;

import de.lazuli.api.mainmenu.MainMenuHook;
import de.lazuli.features.helloworldmainmenu.api.HelloWorldMainMenuConfig;
import de.lazuli.features.helloworldmainmenu.config.HelloWorldMainMenuConfigIO;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Feature-owned business logic for the "Hello World Main Menu" feature.
 *
 * <p>Pure orchestration: loads the feature's config, decides whether the
 * label should be visible, and calls the injected {@link MainMenuHook}
 * accordingly. Contains no {@code net.minecraft.*} or {@code net.fabricmc.*}
 * imports, and none of its dependencies do either -- it is unit-testable on
 * a plain JVM.
 *
 * <p>Every dependency is supplied via the constructor by the platform
 * module's composition root (e.g. a {@code ClientModInitializer}); this
 * class never looks anything up itself.
 *
 * <p>Usage example:
 * <pre>{@code
 * MainMenuHook hook = new FabricMainMenuHook();
 * HelloWorldMainMenuConfigIO configIO = new HelloWorldMainMenuConfigIO();
 * Path configPath = FabricLoader.getInstance().getConfigDir().resolve("hello-world-main-menu.json");
 * HelloWorldMainMenuService service =
 *         new HelloWorldMainMenuService(hook, configIO, configPath, LazuliMod.LOGGER::warn);
 * service.applyToMainMenu();
 * }</pre>
 */
public final class HelloWorldMainMenuService {

    private final MainMenuHook hook;
    private final HelloWorldMainMenuConfigIO configIO;
    private final Path configFilePath;
    private final Consumer<String> warningLogger;

    /**
     * @param hook           the platform's main-menu label hook
     * @param configIO       reads/writes this feature's config file
     * @param configFilePath the config file's location
     * @param warningLogger  invoked with a human-readable message whenever
     *                       config loading falls back to defaults; typically
     *                       wired to the platform's SLF4J logger
     */
    public HelloWorldMainMenuService(
            MainMenuHook hook,
            HelloWorldMainMenuConfigIO configIO,
            Path configFilePath,
            Consumer<String> warningLogger) {
        this.hook = Objects.requireNonNull(hook, "hook");
        this.configIO = Objects.requireNonNull(configIO, "configIO");
        this.configFilePath = Objects.requireNonNull(configFilePath, "configFilePath");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
    }

    /**
     * Loads the current config and shows or hides the main-menu label to
     * match it. Safe to call multiple times (e.g. once per title screen
     * {@code init()}); each call re-reads the already-loaded config decision
     * cheaply -- it does not re-read the file from disk on every call beyond
     * what {@link HelloWorldMainMenuConfigIO#load} itself does.
     */
    public void applyToMainMenu() {
        HelloWorldMainMenuConfigIO.ParseResult result = configIO.load(configFilePath);
        if (result.warning() != null) {
            warningLogger.accept(result.warning());
        }

        HelloWorldMainMenuConfig config = result.config();
        if (config.shouldDisplayLabel()) {
            hook.showLabel(config.text());
        } else {
            hook.hideLabel();
        }
    }
}


