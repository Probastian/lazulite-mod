package de.lazuli.cloudsync;

import de.lazuli.api.cloudsync.RestoreHandle;
import de.lazuli.api.cloudsync.RestoreProgress;
import de.lazuli.api.cloudsync.RestoreProgressListener;
import de.lazuli.api.cloudsync.WorldConflictResolutionHook;
import de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail;
import de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail.CloudDetail;
import de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail.LocalDetail;
import de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch;
import de.lazuli.api.cloudsync.WorldRestoreHook;
import de.lazuli.api.cloudsync.WorldSyncStatusHook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FR-V's true-two-sided-conflict resolution screen on Minecraft 26.1
 * (Mojang-mapped, unobfuscated) -- distinct from {@link WorldRestoreScreen}
 * (FR-V.5): that screen handles a cloud-only world with no local copy at
 * all; this screen handles the opposite precondition, a local copy that
 * diverged from a Cloud copy that <em>also</em> diverged from a different
 * device (F20e), never opened via {@code WorldsPanel.openRestoreFlow}.
 *
 * <p>"Keep Cloud" reuses {@link WorldRestoreHook#beginRestore} as an
 * implementation detail (FR-V.5) -- this screen never duplicates
 * {@code WorldRestoreService}'s extraction/atomicity logic, only drives it
 * the same way {@link WorldRestoreScreen} does, then reports the resolution
 * back to {@link WorldConflictResolutionHook#recordKeepCloudResolution} so
 * the local ancestor record advances and the conflict doesn't immediately
 * re-trigger.
 *
 * <p>cloud-sync-conflict-ux FR-3: redesigned into two symmetric,
 * field-aligned boxes ("Local save" / "Latest Steam Cloud save"), with
 * match/mismatch value coloring for the four paired fields (F11 rows 1-4)
 * and a "Local-only details" sub-section (F11 rows 5-14) shown only in the
 * Local box.
 */
public final class WorldConflictScreen extends Screen {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault());

    private static final int COLOR_MATCH = 0xFF908C7F;
    private static final int COLOR_MISMATCH = 0xFFFFD700;
    private static final int COLOR_DEFAULT_TEXT = 0xFFEAE8E1;
    private static final int COLOR_MUTED_TEXT = 0xFF908C7F;
    private static final int COLOR_BOX_BACKGROUND = 0xFF201E17;
    private static final int COLOR_BOX_BORDER = 0xFF2A2820;
    private static final String UNKNOWN_PLACEHOLDER = "Unknown";

    private static final int ROW_HEIGHT = 12;
    private static final int BOX_PADDING = 8;
    private static final int BOX_GAP = 10;
    private static final int TITLE_TO_ROWS_GAP = 16;
    private static final int SUB_HEADING_GAP = 14;

    private final String worldSlug;
    private final String worldFolderAbsolutePath;
    private final String displayName;
    private final WorldConflictResolutionHook resolutionHook;
    private final WorldRestoreHook restoreHook;
    private final WorldSyncStatusHook statusHook;
    private final String gameModeDisplayName;
    private final long lastPlayedMillis;
    private final boolean hardcore;
    private final LevelDatBatch levelDatBatch;
    private final Runnable onReturn;

    private ConflictDetail detail;
    private final AtomicReference<RestoreProgress> latestProgress = new AtomicReference<>();
    private final AtomicReference<String> failureReason = new AtomicReference<>();
    private volatile boolean keepCloudCompleted;
    private volatile boolean keepLocalStarted;
    private RestoreHandle restoreHandle;

    private int boxTop;
    private int boxBottom;

    /**
     * @param worldSlug               the world's on-disk save-folder name
     * @param worldFolderAbsolutePath the world's on-disk save folder's
     *                                absolute path, as a plain string
     * @param displayName             a player-facing name for the world
     * @param resolutionHook          drives the "Keep Local"/"Keep Cloud"
     *                                actions and the screen-content read
     * @param restoreHook             drives the "Keep Cloud" action's actual
     *                                restore, reusing the same machinery
     *                                {@code WorldRestoreScreen} already uses
     * @param statusHook              marks/clears the "Keep Cloud" restore's
     *                                download-in-progress flag
     *                                (cloud-sync-status-ui-simplify FR-3.2)
     *                                so {@code WorldsPanel}'s consolidated
     *                                status square can reflect it while this
     *                                screen's restore runs
     * @param gameModeDisplayName     the local world's game mode, already
     *                                resolved to a display string by the
     *                                caller
     * @param lastPlayedMillis        the local world's last-played time,
     *                                epoch millis
     * @param hardcore                the local world's hardcore flag
     * @param levelDatBatch           the local world's batched
     *                                {@code level.dat} NBT read, performed
     *                                once by the caller before this screen
     *                                opens
     * @param onReturn                invoked (on the render thread) when
     *                                this screen is done -- on either
     *                                resolution or on Cancel -- to navigate
     *                                back to whatever screen opened this one
     */
    public WorldConflictScreen(
            String worldSlug,
            String worldFolderAbsolutePath,
            String displayName,
            WorldConflictResolutionHook resolutionHook,
            WorldRestoreHook restoreHook,
            WorldSyncStatusHook statusHook,
            String gameModeDisplayName,
            long lastPlayedMillis,
            boolean hardcore,
            LevelDatBatch levelDatBatch,
            Runnable onReturn) {
        super(Component.literal("Sync Conflict: " + displayName));
        this.worldSlug = worldSlug;
        this.worldFolderAbsolutePath = worldFolderAbsolutePath;
        this.displayName = displayName;
        this.resolutionHook = resolutionHook;
        this.restoreHook = restoreHook;
        this.statusHook = statusHook;
        this.gameModeDisplayName = gameModeDisplayName;
        this.lastPlayedMillis = lastPlayedMillis;
        this.hardcore = hardcore;
        this.levelDatBatch = levelDatBatch;
        this.onReturn = onReturn;
    }

    @Override
    protected void init() {
        detail = resolutionHook.detailFor(worldSlug, worldFolderAbsolutePath, displayName,
                gameModeDisplayName, lastPlayedMillis, hardcore, levelDatBatch);

        // Decision 4: box top/bottom computed from actual content height
        // rather than a literal height/2-relative magic number.
        boxTop = 40;
        int unpairedRowCount = detail != null ? 10 : 0;
        int localRowCount = 4 + (unpairedRowCount > 0 ? 1 + unpairedRowCount : 0);
        boxBottom = boxTop + TITLE_TO_ROWS_GAP + localRowCount * ROW_HEIGHT + BOX_PADDING * 2;

        addRenderableWidget(Button.builder(Component.literal("Keep Local"), button -> onKeepLocal())
                .bounds(width / 2 - 110, boxBottom + 12, 100, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Keep Cloud"), button -> onKeepCloud())
                .bounds(width / 2 + 10, boxBottom + 12, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, delta);

        Font font = Minecraft.getInstance().font;

        if (keepCloudCompleted) {
            resolutionHook.recordKeepCloudResolution(worldSlug, detail.cloud().deviceLabel(), detail.cloud().syncedAtTimestamp());
            onReturn.run();
            return;
        }
        String failure = failureReason.get();
        if (failure != null) {
            guiGraphics.centeredText(font, Component.literal("Restore failed: " + failure), width / 2, 16, 0xFF5555);
        }
        if (keepLocalStarted) {
            guiGraphics.centeredText(font, Component.literal("Re-uploading local copy to Steam Cloud..."), width / 2, 16, 0xFFFFFF);
            return;
        }

        guiGraphics.centeredText(font,
                Component.literal("This world changed on both this device and another device."), width / 2, 16, 0xFFFFFF);

        if (detail == null) {
            guiGraphics.centeredText(font, Component.literal("Conflict detail is no longer available."), width / 2, boxTop, COLOR_MUTED_TEXT);
            return;
        }

        int boxWidth = (width - BOX_GAP - 40) / 2;
        int leftX = 20;
        int rightX = leftX + boxWidth + BOX_GAP;

        guiGraphics.fill(leftX, boxTop, leftX + boxWidth, boxBottom, COLOR_BOX_BACKGROUND);
        guiGraphics.fill(rightX, boxTop, rightX + boxWidth, boxBottom, COLOR_BOX_BACKGROUND);

        guiGraphics.text(font, Component.literal("Local save"), leftX + BOX_PADDING, boxTop + BOX_PADDING, COLOR_DEFAULT_TEXT);
        guiGraphics.text(font, Component.literal("Latest Steam Cloud save"), rightX + BOX_PADDING, boxTop + BOX_PADDING, COLOR_DEFAULT_TEXT);

        int rowY = boxTop + BOX_PADDING + TITLE_TO_ROWS_GAP;
        for (PairedRow row : pairedRows(detail.local(), detail.cloud())) {
            boolean match = valuesMatch(row.localValue(), row.cloudValue());
            int valueColor = match ? COLOR_MATCH : COLOR_MISMATCH;
            drawFieldRow(guiGraphics, font, leftX + BOX_PADDING, rowY, row.key(), row.localValue(), COLOR_DEFAULT_TEXT, valueColor);
            drawFieldRow(guiGraphics, font, rightX + BOX_PADDING, rowY, row.key(), row.cloudValue(), COLOR_DEFAULT_TEXT, valueColor);
            rowY += ROW_HEIGHT;
        }

        List<String[]> unpairedRows = unpairedRows(detail.local());
        if (!unpairedRows.isEmpty()) {
            guiGraphics.text(font, Component.literal("Local-only details"), leftX + BOX_PADDING, rowY, COLOR_MUTED_TEXT);
            rowY += SUB_HEADING_GAP;
            for (String[] row : unpairedRows) {
                drawFieldRow(guiGraphics, font, leftX + BOX_PADDING, rowY, row[0], row[1], COLOR_DEFAULT_TEXT, COLOR_DEFAULT_TEXT);
                rowY += ROW_HEIGHT;
            }
        }

        RestoreProgress progress = latestProgress.get();
        if (progress != null) {
            String statusText = switch (progress.phase()) {
                case READING_FROM_CLOUD -> "Restoring world from Steam Cloud...";
                case EXTRACTING -> "Extracting world files...";
            };
            guiGraphics.centeredText(font, Component.literal(statusText), width / 2, boxBottom + 40, 0xFFFFFF);
        }
    }

    private void drawFieldRow(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, String key, String value, int keyColor, int valueColor) {
        guiGraphics.text(font, Component.literal(key + ": "), x, y, keyColor);
        int valueX = x + font.width(key + ": ");
        guiGraphics.text(font, Component.literal(value), valueX, y, valueColor);
    }

    private record PairedRow(String key, String localValue, String cloudValue) {
    }

    private List<PairedRow> pairedRows(LocalDetail local, CloudDetail cloud) {
        List<PairedRow> rows = new ArrayList<>();
        rows.add(new PairedRow("World", local.displayName(), cloud.displayName()));
        rows.add(new PairedRow("Last changed / synced", formatInstant(local.lastModifiedMillis()), formatInstant(cloud.syncedAtTimestamp())));
        // cloud-world-metadata-file Requirement 6: compares content-identity
        // signatures, not raw folder/archive byte sizes -- the previous "Size"
        // row's comparison flagged a false mismatch far more often than the
        // content actually differed, since java.util.zip.Deflater's compressed
        // output size is not deterministic across even byte-identical inputs.
        rows.add(new PairedRow("Content match", contentSignatureLabel(local.contentSignature()), contentSignatureLabel(cloud.contentSignature())));
        rows.add(new PairedRow("Device", local.deviceLabel(), cloud.deviceLabel()));
        return rows;
    }

    /**
     * Shortens a SHA-256 hex digest to a short, human-scannable prefix for
     * display -- {@link #valuesMatch(String, String)} still compares the raw,
     * un-shortened {@link CloudDetail#contentSignature()}/
     * {@link LocalDetail#contentSignature()} values (this label is cosmetic
     * only, computed after the match/mismatch decision is already made by the
     * caller).
     */
    private static String contentSignatureLabel(String contentSignature) {
        if (contentSignature == null) {
            return UNKNOWN_PLACEHOLDER;
        }
        return contentSignature.length() > 12 ? contentSignature.substring(0, 12) + "..." : contentSignature;
    }

    private List<String[]> unpairedRows(LocalDetail local) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { "Local folder size", formatMb(local.folderSizeBytes()) + " MB" });
        rows.add(new String[] { "Game mode", nullToUnknown(local.gameMode()) });
        rows.add(new String[] { "Last played", formatInstant(local.lastPlayedMillis()) });
        rows.add(new String[] { "Hardcore", formatYesNo(local.hardcore()) });
        if (local.levelDatReadable()) {
            rows.add(new String[] { "Cheats enabled", formatYesNo(local.cheatsEnabled()) });
            rows.add(new String[] { "Difficulty", nullToUnknown(local.difficulty()) });
            rows.add(new String[] { "Seed", local.seed() != null ? String.valueOf(local.seed()) : UNKNOWN_PLACEHOLDER });
            rows.add(new String[] { "Minecraft version", nullToUnknown(local.minecraftVersion()) });
            rows.add(new String[] { "Day count", formatDayCount(local.dayCount()) });
        } else {
            rows.add(new String[] { "Cheats enabled", UNKNOWN_PLACEHOLDER });
            rows.add(new String[] { "Difficulty", UNKNOWN_PLACEHOLDER });
            rows.add(new String[] { "Seed", UNKNOWN_PLACEHOLDER });
            rows.add(new String[] { "Minecraft version", UNKNOWN_PLACEHOLDER });
            rows.add(new String[] { "Day count", UNKNOWN_PLACEHOLDER });
        }
        rows.add(new String[] { "Region files", local.regionFileCount() >= 0 ? String.valueOf(local.regionFileCount()) : UNKNOWN_PLACEHOLDER });
        rows.add(new String[] { "You last synced this world",
                local.ancestorSyncedAtTimestamp() != null ? formatInstant(local.ancestorSyncedAtTimestamp()) : UNKNOWN_PLACEHOLDER });
        return rows;
    }

    private void onKeepLocal() {
        keepLocalStarted = true;
        resolutionHook.resolveKeepLocal(worldSlug, worldFolderAbsolutePath, displayName);
        onReturn.run();
    }

    private void onKeepCloud() {
        // cloud-sync-status-ui-simplify FR-3.2: marked before beginRestore,
        // cleared in both onComplete and onFailed so a failed restore
        // doesn't leave the flag stuck true.
        if (statusHook != null) {
            statusHook.markDownloadPending(worldSlug);
        }
        restoreHandle = restoreHook.beginRestore(worldSlug, new RestoreProgressListener() {
            @Override
            public void onProgress(RestoreProgress progress) {
                latestProgress.set(progress);
            }

            @Override
            public void onComplete(String slug) {
                if (statusHook != null) {
                    statusHook.markDownloadFinished(worldSlug);
                }
                keepCloudCompleted = true;
            }

            @Override
            public void onFailed(String slug, String reason) {
                if (statusHook != null) {
                    statusHook.markDownloadFinished(worldSlug);
                }
                failureReason.set(reason);
            }
        });
    }

    private static String formatInstant(long epochMillis) {
        if (epochMillis < 0) {
            return UNKNOWN_PLACEHOLDER;
        }
        return TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String formatMb(long bytes) {
        if (bytes < 0) {
            return UNKNOWN_PLACEHOLDER;
        }
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private static String formatDayCount(long dayCount) {
        return dayCount >= 0 ? String.valueOf(dayCount) : UNKNOWN_PLACEHOLDER;
    }

    private static String formatYesNo(Boolean value) {
        if (value == null) {
            return UNKNOWN_PLACEHOLDER;
        }
        return value ? "Yes" : "No";
    }

    private static String nullToUnknown(String value) {
        return value != null ? value : UNKNOWN_PLACEHOLDER;
    }

    /**
     * FR-3.4's core value-comparison rule: pure string equality after
     * formatting -- two size values differing only in bytes but rounding to
     * the same displayed string are correctly treated as matching. Package-
     * private static so a unit test can call it directly (Test Strategy
     * item 2).
     */
    static boolean valuesMatch(String localValue, String cloudValue) {
        return localValue.equals(cloudValue);
    }
}
