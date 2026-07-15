package de.lazuli.services.steamworks;

import com.codedisaster.steamworks.SteamLibraryLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * This project's own implementation of steamworks4j's
 * {@link SteamLibraryLoader} extension point (steamworks4j's core
 * {@code java-wrapper} artifact ships only a no-op default; its
 * {@code -gdx}/{@code -lwjgl3} companion loaders are designed for those
 * specific frameworks' asset/classloading conventions and do not apply to a
 * Fabric mod).
 *
 * <p>{@link #loadLibrary(String)} extracts the requested native library
 * resource (steamworks4j calls this with the literal logical names
 * {@code "steam_api"} and {@code "steamworks4j"}) from this project's own
 * classpath - the published {@code steamworks4j} jar bundles both Valve's
 * redistributable Steamworks client library and steamworks4j's own JNI
 * bridge as flat classpath resources - to a caller-supplied extraction
 * directory, then {@link System#load(String)}s it. Never throws; any I/O or
 * link failure is logged via the supplied warning logger and reported back
 * as {@code false}, consistent with this service's "never throw out of a
 * Steamworks bootstrap attempt" contract.
 *
 * <p>The extraction directory is supplied by the caller (this project's
 * platform composition roots resolve it via Fabric Loader's config
 * directory) rather than looked up here, so this class - like the rest of
 * {@code services}/{@code api} - stays buildable/testable with no Fabric
 * Loader dependency on its classpath.
 *
 * <p>Usage example:
 * <pre>{@code
 * Path nativeDir = FabricLoader.getInstance().getConfigDir()
 *         .resolve("lazuli").resolve("steamworks-natives");
 * SteamLibraryLoader loader = new ClasspathSteamLibraryLoader(nativeDir, warning -> LOGGER.warn(warning));
 * boolean loaded = SteamAPI.loadLibraries(loader);
 * }</pre>
 */
public final class ClasspathSteamLibraryLoader implements SteamLibraryLoader {

    private final Path extractionDirectory;
    private final Consumer<String> warningLogger;

    /**
     * @param extractionDirectory directory the native library files are
     *                            extracted into (created if it does not
     *                            already exist); must be writable
     * @param warningLogger       receives a human-readable message for any
     *                            extraction/link failure; never invoked with
     *                            a thrown exception itself
     */
    public ClasspathSteamLibraryLoader(Path extractionDirectory, Consumer<String> warningLogger) {
        this.extractionDirectory = extractionDirectory;
        this.warningLogger = warningLogger;
    }

    /**
     * Extracts the named native library resource from the classpath and
     * loads it. steamworks4j calls this with the bare logical names
     * {@code "steam_api"} and {@code "steamworks4j"}; the actual per-OS/arch
     * resource file name is resolved via {@link SteamNativeLibraryNames}.
     *
     * @param libraryName the logical library name steamworks4j passes in
     * @return {@code true} if the library was extracted and loaded
     *         successfully; {@code false} on any failure (unsupported
     *         OS/arch, missing resource, I/O error, or link failure) - never
     *         throws
     */
    @Override
    public boolean loadLibrary(String libraryName) {
        try {
            String resourceName = SteamNativeLibraryNames.resourceName(
                    libraryName, System.getProperty("os.name"), System.getProperty("os.arch"));
            if (resourceName == null) {
                warn("Unsupported OS/architecture for Steamworks native library '" + libraryName + "': "
                        + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
                return false;
            }

            Path extracted = extractionDirectory.resolve(resourceName);
            Files.createDirectories(extractionDirectory);

            try (InputStream resource = ClasspathSteamLibraryLoader.class
                    .getClassLoader()
                    .getResourceAsStream(resourceName)) {
                if (resource == null) {
                    warn("Steamworks native library resource not found on classpath: " + resourceName);
                    return false;
                }
                Files.copy(resource, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            System.load(extracted.toAbsolutePath().toString());
            return true;
        } catch (IOException | UnsatisfiedLinkError | RuntimeException e) {
            warn("Failed to load Steamworks native library '" + libraryName + "': " + e);
            return false;
        }
    }

    private void warn(String message) {
        if (warningLogger != null) {
            warningLogger.accept(message);
        }
    }
}
