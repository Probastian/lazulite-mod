package de.lazuli.features.richpresence.services;

import de.lazuli.services.steamworks.SteamFriendsGateway;

import java.util.Optional;

/**
 * Owns the debounced Rich Presence {@code "status"} write (FR-RP4/FR-RP5/
 * FR-RP7): calls {@link LocalPresenceTracker#currentStatus()} once per
 * {@link #tick()}, and only on an actual change either writes the new value
 * (present) or clears (present-to-empty transition only, never repeatedly
 * while already empty).
 *
 * <p><strong>Never</strong> calls {@code setLocalRichPresence} with any key
 * other than {@code "status"} -- FR-RP5, this feature's one hard
 * non-goal-violation guard against clobbering {@code HostingLifecycle}'s
 * {@code "connect"} key.
 */
public final class RichPresencePublisher {

    /** The only Rich Presence key this feature ever writes (FR-RP5). */
    static final String STATUS_KEY = "status";

    private final LocalPresenceTracker tracker;
    private final SteamFriendsGateway gateway;

    private Optional<String> lastWritten = Optional.empty();

    public RichPresencePublisher(LocalPresenceTracker tracker, SteamFriendsGateway gateway) {
        this.tracker = tracker;
        this.gateway = gateway;
    }

    /** Called once per client tick; recomputes and writes only on change. */
    public void tick() {
        Optional<String> current = tracker.currentStatus();
        if (current.equals(lastWritten)) {
            return;
        }
        if (current.isPresent()) {
            gateway.setLocalRichPresence(STATUS_KEY, current.get());
        } else {
            gateway.clearLocalRichPresence();
        }
        lastWritten = current;
    }
}
