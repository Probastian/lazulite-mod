package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.CharacterPose;
import de.lazuli.features.mainmenu.services.IdleCharacterAnimator;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * Owns the "Stonebound" main menu's continuously-rendered 3D background
 * (specification FR8): a fixed-camera dusk scene (sky/sun/mountains/ground)
 * plus an idle, hand-authored {@link ModelPart} character posed every frame
 * from {@link IdleCharacterAnimator}'s pure {@link CharacterPose} output.
 *
 * <h2>Spike finding (implementation plan Sequencing step 4/Decision 3)</h2>
 * Minecraft 26.2 replaced the old immediate-mode {@code Screen.render}
 * pipeline with a render-state-<em>extraction</em> model
 * ({@code Screen.extractRenderState(GuiGraphicsExtractor, ...)}): a
 * {@code Screen} no longer issues raw {@code PoseStack}/{@code VertexConsumer}
 * draw calls itself, it only records descriptor objects
 * ({@code GuiElementRenderState}s) into a {@code GuiRenderState}, which some
 * later, separate render pass turns into actual GPU draw calls. There is no
 * public entry point on {@link GuiGraphicsExtractor} to submit arbitrary
 * hand-rolled 3D vertex geometry with a fully custom camera the way
 * {@code Screen.render} used to allow (confirmed by direct enumeration of
 * every public method on this version's real {@code GuiGraphicsExtractor}
 * class, no cached decompiled source was available in this environment so
 * this enumeration was done via {@code javap} against the resolved,
 * remapped Minecraft jar).
 *
 * <p>The exit criterion for the spike is satisfied by
 * {@link GuiGraphicsExtractor#skin(Model.Simple, Identifier, float, float,
 * float, int, int, int, int, float)} -- confirmed (again via {@code javap} on
 * the resolved jar, including the backing
 * {@code GuiSkinRenderState} record's field names) to be exactly this: a
 * "picture-in-picture" extraction call that submits an arbitrary
 * {@link Model.Simple} (i.e. any hand-authored {@link ModelPart} hierarchy,
 * not just a player skin) to be rendered with its own real perspective
 * camera/lighting into an arbitrary screen-space rectangle
 * ({@code x0,y0,x1,y1}), with {@code rotationX}/{@code rotationY} orbiting the
 * camera and {@code scale} controlling zoom. This is the same mechanism
 * vanilla itself uses for e.g. the skin-customization screen's live player
 * preview and the book/banner-pattern previews -- reused here at
 * background-covering size instead of a small preview thumbnail.
 *
 * <p><strong>No manual matrix push/pop sequencing was needed or possible</strong>
 * to make this coexist with the rest of {@code MainMenuScreen}'s own 2D
 * {@code GuiGraphicsExtractor} calls in the same
 * {@code extractRenderState} frame: because this whole rendering model is
 * "record a descriptor, render it later," Mojang's own
 * {@code GuiRenderState}/{@code PictureInPictureRenderState} machinery is
 * entirely responsible for isolating this call's internal 3D camera/lighting
 * state from every other 2D draw call recorded in the same frame -- calling
 * {@link #render} at any point inside {@code extractRenderState} (before,
 * after, or interleaved with 2D {@code fill}/{@code text}/etc. calls) produced
 * no visual corruption or state bleed in manual testing, precisely because
 * the caller never touches a shared mutable matrix stack at all here (unlike
 * the old {@code PoseStack}-based render model). This is the concrete,
 * load-bearing finding this class's Javadoc exists to record for future
 * readers, per the implementation plan's own instruction.
 *
 * <p>The one real limitation this approach accepts: each {@code skin(...)}
 * call is its own independent picture-in-picture render pass (its own
 * implicit camera/lighting/depth buffer), so the background scene and the
 * character are two separate calls layered by draw order (scene first, then
 * character on top) rather than one single unified 3D scene graph sharing one
 * camera -- visually equivalent for this feature's fixed-camera, non-
 * interactive background, but worth knowing if a future change wants the
 * character to e.g. cast a shadow onto the ground plane.
 *
 * <p>Zero I/O/network/Steamworks dependency (FR8.8) -- this class only reads
 * an in-memory {@link IdleCharacterAnimator} and issues draw calls.
 */
public final class MainMenuBackgroundRenderer {

    private static final Identifier PALETTE = Identifier.fromNamespaceAndPath("lazuli", "textures/mainmenu/palette.png");
    private static final int TEX_SIZE = 384;
    private static final int CELL = 96;

    private final IdleCharacterAnimator animator = new IdleCharacterAnimator();
    private final ModelPart sceneRoot;
    private final ModelPart characterRoot;
    private final ModelPart head;
    private final ModelPart torso;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    private final Model.Simple sceneModel;
    private final Model.Simple characterModel;

    private final long startNanos = System.nanoTime();

    public MainMenuBackgroundRenderer() {
        this.sceneRoot = buildScene();
        this.sceneModel = new Model.Simple(sceneRoot, RenderTypes::entityTranslucent);

        MeshDefinition characterMesh = buildCharacterMesh();
        ModelPart root = LayerDefinition.create(characterMesh, TEX_SIZE, TEX_SIZE).bakeRoot();
        this.characterRoot = root;
        this.head = root.getChild("head");
        this.torso = root.getChild("torso");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg = root.getChild("left_leg");
        this.characterModel = new Model.Simple(root, RenderTypes::entityTranslucent);
    }

    private static int cellU(int col) {
        return col * CELL;
    }

    private static int cellV(int row) {
        return row * CELL;
    }

    /** Builds the static (built-once, per Performance) sky/sun/mountains/ground geometry (FR8.2-FR8.5). */
    private static ModelPart buildScene() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // Sky: four stacked flat bands approximating the dusk gradient's
        // stops (Design Tokens), from top (deep violet) to bottom (pale gold).
        int[][] skyCells = { { 0, 0 }, { 1, 0 }, { 2, 0 }, { 3, 0 } };
        float skyBandHeight = 18f;
        for (int i = 0; i < skyCells.length; i++) {
            root.addOrReplaceChild("sky_" + i,
                    CubeListBuilder.create().texOffs(cellU(skyCells[i][0]), cellV(skyCells[i][1]))
                            .addBox(-40f, 0f, -1f, 80f, skyBandHeight, 1f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                    PartPose.offset(0f, -40f + i * skyBandHeight, 0f));
        }

        // Sun glow (behind) + core, upper-right per design doc placement.
        root.addOrReplaceChild("sun_glow",
                CubeListBuilder.create().texOffs(cellU(1), cellV(1))
                        .addBox(-10f, -10f, -0.5f, 20f, 20f, 1f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(24f, -18f, -1.5f));
        root.addOrReplaceChild("sun_core",
                CubeListBuilder.create().texOffs(cellU(0), cellV(1))
                        .addBox(-6f, -6f, -0.5f, 12f, 12f, 1f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(24f, -18f, -1f));

        // Mountains: two semi-transparent jagged silhouette layers along the bottom.
        int[][] farPeaks = { { -36, 10 }, { -18, 16 }, { 0, 12 }, { 18, 18 }, { 36, 11 } };
        for (int i = 0; i < farPeaks.length; i++) {
            root.addOrReplaceChild("mountain_far_" + i,
                    CubeListBuilder.create().texOffs(cellU(2), cellV(1))
                            .addBox(-6f, -farPeaks[i][1], -0.5f, 12f, farPeaks[i][1], 1f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                    PartPose.offset(farPeaks[i][0], 14f, -0.8f));
        }
        int[][] nearPeaks = { { -30, 8 }, { -8, 13 }, { 14, 9 }, { 32, 14 } };
        for (int i = 0; i < nearPeaks.length; i++) {
            root.addOrReplaceChild("mountain_near_" + i,
                    CubeListBuilder.create().texOffs(cellU(3), cellV(1))
                            .addBox(-7f, -nearPeaks[i][1], -0.5f, 14f, nearPeaks[i][1], 1f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                    PartPose.offset(nearPeaks[i][0], 14f, -0.5f));
        }

        // Ground: flat plane, darker base + lighter top strip highlight.
        root.addOrReplaceChild("ground_base",
                CubeListBuilder.create().texOffs(cellU(0), cellV(2))
                        .addBox(-40f, 0f, -0.5f, 80f, 12f, 1f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(0f, 14f, 0f));
        root.addOrReplaceChild("ground_top",
                CubeListBuilder.create().texOffs(cellU(1), cellV(2))
                        .addBox(-40f, 0f, -0.4f, 80f, 3f, 1f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(0f, 14f, 0f));
        root.addOrReplaceChild("ground_highlight",
                CubeListBuilder.create().texOffs(cellU(2), cellV(2))
                        .addBox(-40f, 0f, -0.3f, 80f, 1f, 1f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(0f, 14f, 0f));

        return LayerDefinition.create(mesh, TEX_SIZE, TEX_SIZE).bakeRoot();
    }

    /** Hand-authored idle-character {@link ModelPart} hierarchy (FR8.6, vanilla-player-blockiness proportions). */
    private static MeshDefinition buildCharacterMesh() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("head",
                CubeListBuilder.create().texOffs(cellU(3), cellV(2))
                        .addBox(-4f, -8f, -4f, 8f, 8f, 8f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(0f, 0f, 0f));
        root.addOrReplaceChild("hair",
                CubeListBuilder.create().texOffs(cellU(0), cellV(3))
                        .addBox(-4.3f, -8.3f, -4.3f, 8.6f, 3.6f, 8.6f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(0f, 0f, 0f));
        root.addOrReplaceChild("torso",
                CubeListBuilder.create().texOffs(cellU(1), cellV(3))
                        .addBox(-4f, 0f, -2f, 8f, 12f, 4f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(0f, 0f, 0f));
        root.addOrReplaceChild("right_arm",
                CubeListBuilder.create().texOffs(cellU(3), cellV(2))
                        .addBox(-2f, -2f, -2f, 4f, 12f, 4f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(-6f, 2f, 0f));
        root.addOrReplaceChild("left_arm",
                CubeListBuilder.create().texOffs(cellU(3), cellV(2))
                        .addBox(-2f, -2f, -2f, 4f, 12f, 4f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(6f, 2f, 0f));
        root.addOrReplaceChild("right_leg",
                CubeListBuilder.create().texOffs(cellU(2), cellV(3))
                        .addBox(-2f, 0f, -2f, 4f, 12f, 4f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(-2f, 12f, 0f));
        root.addOrReplaceChild("left_leg",
                CubeListBuilder.create().texOffs(cellU(2), cellV(3))
                        .addBox(-2f, 0f, -2f, 4f, 12f, 4f, CubeDeformation.NONE, TEX_SIZE, TEX_SIZE),
                PartPose.offset(2f, 12f, 0f));
        return mesh;
    }

    /**
     * Renders the continuously-updating background (FR8.1/FR1.4) -- called
     * every frame from {@link MainMenuScreen#extractRenderState}, regardless
     * of tab state.
     */
    public void render(GuiGraphicsExtractor guiGraphics, int screenWidth, int screenHeight) {
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

        // Scene: fixed camera, no rotation, fills the whole screen (FR8.7 -- no dynamic time-of-day/camera movement).
        // skin(model, texture, scale, rotationX, rotationY, pivotY, x0, y0, x1, y1) -- confirmed param order via
        // javap against GuiGraphicsExtractor.class (differs from GuiSkinRenderState's own record field order).
        guiGraphics.skin(sceneModel, PALETTE, 30f, 0f, 0f, 0f, 0, 0, screenWidth, screenHeight);

        // Character: posed per-frame from the pure animation math (FR8.6), framed bottom-left per design doc.
        CharacterPose pose = animator.poseAt(elapsedSeconds);
        applyPose(pose);
        int charSize = Math.max(160, screenHeight / 2);
        int charX0 = (int) (screenWidth * 0.08);
        int charY0 = screenHeight - charSize;
        guiGraphics.skin(characterModel, PALETTE, 22f, 0f, 20f, 0f, charX0, charY0, charX0 + charSize, screenHeight);
    }

    private void applyPose(CharacterPose pose) {
        characterRoot.y = (float) pose.bobOffset();
        rightArm.xRot = (float) pose.armSwingAngle();
        leftArm.xRot = (float) -pose.armSwingAngle();
        rightLeg.xRot = (float) -pose.legSwayAngle();
        leftLeg.xRot = (float) pose.legSwayAngle();
    }
}
