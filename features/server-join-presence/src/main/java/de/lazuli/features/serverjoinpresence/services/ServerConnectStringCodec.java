package de.lazuli.features.serverjoinpresence.services;

import java.util.Optional;

/**
 * Owns this feature's Steam Rich Presence {@code "connect"}-string format
 * (spec FR1.4), so the exact literal is never hand-rolled with string
 * slicing scattered across callers. Pure, plain-JVM-testable, zero
 * {@code net.minecraft.*}/steamworks4j import -- one of this feature's two
 * primary unit-test targets (alongside {@link ServerPresenceScanner}).
 *
 * <p>Format: {@code "+lazuli_connect <host>:<port>"} -- deliberately a
 * different literal prefix than
 * {@code features.worldhosting.services.ConnectStringCodec}'s own
 * {@code "+lazuli_join <steamId64>"}, so a single shared read path (the
 * platform's {@code SteamJoinRequestDispatcher}, and this feature's own
 * friend-presence scanner) can always tell which of the two formats -- if
 * either -- a given raw Rich Presence {@code "connect"} value is (plan
 * Decision 1/2).
 *
 * <p>Usage example:
 * <pre>{@code
 * String connect = ServerConnectStringCodec.encode("example.com", 25565);       // set Rich Presence "connect"
 * Optional<HostPort> target = ServerConnectStringCodec.decode(friendConnect);   // is this friend on a server?
 * }</pre>
 */
public final class ServerConnectStringCodec {

    /** The fixed connect-string prefix (includes its trailing space). */
    static final String PREFIX = "+lazuli_connect ";

    /** Vanilla Minecraft's own default server port, used when none is given. */
    public static final int DEFAULT_PORT = 25565;

    private ServerConnectStringCodec() {
    }

    /**
     * A decoded (host, port) pair.
     *
     * @param host the server hostname/IP
     * @param port the server port
     */
    public record HostPort(String host, int port) {

        /** @return this pair rendered as a normalized {@code "host:port"} string. */
        public String asHostPort() {
            return host + ":" + port;
        }
    }

    /**
     * Builds the connect string advertising the given server.
     *
     * @param host the server's hostname/IP
     * @param port the server's port
     * @return {@code "+lazuli_connect <host>:<port>"}
     */
    public static String encode(String host, int port) {
        return PREFIX + host + ":" + port;
    }

    /**
     * Extracts the target server address from a Rich Presence {@code "connect"}
     * value. Never throws: {@code null}, blank, wrong-prefix, or unparsable
     * input all return {@link Optional#empty()}.
     *
     * <p>Tolerates trailing content after the address (space-delimited), so a
     * future additive suffix on the connect string does not break decoding.
     * If no port is present after the host, {@link #DEFAULT_PORT} is assumed.
     *
     * @param richPresenceConnectValue the raw Rich Presence {@code "connect"}
     *                                 value, or {@code null}
     * @return the decoded host/port, or empty if the value does not match
     *         this feature's format
     */
    public static Optional<HostPort> decode(String richPresenceConnectValue) {
        if (richPresenceConnectValue == null) {
            return Optional.empty();
        }
        String value = richPresenceConnectValue.trim();
        if (!value.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = value.substring(PREFIX.length()).trim();
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        int space = rest.indexOf(' ');
        String addressPart = space >= 0 ? rest.substring(0, space) : rest;
        return parseHostPort(addressPart);
    }

    /**
     * Normalizes an arbitrary {@code "host"} or {@code "host:port"} string
     * (e.g. a saved server's raw address) into a canonical
     * {@code "host:port"} form, so callers comparing addresses from different
     * sources (a friend's decoded connect string vs. a locally-known server
     * entry) match regardless of whether a default port was explicit.
     *
     * @param hostPortString the address to normalize
     * @return the normalized {@code "host:port"} string, or the original
     *         (trimmed) input if it could not be parsed as a host/port
     */
    public static String normalize(String hostPortString) {
        if (hostPortString == null) {
            return "";
        }
        Optional<HostPort> parsed = parseHostPort(hostPortString.trim());
        return parsed.map(HostPort::asHostPort).orElse(hostPortString.trim());
    }

    private static Optional<HostPort> parseHostPort(String addressPart) {
        if (addressPart == null || addressPart.isEmpty()) {
            return Optional.empty();
        }
        int lastColon = addressPart.lastIndexOf(':');
        // IPv6-bracket form ("[::1]:25565") is intentionally out of scope;
        // this feature only needs to round-trip its own encode() output and
        // plain hostnames/IPv4 addresses, matching ServerAddress.parseString's
        // own commonly-used shape for the server lists this feature targets.
        if (lastColon <= 0 || lastColon == addressPart.length() - 1) {
            return addressPart.isEmpty() ? Optional.empty() : Optional.of(new HostPort(addressPart, DEFAULT_PORT));
        }
        String host = addressPart.substring(0, lastColon);
        String portPart = addressPart.substring(lastColon + 1);
        try {
            int port = Integer.parseInt(portPart);
            if (port < 1 || port > 65535) {
                return Optional.empty();
            }
            return Optional.of(new HostPort(host, port));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
