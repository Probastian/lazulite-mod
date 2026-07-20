package de.lazuli.mixin;

import de.lazuli.worldhosting.SteamAddress;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.crypto.SecretKey;
import java.security.PublicKey;

/**
 * Client-side auth bypass for Steam P2P connections (Networking, the accepted
 * fixed-stub-digest simplification, resolved Open Question 6): suppresses the
 * RSA public key and substitutes a fixed 20-byte digest in place of the real
 * Mojang session-hash computation. Both peers are already authenticated by real
 * {@code SteamID} at the transport layer (FR1.3). Ported from the prototype's
 * {@code ClientHandshakePacketListenerImplMixin}.
 *
 * <p>Targets confirmed via {@code javap -c} against the resolved 26.2 jar:
 * {@code ClientHandshakePacketListenerImpl#handleHello} calls
 * {@code ClientboundHelloPacket#getPublicKey()} and
 * {@code Crypt#digestData(String, PublicKey, SecretKey)}.
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
public class ClientHandshakeStubDigestMixin {

    @Shadow
    @Final
    private Connection connection;

    @Redirect(method = "handleHello",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/login/ClientboundHelloPacket;getPublicKey()Ljava/security/PublicKey;"))
    private PublicKey lazuli$nullifyPublicKey(ClientboundHelloPacket instance) throws CryptException {
        if (connection.getRemoteAddress() instanceof SteamAddress) {
            return null;
        }
        return instance.getPublicKey();
    }

    @Redirect(method = "handleHello",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/Crypt;digestData(Ljava/lang/String;Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B"))
    private byte[] lazuli$stubDigestData(String serverId, PublicKey pubKey, SecretKey secretKey) throws CryptException {
        if (connection.getRemoteAddress() instanceof SteamAddress) {
            return new byte[20];
        }
        return Crypt.digestData(serverId, pubKey, secretKey);
    }
}
