package de.lazuli.features.richpresence.services;

import de.lazuli.services.steamworks.SteamFriendsGateway;

import java.util.Optional;
import java.util.function.Consumer;

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
    private final Consumer<String> changeLogger;

    private Optional<String> lastWritten = Optional.empty();

    public RichPresencePublisher(LocalPresenceTracker tracker, SteamFriendsGateway gateway, Consumer<String> changeLogger) {
        this.tracker = tracker;
        this.gateway = gateway;
        this.changeLogger = changeLogger;
    }

    /** Called once per client tick; recomputes and writes only on change. */
    public void tick() {
        Optional<String> current = tracker.currentStatus();
        if (current.equals(lastWritten)) {
            return;
        }
        Optional<String> previous = lastWritten;
        if (current.isPresent()) {
            boolean accepted = gateway.setLocalRichPresence(STATUS_KEY, current.get());
            if (!accepted) {
                changeLogger.accept("Failed to set local Rich Presence key \"" + STATUS_KEY
                        + "\" to \"" + current.get() + "\": rejected by Steam (not running, app not "
                        + "initialized, or invalid key/value).");
                return;
            }
        } else {
            gateway.clearLocalRichPresence();
        }
        changeLogger.accept("Rich Presence changed: " + describe(previous) + " -> " + describe(current));
        lastWritten = current;
    }

    private static String describe(Optional<String> value) {
        return value.orElse("(none)");
    }
}
