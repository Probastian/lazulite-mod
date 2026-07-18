package de.lazuli.mixin;

import de.lazuli.LazuliMod;
import de.lazuli.WorldSyncToggleHookHolder;
import de.lazuli.api.cloudsync.WorldSyncToggleHook;
import de.lazuli.cloudsync.WorldEntryReflection;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.world.level.storage.LevelSummary;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws Group 6's per-world sync-toggle icon (FR6.1) directly inside every
 * local world's own row on the Singleplayer world-select screen -- the
 * 1.21.11 (Yarn-mapped) counterpart of {@code WorldListEntrySyncIconMixin}, a
 * {@code ui-guidelines.md} Pattern 3 injection ("rendering/click-handling
 * inside an existing real list entry").
 *
 * <p><strong>Corrected after a real in-game crash</strong>: the first version
 * used {@code @Shadow} to declare {@code getX()}/{@code getY()}/
 * {@code getWidth()}/{@code getLevel()} stubs. All four are {@code public},
 * but declared on an <em>ancestor</em> class ({@code EntryListWidget.Entry}),
 * not on {@code WorldEntry} itself (the exact {@code @Mixin} target) --
 * {@code @Shadow} only resolves members declared directly on the exact
 * target class, so it failed with
 * {@code InvalidMixinException: @Shadow method getX()I ... was not located
 * in the target class ... $WorldEntry}. Fixed by reading them via
 * {@link WorldEntryReflection} instead ({@link Class#getMethod}, unlike
 * {@code getDeclaredMethod}, searches the full public inheritance chain).
 * {@code render}/{@code mouseClicked} themselves stay hooked via ordinary
 * {@code @Inject} -- that mechanism was never the problem.
 *
 * <p>Bridged to this feature's {@link WorldSyncToggleHook} via
 * {@link WorldSyncToggleHookHolder} -- identical reasoning to the 26.x/26.1
 * mixin: vanilla constructs these row objects itself, so there is no
 * constructor call site to inject a dependency through.
 */
@Mixin(WorldListWidget.WorldEntry.class)
public abstract class WorldEntrySyncIconMixin {

    private static final int ICON_SIZE = 8;
    private static final int ICON_MARGIN = 4;
    private static final int COLOR_ENABLED = 0xFF3399FF;
    private static final int COLOR_DISABLED = 0xFF808080;

    @Inject(method = "render", at = @At("TAIL"))
    private void lazuli$drawSyncIcon(DrawContext context, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
        WorldSyncToggleHook hook = WorldSyncToggleHookHolder.getOrNull();
        LevelSummary summary = WorldEntryReflection.getLevel(this);
        if (hook == null || summary == null) {
            LazuliMod.LOGGER.info("Sync icon NOT drawn for a world row (hook={}, summary={}).", hook != null, summary != null);
            return;
        }
        boolean enabled = hook.isSyncEnabled(summary.getName());
        int left = lazuli$iconLeft();
        int top = lazuli$iconTop();
        context.fill(left, top, left + ICON_SIZE, top + ICON_SIZE, enabled ? COLOR_ENABLED : COLOR_DISABLED);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void lazuli$handleSyncIconClick(Click click, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        WorldSyncToggleHook hook = WorldSyncToggleHookHolder.getOrNull();
        LevelSummary summary = WorldEntryReflection.getLevel(this);
        if (hook == null || summary == null) {
            return;
        }
        int left = lazuli$iconLeft();
        int top = lazuli$iconTop();
        if (click.x() >= left && click.x() < left + ICON_SIZE && click.y() >= top && click.y() < top + ICON_SIZE) {
            hook.toggleSync(summary.getName());
            LazuliMod.LOGGER.info("Sync icon clicked for world \"{}\" -- sync is now {}.",
                    summary.getName(), hook.isSyncEnabled(summary.getName()) ? "ENABLED" : "DISABLED");
            cir.setReturnValue(true);
        }
    }

    private int lazuli$iconLeft() {
        return WorldEntryReflection.getX(this) + WorldEntryReflection.getWidth(this) - ICON_MARGIN - ICON_SIZE;
    }

    private int lazuli$iconTop() {
        return WorldEntryReflection.getY(this) + ICON_MARGIN;
    }
}
