package de.lazuli.api.serverbrowser;

/**
 * Constructs a fresh {@link ServerBrowserSession} per open of
 * {@code ServerBrowserScreen} (spec Decision 2). Constructed once by each
 * platform module's composition root (wrapping {@code SteamAvailability} and
 * the already-resolved {@code SteamworksService.steamAppId()}, per
 * Architecture's "no new App-ID resolution path") and handed to the
 * Multiplayer-screen button injector.
 *
 * <p>Usage example (from a platform composition root):
 * <pre>{@code
 * ServerBrowserSessionFactory factory = new ServerBrowserSessionFactoryImpl(steamworksService, steamworksService::steamAppId);
 * new FabricServerBrowserButtonInjector(factory);
 * }</pre>
 */
public interface ServerBrowserSessionFactory {

    /**
     * @return a brand-new {@link ServerBrowserSession}, not yet started
     *         ({@link ServerBrowserSession#start} must still be called)
     */
    ServerBrowserSession newSession();
}
