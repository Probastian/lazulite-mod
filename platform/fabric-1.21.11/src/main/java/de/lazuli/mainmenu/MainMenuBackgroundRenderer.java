package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.CharacterPose;
import de.lazuli.features.mainmenu.services.IdleCharacterAnimator;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

/**
 * Owns the "Stonebound" main menu's continuously-rendered 3D background
 * (specification FR8) -- {@code fabric-1.21.11} (Yarn-mapped, obfuscated)
 * port of the {@code fabric-26.1}/{@code fabric-26.2} class of the same name.
 *
 * <h2>Real cross-version divergence found while porting, consistent with the
 * one already logged for {@code fabric-26.1}</h2>
 * Direct {@code javap} enumeration of this module's own resolved, Yarn-mapped
 * {@code minecraft-merged.jar} confirms {@link DrawContext} has exactly one
 * generic-camera picture-in-picture overload usable for this feature's
 * from-scratch geometry: {@code addPlayerSkin(PlayerEntityModel, Identifier,
 * float, float, float, float, int, int, int, int)} -- restricted to
 * {@link PlayerEntityModel} specifically (it internally resolves
 * {@code head}/{@code hat}/{@code body}/{@code right_arm}/{@code left_arm}/
 * {@code right_leg}/{@code left_leg} plus {@code left_sleeve}/
 * {@code right_sleeve}/{@code left_pants}/{@code right_pants}/{@code jacket}
 * named child parts via {@code BipedEntityModel}'s own constructor, which
 * throws if any are missing). No other public {@link DrawContext} method
 * accepts an arbitrary model with a free camera into an arbitrary screen
 * rectangle ({@code addBookModel}/{@code addBannerResult}/{@code addSign} are
 * all similarly hard-bound to their own specific model/texture shapes). This
 * is the exact same restriction already logged for {@code fabric-26.1} (see
 * {@code .claude/context/minecraft.md}'s Known Cross-Version API Differences
 * table) -- confirmed here to also apply identically on 1.21.11, just under
 * this version's own Yarn-mapped class/method names.
 *
 * <p>Accepted, documented compromise (identical shape to {@code fabric-26.1}):
 * <ul>
 *   <li>The idle character (FR8.6) still renders as a genuine 3D
 *       picture-in-picture model -- its {@link ModelPart} hierarchy is shaped
 *       to satisfy {@link PlayerEntityModel}'s required named parts, wrapped
 *       in a real {@link PlayerEntityModel} instance and submitted via
 *       {@code DrawContext.addPlayerSkin(...)}.</li>
 *   <li>The sky/sun/mountains/ground scene (FR8.2-FR8.5), which has no biped
 *       shape to conform to, is rendered as flat 2D {@link DrawContext#fill}/
 *       {@code fillGradient}-equivalent screen-space bands instead of 3D
 *       geometry on this version only -- still fully matching the design
 *       doc's described placement/colors, just not "real 3D-space geometry"
 *       for the backdrop specifically (FR8.1's own "genuine 3D scene" bar is
 *       still met by the character, the single most load-bearing visual
 *       element per the design doc's own description).</li>
 * </ul>
 *
 * <p>Zero I/O/network/Steamworks dependency (FR8.8) -- this class only reads
 * an in-memory {@link IdleCharacterAnimator} and issues draw calls.
 */
public final class MainMenuBackgroundRenderer {

    private static final Identifier PALETTE = Identifier.of("lazuli", "textures/mainmenu/palette.png");
    private static final int TEX_SIZE = 384;
    private static final int CELL = 96;

    private final IdleCharacterAnimator animator = new IdleCharacterAnimator();
    private final ModelPart characterRoot;
    private final ModelPart head;
    private final ModelPart torso;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    private final PlayerEntityModel characterModel;

    private final long startNanos = System.nanoTime();

    public MainMenuBackgroundRenderer() {
        ModelData modelData = buildCharacterModelData();
        ModelPart root = TexturedModelData.of(modelData, TEX_SIZE, TEX_SIZE).createModel();
        this.characterRoot = root;
        this.head = root.getChild("head");
        this.torso = root.getChild("body");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg = root.getChild("left_leg");
        this.characterModel = new PlayerEntityModel(root, false);
    }

