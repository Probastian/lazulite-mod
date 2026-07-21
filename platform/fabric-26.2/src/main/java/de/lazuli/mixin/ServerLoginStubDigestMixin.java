package de.lazuli.mixin;

import de.lazuli.worldhosting.SteamAddress;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;

import net.fabricmc.loader.api.FabricLoader;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Server-side auth bypass for Steam P2P connections (mirror of
 * {@link ClientHandshakeStubDigestMixin}): sends an empty RSA public key,
 * bypasses the challenge/secret-key exchange, and substitutes a fixed 20-byte
 * digest (matching the client) in place of the real Mojang session-hash. The
 * actual gate that matters (who may connect at all) already happened one layer
 * down, at the Steam-P2P friend check (FR1.3). Ported from the prototype's
 * {@code ServerLoginPacketListenerImplMixin}.
 *
 * <p>Targets confirmed via {@code javap -c} against the resolved 26.2 jar:
 * {@code handleHello} calls {@code PublicKey#getEncoded()}; {@code handleKey}
 * calls {@code ServerboundKeyPacket#isChallengeValid([B, PrivateKey)},
 * {@code ServerboundKeyPacket#getSecretKey(PrivateKey)},
 * {@code Crypt#getCipher(int, Key)}, {@code Crypt#digestData(...)}.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public class ServerLoginStubDigestMixin {

    @Shadow
    @Final
    Connection connection;

    @Redirect(method = "handleHello",
            at = @At(value = "INVOKE", target = "Ljava/security/PublicKey;getEncoded()[B"))
    private byte[] lazuli$suppressPublicKey(PublicKey instance) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return instance.getEncoded();
        }
        if (connection.getRemoteAddress() instanceof SteamAddress) {
            return new byte[0];
        }
        return instance.getEncoded();
    }

    @Redirect(method = "handleKey",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/login/ServerboundKeyPacket;isChallengeValid([BLjava/security/PrivateKey;)Z"))
    private boolean lazuli$bypassChallengeValidation(ServerboundKeyPacket instance, byte[] challenge, PrivateKey key) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return instance.isChallengeValid(challenge, key);
        }
        if (connection.getRemoteAddress() instanceof SteamAddress) {
            return true;
        }
        return instance.isChallengeValid(challenge, key);
    }

    @Redirect(method = "handleKey",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/login/ServerboundKeyPacket;getSecretKey(Ljava/security/PrivateKey;)Ljavax/crypto/SecretKey;"))
    private SecretKey lazuli$nullifySecretKey(ServerboundKeyPacket instance, PrivateKey key) throws CryptException {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return instance.getSecretKey(key);
        }
        if (connection.getRemoteAddress() instanceof SteamAddress) {
            return null;
        }
        return instance.getSecretKey(key);
    }

    @Redirect(method = "handleKey",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Crypt;getCipher(ILjava/security/Key;)Ljavax/crypto/Cipher;"))
    private Cipher lazuli$nullifyCipher(int mode, Key key) throws CryptException {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return Crypt.getCipher(mode, key);
        }
        if (connection.getRemoteAddress() instanceof SteamAddress) {
            return null;
        }
        return Crypt.getCipher(mode, key);
    }

    @Redirect(method = "handleKey",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/Crypt;digestData(Ljava/lang/String;Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B"))
    private byte[] lazuli$stubDigestData(String serverId, PublicKey pubKey, SecretKey secretKey) throws CryptException {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return Crypt.digestData(serverId, pubKey, secretKey);
        }
        if (connection.getRemoteAddress() instanceof SteamAddress) {
            return new byte[20];
        }
        return Crypt.digestData(serverId, pubKey, secretKey);
    }
}
