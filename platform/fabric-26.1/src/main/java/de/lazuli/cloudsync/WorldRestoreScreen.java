package de.lazuli.cloudsync;

import de.lazuli.api.cloudsync.CloudOnlyWorldSummary;
import de.lazuli.api.cloudsync.RestoreHandle;
import de.lazuli.api.cloudsync.RestoreProgress;
import de.lazuli.api.cloudsync.RestoreProgressListener;
import de.lazuli.api.cloudsync.WorldRestoreHook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin {@code Screen} subclass driving/displaying one FR6.10-FR6.12 world
 * restore attempt on Minecraft 26.2 (Mojang-mapped, unobfuscated).
 *
 * <p>Uses the honest "Restoring.../Extracting..." framing (FR6.11) -- never
 * "Downloading...", since the real Cloud transfer already happened silently
 * before Minecraft launched. The progress bar is a manually-drawn filled
 * rectangle sized to {@code processedBytes/totalBytes}, drawn via
 * {@link GuiGraphicsExtractor} primitives (26.x's renamed/refactored
 * rendering entry point, {@code extractRenderState}, replacing the older
 * {@code render(DrawContext,...)} model 1.21.11 still uses -- see
 * {@code .claude/context/minecraft.md}'s Known Cross-Version API Differences
 * table) rather than any per-version reusable progress-bar widget class.
 *
 * <p>{@link RestoreProgressListener} callbacks may arrive from a background
 * thread ({@code CloudSyncWorker}); this screen only ever reads the latest
 * snapshot from a {@code volatile}/{@link AtomicReference} field on the
 * render thread, never blocking it.
 *
 * <p>Cloud Sync Restoration Decision 1: pushed as a full screen from the
 * Worlds tab's cloud-only synthetic row, reused verbatim except for the
 * {@code onReturn} callback below (originally hardcoded to reopen a fresh
 * {@code SelectWorldScreen}), which now lets the caller (a
 * {@code features/main-menu} {@code WorldsPanel}) reopen/refresh
 * {@code MainMenuScreen}'s Worlds tab instead (FR-E.5).
 */
public final class WorldRestoreScreen extends Screen {

    private final CloudOnlyWorldSummary summary;
    private final WorldRestoreHook restoreHook;
    private final Runnable onReturn;
    private final AtomicReference<RestoreProgress> latestProgress = new AtomicReference<>();
    private final AtomicReference<String> failureReason = new AtomicReference<>();
    private volatile boolean completed;
    private RestoreHandle handle;

    /**
     * @param summary     the cloud-only world being restored
     * @param restoreHook drives the restore attempt
     * @param onReturn    invoked (on the render thread) when this screen is
     *                    done -- on successful completion or on Cancel -- to
     *                    navigate back to whatever screen opened this one
     */
    public WorldRestoreScreen(CloudOnlyWorldSummary summary, WorldRestoreHook restoreHook, Runnable onReturn) {
        super(Component.literal("Restoring " + summary.displayName()));
        this.summary = summary;
        this.restoreHook = restoreHook;
        this.onReturn = onReturn;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onCancel())
                .bounds(width / 2 - 50, height / 2 + 40, 100, 20)
                .build());

        handle = restoreHook.beginRestore(summary.worldSlug(), new RestoreProgressListener() {
            @Override
            public void onProgress(RestoreProgress progress) {
                latestProgress.set(progress);
            }

            @Override
            public void onComplete(String worldSlug) {
                completed = true;
            }

            @Override
            public void onFailed(String worldSlug, String reason) {
                failureReason.set(reason);
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, delta);

        if (completed) {
            onReturn.run();
            return;
        }
        String failure = failureReason.get();
        if (failure != null) {
            guiGraphics.centeredText(Minecraft.getInstance().font,
                    Component.literal("Restore failed: " + failure), width / 2, height / 2 - 20, 0xFF5555);
            return;
        }

        RestoreProgress progress = latestProgress.get();
        String statusText = progress == null
                ? "Restoring world from Steam Cloud..."
                : switch (progress.phase()) {
                    case READING_FROM_CLOUD -> "Restoring world from Steam Cloud...";
                    case EXTRACTING -> "Extracting world files...";
                };
        guiGraphics.centeredText(Minecraft.getInstance().font, Component.literal(statusText), width / 2, height / 2 - 30, 0xFFFFFF);

        int barWidth = 200;
        int barX = width / 2 - barWidth / 2;
        int barY = height / 2;
        guiGraphics.fill(barX, barY, barX + barWidth, barY + 10, 0xFF555555);
        if (progress != null && progress.totalBytes() > 0) {
            float fraction = Math.min(1f, (float) progress.processedBytes() / progress.totalBytes());
            guiGraphics.fill(barX, barY, barX + (int) (barWidth * fraction), barY + 10, 0xFF33AA33);
        }
    }

    private void onCancel() {
        if (handle != null) {
            restoreHook.cancelRestore(handle);
        }
        onReturn.run();
    }
}