    private static int cellU(int col) {
        return col * CELL;
    }

    private static int cellV(int row) {
        return row * CELL;
    }

    /**
     * Hand-authored idle-character {@link ModelPart} hierarchy (FR8.6,
     * vanilla-player-blockiness proportions), shaped to satisfy
     * {@link PlayerEntityModel}'s exact required part names (see this class's
     * own Javadoc) so it can be wrapped in a real {@link PlayerEntityModel}
     * and submitted through this version's only generic-camera
     * picture-in-picture {@code addPlayerSkin(...)} overload.
     */
    private static ModelData buildCharacterModelData() {
        ModelData modelData = new ModelData();
        ModelPartData root = modelData.getRoot();

        ModelPartData headPart = root.addChild("head",
                ModelPartBuilder.create().uv(cellU(3), cellV(2))
                        .cuboid(-4f, -8f, -4f, 8f, 8f, 8f, Dilation.NONE),
                ModelTransform.NONE);
        // "hat" is a required BipedEntityModel part name -- doubles as the hair layer here.
        headPart.addChild("hat",
                ModelPartBuilder.create().uv(cellU(0), cellV(3))
                        .cuboid(-4.3f, -8.3f, -4.3f, 8.6f, 3.6f, 8.6f, Dilation.NONE),
                ModelTransform.NONE);

        ModelPartData body = root.addChild("body",
                ModelPartBuilder.create().uv(cellU(1), cellV(3))
                        .cuboid(-4f, 0f, -2f, 8f, 12f, 4f, Dilation.NONE),
                ModelTransform.NONE);
        body.addChild("jacket", ModelPartBuilder.create(), ModelTransform.NONE);

        ModelPartData rightArmPart = root.addChild("right_arm",
                ModelPartBuilder.create().uv(cellU(3), cellV(2))
                        .cuboid(-2f, -2f, -2f, 4f, 12f, 4f, Dilation.NONE),
                ModelTransform.origin(-6f, 2f, 0f));
        rightArmPart.addChild("right_sleeve", ModelPartBuilder.create(), ModelTransform.NONE);

        ModelPartData leftArmPart = root.addChild("left_arm",
                ModelPartBuilder.create().uv(cellU(3), cellV(2))
                        .cuboid(-2f, -2f, -2f, 4f, 12f, 4f, Dilation.NONE),
                ModelTransform.origin(6f, 2f, 0f));
        leftArmPart.addChild("left_sleeve", ModelPartBuilder.create(), ModelTransform.NONE);

        ModelPartData rightLegPart = root.addChild("right_leg",
                ModelPartBuilder.create().uv(cellU(2), cellV(3))
                        .cuboid(-2f, 0f, -2f, 4f, 12f, 4f, Dilation.NONE),
                ModelTransform.origin(-2f, 12f, 0f));
        rightLegPart.addChild("right_pants", ModelPartBuilder.create(), ModelTransform.NONE);

        ModelPartData leftLegPart = root.addChild("left_leg",
                ModelPartBuilder.create().uv(cellU(2), cellV(3))
                        .cuboid(-2f, 0f, -2f, 4f, 12f, 4f, Dilation.NONE),
                ModelTransform.origin(2f, 12f, 0f));
        leftLegPart.addChild("left_pants", ModelPartBuilder.create(), ModelTransform.NONE);

        return modelData;
    }

