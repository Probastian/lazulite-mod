package de.lazuli.services.steamworks;

/**
 * Pure, unit-testable mapping from a steamworks4j logical library name (the
 * literal {@code String} steamworks4j's own {@code SteamAPI.loadLibraries(...)}
 * passes to {@code SteamLibraryLoader.loadLibrary(String)} - {@code "steam_api"}
 * for Valve's redistributable Steamworks client library, or
 * {@code "steamworks4j"} for steamworks4j's own small JNI bridge - to the
 * actual classpath resource file name bundled inside the published
 * {@code steamworks4j} jar for a given {@code os.name}/{@code os.arch}.
 *
 * <p>Takes {@code os.name}/{@code os.arch}-shaped {@link String} parameters
 * rather than reading {@link System#getProperty(String)} itself, so every
 * OS/arch combination can be exercised from a single CI machine; only
 * {@link ClasspathSteamLibraryLoader} passes the JVM's real property values.
 *
 * <p>Resolved by inspecting the actual resources bundled in the published
 * {@code com.code-disaster.steamworks4j:steamworks4j:1.10.0} jar (both
 * Valve's and steamworks4j's own binaries are packaged flat at the jar
 * root, not per-platform subdirectories):
 * <pre>
 * steam_api.dll        (Windows 32-bit, unused - this project targets 64-bit only)
 * steam_api64.dll       (Windows 64-bit)
 * libsteam_api.so        (Linux, all supported architectures)
 * libsteam_api.dylib      (macOS, universal x86_64 + arm64)
 * steamworks4j.dll       (Windows 32-bit, unused)
 * steamworks4j64.dll      (Windows 64-bit)
 * libsteamworks4j.so       (Linux, all supported architectures)
 * libsteamworks4j.dylib     (macOS, universal x86_64 + arm64)
 * </pre>
 *
 * <p>Usage example:
 * <pre>{@code
 * String resource = SteamNativeLibraryNames.resourceName(
 *         "steam_api", System.getProperty("os.name"), System.getProperty("os.arch"));
 * // -> "steam_api64.dll" on a 64-bit Windows JVM
 * }</pre>
 */
final class SteamNativeLibraryNames {

    private SteamNativeLibraryNames() {
    }

    /**
     * @param logicalName the literal library name steamworks4j passes to
     *                     {@code SteamLibraryLoader.loadLibrary(String)}
     *                     ({@code "steam_api"} or {@code "steamworks4j"})
     * @param osName       {@code System.getProperty("os.name")}-shaped value
     * @param osArch       {@code System.getProperty("os.arch")}-shaped value
     * @return the classpath resource file name bundled in the steamworks4j
     *         jar for that logical library on that OS/arch, or {@code null}
     *         if the combination is not supported (e.g. 32-bit Windows,
     *         which this project does not target)
     */
    static String resourceName(String logicalName, String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(java.util.Locale.ROOT);
        boolean is64Bit = arch.contains("64");

        if (os.contains("win")) {
            return is64Bit ? logicalName + "64.dll" : null;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "lib" + logicalName + ".dylib";
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("linux")) {
            return is64Bit ? "lib" + logicalName + ".so" : null;
        }
        return null;
    }
}
