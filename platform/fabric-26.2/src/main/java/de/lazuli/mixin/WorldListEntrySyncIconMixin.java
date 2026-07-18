package de.lazuli.mixin;

import de.lazuli.LazuliMod;
import de.lazuli.WorldSyncToggleHookHolder;
import de.lazuli.api.cloudsync.WorldSyncToggleHook;
import de.lazuli.cloudsync.WorldListEntryReflection;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.level.storage.LevelSummary;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws Group 6's per-world sync-toggle icon (FR6.1) directly inside every
 * local world's own row on the Singleplayer world-select screen -- a
 * {@code ui-guidelines.md} Pattern 3 injection ("rendering/click-handling
 * inside an existing real list entry"), replacing the v1 implementation's
 * single global footer button (found, after manual verification, to not
 * match FR6.1's own wording of "a new icon widget ... added to every ...
 * world's row").
 *
 * <p><strong>Corrected after a real in-game crash</strong> (found on the
 * 1.21.11 sibling of this mixin first, but structurally identical here): the
 * first version used {@code @Shadow} to declare {@code getX()}/{@code getY()}/
 * {@code getWidth()}/{@code getLevelSummary()} stubs. All four are
 * {@code public}, but declared on an <em>ancestor</em> class
 * ({@code AbstractSelectionList.Entry}), not on {@code WorldListEntry} itself
 * (the exact {@code @Mixin} target) -- {@code @Shadow} only resolves members
 * declared directly on the exact target class, so it fails with
 * {@code InvalidMixinException: @Shadow method getX()I ... was not located
 * in the target class ... $WorldListEntry}. Fixed by reading them via
 * {@link WorldListEntryReflection} instead ({@link Class#getMethod}, unlike
 * {@code getDeclaredMethod}, searches the full public inheritance chain).
 * {@code extractContent}/{@code mouseClicked} themselves stay hooked via
 * ordinary {@code @Inject} -- that mechanism was never the problem.
 *
 * <p>Bridged to this feature's {@link WorldSyncToggleHook} via
 * {@link WorldSyncToggleHookHolder} -- this mixin class is merged directly
 * into vanilla's own row objects, which are never constructed by our code, so
 * there is no constructor call site to inject a dependency through (the same
 * reason {@code de.lazuli.SteamworksServiceHandoff} exists for
 * {@code SteamworksService}).
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntrySyncIconMixin {

    private static final int ICON_SIZE = 8;
    private static final int ICON_MARGIN = 4;
    private static final int COLOR_ENABLED = 0xFF3399FF;
    private static final int COLOR_DISABLED = 0xFF808080;

    @Inject(method = "extractContent", at = @At("TAIL"))
    private void lazuli$drawSyncIcon(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
        WorldSyncToggleHook hook = WorldSyncToggleHookHolder.getOrNull();
        LevelSummary summary = WorldListEntryReflection.getLevelSummary(this);
        if (hook == null || summary == null) {
            LazuliMod.LOGGER.info("Sync icon NOT drawn for a world row (hook={}, summary={}).", hook != null, summary != null);
            return;
        }
        boolean enabled = hook.isSyncEnabled(summary.getLevelId());
        int left = lazuli$iconLeft();
        int top = lazuli$iconTop();
        graphics.fill(left, top, left + ICON_SIZE, top + ICON_SIZE, enabled ? COLOR_ENABLED : COLOR_DISABLED);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void lazuli$handleSyncIconClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        WorldSyncToggleHook hook = WorldSyncToggleHookHolder.getOrNull();
        LevelSummary summary = WorldListEntryReflection.getLevelSummary(this);
        if (hook == null || summary == null) {
            return;
        }
        int left = lazuli$iconLeft();
        int top = lazuli$iconTop();
        if (event.x() >= left && event.x() < left + ICON_SIZE && event.y() >= top && event.y() < top + ICON_SIZE) {
            hook.toggleSync(summary.getLevelId());
            LazuliMod.LOGGER.info("Sync icon clicked for world \"{}\" -- sync is now {}.",
                    summary.getLevelId(), hook.isSyncEnabled(summary.getLevelId()) ? "ENABLED" : "DISABLED");
            cir.setReturnValue(true);
        }
    }

    private int lazuli$iconLeft() {
        return WorldListEntryReflection.getX(this) + WorldListEntryReflection.getWidth(this) - ICON_MARGIN - ICON_SIZE;
    }

    private int lazuli$iconTop() {
        return WorldListEntryReflection.getY(this) + ICON_MARGIN;
    }
}
