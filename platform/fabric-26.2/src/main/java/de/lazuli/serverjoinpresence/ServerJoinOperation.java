package de.lazuli.serverjoinpresence;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * The client-side "connect to this real multiplayer server" operation (spec
 * FR2.1/FR2.2, plan Decision 5) -- reuses vanilla's own
 * {@code ConnectScreen}/{@code Connection.connect(...)} flow exactly as a
 * normal saved-server/server-browser row click already does
 * ({@code de.lazuli.mainmenu.ServersPanel}'s own reused pattern), so no new
 * networking code, mixin, or disconnect-reason handling is needed -- a
 * failed connect surfaces exactly as any other failed Direct Connect attempt
 * already does in vanilla (FR2.3).
 *
 * <p>Invoked with no {@code Screen} owner available (a Steam callback
 * context, not a button click) -- {@code null} is passed for the owner
 * screen, mirroring {@code ConnectScreen.startConnecting}'s own tolerance for
 * a null "screen to return to on failure/cancel" (confirmed sufficient for
 * this feature's purposes; see implementation plan Risk 3).
 */
public final class ServerJoinOperation {

    public static final ServerJoinOperation INSTANCE = new ServerJoinOperation();

    private ServerJoinOperation() {
    }

    /**
     * Connects the local client to the given real multiplayer server, exactly
     * as if the player had entered this address via Direct Connect.
     *
     * @param host the server's hostname/IP
     * @param port the server's port
     */
    public void connectToServer(String host, int port) {
        Minecraft client = Minecraft.getInstance();
        String raw = host + ":" + port;
        ServerAddress address = ServerAddress.parseString(raw);
        ServerData serverData = new ServerData(host, raw, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(null, client, address, serverData, false, null);
    }
}
