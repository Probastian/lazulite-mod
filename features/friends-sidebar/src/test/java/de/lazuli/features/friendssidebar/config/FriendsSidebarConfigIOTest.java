package de.lazuli.features.friendssidebar.config;

import de.lazuli.features.friendssidebar.api.FriendsSidebarConfig;
import de.lazuli.features.friendssidebar.api.JoinPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FriendsSidebarConfigIOTest {

    private final FriendsSidebarConfigIO configIO = new FriendsSidebarConfigIO();

    @Test
    void roundTripsThroughSerializeAndParse() {
        FriendsSidebarConfig config = new FriendsSidebarConfig(false, 10, JoinPolicy.EVERYONE);
        String serialized = configIO.serialize(config);
        FriendsSidebarConfigIO.ParseResult result = configIO.parse(serialized);

        assertThat(result.warning()).isNull();
        assertThat(result.config()).isEqualTo(config);
    }

    @Test
    void missingJoinPolicyKeyDefaultsToFriendsPreservingOtherFields() {
        FriendsSidebarConfigIO.ParseResult result = configIO.parse("{\"enabled\": false, \"refreshIntervalSeconds\": 7}");

        assertThat(result.warning()).isNull();
        assertThat(result.config()).isEqualTo(new FriendsSidebarConfig(false, 7, JoinPolicy.FRIENDS));
    }

    @Test
    void invalidJoinPolicyValueFallsBackToWholeFileDefault() {
        FriendsSidebarConfigIO.ParseResult result =
                configIO.parse("{\"enabled\": false, \"refreshIntervalSeconds\": 7, \"joinPolicy\": \"MAYBE\"}");

        assertThat(result.warning()).isNotNull();
        assertThat(result.config()).isEqualTo(FriendsSidebarConfig.DEFAULT);
    }

    @Test
    void saveThenLoadRoundTripsEveryFieldIncludingNonDefaultJoinPolicy(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("friends-sidebar.json");
        FriendsSidebarConfig config = new FriendsSidebarConfig(false, 42, JoinPolicy.NOBODY);

        configIO.save(path, config);
        FriendsSidebarConfigIO.ParseResult result = configIO.load(path);

        assertThat(result.warning()).isNull();
        assertThat(result.config()).isEqualTo(config);
    }

    @Test
    void loadCreatesDefaultFileWhenMissing(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("friends-sidebar.json");

        FriendsSidebarConfigIO.ParseResult result = configIO.load(path);

        assertThat(result.warning()).isNull();
        assertThat(result.config()).isEqualTo(FriendsSidebarConfig.DEFAULT);
        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void malformedJsonFallsBackToDefaultWithWarning() {
        FriendsSidebarConfigIO.ParseResult result = configIO.parse("{ not json");

        assertThat(result.warning()).isNotNull();
        assertThat(result.config()).isEqualTo(FriendsSidebarConfig.DEFAULT);
    }

    @Test
    void missingRequiredKeyFallsBackToDefaultWithWarning() {
        FriendsSidebarConfigIO.ParseResult result = configIO.parse("{\"enabled\": true}");

        assertThat(result.warning()).isNotNull();
        assertThat(result.config()).isEqualTo(FriendsSidebarConfig.DEFAULT);
    }

    @Test
    void unknownKeyFallsBackToDefaultWithWarning() {
        FriendsSidebarConfigIO.ParseResult result =
                configIO.parse("{\"enabled\": true, \"refreshIntervalSeconds\": 5, \"extra\": 1}");

        assertThat(result.warning()).isNotNull();
        assertThat(result.config()).isEqualTo(FriendsSidebarConfig.DEFAULT);
    }

    @Test
    void nullContentFallsBackToDefaultWithWarning() {
        FriendsSidebarConfigIO.ParseResult result = configIO.parse(null);

        assertThat(result.warning()).isNotNull();
        assertThat(result.config()).isEqualTo(FriendsSidebarConfig.DEFAULT);
    }
}
