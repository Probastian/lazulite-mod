package de.lazuli.features.worldhosting.services;

import java.util.OptionalLong;

/**
 * Owns this feature's Steam Rich Presence {@code "connect"}-string format
 * (FR2.3), so the exact literal is never hand-rolled with string slicing
 * scattered across callers. Pure, plain-JVM-testable, zero
 * {@code net.minecraft.*}/steamworks4j import -- one of this feature's two
 * primary unit-test targets (alongside {@link HostGateway}).
 *
 * <p>Format: {@code "+lazuli_join <steamId64>"} -- the prototype's own
 * {@code "+steamshare_join <id>"} shape, rebranded into this mod's own
 * namespace so it can never collide with a separately-installed copy of the
 * original prototype reading the same key on a shared friend's machine
 * (Decision 3). The {@code SteamID64} is emitted/parsed as an unsigned decimal.
 *
 * <p>Usage example:
 * <pre>{@code
 * String connect = ConnectStringCodec.encode(localSteamId64);   // set Rich Presence "connect"
 * OptionalLong host = ConnectStringCodec.decode(friendConnect); // is this friend hosting?
 * }</pre>
 */
public final class ConnectStringCodec {

    /** The fixed connect-string prefix (includes its trailing space). */
    static final String PREFIX = "+lazuli_join ";

    private ConnectStringCodec() {
    }

    /**
     * Builds the connect string advertising the given host.
     *
     * @param hostSteamId64 the host's own {@code SteamID64}
     * @return {@code "+lazuli_join <hostSteamId64>"}, {@code SteamID64} as an
     *         unsigned decimal
     */
    public static String encode(long hostSteamId64) {
        return PREFIX + Long.toUnsignedString(hostSteamId64);
    }

    /**
     * Extracts the host {@code SteamID64} from a friend's Rich Presence
     * {@code "connect"} value. Never throws: {@code null}, blank, wrong-prefix,
     * or non-numeric input all return {@link OptionalLong#empty()}.
     *
     * <p>Tolerates trailing content after the ID (space-delimited), so a future
     * additive suffix on the connect string does not break decoding.
     *
     * @param richPresenceConnectValue the raw Rich Presence {@code "connect"}
     *                                 value, or {@code null}
     * @return the decoded host {@code SteamID64}, or empty if the value does not
     *         match this feature's format
     */
    public static OptionalLong decode(String richPresenceConnectValue) {
        if (richPresenceConnectValue == null) {
            return OptionalLong.empty();
        }
        String value = richPresenceConnectValue.trim();
        if (!value.startsWith(PREFIX)) {
            return OptionalLong.empty();
        }
        String rest = value.substring(PREFIX.length()).trim();
        if (rest.isEmpty()) {
            return OptionalLong.empty();
        }
        int space = rest.indexOf(' ');
        String idPart = space >= 0 ? rest.substring(0, space) : rest;
        try {
            return OptionalLong.of(Long.parseUnsignedLong(idPart));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
