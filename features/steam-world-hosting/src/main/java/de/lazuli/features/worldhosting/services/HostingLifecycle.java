package de.lazuli.features.worldhosting.services;

import de.lazuli.api.worldhosting.HostedWorldStatus;
import de.lazuli.services.steamworks.SteamFriendsGateway;

/**
 * Holds this feature's plain on/off hosting state (FR1.1/FR1.2) and drives the
 * host-advertising Rich Presence {@code "connect"} key symmetrically
 * (FR2.1/FR2.2): {@link #start()} sets it to
 * {@code ConnectStringCodec.encode(localSteamId64)}, {@link #stop()} clears it.
 *
 * <p>Owns only the plain state (whether hosting, and the local
 * {@code SteamID64}) plus the Rich Presence string -- <strong>not</strong> the
 * peer-connected bookkeeping, which lives in the platform's Netty layer
 * (cannot live below {@code platform/}). {@code start()}/{@code stop()} are
 * called by the platform's {@code IntegratedServer}-lifecycle hook.
 *
 * <p>Constructed only when Steam is available and this feature's config is
 * enabled; otherwise {@link NoopHostingLifecycle} is used (FR0.2/FR0.3).
 */
public final class HostingLifecycle {

    /** Valve-reserved Rich Presence key that drives the native "Join Game" button (FR2.1). */
    static final String CONNECT_KEY = "connect";

    private final SteamFriendsGateway gateway;

    private volatile boolean hosting;
    private volatile long localSteamId64;

    /**
     * @param gateway the shared Steam-friends seam used to set/clear the local
     *                Rich Presence {@code "connect"} key
     */
    public HostingLifecycle(SteamFriendsGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * Marks hosting active and advertises the local host via Rich Presence
     * (FR1.2/FR2.1). Idempotent: a redundant call re-asserts the same state.
     */
    public void start() {
        long id = gateway.localSteamId64();
        this.localSteamId64 = id;
        this.hosting = true;
        gateway.setLocalRichPresence(CONNECT_KEY, ConnectStringCodec.encode(id));
    }

    /**
     * Marks hosting inactive and clears the advertised Rich Presence
     * (FR1.2/FR2.2), so the native "Join Game" button disappears for friends
     * immediately. Idempotent.
     */
    public void stop() {
        this.hosting = false;
        this.localSteamId64 = 0L;
        gateway.clearLocalRichPresence();
    }

    /**
     * @return a plain snapshot of the current hosting state (spec Public API
     *         item 1)
     */
    public HostedWorldStatus currentStatus() {
        return new HostedWorldStatus(hosting, localSteamId64);
    }
}
