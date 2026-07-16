package de.lazuli;

import de.lazuli.services.steamworks.SteamworksService;

/**
 * A narrow, composition-root-scoped hand-off publishing the single
 * {@link SteamworksService} instance {@link SteamworksClientInitializer}
 * constructs, so a second client entrypoint in this same module
 * ({@link SteamCloudSyncClientInitializer}) can obtain the same instance
 * rather than constructing a second, competing one (double-{@code init()}ing
 * the Steamworks API).
 *
 * <p>Correctness depends only on {@code SteamworksClientInitializer}
 * appearing before {@code SteamCloudSyncClientInitializer} in this module's
 * {@code fabric.mod.json} {@code "client"} entrypoint array -- Fabric Loader
 * invokes a mod's own same-type entrypoints in declared array order, the
 * exact mechanism this repo already relies on for
 * {@code HelloWorldMainMenuClientInitializer} before
 * {@code SteamworksClientInitializer}.
 *
 * <p>This is a deliberate, narrow exception to this repo's own "constructor
 * injection over globals" guidance (see
 * {@code features/steam-cloud-sync/implementation-plan.md}'s Decision 1 and
 * Risk 7): a static field is real, if narrow, global state, accepted here as
 * scoped to exactly one composition-root-to-composition-root hand-off within
 * this single platform module. No {@code Feature}, {@code Services}, or
 * {@code api} code ever references this class -- only the two platform
 * composition-root classes in this module do.
 *
 * <p>Usage example (from {@code SteamworksClientInitializer}, right after
 * construction):
 * <pre>{@code
 * SteamworksService steamworksService = SteamworksService.create(appId, nativeDir, LazuliMod.LOGGER::warn);
 * SteamworksServiceHandoff.publish(steamworksService);
 * }</pre>
 *
 * <p>Usage example (from {@code SteamCloudSyncClientInitializer}):
 * <pre>{@code
 * SteamworksService steamworksService = SteamworksServiceHandoff.require();
 * }</pre>
 */
public final class SteamworksServiceHandoff {

    private static volatile SteamworksService instance;

    private SteamworksServiceHandoff() {
    }

    /**
     * Publishes {@code service} for {@link #require()} to later retrieve.
     * Called exactly once, by {@link SteamworksClientInitializer}.
     *
     * @param service the constructed {@link SteamworksService}
     */
    public static void publish(SteamworksService service) {
        instance = service;
    }

    /**
     * @return the previously-published {@link SteamworksService}
     * @throws IllegalStateException if called before {@link #publish(SteamworksService)}
     *                                -- check this module's {@code fabric.mod.json}
     *                                {@code "client"} entrypoint order
     */
    public static SteamworksService require() {
        SteamworksService published = instance;
        if (published == null) {
            throw new IllegalStateException(
                    "SteamworksServiceHandoff.require() called before SteamworksClientInitializer "
                            + "published a SteamworksService -- check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
