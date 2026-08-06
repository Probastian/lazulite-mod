package de.lazuli.api.cloudsync;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Single, non-duplicated, Minecraft-free utility for detecting and safely
 * deleting stale/junk local save folders (a folder that exists on disk but
 * contains no readable {@code level.dat} -- e.g. leftover from an
 * interrupted/cancelled world creation, or a stray {@code session.lock}-only
 * directory from an old bug).
 *
 * <p>{@link #isRealSaveFolder(Path)} and {@link #deleteRecursively(Path)} are
 * the exact criteria/mechanism {@code WorldRestoreService}'s reactive,
 * same-slug-collision heal has always used (moved here verbatim, no
 * behavioral change). {@link #healStaleFolders(Path, Predicate, Consumer)} is
 * the new, proactive batch scan: every {@code WorldsPanel.reload()}
 * (all three platform modules) calls it once, up front, to silently clear any
 * stale folder sitting anywhere in the saves directory -- not just the one
 * slug a restore happens to target.
 */
public final class StaleSaveFolderHealer {

    /**
     * Any candidate folder touched (its own timestamp, or any immediate
     * child's) within this many milliseconds of "now" is skipped, regardless
     * of what the caller's busy-predicate reports (SR2 guard 2). 45 seconds
     * is comfortably inside the recommended 30-60s range: generous enough to
     * cover a typical Steam Cloud read of a large world archive plus zip
     * extraction (the window between a restore starting and the folder
     * actually existing/being touched) without needing a live-timed
     * benchmark, while still healing a genuinely stale folder promptly (well
     * under a minute) the next time the Worlds tab opens after it went stale.
     */
    private static final long SAFETY_MARGIN_MILLIS = 45_000L;

    private StaleSaveFolderHealer() {
    }

    /**
     * A folder is only ever treated as "real" when it contains a readable
     * {@code level.dat} -- the same file vanilla's own {@code LevelStorage}
     * requires to load a save, so this matches the exact criterion that
     * causes such a folder to be silently skipped by {@code WorldsPanel}'s
     * local-world scan in the first place.
     */
    public static boolean isRealSaveFolder(Path candidate) {
        if (!Files.isDirectory(candidate)) {
            // A plain file (not a directory) at this path is never a real
            // save folder either; treat it the same as an empty leftover.
            return false;
        }
        return Files.isRegularFile(candidate.resolve("level.dat"));
    }

    /**
     * Best-effort recursive delete; never throws. A leftover staging/junk
     * file is harmless (never visible as a real world), so any per-entry
     * {@link IOException} is swallowed rather than aborting the cleanup.
     */
    public static void deleteRecursively(Path path) {
        if (Files.notExists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup; a leftover staging file is harmless (never visible as a real world).
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    /**
     * Scans the immediate children of {@code savesDirectory} (SR3: never
     * recurses into grandchildren for candidate discovery) and deletes every
     * child directory that (a) is not a real save folder per
     * {@link #isRealSaveFolder(Path)}, (b) is not reported busy by
     * {@code worldSlugIsBusy} (FR4), and (c) has no filesystem activity
     * within {@link #SAFETY_MARGIN_MILLIS} of "now" (SR2 guard 2).
     *
     * <p>Never throws (SR4): a missing/absent {@code savesDirectory}, or any
     * I/O error while listing it or a single candidate, is reported via
     * {@code warningLogger} (listing-level) or silently skips just that one
     * candidate (per-candidate), never the whole scan.
     *
     * @return the folder names actually deleted (never {@code null}, empty if
     *         none)
     */
    public static List<String> healStaleFolders(
            Path savesDirectory, Predicate<String> worldSlugIsBusy, Consumer<String> warningLogger) {
        List<String> healed = new ArrayList<>();
        if (Files.notExists(savesDirectory) || !Files.isDirectory(savesDirectory)) {
            return healed;
        }

        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDirectory)) {
            for (Path candidate : stream) {
                candidates.add(candidate);
            }
        } catch (IOException e) {
            warningLogger.accept("Failed to list saves directory \"" + savesDirectory + "\" for stale-folder healing: " + e);
            return healed;
        }

        long now = System.currentTimeMillis();
        for (Path candidate : candidates) {
            try {
                if (!Files.isDirectory(candidate)) {
                    continue;
                }
                if (isRealSaveFolder(candidate)) {
                    continue;
                }
                String slug = candidate.getFileName().toString();
                if (worldSlugIsBusy.test(slug)) {
                    continue;
                }
                if (now - mostRecentModificationMillis(candidate) < SAFETY_MARGIN_MILLIS) {
                    continue;
                }
                deleteRecursively(candidate);
                healed.add(slug);
            } catch (IOException | RuntimeException e) {
                warningLogger.accept("Skipping stale-folder healing candidate \"" + candidate + "\" after error: " + e);
            }
        }
        return healed;
    }

    /**
     * SR2.2's defensive one-level check: the folder's own last-modified
     * timestamp, and (if non-empty) the max last-modified timestamp across
     * its immediate children too -- sufficient to catch an *active* write,
     * which by definition touches a file very recently.
     */
    private static long mostRecentModificationMillis(Path candidate) throws IOException {
        long mostRecent = Files.getLastModifiedTime(candidate).toMillis();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(candidate)) {
            for (Path child : children) {
                long childMillis = Files.getLastModifiedTime(child).toMillis();
                if (childMillis > mostRecent) {
                    mostRecent = childMillis;
                }
            }
        }
        return mostRecent;
    }
}
