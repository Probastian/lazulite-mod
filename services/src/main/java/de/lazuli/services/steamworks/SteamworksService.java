package de.lazuli.services.steamworks;

import com.codedisaster.steamworks.SteamAPI;
import com.codedisaster.steamworks.SteamException;

import de.lazuli.api.steamworks.SteamAvailability;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The shared, single owner of this process's Steamworks native-library load,
 * {@code SteamAPI} initialization, per-tick callback pump, and shutdown.
 *
 * <p>Built directly in {@code services}, not behind a feature-specific
 * wrapper, because four already-planned features (Cloud, Friends, Workshop,
 * matchmaking) all depend on the same one-per-process Steam client session -
 * see this feature's specification for the full "graduate-on-second-use"
 * exception rationale.
 *
 * <p>Ordinary object, not a Java-level singleton/registry: each platform
 * module's client composition root constructs exactly one instance via
 * {@link #create(long, Path, Consumer)} and threads it explicitly to
 * whatever registers {@link #pumpCallbacks()} on the client tick loop and
 * {@link #shutdown()} on client stop. The only process-wide singleton here is
 * Valve's own native {@code SteamAPI} - a property of the underlying C API,
 * not a Java design choice this class compounds with a registry of its own.
 *
 * <p>Every failure mode (Steam not running, no resolvable App ID, native
 * library load failure) is caught and converted into a logged warning plus
 * {@link #isSteamAvailable()} returning {@code false} - {@link #create}
 * never throws.
 *
 * <p>Usage example (from a platform module's client composition root):
 * <pre>{@code
 * Path nativeDir = FabricLoader.getInstance().getConfigDir()
 *         .resolve("lazuli").resolve("steamworks-natives");
 * SteamworksService steam = SteamworksService.create(480L, nativeDir, LOGGER::warn);
 *
 * ClientTickEvents.END_CLIENT_TICK.register(client -> steam.pumpCallbacks());
 * ClientLifecycleEvents.CLIENT_STOPPING.register(client -> steam.shutdown());
 * }</pre>
 */
public final class SteamworksService implements SteamAvailability {

    private final boolean available;
    private final long appId;
    private boolean shutDown;

    /**
     * Package-private constructor for a precomputed availability/App-ID
     * state, used by tests to exercise {@link #pumpCallbacks()}/
     * {@link #shutdown()} idempotency without a real native-library attempt.
     * Production code should use {@link #create(long, Path, Consumer)}.
     */
    SteamworksService(boolean available, long appId) {
        this.available = available;
        this.appId = appId;
    }

    /**
     * Attempts to load the Steamworks native libraries and initialize the
     * Steamworks API for {@code appId}. Never throws: any native-library
     * load failure, {@link SteamException}, or unexpected failure results in
     * an unavailable instance and a message passed to {@code warningLogger},
     * never an exception escaping this method.
     *
     * @param appId                 the App ID to report via
     *                              {@link #steamAppId()} for
     *                              diagnostics/logging (see
     *                              {@code SteamAppIdResolver} - this value is
     *                              not itself passed to {@code SteamAPI},
     *                              which resolves its App ID only via a
     *                              {@code steam_appid.txt} file or having
     *                              been launched by Steam)
     * @param nativeLibraryDirectory writable directory the native library
     *                               files are extracted into
     * @param warningLogger          receives a human-readable message for
     *                               every failure mode; never invoked with a
     *                               thrown exception
     * @return a new {@code SteamworksService}; check {@link #isSteamAvailable()}
     *         to see whether initialization actually succeeded
     */
    public static SteamworksService create(long appId, Path nativeLibraryDirectory, Consumer<String> warningLogger) {
        try {
            ClasspathSteamLibraryLoader loader = new ClasspathSteamLibraryLoader(nativeLibraryDirectory, warningLogger);
            boolean librariesLoaded = SteamAPI.loadLibraries(loader);
            if (!librariesLoaded) {
                warn(warningLogger, "Steamworks native libraries failed to load; Steam features unavailable.");
                return new SteamworksService(false, appId);
            }

            SteamAPI.InitResult result = SteamAPI.initEx();
            if (result != SteamAPI.InitResult.OK) {
                warn(warningLogger, "Steamworks API failed to initialize (" + result
                        + "); Steam features unavailable. Is Steam running, and is a valid "
                        + "steam_appid.txt present (App ID " + appId + ")?");
                return new SteamworksService(false, appId);
            }

            return new SteamworksService(true, appId);
        } catch (SteamException e) {
            warn(warningLogger, "Steamworks API threw during initialization; Steam features unavailable: " + e);
            return new SteamworksService(false, appId);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            // Defense in depth beyond the checked SteamException above: this
            // bootstrap must never throw out of Minecraft's client startup,
            // regardless of what an unexpected native-layer failure raises.
            warn(warningLogger, "Unexpected failure initializing Steamworks API; Steam features unavailable: " + e);
            return new SteamworksService(false, appId);
        }
    }

    /**
     * Pumps Steam's callback dispatch queue. Must be called once per client
     * tick, from the same thread {@code SteamAPI.init()} ran on (Minecraft's
     * client tick thread, in every platform module this project supports).
     * A no-op if this instance is unavailable.
     */
    public void pumpCallbacks() {
        if (available) {
            SteamAPI.runCallbacks();
        }
    }

    /**
     * Releases the Steamworks API session if it was ever successfully
     * initialized. Safe to call multiple times, or when never initialized -
     * idempotent, never throws.
     */
    public void shutdown() {
        if (available && !shutDown) {
            SteamAPI.shutdown();
            shutDown = true;
        }
    }

    @Override
    public boolean isSteamAvailable() {
        return available;
    }

    @Override
    public long steamAppId() {
        return appId;
    }

    private static void warn(Consumer<String> warningLogger, String message) {
        if (warningLogger != null) {
            warningLogger.accept(message);
        }
    }
}
