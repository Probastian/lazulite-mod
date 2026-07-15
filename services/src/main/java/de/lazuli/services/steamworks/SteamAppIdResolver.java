package de.lazuli.services.steamworks;

import java.util.function.Function;

/**
 * Pure, unit-testable resolution of which Steamworks App ID this process
 * should attempt to initialize the Steamworks API for.
 *
 * <p>Resolution order:
 * <ol>
 *     <li>The {@code lazuli.steamAppId} JVM system property, if present and
 *     parseable as a {@code long}.</li>
 *     <li>Otherwise, Valve's public test App ID {@code 480} ("Spacewar"),
 *     the standard placeholder for local development before a real
 *     Steamworks App ID is issued.</li>
 * </ol>
 *
 * <p><strong>This is entirely this project's own convenience layer, not a
 * Steamworks-native mechanism.</strong> Valve's Steamworks API itself only
 * ever reads an App ID from a {@code steam_appid.txt} file in the process's
 * working directory at the moment {@code SteamAPI_Init} runs (or from being
 * launched directly by the Steam client) - it does not read this or any
 * other JVM system property, and Valve's public docs do not document a
 * {@code SteamAppId} environment variable as part of the supported API. The
 * value resolved here is used only for this service's own diagnostics/
 * logging (see {@code SteamAvailability#steamAppId()}); generating the dev
 * {@code run/steam_appid.txt} file itself is a separate, Gradle-build-time
 * concern (see each {@code platform/fabric-<version>/build.gradle}'s
 * {@code generateSteamAppId} task), deliberately not conflated with this
 * runtime resolver.
 *
 * <p>The system-property lookup is injected as a {@link Function} rather
 * than read via {@link System#getProperty(String)} directly, so tests can
 * supply a fake lookup instead of mutating real system properties.
 *
 * <p>Usage example:
 * <pre>{@code
 * long appId = SteamAppIdResolver.resolve(System::getProperty);
 * // -> the lazuli.steamAppId system property's value, or 480L if absent/invalid
 * }</pre>
 */
public final class SteamAppIdResolver {

    /**
     * The JVM system property this project reads as its own App ID
     * override convenience layer. Not read by Valve's Steamworks API itself.
     */
    public static final String SYSTEM_PROPERTY = "lazuli.steamAppId";

    /**
     * Valve's public test App ID ("Spacewar"), the standard placeholder used
     * for local development before a real Steamworks App ID is issued.
     */
    public static final long DEFAULT_APP_ID = 480L;

    private SteamAppIdResolver() {
    }

    /**
     * @param systemPropertyLookup a system-property-shaped lookup (e.g.
     *                             {@code System::getProperty}); may return
     *                             {@code null} for an absent property
     * @return the resolved App ID: the {@link #SYSTEM_PROPERTY} override if
     *         present and parseable as a {@code long}; otherwise
     *         {@link #DEFAULT_APP_ID}. Never throws.
     */
    public static long resolve(Function<String, String> systemPropertyLookup) {
        String override = systemPropertyLookup == null ? null : systemPropertyLookup.apply(SYSTEM_PROPERTY);
        if (override == null || override.isBlank()) {
            return DEFAULT_APP_ID;
        }
        try {
            return Long.parseLong(override.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_APP_ID;
        }
    }
}
