package de.lazuli.mixin;

import de.lazuli.api.waypoints.Waypoint;
import de.lazuli.waypoints.WaypointEngineHandoff;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Waypoints spec R2/Goal 2 (compass bar): draws a horizontal directional
 * strip directly above the hotbar, one distance-scaled colored dot per
 * current-dimension waypoint (R10-R16).
 *
 * <p><strong>Confirmed via {@code javap} against 26.2's own resolved
 * Minecraft jar, correcting the plan's own guessed method name</strong>
 * ({@code Hud.extractHotbar}, reasoned only from {@code
 * HudCustomCrosshairMixin}'s {@code extractCrosshair} precedent): the real
 * target is {@code Hud.extractHotbarAndDecorations(GuiGraphicsExtractor,
 * DeltaTracker)} -- see {@code .claude/context/minecraft.md}'s Known
 * Cross-Version API Differences table for the full finding. Unlike the
 * crosshair mixin, this injects at {@code TAIL} and never cancels -- it
 * draws <em>after</em> vanilla's own hotbar/decorations extraction runs,
 * since Goal 2 is "rendered above the hotbar," not "replaces the hotbar."
 */
@Mixin(Hud.class)
abstract class HudWaypointCompassBarMixin {

    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void lazuli$drawWaypointCompassBar(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        CompassBarPainter.paint(extractor);
    }

    @Unique
    static final class CompassBarPainter {
        private CompassBarPainter() {
        }

        // Plan section 4 (numeric/visual-design defaults, hardcoded --
        // Waypoints has no config screen of its own per spec Configuration).
        private static final int BAR_WIDTH = 182;
        private static final int BAR_HEIGHT = 10;
        private static final int BAR_BOTTOM_MARGIN = 40;
        private static final double FOV_HALF_DEGREES = 90.0;
        private static final int DOT_MIN_SIZE = 2;
        private static final int DOT_MAX_SIZE = 6;
        private static final double DOT_MIN_SIZE_DISTANCE = 128.0;
        private static final double DOT_MAX_SIZE_DISTANCE = 8.0;
        private static final double ELEVATION_CHEVRON_THRESHOLD = 10.0;
        private static final double NAME_LABEL_HALF_DEGREES = 5.0;

        private static final int COLOR_BAR_BG = 0x66000000;

        static void paint(GuiGraphicsExtractor extractor) {
            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            if (player == null || client.level == null) {
                return;
            }

            List<Waypoint> waypoints = WaypointEngineHandoff.require().waypointsForCurrentDimension();
            if (waypoints.isEmpty()) {
                // R17: zero-waypoint dimensions add no meaningful per-frame cost
                // beyond this early-return guard.
                return;
            }

            int guiWidth = extractor.guiWidth();
            int guiHeight = extractor.guiHeight();
            int barLeft = (guiWidth - BAR_WIDTH) / 2;
            int barBottom = guiHeight - BAR_BOTTOM_MARGIN;
            int barTop = barBottom - BAR_HEIGHT;
            int barCenterX = barLeft + BAR_WIDTH / 2;

            extractor.fill(barLeft, barTop, barLeft + BAR_WIDTH, barBottom, COLOR_BAR_BG);

            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            double yawRad = Math.toRadians(player.getYRot());
            double sinYaw = Math.sin(yawRad);
            double cosYaw = Math.cos(yawRad);

            Waypoint nearestCentered = null;
            double nearestCenteredAbsTheta = Double.MAX_VALUE;

            for (Waypoint waypoint : waypoints) {
                double dx = (waypoint.x() + 0.5) - px;
                double dz = (waypoint.z() + 0.5) - pz;
                double forward = -dx * sinYaw + dz * cosYaw;
                double right = -dx * cosYaw - dz * sinYaw;
                double thetaDeg = Math.toDegrees(Math.atan2(right, forward));
                if (Math.abs(thetaDeg) > FOV_HALF_DEGREES) {
                    // R11/R16: outside the represented angular window (including
                    // fully behind the player) -- not drawn, no edge-clamping.
                    continue;
                }

                int dotX = barCenterX + (int) Math.round((thetaDeg / FOV_HALF_DEGREES) * (BAR_WIDTH / 2.0));
                int dotY = barTop + BAR_HEIGHT / 2;

                double dy = waypoint.y() - py;
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                int dotSize = dotSizeFor(distance);

                drawDot(extractor, dotX, dotY, dotSize, waypoint.color());

                if (Math.abs(dy) >= ELEVATION_CHEVRON_THRESHOLD) {
                    drawElevationChevron(extractor, dotX, dotY, dotSize, dy > 0, waypoint.color());
                }

                if (Math.abs(thetaDeg) <= NAME_LABEL_HALF_DEGREES && Math.abs(thetaDeg) < nearestCenteredAbsTheta) {
                    nearestCenteredAbsTheta = Math.abs(thetaDeg);
                    nearestCentered = waypoint;
                }
            }

            if (nearestCentered != null) {
                drawNameLabel(extractor, client, barCenterX, barTop, nearestCentered);
            }
        }

        /** R12: linear interpolation between {@link #DOT_MAX_SIZE} (close) and {@link #DOT_MIN_SIZE} (far), clamped. */
        private static int dotSizeFor(double distance) {
            if (distance <= DOT_MAX_SIZE_DISTANCE) {
                return DOT_MAX_SIZE;
            }
            if (distance >= DOT_MIN_SIZE_DISTANCE) {
                return DOT_MIN_SIZE;
            }
            double t = (distance - DOT_MAX_SIZE_DISTANCE) / (DOT_MIN_SIZE_DISTANCE - DOT_MAX_SIZE_DISTANCE);
            return (int) Math.round(DOT_MAX_SIZE - t * (DOT_MAX_SIZE - DOT_MIN_SIZE));
        }

        private static void drawDot(GuiGraphicsExtractor extractor, int centerX, int centerY, int size, int color) {
            int half = Math.max(1, size / 2);
            extractor.fill(centerX - half, centerY - half, centerX + half, centerY + half, 0xFF000000 | (color & 0xFFFFFF));
        }

        /** R13: a small up/down chevron (drawn as a 3px triangle-shaped fill) above/below the dot. */
        private static void drawElevationChevron(GuiGraphicsExtractor extractor, int centerX, int dotY, int dotSize, boolean above, int color) {
            int argb = 0xFF000000 | (color & 0xFFFFFF);
            int chevronY = above ? dotY - dotSize / 2 - 4 : dotY + dotSize / 2 + 2;
            extractor.fill(centerX - 1, chevronY, centerX + 1, chevronY + 2, argb);
        }

        /** R14/R15: only the nearest near-center waypoint's name, drawn above the bar in its own color. */
        private static void drawNameLabel(GuiGraphicsExtractor extractor, Minecraft client, int barCenterX, int barTop, Waypoint waypoint) {
            int argb = 0xFF000000 | (waypoint.color() & 0xFFFFFF);
            extractor.centeredText(client.font, Component.literal(waypoint.name()), barCenterX, barTop - 10, argb);
        }
    }
}
