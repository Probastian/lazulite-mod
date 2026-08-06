package de.lazuli.services.steamworks;

import com.codedisaster.steamworks.SteamAPI;
import com.codedisaster.steamworks.SteamException;

import de.lazuli.api.steamworks.SteamAvailability;

import java.io.IOException;
import java.nio.file.Files;
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
 * SteamworksService steam = SteamworksService.create(5052800L, nativeDir, LOGGER::warn);
 *
 * ClientTickEvents.END_CLIENT_TICK.register(client -> steam.pumpCallbacks());
 * ClientLifecycleEvents.CLIENT_STOPPING.register(client -> steam.shutdown());
 * }</pre>
 *
 * <p>{@link #shutdown()} deliberately never calls the native
 * {@code SteamAPI.shutdown()} - see that method's Javadoc.
 */
public final class SteamworksService implements SteamAvailability {

    private final boolean available;
    private final long appId;
    private final Consumer<String> warningLogger;
    private boolean shutDown;
    private boolean pumpFailureLogged;

    /**
     * Package-private constructor for a precomputed availability/App-ID
     * state, used by tests to exercise {@link #pumpCallbacks()}/
     * {@link #shutdown()} idempotency without a real native-library attempt.
     * Production code should use {@link #create(long, Path, Consumer)}.
     */
    SteamworksService(boolean available, long appId) {
        this(available, appId, null);
    }

    private SteamworksService(boolean available, long appId, Consumer<String> warningLogger) {
        this.available = available;
        this.appId = appId;
        this.warningLogger = warningLogger;
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
            writeSteamAppIdFile(appId, warningLogger);

            ClasspathSteamLibraryLoader loader = new ClasspathSteamLibraryLoader(nativeLibraryDirectory, warningLogger);
            boolean librariesLoaded = SteamAPI.loadLibraries(loader);
            if (!librariesLoaded) {
                warn(warningLogger, "Steamworks native libraries failed to load; Steam features unavailable.");
                return new SteamworksService(false, appId, warningLogger);
            }

            SteamAPI.InitResult result = SteamAPI.initEx();
            if (result != SteamAPI.InitResult.OK) {
                warn(warningLogger, "Steamworks API failed to initialize (" + result
                        + "); Steam features unavailable. Is Steam running, and is a valid "
                        + "steam_appid.txt present (App ID " + appId + ")?");
                return new SteamworksService(false, appId, warningLogger);
            }

            return new SteamworksService(true, appId, warningLogger);
        } catch (SteamException e) {
            warn(warningLogger, "Steamworks API threw during initialization; Steam features unavailable: " + e);
            return new SteamworksService(false, appId, warningLogger);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            // Defense in depth beyond the checked SteamException above: this
            // bootstrap must never throw out of Minecraft's client startup,
            // regardless of what an unexpected native-layer failure raises.
            warn(warningLogger, "Unexpected failure initializing Steamworks API; Steam features unavailable: " + e);
            return new SteamworksService(false, appId, warningLogger);
        }
    }

    /**
     * Pumps Steam's callback dispatch queue. Must be called once per client
     * tick, from the same thread {@code SteamAPI.init()} ran on (Minecraft's
     * client tick thread, in every platform module this project supports).
     * A no-op if this instance is unavailable.
     *
     * <p>Deliberately never lets a {@link SteamException} (or any other
     * {@link RuntimeException}) escape to the caller. In production, a
     * client-crashing {@code SteamException} ("Couldn't retrieve callback
     * method.") was observed here while a Steam Cloud world-restore's
     * chunked {@code FileReadAsync}/{@code FileReadAsyncComplete} loop was in
     * flight - a native steamworks4j-fork-layer failure resolving the JNI
     * callback method for that particular pending call, not a
     * threading/reentrancy bug in this project's call sites (the Steamworks
     * call that issues the read and this per-tick callback pump both run on
     * the same client tick thread, sequentially, per this project's
     * single-thread Steamworks convention - never nested/reentrant with each
     * other). Because Valve's C API gives no way to cancel or requery a
     * single broken pending call, a callback that fails to dispatch this way
     * is dropped for good; any in-flight caller waiting on it would stop
     * receiving progress and never see a terminal outcome for that one call,
     * rather than the whole client crashing.
     *
     * <p>{@code SteamRemoteStorageWorldArchiveStore.beginAsyncRead} (the cloud
     * world-restore read path that originally triggered this) has since been
     * switched to a single synchronous {@code FileRead} call and no longer
     * depends on this pump at all, so it is no longer exposed to this failure
     * mode. This catch remains in place as defense-in-depth for any other
     * consumer of this fork's async/callback surface (e.g. {@code
     * FileWriteAsync}, UGC downloads) that has not been given the same
     * treatment - such a consumer would still see the "stops receiving
     * progress, no terminal outcome" stall described above rather than a
     * crash, and would need either the same synchronous-call treatment or a
     * tick-thread timeout/watchdog if no synchronous alternative exists for
     * it.
     */
    public void pumpCallbacks() {
        if (!available) {
            return;
        }
        try {
            SteamAPI.runCallbacks();
        } catch (Exception e) {
            // SteamAPI.runCallbacks() does not declare "throws SteamException"
            // (confirmed against the fork's compiled bytecode), so this must
            // catch the unchecked-from-javac's-perspective java.lang.Exception
            // rather than SteamException itself - the JVM does not enforce
            // checked-exception declarations for native methods, so the
            // observed crash (a SteamException actually thrown from this
            // native call at runtime) would make a narrower catch clause
            // "unreachable code" at compile time despite being reachable at
            // runtime.
            if (!pumpFailureLogged) {
                pumpFailureLogged = true;
                warn(warningLogger, "Steamworks callback pump threw and was suppressed to avoid crashing the "
                        + "client (this will keep happening every tick, and any Steam call still waiting on a "
                        + "callback may never complete): " + e);
            }
        }
    }

    /**
     * Marks this session as shut down. Deliberately does NOT call
     * {@code SteamAPI.shutdown()}: that native call races with steamworks4j's
     * own background callback thread during JVM exit and reliably produces an
     * {@code EXCEPTION_ACCESS_VIOLATION} crash in {@code steamworks4j64.dll}
     * on client stop (a known upstream issue, not specific to this mod's
     * usage). The process is already tearing down at this point, so the OS
     * reclaims the native session regardless. Safe to call multiple times, or
     * when never initialized - idempotent, never throws.
     */
    public void shutdown() {
        if (available && !shutDown) {
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

    /**
     * Writes {@code steam_appid.txt} into the process's working directory
     * (the only place {@code SteamAPI_Init} looks for it) so that a release
     * jar launched directly - not through the Steam client, and without the
     * dev-only {@code generateSteamAppId} Gradle task - can still initialize.
     * Never overwrites a file already there (e.g. one Steam itself wrote, or
     * one a player placed deliberately), and never throws: a failure here
     * just means {@code SteamAPI.initEx()} goes on to fail the same way it
     * always did without this file.
     */
    private static void writeSteamAppIdFile(long appId, Consumer<String> warningLogger) {
        try {
            Path appIdFile = Path.of("steam_appid.txt");
            if (Files.notExists(appIdFile)) {
                Files.writeString(appIdFile, Long.toString(appId) + System.lineSeparator());
            }
        } catch (IOException | RuntimeException e) {
            warn(warningLogger, "Failed to write steam_appid.txt for App ID " + appId + ": " + e);
        }
    }

    private static void warn(Consumer<String> warningLogger, String message) {
        if (warningLogger != null) {
            warningLogger.accept(message);
        }
    }
}
