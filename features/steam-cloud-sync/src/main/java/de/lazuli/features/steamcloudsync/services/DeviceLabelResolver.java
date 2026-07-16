package de.lazuli.features.steamcloudsync.services;

/**
 * Pure derivation of a human-readable device label (used as
 * {@code WorldFingerprint.deviceLabel}, FR6.6) from a username and an
 * optional hostname. Kept as a small, plain-JVM-testable pure function so its
 * fallback logic (missing hostname, missing username, both missing) is
 * exercised with fixed inputs rather than depending on this process's real
 * environment inside a test.
 *
 * <p>The one real call site ({@code WorldSaveSyncService}'s construction,
 * inside this feature -- no platform involvement needed) supplies
 * {@code System.getProperty("user.name")} and (wrapped in a try/catch
 * defaulting to {@code null}) {@code InetAddress.getLocalHost().getHostName()}.
 *
 * <p>Usage example:
 * <pre>{@code
 * String label = DeviceLabelResolver.resolve("duck", "ducks-pc");   // "duck@ducks-pc"
 * String noHost = DeviceLabelResolver.resolve("duck", null);        // "duck"
 * String neither = DeviceLabelResolver.resolve(null, null);         // "Unknown device"
 * }</pre>
 */
public final class DeviceLabelResolver {

    /** Used when neither a username nor a hostname is available. */
    public static final String UNKNOWN_DEVICE_LABEL = "Unknown device";

    private DeviceLabelResolver() {
    }

    /**
     * @param userName       {@code System.getProperty("user.name")}'s value,
     *                       or {@code null}/blank if unavailable
     * @param hostNameOrNull the local hostname, or {@code null} if it could
     *                       not be resolved (e.g. {@code UnknownHostException})
     * @return {@code "user@host"} if both are available, just the username or
     *         hostname if only one is, or {@link #UNKNOWN_DEVICE_LABEL} if
     *         neither is
     */
    public static String resolve(String userName, String hostNameOrNull) {
        boolean hasUser = userName != null && !userName.isBlank();
        boolean hasHost = hostNameOrNull != null && !hostNameOrNull.isBlank();

        if (hasUser && hasHost) {
            return userName + "@" + hostNameOrNull;
        }
        if (hasUser) {
            return userName;
        }
        if (hasHost) {
            return hostNameOrNull;
        }
        return UNKNOWN_DEVICE_LABEL;
    }
}
