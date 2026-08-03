package de.lazuli.features.richpresence.services;

import de.lazuli.services.steamworks.SteamFriendsGateway;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RichPresencePublisherTest {

    /** A fake tracker returning a scripted sequence of values across successive currentStatus()/currentTier() calls. */
    private static final class ScriptedTracker implements LocalPresenceTracker {
        private final Optional<String>[] statusScript;
        private final Optional<LocalPresenceTierSnapshot>[] tierScript;
        private int statusIndex;
        private int tierIndex;

        @SafeVarargs
        ScriptedTracker(Optional<String>[] statusScript, Optional<LocalPresenceTierSnapshot>... tierScript) {
            this.statusScript = statusScript;
            this.tierScript = tierScript;
        }

        @Override
        public Optional<String> currentStatus() {
            Optional<String> value = statusScript[Math.min(statusIndex, statusScript.length - 1)];
            statusIndex++;
            return value;
        }

        @Override
        public Optional<LocalPresenceTierSnapshot> currentTier() {
            Optional<LocalPresenceTierSnapshot> value = tierScript[Math.min(tierIndex, tierScript.length - 1)];
            tierIndex++;
            return value;
        }
    }

    @SafeVarargs
    private static Optional<String>[] statuses(Optional<String>... values) {
        return values;
    }

    @SafeVarargs
    private static Optional<LocalPresenceTierSnapshot>[] tiers(Optional<LocalPresenceTierSnapshot>... values) {
        return values;
    }

    private static Optional<LocalPresenceTierSnapshot> tier(TierKind kind, String biome, boolean nether, boolean end) {
        return Optional.of(new LocalPresenceTierSnapshot(kind, biome, nether, end));
    }

    @Test
    void writesOnlyOnActualChange() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(
                        Optional.of("Exploring Plains"),
                        Optional.of("Exploring Plains"),
                        Optional.of("Exploring Plains"),
                        Optional.of("Building in Plains")),
                tier(TierKind.EXPLORING, "Plains", false, false),
                tier(TierKind.EXPLORING, "Plains", false, false),
                tier(TierKind.EXPLORING, "Plains", false, false),
                tier(TierKind.BUILDING, "Plains", false, false));
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(true);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, msg -> { });

        publisher.tick();
        publisher.tick();
        publisher.tick();
        publisher.tick();

        verify(gateway, times(1)).setLocalRichPresence(eq("status"), eq("Exploring Plains"));
        verify(gateway, times(1)).setLocalRichPresence(eq("status"), eq("Building in Plains"));
        verify(gateway, never()).clearLocalRichPresence();
    }

    @Test
    void logsExactlyOnceOnActualChange() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(
                        Optional.of("Exploring Plains"),
                        Optional.of("Exploring Plains"),
                        Optional.of("Exploring Plains"),
                        Optional.of("Building in Plains")),
                tier(TierKind.EXPLORING, "Plains", false, false),
                tier(TierKind.EXPLORING, "Plains", false, false),
                tier(TierKind.EXPLORING, "Plains", false, false),
                tier(TierKind.BUILDING, "Plains", false, false));
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(true);
        List<String> logged = new ArrayList<>();
        Consumer<String> changeLogger = logged::add;
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, changeLogger);

        publisher.tick();
        publisher.tick();
        publisher.tick();
        publisher.tick();

        assertEquals(2, logged.size());
        assertEquals("Rich Presence changed: (none) -> Exploring Plains", logged.get(0));
        assertEquals("Rich Presence changed: Exploring Plains -> Building in Plains", logged.get(1));
    }

    @Test
    void logsExactlyOnceOnClear() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(
                        Optional.of("Exploring Plains"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                tier(TierKind.EXPLORING, "Plains", false, false),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(true);
        List<String> logged = new ArrayList<>();
        Consumer<String> changeLogger = logged::add;
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, changeLogger);

        publisher.tick();
        publisher.tick();
        publisher.tick();
        publisher.tick();

        assertEquals(2, logged.size());
        assertEquals("Rich Presence changed: (none) -> Exploring Plains", logged.get(0));
        assertEquals("Rich Presence changed: Exploring Plains -> (none)", logged.get(1));
    }

    @Test
    void clearsOnlyOncePerPresentToEmptyTransition() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(
                        Optional.of("Exploring Plains"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                tier(TierKind.EXPLORING, "Plains", false, false),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(true);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, msg -> { });

        publisher.tick();
        publisher.tick();
        publisher.tick();
        publisher.tick();

        verify(gateway, times(1)).clearLocalRichPresence();
    }

    @Test
    void neverCallsSetLocalRichPresenceWithConnectKey() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(Optional.of("Staying in Plains")),
                tier(TierKind.STAYING, "Plains", false, false));
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(true);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, msg -> { });

        publisher.tick();

        verify(gateway, never()).setLocalRichPresence(eq("connect"), any());
    }

    @Test
    void logsWarningAndDoesNotThrowWhenSteamRejectsWrite() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(Optional.of("Exploring Plains")),
                tier(TierKind.EXPLORING, "Plains", false, false));
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(false);
        List<String> logged = new ArrayList<>();
        Consumer<String> changeLogger = logged::add;
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, changeLogger);

        publisher.tick();

        verify(gateway, times(1)).setLocalRichPresence(eq("status"), eq("Exploring Plains"));
        verify(gateway, never()).setLocalRichPresence(eq("steam_display"), any());
        assertEquals(1, logged.size());
        assertEquals(
                "Failed to set local Rich Presence key \"status\" to \"Exploring Plains\": rejected by "
                        + "Steam (not running, app not initialized, or invalid key/value).",
                logged.get(0));
    }

    @Test
    void doesNotClearOnFirstTickWhenAlreadyEmpty() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(Optional.empty(), Optional.empty()),
                Optional.empty(), Optional.empty());
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, msg -> { });

        publisher.tick();
        publisher.tick();

        verify(gateway, never()).clearLocalRichPresence();
    }

    @Test
    void writesSteamDisplayAndCombinedLocationInOverworld() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(Optional.of("Exploring Plains")),
                tier(TierKind.EXPLORING, "Plains", false, false));
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(true);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, msg -> { });

        publisher.tick();

        verify(gateway, times(1)).setLocalRichPresence(eq("steam_display"), eq("#Status_Exploring"));
        verify(gateway, times(1)).setLocalRichPresence(eq("location"), eq("Plains"));
    }

    @Test
    void writesCombinedLocationInTheNether() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(Optional.of("Exploring Plains in the Nether")),
                tier(TierKind.EXPLORING, "Plains", true, false));
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(true);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, msg -> { });

        publisher.tick();

        verify(gateway, times(1)).setLocalRichPresence(eq("steam_display"), eq("#Status_Exploring"));
        verify(gateway, times(1)).setLocalRichPresence(eq("location"), eq("Plains in the Nether"));
    }

    @Test
    void writesCombinedLocationInTheEndForRidingBoat() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(Optional.of("Sailing through Ocean in the End")),
                tier(TierKind.RIDING_BOAT, "Ocean", false, true));
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(true);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, msg -> { });

        publisher.tick();

        verify(gateway, times(1)).setLocalRichPresence(eq("steam_display"), eq("#Status_RidingBoat"));
        verify(gateway, times(1)).setLocalRichPresence(eq("location"), eq("Ocean in the End"));
    }

    @Test
    void nonBiomeBearingTierOmitsLocation() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(Optional.of("Paused")),
                tier(TierKind.PAUSED, "", false, false));
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(true);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, msg -> { });

        publisher.tick();

        verify(gateway, times(1)).setLocalRichPresence(eq("steam_display"), eq("#Status_Paused"));
        verify(gateway, never()).setLocalRichPresence(eq("location"), any());
    }

    @Test
    void mainMenuNeverWritesSteamDisplayOrLocation() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(Optional.empty()),
                Optional.empty());
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, msg -> { });

        publisher.tick();

        verify(gateway, never()).setLocalRichPresence(eq("steam_display"), any());
        verify(gateway, never()).setLocalRichPresence(eq("location"), any());
        verify(gateway, never()).clearLocalRichPresence();
    }

    @Test
    void logsWarningWhenStatusKeyAcceptedButSteamDisplayRejected() {
        ScriptedTracker tracker = new ScriptedTracker(
                statuses(Optional.of("Exploring Plains")),
                tier(TierKind.EXPLORING, "Plains", false, false));
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(eq("status"), anyString())).thenReturn(true);
        when(gateway.setLocalRichPresence(eq("steam_display"), anyString())).thenReturn(false);
        when(gateway.setLocalRichPresence(eq("location"), anyString())).thenReturn(true);
        List<String> logged = new ArrayList<>();
        Consumer<String> changeLogger = logged::add;
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, changeLogger);

        publisher.tick();

        // steam_display rejection must not prevent the still-independent location write from being attempted.
        verify(gateway, times(1)).setLocalRichPresence(eq("steam_display"), eq("#Status_Exploring"));
        verify(gateway, times(1)).setLocalRichPresence(eq("location"), eq("Plains"));
        boolean sawRejectionWarning = logged.stream()
                .anyMatch(line -> line.contains("steam_display") && line.contains("rejected by Steam"));
        assertEquals(true, sawRejectionWarning);
    }
}
