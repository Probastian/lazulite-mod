package de.lazuli.common.mainmenu;

/**
 * Plain-data description of a single named cuboid ("bone") within the main
 * menu background's shared character + scenery mesh (see
 * {@link MainMenuMeshDefinitions}) -- deliberately free of any
 * {@code net.minecraft.*} import so this class compiles identically
 * regardless of Yarn/Mojmap mappings or Minecraft version, letting all three
 * platform modules translate the same numeric data into their own native
 * builder API calls.
 *
 * @param name                       this bone's unique name within the shared
 *                                   hierarchy (see {@link MainMenuPartNames}).
 * @param parentName                 the name of this bone's parent bone, or
 *                                    {@code null} if this bone is a top-level
 *                                    child of the mesh root.
 * @param pivotX                     this bone's pivot offset from its parent,
 *                                    X axis (model units).
 * @param pivotY                     this bone's pivot offset from its parent,
 *                                    Y axis (model units).
 * @param pivotZ                     this bone's pivot offset from its parent,
 *                                    Z axis (model units).
 * @param originX                    the cuboid box's own local origin, X axis
 *                                    (model units, relative to the pivot).
 * @param originY                    the cuboid box's own local origin, Y axis.
 * @param originZ                    the cuboid box's own local origin, Z axis.
 * @param sizeX                      the cuboid box's width (X axis).
 * @param sizeY                      the cuboid box's height (Y axis).
 * @param sizeZ                      the cuboid box's depth (Z axis).
 * @param uvCol                      the texture atlas cell column this box's
 *                                    UV mapping starts at (see
 *                                    {@link MainMenuMeshDefinitions#cellU(int)}).
 * @param uvRow                      the texture atlas cell row this box's UV
 *                                    mapping starts at (see
 *                                    {@link MainMenuMeshDefinitions#cellV(int)}).
 * @param isBipedRequiredPlaceholder {@code true} for bones that exist purely
 *                                   to satisfy {@code PlayerModel}/
 *                                   {@code PlayerEntityModel}'s required-child
 *                                   constructor lookup ({@code hat},
 *                                   {@code left_sleeve}, {@code right_sleeve},
 *                                   {@code left_pants}, {@code right_pants},
 *                                   {@code jacket}) and therefore carry a
 *                                   zero-size box (no visible geometry) on
 *                                   platforms that don't need real geometry
 *                                   for them.
 */
public record MeshCubeSpec(
        String name,
        String parentName,
        float pivotX,
        float pivotY,
        float pivotZ,
        float originX,
        float originY,
        float originZ,
        float sizeX,
        float sizeY,
        float sizeZ,
        int uvCol,
        int uvRow,
        boolean isBipedRequiredPlaceholder) {
}
