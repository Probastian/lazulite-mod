package de.lazuli.api.steamworks;

/**
 * Stable, steamworks4j-free contract for asking whether a live Steamworks API
 * session is available in this process.
 *
 * <p><strong>Unlike {@code de.lazuli.api.mainmenu.MainMenuHook}, this is not a
 * "Platform API + Version Adapter" pair.</strong> steamworks4j is a plain Java
 * library with no {@code net.minecraft.*}/{@code net.fabricmc.*} dependency,
 * so its behavior does not vary across this project's supported Minecraft
 * versions - there is exactly one implementation of this interface
 * ({@code de.lazuli.services.steamworks.SteamworksService}), not one
 * per-platform-module Version Adapter. A future reader should not go looking
 * for a {@code FabricSteamworksHook} per platform module: there isn't one,
 * and there shouldn't be. This type lives in {@code api} purely because
 * {@code api} already guarantees "zero dependencies, safe for any layer to
 * reference," not because of the multi-version adapter pattern.
 *
 * <p>Availability is resolved once, early, by each platform module's client
 * composition root, and never changes afterward for the lifetime of the
 * process (there is no "Steam became available later" notification in this
 * version of the contract).
 *
 * <p>Usage example (from feature business logic, holding a
 * constructor-injected {@code SteamAvailability}):
 * <pre>{@code
 * SteamAvailability steam = ...; // supplied by the platform composition root
 * if (steam.isSteamAvailable()) {
 *     // safe to construct steamworks4j interface objects (SteamFriends, etc.)
 *     // via the concrete de.lazuli.services.steamworks.SteamworksService
 * } else {
 *     // degrade gracefully; Steam is not running/available
 * }
 * }</pre>
 */
public interface SteamAvailability {

    /**
     * @return {@code true} if the Steamworks API was successfully
     *         initialized for this process and is safe to use; {@code false}
     *         in every failure mode (Steam not running, native library load
     *         failure, no resolvable App ID, etc).
     */
    boolean isSteamAvailable();

    /**
     * @return the App ID this process attempted to initialize the Steamworks
     *         API for, as a raw primitive (not a steamworks4j type), for
     *         diagnostics/logging. Meaningful regardless of
     *         {@link #isSteamAvailable()}'s result.
     */
    long steamAppId();
}
