package de.lazuli.mixin;

import de.lazuli.LazuliMod;
import de.lazuli.worldhosting.WorldHostingHookHolder;

import net.minecraft.server.integrated.IntegratedServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.11 (Yarn-mapped) variant of the world-load host bootstrap + pause
 * suppression (FR1.1/FR1.2/FR1.4). Ported from the prototype's
 * {@code IntegratedServerMixin}.
 *
 * <p>Targets confirmed via {@code javap} against this module's resolved Yarn
 * jar: {@code net.minecraft.server.integrated.IntegratedServer#setupServer()Z}
 * (Yarn name for {@code initServer}) and {@code #isRemote()Z} (Yarn name for
 * {@code isPublished}); the ephemeral bind uses
 * {@code MinecraftServer#getNetworkIo()} &rarr;
 * {@code ServerNetworkIo#bind(InetAddress, int)}.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerWorldHostingMixin {

    @Inject(method = "setupServer", at = @At("RETURN"))
    private void lazuli$onSetupServer(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (WorldHostingHookHolder.isEnabled()) {
            IntegratedServer self = (IntegratedServer) (Object) this;
            try {
                // Port 0 = OS-assigned ephemeral local port. Never advertised.
                self.getNetworkIo().bind(null, 0);
            } catch (Exception e) {
                LazuliMod.LOGGER.error("[WorldHosting] Failed to bootstrap Steam pipeline: {}", e.getMessage());
            }
        }
        WorldHostingHookHolder.onWorldLoad();
    }

    @Inject(method = "isRemote", at = @At("RETURN"), cancellable = true)
    private void lazuli$isRemote(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return; // already published via LAN
        }
        if (WorldHostingHookHolder.hasConnectedPeers()) {
            cir.setReturnValue(true);
        }
    }
}
