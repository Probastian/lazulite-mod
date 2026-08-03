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

    /** A fake tracker returning a scripted sequence of values across successive currentStatus() calls. */
    private static final class ScriptedTracker implements LocalPresenceTracker {
        private final Optional<String>[] script;
        private int index;

        @SafeVarargs
        ScriptedTracker(Optional<String>... script) {
            this.script = script;
        }

        @Override
        public Optional<String> currentStatus() {
            Optional<String> value = script[Math.min(index, script.length - 1)];
            index++;
            return value;
        }
    }

    @Test
    void writesOnlyOnActualChange() {
        ScriptedTracker tracker = new ScriptedTracker(
                Optional.of("Exploring Plains"),
                Optional.of("Exploring Plains"),
                Optional.of("Exploring Plains"),
                Optional.of("Building in Plains"));
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
                Optional.of("Exploring Plains"),
                Optional.of("Exploring Plains"),
                Optional.of("Exploring Plains"),
                Optional.of("Building in Plains"));
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
                Optional.of("Exploring Plains"),
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
                Optional.of("Exploring Plains"),
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
        ScriptedTracker tracker = new ScriptedTracker(Optional.of("Staying in Plains"));
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(true);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, msg -> { });

        publisher.tick();

        verify(gateway, never()).setLocalRichPresence(eq("connect"), any());
    }

    @Test
    void logsWarningAndDoesNotThrowWhenSteamRejectsWrite() {
        ScriptedTracker tracker = new ScriptedTracker(Optional.of("Exploring Plains"));
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        when(gateway.setLocalRichPresence(anyString(), anyString())).thenReturn(false);
        List<String> logged = new ArrayList<>();
        Consumer<String> changeLogger = logged::add;
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, changeLogger);

        publisher.tick();

        verify(gateway, times(1)).setLocalRichPresence(eq("status"), eq("Exploring Plains"));
        assertEquals(1, logged.size());
        assertEquals(
                "Failed to set local Rich Presence key \"status\" to \"Exploring Plains\": rejected by "
                        + "Steam (not running, app not initialized, or invalid key/value).",
                logged.get(0));
    }

    @Test
    void doesNotClearOnFirstTickWhenAlreadyEmpty() {
        ScriptedTracker tracker = new ScriptedTracker(Optional.empty(), Optional.empty());
        SteamFriendsGateway gateway = Mockito.mock(SteamFriendsGateway.class);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, msg -> { });

        publisher.tick();
        publisher.tick();

        verify(gateway, never()).clearLocalRichPresence();
    }
}
