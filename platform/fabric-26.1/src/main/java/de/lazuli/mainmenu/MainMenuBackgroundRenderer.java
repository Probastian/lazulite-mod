package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.CharacterPose;
import de.lazuli.features.mainmenu.services.IdleCharacterAnimator;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;

/**
 * Owns the "Stonebound" main menu's continuously-rendered 3D background
 * (specification FR8) -- {@code fabric-26.1} port of the {@code fabric-26.2}
 * class of the same name.
 *
 * <h2>Real cross-version divergence found while porting (Sequencing step 13),
 * not anticipated by the plan (which expected 26.1 to be "near-identical" to
 * 26.2)</h2>
 * {@code fabric-26.2}'s {@code MainMenuBackgroundRenderer} submits an
 * arbitrary hand-authored {@code ModelPart} hierarchy (both the sky/sun/
 * mountains/ground scene <em>and</em> the idle character) via
 * {@code GuiGraphicsExtractor.skin(Model.Simple, Identifier, ...)} -- a
 * generic "picture-in-picture" overload confirmed present on 26.2. Direct
 * {@code javap} enumeration of this module's own resolved, remapped
 * {@code minecraft-client.jar} (26.1) confirms {@code skin(...)} on
 * <strong>this</strong> version is overloaded only for
 * {@code net.minecraft.client.model.player.PlayerModel}, not
 * {@code Model.Simple} -- i.e. 26.1's {@code skin()} can only submit a real
 * biped player-shaped model (it internally resolves {@code head}/{@code hat}/
 * {@code body}/{@code left_arm}/{@code right_arm}/{@code left_leg}/
 * {@code right_leg} plus {@code left_sleeve}/{@code right_sleeve}/
 * {@code left_pants}/{@code right_pants}/{@code jacket} named child parts via
 * {@code ModelPart.getChild(String)}, which throws if any are missing -- it
 * does not generically render arbitrary named children the way this
 * feature's from-scratch scene geometry needs). No other public
 * {@code GuiGraphicsExtractor} method on 26.1 accepts an arbitrary
 * {@code Model.Simple} with a free camera into an arbitrary screen rectangle
 * either (the only other model-shaped picture-in-picture overloads are
 * {@code book(BookModel, ...)}, {@code bannerPattern(BannerFlagModel, ...)},
 * and {@code sign(Model.Simple, float, WoodType, ...)} -- the last is
 * generic-model-shaped but hard-binds its texture to a {@code WoodType}, not
 * an arbitrary {@code Identifier}, so it cannot host this feature's own
 * palette texture either).
 *
 * <p><strong>This is flagged, not silently degraded</strong> (implementation
 * batch instruction) and logged in {@code .claude/context/minecraft.md}'s
 * cross-version divergence table. The accepted, documented compromise for
 * 26.1 only:
 * <ul>
 *   <li>The idle character (FR8.6) still renders as a genuine 3D
 *       picture-in-picture model, exactly as on 26.2 -- its {@code ModelPart}
 *       hierarchy is reshaped to satisfy {@link PlayerModel}'s required named
 *       parts (head/hat/body/left_arm/right_arm/left_leg/right_leg, plus
 *       empty zero-cube {@code left_sleeve}/{@code right_sleeve}/
 *       {@code left_pants}/{@code right_pants}/{@code jacket} placeholders
 *       {@link PlayerModel}'s own constructor requires to exist), wrapped in
 *       a real {@link PlayerModel} instance and submitted via
 *       {@code skin(PlayerModel, ...)}.</li>
 *   <li>The sky/sun/mountains/ground scene (FR8.2-FR8.5), which has no
 *       biped shape to conform to, is rendered as flat 2D
 *       {@code GuiGraphicsExtractor.fill}/{@code fillGradient} screen-space
 *       bands instead of 3D geometry on this version only -- still fully
 *       matching the design doc's described placement/colors, just not
 *       "real 3D-space geometry" for the backdrop specifically (FR8.1's own
 *       "genuine 3D scene" bar is still met by the character, the single
 *       most load-bearing visual element per the design doc's own
 *       description).</li>
 * </ul>
 *
 * <p>Zero I/O/network/Steamworks dependency (FR8.8) -- this class only reads
 * an in-memory {@link IdleCharacterAnimator} and issues draw calls.
 */
