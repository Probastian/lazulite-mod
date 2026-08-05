package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.CloudSyncable;
import de.lazuli.api.steamworks.SteamAvailability;
import de.lazuli.features.steamcloudsync.api.SteamCloudSyncConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CloudSyncCoordinatorTest {

    private static final class UnavailableSteam implements SteamAvailability {
        @Override
        public boolean isSteamAvailable() {
            return false;
        }

        @Override
        public long steamAppId() {
            return 0L;
        }
    }

    private static final class FakeCloudSyncable implements CloudSyncable {
        byte[] exported = "exported-state".getBytes();
        byte[] imported;
        long localLastModifiedMillis = -1L;

        @Override
        public String cloudSyncId() {
            return "fake-syncable";
        }

        @Override
        public byte[] exportState() {
            return exported;
        }

        @Override
        public void importState(byte[] data) {
            imported = data;
        }

        @Override
        public long localLastModifiedMillis() {
            return localLastModifiedMillis;
        }
    }

    @Test
    void everySyncOperationIsAStructuralNoOpWhenSteamUnavailable(@TempDir Path tempDir) {
        FakeCloudSyncable syncable = new FakeCloudSyncable();
        CloudSyncCoordinator coordinator = new CloudSyncCoordinator(
                new UnavailableSteam(), SteamCloudSyncConfig.DEFAULT, List.of(syncable),
                tempDir, tempDir.resolve("saves"), w -> { }, m -> { }, p -> { });

        assertThatCode(coordinator::reconcileAtStartup).doesNotThrowAnyException();
        assertThatCode(coordinator::syncOnShutdown).doesNotThrowAnyException();

        // Steam unavailable -> Noop store never delivers anything to import.
        assertThat(syncable.imported).isNull();
    }

    @Test
    void localCrudStillWorksWithSteamUnavailable(@TempDir Path tempDir) {
        CloudSyncCoordinator coordinator = new CloudSyncCoordinator(
                new UnavailableSteam(), SteamCloudSyncConfig.DEFAULT, List.of(),
                tempDir, tempDir.resolve("saves"), w -> { }, m -> { }, p -> { });
        coordinator.reconcileAtStartup();

        coordinator.bookmarkedServersService().add("My Server", "play.example.com:25565");
        assertThat(coordinator.bookmarkedServersService().list()).hasSize(1);

        coordinator.notesService().add("A reminder");
        assertThat(coordinator.notesService().list()).hasSize(1);

        coordinator.worldSyncPreferenceService().toggleSync("my_world");
        assertThat(coordinator.worldSyncPreferenceService().isSyncEnabled("my_world")).isTrue();

        assertThat(coordinator.cloudOnlyWorldsFacade().listCloudOnlyWorlds(List.of())).isEmpty();

        coordinator.syncOnShutdown();
    }

    @Test
    void noWarningLoggedForFreshInstallation(@TempDir Path tempDir) {
        List<String> warnings = new ArrayList<>();
        CloudSyncCoordinator coordinator = new CloudSyncCoordinator(
                new UnavailableSteam(), SteamCloudSyncConfig.DEFAULT, List.of(),
                tempDir, tempDir.resolve("saves"), warnings::add, m -> { }, p -> { });

        coordinator.reconcileAtStartup();
        coordinator.syncOnShutdown();

        assertThat(warnings).isEmpty();
    }

    @Test
    void onReturnToMainMenuRefreshesFingerprintCacheWithoutSideEffectsOnUnrelatedState(@TempDir Path tempDir) {
        CloudSyncCoordinator coordinator = new CloudSyncCoordinator(
                new UnavailableSteam(), SteamCloudSyncConfig.DEFAULT, List.of(),
                tempDir, tempDir.resolve("saves"), w -> { }, m -> { }, p -> { });
        coordinator.reconcileAtStartup();

        coordinator.bookmarkedServersService().add("My Server", "play.example.com:25565");
        coordinator.notesService().add("A reminder");
        coordinator.worldSyncPreferenceService().toggleSync("my_world");

        assertThatCode(coordinator::onReturnToMainMenu).doesNotThrowAnyException();

        assertThat(coordinator.bookmarkedServersService().list()).hasSize(1);
        assertThat(coordinator.notesService().list()).hasSize(1);
        assertThat(coordinator.worldSyncPreferenceService().isSyncEnabled("my_world")).isTrue();
        assertThat(coordinator.cloudOnlyWorldsFacade().listCloudOnlyWorlds(List.of())).isEmpty();
    }
}
