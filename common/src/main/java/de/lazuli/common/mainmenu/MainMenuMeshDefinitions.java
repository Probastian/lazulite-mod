package de.lazuli.common.mainmenu;

import java.util.List;

import static de.lazuli.common.mainmenu.MainMenuPartNames.BODY;
import static de.lazuli.common.mainmenu.MainMenuPartNames.GROUND_BASE;
import static de.lazuli.common.mainmenu.MainMenuPartNames.GROUND_HIGHLIGHT;
import static de.lazuli.common.mainmenu.MainMenuPartNames.GROUND_TOP;
import static de.lazuli.common.mainmenu.MainMenuPartNames.HAT;
import static de.lazuli.common.mainmenu.MainMenuPartNames.HEAD;
import static de.lazuli.common.mainmenu.MainMenuPartNames.JACKET;
import static de.lazuli.common.mainmenu.MainMenuPartNames.LEFT_ARM;
import static de.lazuli.common.mainmenu.MainMenuPartNames.LEFT_LEG;
import static de.lazuli.common.mainmenu.MainMenuPartNames.LEFT_PANTS;
import static de.lazuli.common.mainmenu.MainMenuPartNames.LEFT_SLEEVE;
import static de.lazuli.common.mainmenu.MainMenuPartNames.MOUNTAIN_FAR_PREFIX;
import static de.lazuli.common.mainmenu.MainMenuPartNames.MOUNTAIN_NEAR_PREFIX;
import static de.lazuli.common.mainmenu.MainMenuPartNames.RIGHT_ARM;
import static de.lazuli.common.mainmenu.MainMenuPartNames.RIGHT_LEG;
import static de.lazuli.common.mainmenu.MainMenuPartNames.RIGHT_PANTS;
import static de.lazuli.common.mainmenu.MainMenuPartNames.RIGHT_SLEEVE;
import static de.lazuli.common.mainmenu.MainMenuPartNames.SKY_PREFIX;
import static de.lazuli.common.mainmenu.MainMenuPartNames.SUN_CORE;
import static de.lazuli.common.mainmenu.MainMenuPartNames.SUN_GLOW;

/**
 * The single canonical cube-geometry source for the main menu background's
 * character + scenery mesh -- transcribed, unchanged in position/size/UV
 * (per the approved plan's "not changing cube geometry values" Non-goal),
 * from {@code fabric-26.2}'s previous private {@code buildScene()}/
 * {@code buildCharacterMesh()} methods, with the character bones renamed
 * from that version's old {@code torso}/{@code hair} naming to the shared
 * {@code body}/{@code hat} naming already used by {@code fabric-26.1}/
 * {@code fabric-1.21.11} (both constrained by {@code PlayerModel}/
 * {@code PlayerEntityModel}'s required-child names), plus the five
 * required-placeholder bones ({@code left_sleeve}, {@code right_sleeve},
 * {@code left_pants}, {@code right_pants}, {@code jacket}) added as
 * zero-size boxes.
 *
 * <p>Each platform module's {@code MainMenuBackgroundRenderer} iterates
 * {@link #CHARACTER_PARTS}/{@link #SCENERY_PARTS} and translates every
 * {@link MeshCubeSpec} into its own native builder API calls (see the
 * approved plan's Architecture Decision -- true shared *source* covering all
 * three platforms' differing builder-API class families is not possible,
 * only the geometry *data* is shared here).
 */
public final class MainMenuMeshDefinitions {

    private MainMenuMeshDefinitions() {
    }

    public static final int TEX_SIZE = 384;
    public static final int CELL = 96;

    public static int cellU(int col) {
        return col * CELL;
    }

    public static int cellV(int row) {
        return row * CELL;
    }

