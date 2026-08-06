package de.lazuli.cloudsync;

import de.lazuli.LazuliMod;
import de.lazuli.api.cloudsync.CloudOnlyWorldSummary;
import de.lazuli.api.cloudsync.DownloadProgressPresenter;
import de.lazuli.api.cloudsync.RestoreHandle;
import de.lazuli.api.cloudsync.RestoreProgress;
import de.lazuli.api.cloudsync.RestoreFailureMessages;
import de.lazuli.api.cloudsync.RestoreProgressListener;
import de.lazuli.api.cloudsync.WorldRestoreHook;
import de.lazuli.api.cloudsync.WorldSyncStatusHook;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin {@code Screen} subclass driving/displaying one cloud-world download
 * (cloud-world-download spec) on Minecraft 1.21.11 (Yarn-mapped, obfuscated).
 *
 * <p>Uses the "Downloading '&lt;world_name&gt;' from Steam Cloud..." framing
 * (FR1.1) with a percentage, human-readable current/total size, and a
 * once-per-second ETA (FR1.3-FR1.5), all computed by the shared, Minecraft-free
 * {@link DownloadProgressPresenter} (FR3-FR5) so the numbers are identical
 * across all three platforms (FR1.7). The progress bar is a manually-drawn
 * filled rectangle, drawn via {@link DrawContext} primitives (this version's
 * older, still-immediate-mode rendering model, as opposed to 26.x's
 * renamed/refactored {@code GuiGraphicsExtractor}/{@code extractRenderState}
 * model -- see {@code .claude/context/minecraft.md}'s Known Cross-Version API
 * Differences table) rather than any per-version reusable progress-bar widget
 * class.
 *
 * <p>{@link RestoreProgressListener} callbacks may arrive from a background
 * thread ({@code CloudSyncWorker}); this screen only ever reads the latest
 * snapshot from a {@link AtomicReference} field on the render thread, never
 * blocking it.
 *
 * <p>FR2: pressing Cancel does <em>not</em> call
 * {@link WorldRestoreHook#cancelRestore(RestoreHandle)} -- it only navigates
 * back to the Worlds tab via {@code onReturn}. The in-flight
 * {@link RestoreProgressListener} (owned by {@code WorldRestoreService}'s
 * internal restore context, not by this screen) keeps running to completion
 * in the background exactly like {@code WorldConflictScreen}'s "Keep Cloud"
 * flow already does, using the same {@link WorldSyncStatusHook}
 * markDownloadPending/markDownloadFinished bracketing (FR2.4).
 */
public final class WorldRestoreScreen extends Screen {

    private final CloudOnlyWorldSummary summary;
    private final WorldRestoreHook restoreHook;
    private final WorldSyncStatusHook statusHook;
    private final Runnable onCompleted;
    private final Runnable onReturn;
    private final AtomicReference<RestoreProgress> latestProgress = new AtomicReference<>();
    private final AtomicReference<String> failureReason = new AtomicReference<>();
    private volatile boolean completed;
    private RestoreHandle handle;
    private DownloadProgressPresenter presenter;

    /**
     * @param summary     the cloud-only world being downloaded
     * @param restoreHook drives the download/restore attempt
     * @param statusHook  nullable; used to bracket the download with
     *                    {@code markDownloadPending}/{@code markDownloadFinished}
     *                    (FR2.4) so the Worlds tab's existing blocked-row gate
     *                    picks this download up with no further changes
     * @param onCompleted nullable; invoked (on the render thread) instead of
     *                    {@code onReturn} at the natural-completion call site
     *                    only (never on Cancel) -- e.g. to launch the
     *                    just-restored world (Requirement 4's "Download &amp;
     *                    Play" pill). Falls back to {@code onReturn} when
     *                    {@code null}, preserving this screen's original
     *                    single-Runnable behavior.
     * @param onReturn    invoked (on the render thread) when this screen is
     *                    done -- on successful completion (when {@code
     *                    onCompleted} is {@code null}) or on Cancel -- to
     *                    navigate back to whatever screen opened this one
     */
    public WorldRestoreScreen(
            CloudOnlyWorldSummary summary,
            WorldRestoreHook restoreHook,
            WorldSyncStatusHook statusHook,
            Runnable onCompleted,
            Runnable onReturn) {
        super(Text.literal("Downloading '" + summary.displayName() + "' from Steam Cloud..."));
        this.summary = summary;
        this.restoreHook = restoreHook;
        this.statusHook = statusHook;
        this.onCompleted = onCompleted;
        this.onReturn = onReturn;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> onCancel())
                .dimensions(width / 2 - 50, height / 2 + 52, 100, 20)
                .build());

        String worldSlug = summary.worldSlug();
        if (statusHook != null) {
            statusHook.markDownloadPending(worldSlug);
        }

        handle = restoreHook.beginRestore(worldSlug, summary.displayName(), new RestoreProgressListener() {
            @Override
            public void onProgress(RestoreProgress progress) {
                latestProgress.set(progress);
            }

            @Override
            public void onComplete(String worldSlug) {
                if (statusHook != null) {
                    statusHook.markDownloadFinished(worldSlug);
                }
                completed = true;
            }

            @Override
            public void onFailed(String worldSlug, String reason) {
                if (statusHook != null) {
                    statusHook.markDownloadFinished(worldSlug);
                }
                failureReason.set(reason);
            }
        });
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        if (completed) {
            if (onCompleted != null) {
                onCompleted.run();
            } else {
                onReturn.run();
            }
            return;
        }
        String failure = failureReason.get();
        if (failure != null) {
            context.drawCenteredTextWithShadow(textRenderer,
                    "Restore failed: " + RestoreFailureMessages.toPlayerMessage(failure),
                    width / 2, height / 2 - 20, 0xFF5555);
            return;
        }

        RestoreProgress progress = latestProgress.get();
        if (presenter == null && progress != null) {
            presenter = new DownloadProgressPresenter(progress.totalBytes(), progress.totalBytes());
        }
        if (presenter != null && progress != null) {
            presenter.onProgress(progress);
        }

        int barWidth = 200;
        int barX = width / 2 - barWidth / 2;
        int barY = height / 2;
        context.fill(barX, barY, barX + barWidth, barY + 10, 0xFF555555);

        if (presenter != null) {
            DownloadProgressPresenter.DownloadDisplayStats stats = presenter.currentStats(System.currentTimeMillis());
            context.fill(barX, barY, barX + (int) (barWidth * stats.overallFraction()), barY + 10, 0xFF33AA33);

            context.drawCenteredTextWithShadow(textRenderer, stats.percentage() + "%", width / 2, barY + 14, 0xFFFFFF);
            context.drawCenteredTextWithShadow(textRenderer,
                    stats.currentSizeText() + " / " + stats.totalSizeText(), width / 2, barY + 26, 0xFFFFFF);
            context.drawCenteredTextWithShadow(textRenderer, stats.etaText(), width / 2, barY + 38, 0xFFFFFF);
        } else {
            context.drawCenteredTextWithShadow(textRenderer, "Calculating...", width / 2, barY + 14, 0xFFFFFF);
        }
    }

    private void onCancel() {
        LazuliMod.LOGGER.info("Player left the download screen for cloud-only world \""
                + summary.worldSlug() + "\"; download continues in the background.");
        onReturn.run();
    }
}
