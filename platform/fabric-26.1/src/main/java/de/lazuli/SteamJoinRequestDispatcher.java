package de.lazuli;

import de.lazuli.services.steamworks.SteamFriendsGateway;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Composition-root-only, order-independent dispatcher resolving the fact that
 * {@link SteamFriendsGateway#setJoinRequestedListener} accepts at most one
 * listener, while two features ({@code steam-world-hosting} and
 * {@code server-join-presence}) each need to react to Steam's native overlay
 * "Join Game" callback for their own, mutually-exclusive connect-string
 * format (Server Join Presence implementation plan, Decision 1).
 *
 * <p>Each feature's own composition-root initializer calls {@link #addRoute}
 * with a route that tries to decode the raw connect string in its own
 * format, returning {@code true} only if it recognized and fully handled it,
 * then calls {@link #ensureRegisteredWith} (idempotent -- whichever
 * initializer runs first actually registers the single real listener with
 * the gateway; {@code fabric.mod.json} entrypoint order is not load-bearing
 * for this mechanism).
 *
 * <p>Not a feature-owned class -- lives at the same composition-root layer as
 * {@code SteamworksServiceHandoff}/{@code SteamFriendsGatewayHandoff}, since
 * its entire purpose is bridging between two features' composition roots.
 */
public final class SteamJoinRequestDispatcher {

    /** A single feature's attempt to recognize and handle a raw connect string. */
    @FunctionalInterface
    public interface Route {

        /**
         * @param friendSteamId64 the inviting friend's {@code SteamID64}
         * @param connect         the raw Rich Presence {@code "connect"} value
         * @return {@code true} if this route recognized its own format and
         *         fully handled the join (no other route will be tried);
         *         {@code false} to let the next registered route try
         */
        boolean tryHandle(long friendSteamId64, String connect);
    }

    private static final List<Route> ROUTES = new CopyOnWriteArrayList<>();
    private static volatile boolean registered;

    private SteamJoinRequestDispatcher() {
    }

    /**
     * Registers a route. Order between features' calls to this method is not
     * significant beyond first-match-wins among whatever routes are
     * registered by the time a callback actually fires.
     *
     * @param route the route to add
     */
    public static void addRoute(Route route) {
        ROUTES.add(route);
    }

    /**
     * Idempotently ensures the single real {@code setJoinRequestedListener}
     * call has been made against {@code gateway}, dispatching to whichever
     * registered {@link Route} first reports {@code true}. Safe to call from
     * either feature's initializer regardless of {@code fabric.mod.json}
     * entrypoint order; only the first caller's invocation has any effect.
     *
     * @param gateway the shared Steam-friends seam
     */
    public static synchronized void ensureRegisteredWith(SteamFriendsGateway gateway) {
        if (registered) {
            return;
        }
        registered = true;
        gateway.setJoinRequestedListener((friendSteamId64, connect) -> {
            for (Route route : ROUTES) {
                if (route.tryHandle(friendSteamId64, connect)) {
                    return;
                }
            }
        });
    }
}