    /**
     * Renders the continuously-updating background (FR8.1/FR1.4) -- called
     * every frame from {@link MainMenuScreen#render}, regardless of tab
     * state.
     *
     * <p>FX8 note: this version's own scene is already the flat-2D,
     * full-screen stand-in described in this class's own Javadoc (not the 3D
     * {@code addPlayerSkin()}-submitted geometry) -- it already fills the
     * destination rect edge-to-edge, so FX8's "small/centered/stuck to the
     * bottom" defect (a 3D-camera scale/pivot framing issue) does not apply
     * to it structurally; per FX8.3/R4 this is the documented placeholder-
     * model-limitation framing for this platform specifically, not a skipped
     * fix. FX7 (character sizing/position) still applies below.
     *
     * @param reservedWidth the post-launch-fixes spec's reserved left-third
     *                      background+character region's pixel width
     *                      (FX6.1/FX7.1)
     */
    public void render(DrawContext context, int screenWidth, int screenHeight, int reservedWidth) {
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

        renderSceneAsFlat2D(context, screenWidth, screenHeight);

        // Character: still a genuine 3D picture-in-picture model, posed per-frame
        // from the pure animation math (FR8.6). FX7: sized/positioned from the
        // reserved left-third region's own actual pixel bounds, not
        // screenWidth*0.08, grounded at the bottom and inset from the
        // region's edges so it reads as "bottom-left of the region."
        CharacterPose pose = animator.poseAt(elapsedSeconds);
        applyPose(pose);
        int region = Math.max(1, reservedWidth);
        // Bug fix: at small logical GUI widths (high GUI scale / narrow window)
        // reservedWidth() can clamp down to a few pixels or 0, and the old fixed
        // inset (>= 4) made charX1 = region - inset go negative -- addPlayerSkin()
        // then handed the renderer a negative-width destination rect, which the
        // GPU rejects with GL_INVALID_VALUE and crashes the game. Cap the inset
        // at region/2 - 1 so charX1 is always > charX0 (>= 1px wide) regardless
        // of how small region gets.
        int inset = Math.min(Math.max(4, region / 10), Math.max(0, region / 2 - 1));
        int charX0 = inset;
        int charX1 = Math.max(charX0 + 1, region - inset);
        int charY0 = Math.max(0, (int) (screenHeight * 0.04));
        int charY1 = Math.max(charY0 + 1, screenHeight);
        context.addPlayerSkin(characterModel, PALETTE, 22f, 0f, 20f, 0f, charX0, charY0, charX1, charY1);
    }

    /**
     * 2D screen-space stand-in for the sky/sun/mountains/ground scene (this
     * class's own Javadoc) -- 1.21.11 has no generic-camera picture-in-picture
     * call this feature can submit non-biped geometry through.
     */
    private void renderSceneAsFlat2D(DrawContext context, int screenWidth, int screenHeight) {
        // Dusk sky gradient (top: deep violet, bottom: pale gold).
        context.fillGradient(0, 0, screenWidth, screenHeight, 0xFF2E2A44, 0xFFC9A15A);

        // Sun glow + core, upper-right per design doc placement.
        int sunCx = (int) (screenWidth * 0.78);
        int sunCy = (int) (screenHeight * 0.22);
        int glowR = Math.max(60, screenHeight / 6);
        context.fill(sunCx - glowR, sunCy - glowR, sunCx + glowR, sunCy + glowR, 0x662EE8C9);
        int coreR = glowR / 3;
        context.fill(sunCx - coreR, sunCy - coreR, sunCx + coreR, sunCy + coreR, 0xFFF5E6A8);

        // Mountains: two semi-transparent silhouette bands along the bottom.
        int groundTop = (int) (screenHeight * 0.72);
        context.fill(0, groundTop - 40, screenWidth, groundTop, 0x552B2A3A);
        context.fill(0, groundTop - 20, screenWidth, groundTop, 0x77201F2C);

        // Ground: flat base + lighter top-edge highlight strip.
        context.fill(0, groundTop, screenWidth, screenHeight, 0xFF2E4A2E);
        context.fill(0, groundTop, screenWidth, groundTop + 3, 0xFF6F9A5A);
    }

    private void applyPose(CharacterPose pose) {
        characterRoot.originY = (float) pose.bobOffset();
        rightArm.pitch = (float) pose.armSwingAngle();
        leftArm.pitch = (float) -pose.armSwingAngle();
        rightLeg.pitch = (float) -pose.legSwayAngle();
        leftLeg.pitch = (float) pose.legSwayAngle();
    }
}