    /**
     * Hand-authored idle-character bones (FR8.6, vanilla-player-blockiness
     * proportions), named to satisfy {@code PlayerModel}/
     * {@code PlayerEntityModel}'s required-child constructor lookup.
     */
    public static final List<MeshCubeSpec> CHARACTER_PARTS = List.of(
            new MeshCubeSpec(HEAD, null, 0f, 0f, 0f,
                    -4f, -8f, -4f, 8f, 8f, 8f, 3, 2, false),
            new MeshCubeSpec(HAT, HEAD, 0f, 0f, 0f,
                    -4.3f, -8.3f, -4.3f, 8.6f, 3.6f, 8.6f, 0, 3, false),
            new MeshCubeSpec(BODY, null, 0f, 0f, 0f,
                    -4f, 0f, -2f, 8f, 12f, 4f, 1, 3, false),
            new MeshCubeSpec(JACKET, BODY, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f, 0f, 0, 0, true),
            new MeshCubeSpec(RIGHT_ARM, null, -6f, 2f, 0f,
                    -2f, -2f, -2f, 4f, 12f, 4f, 3, 2, false),
            new MeshCubeSpec(RIGHT_SLEEVE, RIGHT_ARM, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f, 0f, 0, 0, true),
            new MeshCubeSpec(LEFT_ARM, null, 6f, 2f, 0f,
                    -2f, -2f, -2f, 4f, 12f, 4f, 3, 2, false),
            new MeshCubeSpec(LEFT_SLEEVE, LEFT_ARM, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f, 0f, 0, 0, true),
            new MeshCubeSpec(RIGHT_LEG, null, -2f, 12f, 0f,
                    -2f, 0f, -2f, 4f, 12f, 4f, 2, 3, false),
            new MeshCubeSpec(RIGHT_PANTS, RIGHT_LEG, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f, 0f, 0, 0, true),
            new MeshCubeSpec(LEFT_LEG, null, 2f, 12f, 0f,
                    -2f, 0f, -2f, 4f, 12f, 4f, 2, 3, false),
            new MeshCubeSpec(LEFT_PANTS, LEFT_LEG, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f, 0f, 0, 0, true));

    /**
     * Static (built-once) sky/sun/mountains/ground scenery bones
     * (FR8.2-FR8.5) -- top-level children of the shared root, render
     * generically regardless of name, never per-frame posed.
     */
    public static final List<MeshCubeSpec> SCENERY_PARTS = buildSceneryParts();

    private static List<MeshCubeSpec> buildSceneryParts() {
        java.util.ArrayList<MeshCubeSpec> parts = new java.util.ArrayList<>();

        // Sky: four stacked flat bands approximating the dusk gradient's stops.
        int[][] skyCells = { { 0, 0 }, { 1, 0 }, { 2, 0 }, { 3, 0 } };
        float skyBandHeight = 18f;
        for (int i = 0; i < skyCells.length; i++) {
            parts.add(new MeshCubeSpec(SKY_PREFIX + i, null,
                    0f, -40f + i * skyBandHeight, 0f,
                    -40f, 0f, -1f, 80f, skyBandHeight, 1f,
                    skyCells[i][0], skyCells[i][1], false));
        }

        // Sun glow (behind) + core, upper-right per design doc placement.
        parts.add(new MeshCubeSpec(SUN_GLOW, null, 24f, -18f, -1.5f,
                -10f, -10f, -0.5f, 20f, 20f, 1f, 1, 1, false));
        parts.add(new MeshCubeSpec(SUN_CORE, null, 24f, -18f, -1f,
                -6f, -6f, -0.5f, 12f, 12f, 1f, 0, 1, false));

        // Mountains: two semi-transparent jagged silhouette layers along the bottom.
        int[][] farPeaks = { { -36, 10 }, { -18, 16 }, { 0, 12 }, { 18, 18 }, { 36, 11 } };
        for (int i = 0; i < farPeaks.length; i++) {
            parts.add(new MeshCubeSpec(MOUNTAIN_FAR_PREFIX + i, null,
                    farPeaks[i][0], 14f, -0.8f,
                    -6f, -farPeaks[i][1], -0.5f, 12f, farPeaks[i][1], 1f,
                    2, 1, false));
        }
        int[][] nearPeaks = { { -30, 8 }, { -8, 13 }, { 14, 9 }, { 32, 14 } };
        for (int i = 0; i < nearPeaks.length; i++) {
            parts.add(new MeshCubeSpec(MOUNTAIN_NEAR_PREFIX + i, null,
                    nearPeaks[i][0], 14f, -0.5f,
                    -7f, -nearPeaks[i][1], -0.5f, 14f, nearPeaks[i][1], 1f,
                    3, 1, false));
        }

        // Ground: flat plane, darker base + lighter top strip highlight.
        parts.add(new MeshCubeSpec(GROUND_BASE, null, 0f, 14f, 0f,
                -40f, 0f, -0.5f, 80f, 12f, 1f, 0, 2, false));
        parts.add(new MeshCubeSpec(GROUND_TOP, null, 0f, 14f, 0f,
                -40f, 0f, -0.4f, 80f, 3f, 1f, 1, 2, false));
        parts.add(new MeshCubeSpec(GROUND_HIGHLIGHT, null, 0f, 14f, 0f,
                -40f, 0f, -0.3f, 80f, 1f, 1f, 2, 2, false));

        return List.copyOf(parts);
    }
}
