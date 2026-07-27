package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.CharacterPose;
import de.lazuli.common.mainmenu.MainMenuMeshDefinitions;
import de.lazuli.common.mainmenu.MainMenuPartNames;
import de.lazuli.common.mainmenu.MeshCubeSpec;
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
 * <h2>Unified mesh module (unified-mainmenu-background)</h2>
 * {@code PlayerEntityModel}'s constructor (via {@code BipedEntityModel}) only
 * checks that its required named children (see {@link MainMenuPartNames})
 * <em>exist</em> -- it does not restrict what else may be attached to the
 * same {@code ModelPart} root (confirmed via {@code javap} bytecode trace of
 * {@code ModelPart.render}'s generic children-map traversal, see
 * {@code docs/specs/unified-mainmenu-background.md}'s "Critical assumption
 * under test" section). This class therefore now builds <strong>one</strong>
 * shared {@link ModelData} combining {@code :common}'s
 * {@link MainMenuMeshDefinitions#SCENERY_PARTS} (attached as extra top-level
 * children, rendered generically alongside the character) and
 * {@code CHARACTER_PARTS} (satisfying {@link PlayerEntityModel}'s required
 * names), wraps it in one real {@link PlayerEntityModel}, and submits it via
 * a single {@code addPlayerSkin(...)} call per frame -- replacing the
 * previous split of a real 3D character plus flat 2D {@code fill}/
 * {@code fillGradient} scenery ({@code renderSceneAsFlat2D}, now removed).
 * Scenery bones are static (no per-frame pose, FR8.7); only the character's
 * named parts are mutated each frame via {@link #applyPose}.
 *
 * <p>Zero I/O/network/Steamworks dependency (FR8.8) -- this class only reads
 * an in-memory {@link IdleCharacterAnimator} and issues draw calls.
 */
public final class MainMenuBackgroundRenderer {

    private static final Identifier PALETTE = Identifier.of("lazuli", "textures/mainmenu/palette.png");
    private static final int TEX_SIZE = MainMenuMeshDefinitions.TEX_SIZE;

    private final IdleCharacterAnimator animator = new IdleCharacterAnimator();
    private final ModelPart characterRoot;
    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    private final PlayerEntityModel characterModel;

    private final long startNanos = System.nanoTime();

    public MainMenuBackgroundRenderer() {
        ModelData modelData = buildModelData();
        ModelPart root = TexturedModelData.of(modelData, TEX_SIZE, TEX_SIZE).createModel();
        this.characterRoot = root;
        this.head = root.getChild(MainMenuPartNames.HEAD);
        this.body = root.getChild(MainMenuPartNames.BODY);
        this.rightArm = root.getChild(MainMenuPartNames.RIGHT_ARM);
        this.leftArm = root.getChild(MainMenuPartNames.LEFT_ARM);
        this.rightLeg = root.getChild(MainMenuPartNames.RIGHT_LEG);
        this.leftLeg = root.getChild(MainMenuPartNames.LEFT_LEG);
        this.characterModel = new PlayerEntityModel(root, false);
    }

    /**
     * Builds one shared {@link ModelData} combining the character bones
     * (satisfying {@link PlayerEntityModel}'s required names) and the scenery
     * bones (extra top-level children, rendered generically) from
     * {@code :common}'s canonical geometry.
     */
    private static ModelData buildModelData() {
        ModelData modelData = new ModelData();
        ModelPartData root = modelData.getRoot();

        java.util.Map<String, ModelPartData> byName = new java.util.HashMap<>();
        for (MeshCubeSpec spec : MainMenuMeshDefinitions.CHARACTER_PARTS) {
            ModelPartData parent = spec.parentName() == null ? root : byName.get(spec.parentName());
            ModelPartData added = parent.addChild(spec.name(), toModelPartBuilder(spec), toModelTransform(spec));
            byName.put(spec.name(), added);
        }
        for (MeshCubeSpec spec : MainMenuMeshDefinitions.SCENERY_PARTS) {
            root.addChild(spec.name(), toModelPartBuilder(spec), toModelTransform(spec));
        }
        return modelData;
    }

    private static ModelPartBuilder toModelPartBuilder(MeshCubeSpec spec) {
        return ModelPartBuilder.create()
                .uv(MainMenuMeshDefinitions.cellU(spec.uvCol()), MainMenuMeshDefinitions.cellV(spec.uvRow()))
                .cuboid(spec.originX(), spec.originY(), spec.originZ(),
                        spec.sizeX(), spec.sizeY(), spec.sizeZ(), Dilation.NONE);
    }

    private static ModelTransform toModelTransform(MeshCubeSpec spec) {
        if (spec.pivotX() == 0f && spec.pivotY() == 0f && spec.pivotZ() == 0f) {
            return ModelTransform.NONE;
        }
        return ModelTransform.origin(spec.pivotX(), spec.pivotY(), spec.pivotZ());
    }

    /**
     * Renders the continuously-updating background (FR8.1/FR1.4) -- called
     * every frame from {@link MainMenuScreen#render}, regardless of tab
     * state.
     *
     * <h2>Camera re-tuning for the merged scene+character single call</h2>
     * Previously this version's character-only {@code addPlayerSkin(...)}
     * call used {@code scale=22f, rotationY=20f, pivotY=0f}. Now that the same
     * call also carries the scenery bones (model-space vertical extent
     * roughly {@code y=-40..32}, identical to 26.2's own scene geometry since
     * both read from the same {@code :common} data), a single camera has to
     * frame both without clipping the sky/ground or shrinking the character
     * to a speck. Starting point per the plan's Risk 1 mitigation: widen the
     * field of view (lower {@code scale}, matching 26.2's own scene-call
     * tuning of {@code 18f}) and re-aim {@code pivotY} toward the combined
     * mesh's vertical center (roughly {@code -4}, splitting the difference
     * between the scene's own {@code -6} pivot and the character's own
     * {@code 0} pivot) so the horizon reads mid-region rather than
     * clipped/pinned to an edge -- exact fine-tuning is left as a follow-up
     * in-game pass per the plan's Test Strategy/Acceptance Criteria.
     *
     * @param leftOffset    Batch-2 FR-BB1.2: the left-docked sidebar's own
     *                      collapsed width + margin -- the reserved region
     *                      now starts here instead of screen x = 0.
     * @param reservedWidth the post-launch-fixes spec's reserved left-third
     *                      background+character region's pixel width
     *                      (FX6.1/FX7.1)
     */
    public void render(DrawContext context, int screenWidth, int screenHeight, int leftOffset, int reservedWidth) {
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
        // inset (>= 4) made charX1 = region - inset go negative -- addPlayerSkin()
        // then handed the renderer a negative-width destination rect, which the
        // GPU rejects with GL_INVALID_VALUE and crashes the game. Cap the inset
        // at region/2 - 1 so charX1 is always > charX0 (>= 1px wide) regardless
        // of how small region gets.
        int inset = Math.min(Math.max(4, region / 10), Math.max(0, region / 2 - 1));
        // Batch-2 FR-BB1.2: the region now starts at leftOffset (past the
        // left-docked sidebar), not at screen x = 0.
        int charX0 = leftOffset + inset;
        int charX1 = Math.max(charX0 + 1, leftOffset + region - inset);
        int charY0 = Math.max(0, (int) (screenHeight * 0.04));
        int charY1 = Math.max(charY0 + 1, screenHeight);
        context.addPlayerSkin(characterModel, PALETTE, 18f, 0f, 20f, -4f, charX0, charY0, charX1, charY1);
    }

    private void applyPose(CharacterPose pose) {
        characterRoot.originY = (float) pose.bobOffset();
        rightArm.pitch = (float) pose.armSwingAngle();
        leftArm.pitch = (float) -pose.armSwingAngle();
        rightLeg.pitch = (float) -pose.legSwayAngle();
        leftLeg.pitch = (float) pose.legSwayAngle();
    }
}