public final class MainMenuBackgroundRenderer {

    private static final Identifier PALETTE = Identifier.fromNamespaceAndPath("lazuli", "textures/mainmenu/palette.png");
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

    private final PlayerModel characterModel;

    private final long startNanos = System.nanoTime();

    public MainMenuBackgroundRenderer() {
        MeshDefinition characterMesh = buildCharacterMesh();
        ModelPart root = LayerDefinition.create(characterMesh, TEX_SIZE, TEX_SIZE).bakeRoot();
        this.characterRoot = root;
        this.head = root.getChild("head");
        this.torso = root.getChild("body");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg = root.getChild("left_leg");
        this.characterModel = new PlayerModel(root, false);
    }

    private static int cellU(int col) {
        return col * CELL;
    }

    private static int cellV(int row) {
        return row * CELL;
    }

    /**
     * Hand-authored idle-character {@link ModelPart} hierarchy (FR8.6,
     * vanilla-player-blockiness proportions), reshaped from the 26.2 version
     * to use {@link PlayerModel}'s exact required part names (see this
     * class's own Javadoc) so it can be wrapped in a real {@link PlayerModel}
     * and submitted through this version's only generic-camera
     * picture-in-picture {@code skin(...)} overload.
     */
    private static MeshDefinition buildCharacterMesh() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition headPart = root.addOrReplaceChild("head",
                CubeListBuilder.create().texOffs(cellU(3), cellV(2))
                        .addBox(-4f, -8f, -4f, 8f, 8f, 8f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(0f, 0f, 0f));
        // "hat" is a required HumanoidModel part name -- doubles as the hair layer here.
        headPart.addOrReplaceChild("hat",
                CubeListBuilder.create().texOffs(cellU(0), cellV(3))
                        .addBox(-4.3f, -8.3f, -4.3f, 8.6f, 3.6f, 8.6f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(0f, 0f, 0f));

        PartDefinition body = root.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(cellU(1), cellV(3))
                        .addBox(-4f, 0f, -2f, 8f, 12f, 4f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(0f, 0f, 0f));
        body.addOrReplaceChild("jacket", CubeListBuilder.create(), PartPose.ZERO);

        PartDefinition rightArmPart = root.addOrReplaceChild("right_arm",
                CubeListBuilder.create().texOffs(cellU(3), cellV(2))
                        .addBox(-2f, -2f, -2f, 4f, 12f, 4f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(-6f, 2f, 0f));
        rightArmPart.addOrReplaceChild("right_sleeve", CubeListBuilder.create(), PartPose.ZERO);

        PartDefinition leftArmPart = root.addOrReplaceChild("left_arm",
                CubeListBuilder.create().texOffs(cellU(3), cellV(2))
                        .addBox(-2f, -2f, -2f, 4f, 12f, 4f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(6f, 2f, 0f));
        leftArmPart.addOrReplaceChild("left_sleeve", CubeListBuilder.create(), PartPose.ZERO);

        PartDefinition rightLegPart = root.addOrReplaceChild("right_leg",
                CubeListBuilder.create().texOffs(cellU(2), cellV(3))
                        .addBox(-2f, 0f, -2f, 4f, 12f, 4f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(-2f, 12f, 0f));
        rightLegPart.addOrReplaceChild("right_pants", CubeListBuilder.create(), PartPose.ZERO);

        PartDefinition leftLegPart = root.addOrReplaceChild("left_leg",
                CubeListBuilder.create().texOffs(cellU(2), cellV(3))
                        .addBox(-2f, 0f, -2f, 4f, 12f, 4f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(2f, 12f, 0f));
        leftLegPart.addOrReplaceChild("left_pants", CubeListBuilder.create(), PartPose.ZERO);

        return mesh;
    }

    /**
     * Renders the continuously-updating background (FR8.1/FR1.4) -- called
     * every frame from {@link MainMenuScreen#extractRenderState}, regardless
     * of tab state.
     *
     * <p>FX8 note: this version's own scene is already the flat-2D,
     * full-screen stand-in described in this class's own Javadoc (not the
     * 3D {@code skin()}-submitted geometry 26.2 uses) -- it already fills the
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
    public void render(GuiGraphicsExtractor guiGraphics, int screenWidth, int screenHeight, int reservedWidth) {
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

        renderSceneAsFlat2D(guiGraphics, screenWidth, screenHeight);

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
        // inset (>= 4) made charX1 = region - inset go negative -- skin() then
        // handed the renderer a negative-width destination rect, which the GPU
        // rejects with GL_INVALID_VALUE and crashes the game. Cap the inset at
        // region/2 - 1 so charX1 is always > charX0 (>= 1px wide) regardless of
        // how small region gets.
        int inset = Math.min(Math.max(4, region / 10), Math.max(0, region / 2 - 1));
        int charX0 = inset;
        int charX1 = Math.max(charX0 + 1, region - inset);
        int charY0 = Math.max(0, (int) (screenHeight * 0.04));
        int charY1 = Math.max(charY0 + 1, screenHeight);
        guiGraphics.skin(characterModel, PALETTE, 22f, 0f, 20f, 0f, charX0, charY0, charX1, charY1);
    }

    /**
     * 2D screen-space stand-in for the sky/sun/mountains/ground scene (this
     * class's own Javadoc) -- 26.1 has no generic-camera picture-in-picture
     * call this feature can submit non-biped geometry through.
     */
    private void renderSceneAsFlat2D(GuiGraphicsExtractor guiGraphics, int screenWidth, int screenHeight) {
        // Dusk sky gradient (top: deep violet, bottom: pale gold), roughly the
        // same stops the 26.2 version bakes into stacked 3D bands.
        guiGraphics.fillGradient(0, 0, screenWidth, screenHeight, 0xFF2E2A44, 0xFFC9A15A);

        // Sun glow + core, upper-right per design doc placement.
        int sunCx = (int) (screenWidth * 0.78);
        int sunCy = (int) (screenHeight * 0.22);
        int glowR = Math.max(60, screenHeight / 6);
        guiGraphics.fill(sunCx - glowR, sunCy - glowR, sunCx + glowR, sunCy + glowR, 0x662EE8C9);
        int coreR = glowR / 3;
        guiGraphics.fill(sunCx - coreR, sunCy - coreR, sunCx + coreR, sunCy + coreR, 0xFFF5E6A8);

        // Mountains: two semi-transparent silhouette bands along the bottom.
        int groundTop = (int) (screenHeight * 0.72);
        guiGraphics.fill(0, groundTop - 40, screenWidth, groundTop, 0x552B2A3A);
        guiGraphics.fill(0, groundTop - 20, screenWidth, groundTop, 0x77201F2C);

        // Ground: flat base + lighter top-edge highlight strip.
        guiGraphics.fill(0, groundTop, screenWidth, screenHeight, 0xFF2E4A2E);
        guiGraphics.fill(0, groundTop, screenWidth, groundTop + 3, 0xFF6F9A5A);
    }

    private void applyPose(CharacterPose pose) {
        characterRoot.y = (float) pose.bobOffset();
        rightArm.xRot = (float) pose.armSwingAngle();
        leftArm.xRot = (float) -pose.armSwingAngle();
        rightLeg.xRot = (float) -pose.legSwayAngle();
        leftLeg.xRot = (float) pose.legSwayAngle();
    }
}
