package de.lazuli.features.serverbrowser.services;

import de.lazuli.api.serverbrowser.ServerBrowserSession;
import de.lazuli.api.serverbrowser.ServerBrowserSessionFactory;
import de.lazuli.api.steamworks.SteamAvailability;

import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * {@link ServerBrowserSessionFactory} implementation constructed once by
 * each platform module's composition root (spec Decision 2). Constructs a
 * fresh {@link ServerBrowserQuery}/{@link ServerBrowserTableModel}/
 * {@link ServerBrowserSessionImpl} triple on every {@link #newSession()}
 * call -- never a shared/reused session across screen opens (FR1.4/FR3.7).
 *
 * <p>Usage example:
 * <pre>{@code
 * ServerBrowserSessionFactory factory = new ServerBrowserSessionFactoryImpl(
 *         steamworksService, () -> (int) steamworksService.steamAppId(), LOGGER::warn);
 * }</pre>
 */
public final class ServerBrowserSessionFactoryImpl implements ServerBrowserSessionFactory {

    private final SteamAvailability steamAvailability;
    private final IntSupplier appIdSupplier;
    private final Consumer<String> warningLogger;

    public ServerBrowserSessionFactoryImpl(SteamAvailability steamAvailability, IntSupplier appIdSupplier, Consumer<String> warningLogger) {
        this.steamAvailability = steamAvailability;
        this.appIdSupplier = appIdSupplier;
        this.warningLogger = warningLogger;
    }

    @Override
    public ServerBrowserSession newSession() {
        ServerBrowserQuery query = new ServerBrowserQuery(steamAvailability, appIdSupplier, warningLogger);
        return new ServerBrowserSessionImpl(query, new ServerBrowserTableModel());
    }
}
