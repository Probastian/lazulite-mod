package de.lazuli.common.mainmenu;

/**
 * Shared name constants for every bone in the main menu background's mesh
 * hierarchy (see {@link MainMenuMeshDefinitions}) -- referenced by all three
 * platform modules' {@code MainMenuBackgroundRenderer} so a typo in one
 * platform surfaces as an immediate runtime {@code getChild(...)} failure in
 * dev rather than a silently-diverging string (this already happened once,
 * between {@code fabric-26.2}'s old {@code torso}/{@code hair} naming and
 * {@code fabric-26.1}/{@code fabric-1.21.11}'s {@code body}/{@code hat}
 * naming, before this class existed).
 */
public final class MainMenuPartNames {

    private MainMenuPartNames() {
    }

    // Required-biped names (must exist, real non-trivial geometry).
    public static final String HEAD = "head";
    public static final String BODY = "body";
    public static final String LEFT_ARM = "left_arm";
    public static final String RIGHT_ARM = "right_arm";
    public static final String LEFT_LEG = "left_leg";
    public static final String RIGHT_LEG = "right_leg";

    // Required-placeholder names (must exist per PlayerModel/PlayerEntityModel's
    // constructor; hat carries real hair geometry, the rest may be zero-size).
    public static final String HAT = "hat";
    public static final String LEFT_SLEEVE = "left_sleeve";
    public static final String RIGHT_SLEEVE = "right_sleeve";
    public static final String LEFT_PANTS = "left_pants";
    public static final String RIGHT_PANTS = "right_pants";
    public static final String JACKET = "jacket";

    // Scenery/cosmetic bone names -- render generically regardless of name,
    // no per-frame posing, top-level children of the shared root.
    public static final String SKY_PREFIX = "sky_";
    public static final String SUN_GLOW = "sun_glow";
    public static final String SUN_CORE = "sun_core";
    public static final String MOUNTAIN_FAR_PREFIX = "mountain_far_";
    public static final String MOUNTAIN_NEAR_PREFIX = "mountain_near_";
    public static final String GROUND_BASE = "ground_base";
    public static final String GROUND_TOP = "ground_top";
    public static final String GROUND_HIGHLIGHT = "ground_highlight";
}
