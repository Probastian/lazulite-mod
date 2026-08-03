package de.lazuli.features.richpresence.services;

import de.lazuli.services.steamworks.SteamFriendsGateway;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Owns the debounced Rich Presence write (FR-RP4/FR-RP5/FR-RP7): calls
 * {@link LocalPresenceTracker#currentStatus()}/{@link LocalPresenceTracker#currentTier()}
 * once per {@link #tick()}, and only on an actual change (keyed off
 * {@code currentStatus()}'s value, unchanged trigger condition) either
 * writes the new values (present) or clears (present-to-empty transition
 * only, never repeatedly while already empty).
 *
 * <p>Per Addendum FR-RPD1, a change now writes up to three keys together,
 * using the same debounce trigger: {@code "status"} (unchanged, FR-RP4),
 * {@code "steam_display"} (the token name for the current tier, Addendum
 * FR-RPD3), and, only for biome-bearing tiers, a single combined
 * {@code "location"} key (biome name plus dimension suffix, e.g.
 * {@code "Forest in the Nether"}) -- {@code location} is never written as an
 * empty string; it is omitted entirely when the tier carries no biome
 * argument.
 *
 * <p><strong>Lesson learned:</strong> this used to be two separate keys,
 * {@code "biome"} and {@code "dimensionSuffix"}, with token text referencing
 * both {@code %biome%} and {@code %dimensionSuffix%}. Steam's Rich Presence
 * {@code %variable%} interpolation only substitutes a variable if that exact
 * key was set via {@code SetRichPresence} in the same call/session -- for
 * Overworld tiers, {@code dimensionSuffix} was correctly omitted (per the
 * "never write empty string" rule), but the token text still referenced
 * {@code %dimensionSuffix%}, so Steam had no value for it and rendered the
 * literal, un-substituted {@code %dimensionSuffix%} text to real friends
 * (e.g. "Staying in Forest%dimensionSuffix%"). The fix is this single
 * {@code location} key: it is computed fully in code and is always present
 * whenever a tier's token references it, so there is no longer any tier
 * where the token can reference a variable that might be absent.
 *
 * <p><strong>Never</strong> calls {@code setLocalRichPresence} with any key
 * other than {@code "status"}, {@code "steam_display"}, or {@code "location"}
 * -- FR-RP5, this feature's one hard non-goal-violation guard against
 * clobbering {@code HostingLifecycle}'s {@code "connect"} key.
 */
public final class RichPresencePublisher {

    static final String STATUS_KEY = "status";
    static final String STEAM_DISPLAY_KEY = "steam_display";
    static final String LOCATION_KEY = "location";

    private final LocalPresenceTracker tracker;
    private final SteamFriendsGateway gateway;
    private final Consumer<String> changeLogger;
    private final RichPresenceTokenMap tokenMap = new RichPresenceTokenMap();

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
            boolean accepted = writeIfPresent(STATUS_KEY, current.get());
            if (!accepted) {
                return;
            }
            writeTierKeys(tracker.currentTier());
        } else {
            gateway.clearLocalRichPresence();
        }
        changeLogger.accept("Rich Presence changed: " + describe(previous) + " -> " + describe(current));
        lastWritten = current;
    }

    /** Writes {@code steam_display} plus the combined {@code location} interpolation key for the given tier snapshot (FR-RPD1). */
    private void writeTierKeys(Optional<LocalPresenceTierSnapshot> tierSnapshot) {
        if (tierSnapshot.isEmpty()) {
            return;
        }
        LocalPresenceTierSnapshot snapshot = tierSnapshot.get();
        tokenMap.tokenFor(snapshot.kind()).ifPresent(token -> writeIfPresent(STEAM_DISPLAY_KEY, token));
        writeIfPresent(LOCATION_KEY, location(snapshot));
    }

    /**
     * Combines the tier's localized biome name with the dimension suffix
     * into a single string (e.g. {@code "Forest in the Nether"}) -- always
     * present as one unit whenever the tier carries a biome argument, never
     * split into two keys that a Steam token could reference independently
     * (see class Javadoc, "Lesson learned"). Returns {@code ""} for
     * non-biome-bearing tiers, which {@link #writeIfPresent} then omits
     * entirely.
     */
    private static String location(LocalPresenceTierSnapshot snapshot) {
        if (snapshot.localizedBiome().isEmpty()) {
            return "";
        }
        return snapshot.localizedBiome() + dimensionSuffix(snapshot);
    }

    /** Addendum FR-RPD4: {@code ""} (Overworld), {@code " in the Nether"}, {@code " in the End"}. */
    private static String dimensionSuffix(LocalPresenceTierSnapshot snapshot) {
        if (snapshot.nether()) {
            return " in the Nether";
        }
        if (snapshot.end()) {
            return " in the End";
        }
        return "";
    }

    /**
     * Writes {@code key = value} unless {@code value} is null/empty (Addendum
     * "Empty/absent-value keys are never written as \"\"" -- omit entirely
     * instead). Logs a warning and continues (does not abort the rest of the
     * tick's writes) if Steam rejects the write.
     *
     * @return whether the write was accepted (or skipped because value was
     *         empty -- treated as "no problem," only an actual rejection
     *         returns {@code false})
     */
    private boolean writeIfPresent(String key, String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        boolean accepted = gateway.setLocalRichPresence(key, value);
        if (!accepted) {
            changeLogger.accept("Failed to set local Rich Presence key \"" + key
                    + "\" to \"" + value + "\": rejected by Steam (not running, app not "
                    + "initialized, or invalid key/value).");
        }
        return accepted;
    }

    private static String describe(Optional<String> value) {
        return value.orElse("(none)");
    }
}
