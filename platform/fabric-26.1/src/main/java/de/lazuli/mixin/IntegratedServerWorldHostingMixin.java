package de.lazuli.mixin;

import de.lazuli.LazuliMod;
import de.lazuli.worldhosting.WorldHostingHookHolder;

import net.minecraft.client.server.IntegratedServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On every singleplayer world load (FR1.1/FR1.2), bootstraps Minecraft's own
 * Netty {@code childHandler}/{@code EventLoopGroup} pipeline exactly as "Open to
 * LAN" would -- bound to an ephemeral, never-advertised local port (0) -- then
 * starts the Steam P2P listener via {@link WorldHostingHookHolder}. Also
 * overrides {@code isPublished()} to report {@code true} whenever a Steam
 * session has connected peers (FR1.4), so the integrated server does not pause
 * on focus loss. Ported from the prototype's {@code IntegratedServerMixin}.
 *
 * <p>Target confirmed via {@code javap} against this module's resolved 26.2 jar:
 * {@code net.minecraft.client.server.IntegratedServer#initServer()Z} (protected)
 * and {@code #isPublished()Z} (public); the ephemeral bind uses
 * {@code MinecraftServer#getConnection()} &rarr;
 * {@code ServerConnectionListener#startTcpServerListener(InetAddress, int)}.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerWorldHostingMixin {

    @Inject(method = "initServer", at = @At("RETURN"))
    private void lazuli$onInitServer(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (WorldHostingHookHolder.isEnabled()) {
            IntegratedServer self = (IntegratedServer) (Object) this;
            try {
                // Port 0 = OS-assigned ephemeral local port. Never advertised.
                self.getConnection().startTcpServerListener(null, 0);
            } catch (Exception e) {
                LazuliMod.LOGGER.error("[WorldHosting] Failed to bootstrap Steam pipeline: {}", e.getMessage());
            }
        }
        WorldHostingHookHolder.onWorldLoad();
    }

    @Inject(method = "isPublished", at = @At("RETURN"), cancellable = true)
    private void lazuli$isPublished(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return; // already published via LAN
        }
        if (WorldHostingHookHolder.hasConnectedPeers()) {
            cir.setReturnValue(true);
        }
    }
}
