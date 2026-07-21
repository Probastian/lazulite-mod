package de.lazuli.mixin;

import de.lazuli.worldhosting.SteamAddress;

import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.encryption.NetworkEncryptionException;
import net.minecraft.network.encryption.NetworkEncryptionUtils;
import net.minecraft.network.packet.s2c.login.LoginHelloS2CPacket;

import net.fabricmc.loader.api.FabricLoader;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.crypto.SecretKey;
import java.security.PublicKey;

/**
 * 1.21.11 (Yarn-mapped) variant of the client-side auth bypass for Steam P2P
 * connections (Networking, fixed-stub-digest simplification). Ported from the
 * prototype's {@code ClientHandshakePacketListenerImplMixin}.
 *
 * <p>Targets confirmed via {@code javap -c} against the resolved Yarn jar:
 * {@code ClientLoginNetworkHandler#onHello} (Yarn name for {@code handleHello})
 * calls {@code LoginHelloS2CPacket#getPublicKey()} and
 * {@code NetworkEncryptionUtils#computeServerId(String, PublicKey, SecretKey)}
 * (Yarn name for {@code Crypt.digestData}).
 */
@Mixin(ClientLoginNetworkHandler.class)
public class ClientHandshakeStubDigestMixin {

    @Shadow
    @Final
    private ClientConnection connection;

    @Redirect(method = "onHello",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/packet/s2c/login/LoginHelloS2CPacket;getPublicKey()Ljava/security/PublicKey;"))
    private PublicKey lazuli$nullifyPublicKey(LoginHelloS2CPacket instance) throws NetworkEncryptionException {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return instance.getPublicKey();
        }
        if (connection.getAddress() instanceof SteamAddress) {
            return null;
        }
        return instance.getPublicKey();
    }

    @Redirect(method = "onHello",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/encryption/NetworkEncryptionUtils;computeServerId(Ljava/lang/String;Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B"))
    private byte[] lazuli$stubServerId(String serverId, PublicKey pubKey, SecretKey secretKey) throws NetworkEncryptionException {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return NetworkEncryptionUtils.computeServerId(serverId, pubKey, secretKey);
        }
        if (connection.getAddress() instanceof SteamAddress) {
            return new byte[20];
        }
        return NetworkEncryptionUtils.computeServerId(serverId, pubKey, secretKey);
    }
}
