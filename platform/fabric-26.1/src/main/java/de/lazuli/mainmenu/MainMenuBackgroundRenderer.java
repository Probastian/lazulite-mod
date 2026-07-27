package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.CharacterPose;
import de.lazuli.common.mainmenu.MainMenuMeshDefinitions;
import de.lazuli.common.mainmenu.MainMenuPartNames;
import de.lazuli.common.mainmenu.MeshCubeSpec;
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
 * <h2>Unified mesh module (unified-mainmenu-background)</h2>
 * {@code PlayerModel}'s constructor only checks that its required named
 * children (see {@link MainMenuPartNames}) <em>exist</em> -- it does not
 * restrict what else may be attached to the same {@code ModelPart} root
 * (confirmed via {@code javap} bytecode trace of {@code ModelPart.render}'s
 * generic {@code children.values()} traversal, see
 * {@code docs/specs/unified-mainmenu-background.md}'s "Critical assumption
 * under test" section). This class therefore now builds <strong>one</strong>
 * shared {@link MeshDefinition} combining {@code :common}'s
 * {@link MainMenuMeshDefinitions#SCENERY_PARTS} (attached as extra top-level
 * children, rendered generically alongside the character) and
 * {@code CHARACTER_PARTS} (satisfying {@link PlayerModel}'s required names),
 * wraps it in one real {@link PlayerModel}, and submits it via a single
 * {@code skin(PlayerModel, ...)} call per frame -- replacing the previous
 * split of a real 3D character plus flat 2D {@code fill}/{@code fillGradient}
 * scenery. Scenery bones are static (no per-frame pose, FR8.7); only the
 * character's named parts are mutated each frame via {@link #applyPose}.
 *
 * <p>Zero I/O/network/Steamworks dependency (FR8.8) -- this class only reads
 * an in-memory {@link IdleCharacterAnimator} and issues draw calls.
 */
public final class MainMenuBackgroundRenderer {

    private static final Identifier PALETTE = Identifier.fromNamespaceAndPath("lazuli", "textures/mainmenu/palette.png");
    private static final int TEX_SIZE = MainMenuMeshDefinitions.TEX_SIZE;

    private final IdleCharacterAnimator animator = new IdleCharacterAnimator();
    private final ModelPart characterRoot;
    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    private final PlayerModel characterModel;

    private final long startNanos = System.nanoTime();

    public MainMenuBackgroundRenderer() {
        MeshDefinition mesh = buildMesh();
        ModelPart root = LayerDefinition.create(mesh, TEX_SIZE, TEX_SIZE).bakeRoot();
        this.characterRoot = root;
        this.head = root.getChild(MainMenuPartNames.HEAD);
        this.body = root.getChild(MainMenuPartNames.BODY);
        this.rightArm = root.getChild(MainMenuPartNames.RIGHT_ARM);
        this.leftArm = root.getChild(MainMenuPartNames.LEFT_ARM);
        this.rightLeg = root.getChild(MainMenuPartNames.RIGHT_LEG);
        this.leftLeg = root.getChild(MainMenuPartNames.LEFT_LEG);
        this.characterModel = new PlayerModel(root, false);
    }

    /**
     * Builds one shared {@link MeshDefinition} combining the character bones
     * (satisfying {@link PlayerModel}'s required names) and the scenery bones
     * (extra top-level children, rendered generically) from {@code :common}'s
     * canonical geometry.
     */
    private static MeshDefinition buildMesh() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        java.util.Map<String, PartDefinition> byName = new java.util.HashMap<>();
        for (MeshCubeSpec spec : MainMenuMeshDefinitions.CHARACTER_PARTS) {
            PartDefinition parent = spec.parentName() == null ? root : byName.get(spec.parentName());
            PartDefinition added = parent.addOrReplaceChild(spec.name(), toCubeListBuilder(spec), toPartPose(spec));
            byName.put(spec.name(), added);
        }
        for (MeshCubeSpec spec : MainMenuMeshDefinitions.SCENERY_PARTS) {
            root.addOrReplaceChild(spec.name(), toCubeListBuilder(spec), toPartPose(spec));
        }
        return mesh;
    }

    private static CubeListBuilder toCubeListBuilder(MeshCubeSpec spec) {
        return CubeListBuilder.create()
                .texOffs(MainMenuMeshDefinitions.cellU(spec.uvCol()), MainMenuMeshDefinitions.cellV(spec.uvRow()))
                .addBox(spec.originX(), spec.originY(), spec.originZ(),
                        spec.sizeX(), spec.sizeY(), spec.sizeZ(),
                        CubeDeformation.NONE, TEX_SIZE, TEX_SIZE);
    }

    private static PartPose toPartPose(MeshCubeSpec spec) {
        return PartPose.offset(spec.pivotX(), spec.pivotY(), spec.pivotZ());
    }

    /**
     * Renders the continuously-updating background (FR8.1/FR1.4) -- called
     * every frame from {@link MainMenuScreen#extractRenderState}, regardless
     * of tab state.
     *
     * <h2>Camera re-tuning for the merged scene+character single call</h2>
     * Previously this version's character-only {@code skin(...)} call used
     * {@code scale=22f, rotationY=20f, pivotY=0f}. Now that the same call also
     * carries the scenery bones (model-space vertical extent roughly
     * {@code y=-40..32}, identical to 26.2's own scene geometry since both
     * read from the same {@code :common} data), a single camera has to frame
     * both without clipping the sky/ground or shrinking the character to a
     * speck. Starting point per the plan's Risk 1 mitigation: widen the field
     * of view (lower {@code scale}, matching 26.2's own scene-call tuning of
     * {@code 18f}) and re-aim {@code pivotY} toward the combined mesh's
     * vertical center (roughly {@code -4}, splitting the difference between
     * the scene's own {@code -6} pivot and the character's own {@code 0}
     * pivot) so the horizon reads mid-region rather than clipped/pinned to an
     * edge -- exact fine-tuning is left as a follow-up in-game pass per the
     * plan's Test Strategy/Acceptance Criteria.
     *
     * @param leftOffset    Batch-2 FR-BB1.2: the left-docked sidebar's own
     *                      collapsed width + margin -- the reserved region
     *                      now starts here instead of screen x = 0.
     * @param reservedWidth the post-launch-fixes spec's reserved left-third
     *                      background+character region's pixel width
     *                      (FX6.1/FX7.1)
     */
    public void render(GuiGraphicsExtractor guiGraphics, int screenWidth, int screenHeight, int leftOffset, int reservedWidth) {
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

        CharacterPose pose = animator.poseAt(elapsedSeconds);
        applyPose(pose);

        // FX7: sized/positioned from the reserved left-third region's own
        // actual pixel bounds, not screenWidth*0.08, grounded at the bottom
        // and inset from the region's edges so it reads as "bottom-left of
        // the region." Now the destination rect also covers the scenery,
        // since scene + character share one camera/call.
        int region = Math.max(1, reservedWidth);
        // Bug fix: at small logical GUI widths (high GUI scale / narrow window)
        // reservedWidth() can clamp down to a few pixels or 0, and the old fixed
        // inset (>= 4) made charX1 = region - inset go negative -- skin() then
        // handed the renderer a negative-width destination rect, which the GPU
        // rejects with GL_INVALID_VALUE and crashes the game. Cap the inset at
        // region/2 - 1 so charX1 is always > charX0 (>= 1px wide) regardless of
        // how small region gets.
        int inset = Math.min(Math.max(4, region / 10), Math.max(0, region / 2 - 1));
        // Batch-2 FR-BB1.2: the region now starts at leftOffset (past the
        // left-docked sidebar), not at screen x = 0.
        int charX0 = leftOffset + inset;
        int charX1 = Math.max(charX0 + 1, leftOffset + region - inset);
        int charY0 = Math.max(0, (int) (screenHeight * 0.04));
        int charY1 = Math.max(charY0 + 1, screenHeight);
        guiGraphics.skin(characterModel, PALETTE, 18f, 0f, 20f, -4f, charX0, charY0, charX1, charY1);
    }

    private void applyPose(CharacterPose pose) {
        characterRoot.y = (float) pose.bobOffset();
        rightArm.xRot = (float) pose.armSwingAngle();
        leftArm.xRot = (float) -pose.armSwingAngle();
        rightLeg.xRot = (float) -pose.legSwayAngle();
        leftLeg.xRot = (float) pose.legSwayAngle();
    }
}
