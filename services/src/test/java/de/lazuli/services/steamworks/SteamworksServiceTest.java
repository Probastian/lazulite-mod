package de.lazuli.services.steamworks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SteamworksServiceTest {

    // --- Deterministic cases, via the package-private precomputed-state constructor ---

    @Test
    void unavailableInstanceReportsUnavailableAndAppId() {
        SteamworksService service = new SteamworksService(false, 5052800L);

        assertThat(service.isSteamAvailable()).isFalse();
        assertThat(service.steamAppId()).isEqualTo(5052800L);
    }

    @Test
    void unavailableInstancePumpCallbacksIsNoopAndNeverThrows() {
        SteamworksService service = new SteamworksService(false, 5052800L);

        assertThatCode(service::pumpCallbacks).doesNotThrowAnyException();
    }

    @Test
    void unavailableInstanceShutdownIsNoopAndIdempotent() {
        SteamworksService service = new SteamworksService(false, 5052800L);

        assertThatCode(() -> {
            service.shutdown();
            service.shutdown();
        }).doesNotThrowAnyException();
    }

    @Test
    void availableInstanceReportsAvailableAndAppId() {
        SteamworksService service = new SteamworksService(true, 5052800L);

        assertThat(service.isSteamAvailable()).isTrue();
        assertThat(service.steamAppId()).isEqualTo(5052800L);
    }

    // Note: an "available == true but constructed via the precomputed-state
    // constructor without a real loadLibraries()/initEx() call" case for
    // shutdown()/pumpCallbacks() is deliberately NOT exercised here. Both
    // SteamAPI.shutdown() and SteamAPI.runCallbacks() are steamworks4j
    // native methods with no Java-level fake/no-op seam (confirmed by
    // inspecting the real 1.10.0 jar's bytecode: shutdown() unconditionally
    // calls a native method regardless of prior init state) -- invoking them
    // without the native library actually loaded throws UnsatisfiedLinkError,
    // not a Java exception this service could catch. In real usage this
    // combination cannot occur: SteamworksService only ever reports
    // available == true after create(...) has already loaded the natives
    // successfully. Real shutdown()/pumpCallbacks() behavior against actually
    // loaded natives is covered by the integration-style create(...) case
    // below plus manual in-game verification (FR2/FR3).

    // --- One explicitly-environment-dependent integration-style case ---

    @Test
    void createNeverThrowsRegardlessOfWhetherSteamIsRunning(
            @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path nativeLibraryDirectory) {
        // Deliberately run with the real steamworks4j jar and no
        // steam_appid.txt in the test working directory. The hard invariant
        // this test guards is "create(...) never throws" (NFR2/FR1) -- NOT a
        // hard-coded isSteamAvailable() value. A developer running the full
        // suite locally with the real Steam client open and App ID 5052800
        // resolvable may observe isSteamAvailable() == true instead of
        // false; that is expected and not a test failure (see
        // implementation-plan.md, Risk 2).
        //
        // Cleanup is deliberately disabled (CleanupMode.NEVER): once
        // create(...) System.load()s a native library from this directory,
        // the OS keeps it locked for the JVM's lifetime (observed on
        // Windows), so JUnit's automatic @TempDir deletion would fail after
        // the test with an IOException unrelated to this test's actual
        // assertion.
        List<String> warnings = new ArrayList<>();

        assertThatCode(() -> SteamworksService.create(5052800L, nativeLibraryDirectory, warnings::add))
                .doesNotThrowAnyException();
    }
}
