package de.lazuli.mixin;

import de.lazuli.api.waypoints.Waypoint;
import de.lazuli.waypoints.WaypointEngineHandoff;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Waypoints spec R2/Goal 2 (compass bar): draws a horizontal directional
 * strip above the hotbar, one distance-scaled colored dot per
 * current-dimension waypoint (R10-R16), over a bearing-ruler background
 * (live-refinement pass fix #3).
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
 *
 * <p><strong>Live-refinement pass (post-840d49a in-game test) additions,
 * all confirmed via {@code javap -c} against 26.2's own resolved jar:</strong>
 * <ul>
 *   <li>Fix #1: repositioned above vanilla's armor/air-bubble row, whose
 *   real Y-extent ({@code guiHeight() - 49} to {@code guiHeight() - 40}) was
 *   confirmed by decompiling {@code Hud.extractPlayerHealth}/{@code
 *   extractArmor}/{@code extractAirBubbles}'s bytecode (not previously
 *   verified -- plan §4 Risk #2 flagged this exact placement as an
 *   unconfirmed cosmetic risk).</li>
 *   <li>Fix #2: shadows {@code Hud.toolHighlightTimer} (vanilla's own
 *   fade-timer field for {@code extractSelectedItemName}'s held-item-name
 *   text, confirmed via {@code javap -p}) and skips this frame's draw
 *   entirely while it's non-zero, since fix #1's new position overlaps that
 *   text's own row ({@code guiHeight() - 59} to {@code guiHeight() - 50}).</li>
 *   <li>Fix #5: {@link CompassBarPainter#elevationDirection} ports vanilla's
 *   own Locator Bar elevation-arrow decision (real class {@code
 *   net.minecraft.client.gui.contextualbar.LocatorBar}, real logic in
 *   {@code TrackedWaypoint.Vec3iWaypoint#pitchDirectionToCamera}) instead of
 *   the flat Y-delta threshold plan §4/R13 had independently chosen --
 *   see {@code .claude/context/minecraft.md}'s Known Cross-Version API
 *   Differences table for the full finding.</li>
 * </ul>
 */
@Mixin(Hud.class)
abstract class HudWaypointCompassBarMixin {

    @Shadow
    private int toolHighlightTimer;

    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void lazuli$drawWaypointCompassBar(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (toolHighlightTimer > 0) {
            // Fix #2: vanilla's own selected-item-name fade text
            // (Hud.extractSelectedItemName, gated by this same field) now
            // overlaps the compass bar's repositioned (fix #1) row -- skip
            // the compass bar entirely for any frame that text is still
            // active/fading rather than drawing both on top of each other.
            return;
        }
        CompassBarPainter.paint(extractor);
    }

    @Unique
    static final class CompassBarPainter {
        private CompassBarPainter() {
        }

        // Plan section 4 (numeric/visual-design defaults, hardcoded --
        // Waypoints has no config screen of its own per spec Configuration).
        private static final int BAR_WIDTH = 182;
        // Live-refinement pass 2: thinner bar (was 10) per in-game feedback --
        // all dependent vertical-centering math below (dot/tick/label Y) is
        // derived from BAR_HEIGHT so it stays correct at the new value.
        private static final int BAR_HEIGHT = 6;
        // Fix #1: vanilla's armor/air-bubble row top is guiHeight() - 49
        // (javap-confirmed against Hud.extractPlayerHealth/extractArmor/
        // extractAirBubbles -- see class Javadoc); the compass bar now sits
        // a small gap above that row instead of the un-verified guess this
        // feature originally shipped with (guiHeight() - 50, which
        // collided with it).
        private static final int ARMOR_AIR_ROW_TOP_MARGIN = 49;
        private static final int BAR_GAP_ABOVE_ARMOR_ROW = 2;
        private static final int BAR_BOTTOM_MARGIN = ARMOR_AIR_ROW_TOP_MARGIN + BAR_GAP_ABOVE_ARMOR_ROW;
        private static final double FOV_HALF_DEGREES = 90.0;
        private static final int DOT_MIN_SIZE = 2;
        private static final int DOT_MAX_SIZE = 6;
        private static final double DOT_MIN_SIZE_DISTANCE = 128.0;
        private static final double DOT_MAX_SIZE_DISTANCE = 8.0;
        private static final double NAME_LABEL_HALF_DEGREES = 5.0;

        // Fix #3: bearing-ruler background (battle-royale-compass style).
        // Live-refinement pass 2: the old two-tier tick system (10-degree
        // minor / 20-degree major) is gone -- one uniform tier of short/thin
        // ticks (the old "sub-bar" style), now every 15 degrees.
        private static final int TICK_STEP_DEGREES = 15;
        private static final int TICK_HEIGHT = 4;
        private static final int COLOR_TICK = 0xFFC8C8C8;
        private static final int COLOR_CARDINAL_LABEL = 0xFFFFFFFF;
        // Live-refinement pass 2: cardinal letters (N/E/S/W) are now drawn
        // inline in the tick row itself, replacing the tick at that exact
        // 15-degree position, instead of a separate label row above the bar
        // -- gated behind this toggle so they can be turned off entirely.
        private static final boolean SHOW_CARDINALS = true;

        // Fix #4: a little less transparent than the original 0x66 (40%)
        // alpha, plus a thin light-grey border around the bar's rectangle.
        private static final int COLOR_BAR_BG = 0x80000000;
        private static final int COLOR_BAR_BORDER = 0xFFC8C8C8;
        // Live-refinement pass 2: gate the border behind a toggle (default
        // unchanged -- still on) instead of drawing it unconditionally.
        private static final boolean SHOW_BORDER = true;

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
            if (SHOW_BORDER) {
                drawBorder(extractor, barLeft, barTop, barLeft + BAR_WIDTH, barBottom);
            }

            double yaw = player.getYRot();
            drawRuler(extractor, client, barTop, barCenterX, yaw);

            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            double yawRad = Math.toRadians(yaw);
            double sinYaw = Math.sin(yawRad);
            double cosYaw = Math.cos(yawRad);

            Waypoint nearestCentered = null;
            double nearestCenteredAbsTheta = Double.MAX_VALUE;

            for (Waypoint waypoint : waypoints) {
                double wx = waypoint.x() + 0.5;
                double wy = waypoint.y() + 0.5;
                double wz = waypoint.z() + 0.5;
                double dx = wx - px;
                double dz = wz - pz;
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

                int elevationDirection = elevationDirection(client, wx, wy, wz);
                if (elevationDirection != 0) {
                    drawElevationChevron(extractor, dotX, dotY, dotSize, elevationDirection > 0, waypoint.color());
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

        /** Fix #4: a thin 1px light-grey outline around the bar's rectangle. */
        private static void drawBorder(GuiGraphicsExtractor extractor, int left, int top, int right, int bottom) {
            extractor.fill(left - 1, top - 1, right + 1, top, COLOR_BAR_BORDER);
            extractor.fill(left - 1, bottom, right + 1, bottom + 1, COLOR_BAR_BORDER);
            extractor.fill(left - 1, top - 1, left, bottom + 1, COLOR_BAR_BORDER);
            extractor.fill(right, top - 1, right + 1, bottom + 1, COLOR_BAR_BORDER);
        }

        /**
         * Fix #3: a scrolling bearing ruler, world-bearing-fixed (moves across
         * the bar as the player turns, same {@link #FOV_HALF_DEGREES}/linear
         * mapping R11 already uses for waypoint dots) -- a single uniform
         * tier of short/thin ticks every {@link #TICK_STEP_DEGREES} degrees
         * of world bearing (live-refinement pass 2: the old two-tier
         * minor/major tick split is gone). At the four cardinal bearings
         * (using this codebase's own yaw convention: 0=S, 90=W, 180=N, 270=E,
         * matching {@code Entity.getLookAngle()}'s {@code (-sin(yaw),
         * cos(yaw))} forward vector), the letter is drawn inline in place of
         * that position's tick -- not as a separate row above the bar --
         * when {@link #SHOW_CARDINALS} is enabled; otherwise that position
         * just renders a normal tick like any other.
         */
        private static void drawRuler(GuiGraphicsExtractor extractor, Minecraft client, int barTop, int barCenterX, double yaw) {
            int startBearing = (int) (Math.floor((yaw - FOV_HALF_DEGREES) / TICK_STEP_DEGREES) * TICK_STEP_DEGREES);
            int endBearing = (int) (Math.ceil((yaw + FOV_HALF_DEGREES) / TICK_STEP_DEGREES) * TICK_STEP_DEGREES);
            int tickCenterY = barTop + BAR_HEIGHT / 2;
            for (int worldBearing = startBearing; worldBearing <= endBearing; worldBearing += TICK_STEP_DEGREES) {
                double theta = normalizeAngle(worldBearing - yaw);
                if (Math.abs(theta) > FOV_HALF_DEGREES) {
                    continue;
                }

                int tickX = barCenterX + (int) Math.round((theta / FOV_HALF_DEGREES) * (BAR_WIDTH / 2.0));
                int normalizedBearing = ((worldBearing % 360) + 360) % 360;
                String cardinal = SHOW_CARDINALS ? cardinalLabelFor(normalizedBearing) : null;
                if (cardinal != null) {
                    extractor.centeredText(client.font, cardinal, tickX, tickCenterY - 4, COLOR_CARDINAL_LABEL);
                } else {
                    int tickTop = barTop + (BAR_HEIGHT - TICK_HEIGHT) / 2;
                    extractor.fill(tickX, tickTop, tickX + 1, tickTop + TICK_HEIGHT, COLOR_TICK);
                }
            }
        }

        private static String cardinalLabelFor(int normalizedBearing) {
            return switch (normalizedBearing) {
                case 0 -> "S";
                case 90 -> "W";
                case 180 -> "N";
                case 270 -> "E";
                default -> null;
            };
        }

        private static double normalizeAngle(double deg) {
            deg = deg % 360.0;
            if (deg > 180.0) {
                deg -= 360.0;
            } else if (deg < -180.0) {
                deg += 360.0;
            }
            return deg;
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

        /**
         * Fix #5: ports vanilla's own Locator Bar elevation-indicator decision
         * exactly (see class Javadoc) -- projects the target's world position
         * into normalized screen space via {@code GameRenderer#projectPointToScreen}
         * (public on {@code Minecraft.gameRenderer}, javap-confirmed) and
         * compares against the visible [-1, 1] vertical range the same way
         * {@code TrackedWaypoint.Vec3iWaypoint#pitchDirectionToCamera} does,
         * instead of a flat world-space Y-coordinate delta. Returns {@code +1}
         * (UP), {@code -1} (DOWN), or {@code 0} (NONE, within the player's
         * current vertical view).
         */
        private static int elevationDirection(Minecraft client, double targetX, double targetY, double targetZ) {
            Vec3 projected = client.gameRenderer.projectPointToScreen(new Vec3(targetX, targetY, targetZ));
            boolean behindCamera = projected.z() > 1;
            double y = behindCamera ? -projected.y() : projected.y();
            if (y < -1) {
                return -1;
            }
            if (y > 1) {
                return 1;
            }
            if (behindCamera) {
                if (projected.y() > 0) {
                    return 1;
                }
                if (projected.y() < 0) {
                    return -1;
                }
            }
            return 0;
        }

        /** R14/R15: only the nearest near-center waypoint's name, drawn above the bar in its own color. */
        private static void drawNameLabel(GuiGraphicsExtractor extractor, Minecraft client, int barCenterX, int barTop, Waypoint waypoint) {
            int argb = 0xFF000000 | (waypoint.color() & 0xFFFFFF);
            extractor.centeredText(client.font, Component.literal(waypoint.name()), barCenterX, barTop - 10, argb);
        }
    }
}
