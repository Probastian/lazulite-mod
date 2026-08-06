package de.lazuli.api.cloudsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaleSaveFolderHealerTest {

    @Test
    void isRealSaveFolderRequiresDirectoryWithRegularLevelDat(@TempDir Path tempDir) throws IOException {
        Path realSave = tempDir.resolve("realSave");
        Files.createDirectories(realSave);
        Files.writeString(realSave.resolve("level.dat"), "not-really-nbt-but-a-regular-file");
        assertThat(StaleSaveFolderHealer.isRealSaveFolder(realSave)).isTrue();

        Path missingLevelDat = tempDir.resolve("missingLevelDat");
        Files.createDirectories(missingLevelDat);
        assertThat(StaleSaveFolderHealer.isRealSaveFolder(missingLevelDat)).isFalse();

        Path plainFile = tempDir.resolve("plainFile");
        Files.writeString(plainFile, "not a directory");
        assertThat(StaleSaveFolderHealer.isRealSaveFolder(plainFile)).isFalse();

        Path levelDatIsDirectory = tempDir.resolve("levelDatIsDirectory");
        Files.createDirectories(levelDatIsDirectory.resolve("level.dat"));
        assertThat(StaleSaveFolderHealer.isRealSaveFolder(levelDatIsDirectory)).isFalse();
    }

    @Test
    void deleteRecursivelyIsNoOpOnMissingPathAndRemovesPopulatedTree(@TempDir Path tempDir) throws IOException {
        Path missing = tempDir.resolve("doesNotExist");
        StaleSaveFolderHealer.deleteRecursively(missing); // must not throw

        Path root = tempDir.resolve("nested");
        Path child = root.resolve("child");
        Files.createDirectories(child);
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(child.resolve("b.txt"), "b");

        StaleSaveFolderHealer.deleteRecursively(root);

        assertThat(Files.exists(root)).isFalse();
    }

    @Test
    void healStaleFoldersIsNoOpOnMissingOrEmptySavesDirectory(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("noSuchSavesDir");
        List<String> healed = StaleSaveFolderHealer.healStaleFolders(missing, slug -> false, warning -> { });
        assertThat(healed).isEmpty();

        Path empty = tempDir.resolve("emptySaves");
        healed = StaleSaveFolderHealer.healStaleFolders(empty, slug -> false, warning -> { });
        assertThat(healed).isEmpty();
    }

    @Test
    void healStaleFoldersNeverDeletesRealSaveFolder(@TempDir Path tempDir) throws IOException {
        Path savesDir = tempDir.resolve("saves");
        Path realSave = savesDir.resolve("MyWorld");
        Files.createDirectories(realSave);
        Files.writeString(realSave.resolve("level.dat"), "nbt");
        makeOld(realSave);

        List<String> healed = StaleSaveFolderHealer.healStaleFolders(savesDir, slug -> false, warning -> { });

        assertThat(healed).isEmpty();
        assertThat(Files.exists(realSave)).isTrue();
    }

    @Test
    void healStaleFoldersDeletesOldStaleFolderWhenNotBusy(@TempDir Path tempDir) throws IOException {
        Path savesDir = tempDir.resolve("saves");
        Path stale = savesDir.resolve("StaleWorld");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("session.lock"), "lock");
        makeOld(stale);

        List<String> healed = StaleSaveFolderHealer.healStaleFolders(savesDir, slug -> false, warning -> { });

        assertThat(healed).containsExactly("StaleWorld");
        assertThat(Files.exists(stale)).isFalse();
    }

    @Test
    void healStaleFoldersNeverDeletesFolderReportedBusyRegardlessOfTimestamp(@TempDir Path tempDir) throws IOException {
        Path savesDir = tempDir.resolve("saves");
        Path stale = savesDir.resolve("BusyWorld");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("session.lock"), "lock");
        makeOld(stale);

        List<String> healed = StaleSaveFolderHealer.healStaleFolders(
                savesDir, slug -> slug.equals("BusyWorld"), warning -> { });

        assertThat(healed).isEmpty();
        assertThat(Files.exists(stale)).isTrue();
    }

    @Test
    void healStaleFoldersNeverDeletesFolderModifiedWithinSafetyMargin(@TempDir Path tempDir) throws IOException {
        Path savesDir = tempDir.resolve("saves");
        Path stale = savesDir.resolve("FreshJunk");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("session.lock"), "lock");
        // Leave the folder's own/children's timestamps at "now" (default from creation).

        List<String> healed = StaleSaveFolderHealer.healStaleFolders(savesDir, slug -> false, warning -> { });

        assertThat(healed).isEmpty();
        assertThat(Files.exists(stale)).isTrue();
    }

    @Test
    void healStaleFoldersNeverDeletesFolderWithRecentlyTouchedChildEvenIfFolderItselfIsOld(@TempDir Path tempDir)
            throws IOException {
        Path savesDir = tempDir.resolve("saves");
        Path stale = savesDir.resolve("ActivelyWritingJunk");
        Files.createDirectories(stale);
        Path childFile = stale.resolve("session.lock");
        Files.writeString(childFile, "lock");
        // Age the folder itself, but leave the child file's timestamp at "now".
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(600)));

        List<String> healed = StaleSaveFolderHealer.healStaleFolders(savesDir, slug -> false, warning -> { });

        assertThat(healed).isEmpty();
        assertThat(Files.exists(stale)).isTrue();
    }

    private static void makeOld(Path candidate) throws IOException {
        FileTime old = FileTime.from(Instant.now().minusSeconds(600));
        Files.setLastModifiedTime(candidate, old);
        try (var children = Files.newDirectoryStream(candidate)) {
            for (Path child : children) {
                Files.setLastModifiedTime(child, old);
            }
        }
    }
}
