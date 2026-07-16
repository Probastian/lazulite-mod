package de.lazuli.features.steamcloudsync.services;

import de.lazuli.features.steamcloudsync.api.BookmarkedServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class BookmarkedServersServiceTest {

    /**
     * Hand-written fake {@link CloudFileStore} (per the implementation plan's
     * Test Strategy: no mocking framework needed for a surface this small).
     */
    private static final class FakeCloudFileStore implements CloudFileStore {
        final Map<String, byte[]> files = new HashMap<>();
        final Map<String, Long> timestamps = new HashMap<>();
        long clock = 1_000L;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Optional<byte[]> read(String fileName) {
            return Optional.ofNullable(files.get(fileName));
        }

        @Override
        public boolean write(String fileName, byte[] data) {
            files.put(fileName, data);
            timestamps.put(fileName, clock++);
            return true;
        }

        @Override
        public OptionalLong fileTimestamp(String fileName) {
            Long timestamp = timestamps.get(fileName);
            return timestamp == null ? OptionalLong.empty() : OptionalLong.of(timestamp);
        }
    }

    @Test
    void addPersistsLocallyAndToCloud(@TempDir Path tempDir) {
        FakeCloudFileStore store = new FakeCloudFileStore();
        Path localPath = tempDir.resolve("bookmarked-servers.json");
        List<String> warnings = new ArrayList<>();
        BookmarkedServersService service = new BookmarkedServersService(store, localPath, true, warnings::add);
        service.reconcileAtStartup();

        BookmarkedServer added = service.add("My Server", "play.example.com:25565");

        assertThat(service.list()).containsExactly(added);
        assertThat(store.files).containsKey("lazuli-bookmarked-servers.json");
        assertThat(warnings).isEmpty();
    }

    @Test
    void toggleBookmarkAddsThenRemoves(@TempDir Path tempDir) {
        FakeCloudFileStore store = new FakeCloudFileStore();
        BookmarkedServersService service =
                new BookmarkedServersService(store, tempDir.resolve("unused-in-this-test.json"), false, w -> { });

        assertThat(service.isBookmarked("play.example.com:25565")).isFalse();

        service.toggleBookmark("play.example.com:25565", "My Server");
        assertThat(service.isBookmarked("play.example.com:25565")).isTrue();

        service.toggleBookmark("play.example.com:25565", "My Server");
        assertThat(service.isBookmarked("play.example.com:25565")).isFalse();
    }

    @Test
    void whenCloudSyncDisabledNoCloudWriteHappens(@TempDir Path tempDir) {
        FakeCloudFileStore store = new FakeCloudFileStore();
        BookmarkedServersService service =
                new BookmarkedServersService(store, tempDir.resolve("bookmarked-servers.json"), false, w -> { });

        service.add("My Server", "play.example.com:25565");

        assertThat(store.files).isEmpty();
    }

    @Test
    void renameUpdatesLabelOnly(@TempDir Path tempDir) {
        BookmarkedServersService service =
                new BookmarkedServersService(new FakeCloudFileStore(), tempDir.resolve("unused.json"), false, w -> { });
        BookmarkedServer added = service.add("Old Name", "play.example.com:25565");

        boolean renamed = service.rename(added.id(), "New Name");

        assertThat(renamed).isTrue();
        assertThat(service.list()).singleElement().satisfies(entry -> {
            assertThat(entry.label()).isEqualTo("New Name");
            assertThat(entry.address()).isEqualTo("play.example.com:25565");
        });
    }

    @Test
    void removeReturnsFalseForUnknownId(@TempDir Path tempDir) {
        BookmarkedServersService service =
                new BookmarkedServersService(new FakeCloudFileStore(), tempDir.resolve("unused.json"), false, w -> { });

        assertThat(service.remove("unknown-id")).isFalse();
    }
}
