package de.lazuli.features.serverjoinpresence.services;

import de.lazuli.services.steamworks.SteamFriendsGateway;

/**
 * Drives the multiplayer-client-session-advertising Rich Presence
 * {@code "connect"} key symmetrically (spec FR1.1/FR1.2):
 * {@link #onJoinedRemoteServer(String, int)} sets it to
 * {@code ServerConnectStringCodec.encode(host, port)}, {@link #onLeftServer()}
 * clears it.
 *
 * <p>Mirrors {@code features.worldhosting.services.HostingLifecycle}'s own
 * {@code start()}/{@code stop()} shape (no advertise-toggle/join-policy
 * equivalent here -- spec has no such feature). Owns only this plain
 * set/clear state; deciding *whether* a given connection is a real remote
 * server (as opposed to the local integrated server) is the platform
 * composition root's job (spec FR1.1's carve-out, plan Decision 3) -- this
 * class itself never inspects {@code net.minecraft.*} state and is called
 * only once that decision has already been made.
 *
 * <p>Constructed only when Steam is available and this feature's config is
 * enabled; otherwise {@link NoopServerJoinRequester}/
 * {@link NoopFriendServerPresenceReader} are used instead (FR0.2/FR0.3) --
 * this class itself has no {@code Noop} twin since it is never exposed past
 * the platform composition root (unlike the {@code api}-typed contracts).
 */
public final class ServerSessionLifecycle {

    /** Valve-reserved Rich Presence key that drives the native "Join Game" button (FR1.1). */
    static final String CONNECT_KEY = "connect";

    private final SteamFriendsGateway gateway;

    /**
     * @param gateway the shared Steam-friends seam used to set/clear the local
     *                Rich Presence {@code "connect"} key
     */
    public ServerSessionLifecycle(SteamFriendsGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * Advertises the just-joined real multiplayer server via Rich Presence
     * (FR1.1).
     *
     * @param host the server's hostname/IP
     * @param port the server's port
     */
    public void onJoinedRemoteServer(String host, int port) {
        gateway.setLocalRichPresence(CONNECT_KEY, ServerConnectStringCodec.encode(host, port));
    }

    /**
     * Clears the advertised Rich Presence (FR1.2), so the native "Join Game"
     * button disappears for friends immediately. Idempotent.
     */
    public void onLeftServer() {
        gateway.clearLocalRichPresence();
    }
}
