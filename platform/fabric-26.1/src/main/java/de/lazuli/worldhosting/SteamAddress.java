package de.lazuli.worldhosting;

import java.net.SocketAddress;
import java.util.Objects;

/**
 * A {@link SocketAddress} backed by a raw Steam {@code SteamID64} handle. Acts
 * as the marker every mixin uses to recognize "this is a Steam P2P connection"
 * at a glance (the transport swap is invisible above the Netty {@code Channel}
 * layer). Ported from the {@code steamshare} prototype's own {@code SteamAddress}.
 */
public final class SteamAddress extends SocketAddress {

    /** Raw Steam native handle (SteamID64). */
    public final long steamId64;

    public SteamAddress(long steamId64) {
        this.steamId64 = steamId64;
    }

    @Override
    public String toString() {
        return "steam:" + Long.toUnsignedString(steamId64);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SteamAddress other && steamId64 == other.steamId64;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(steamId64);
    }
}
