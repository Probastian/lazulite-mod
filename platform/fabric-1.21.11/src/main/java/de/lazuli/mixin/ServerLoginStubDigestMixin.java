package de.lazuli.mixin;

import de.lazuli.worldhosting.SteamAddress;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.encryption.NetworkEncryptionException;
import net.minecraft.network.encryption.NetworkEncryptionUtils;
import net.minecraft.network.packet.c2s.login.LoginKeyC2SPacket;
import net.minecraft.server.network.ServerLoginNetworkHandler;

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
 * 1.21.11 (Yarn-mapped) variant of the server-side auth bypass for Steam P2P
 * connections (Networking, fixed-stub-digest simplification). Ported from the
 * prototype's {@code ServerLoginPacketListenerImplMixin}.
 *
 * <p>Targets confirmed via {@code javap -c} against the resolved Yarn jar:
 * {@code ServerLoginNetworkHandler#onHello} (Yarn {@code handleHello}) calls
 * {@code PublicKey#getEncoded()}; {@code #onKey} (Yarn {@code handleKey}) calls
 * {@code LoginKeyC2SPacket#verifySignedNonce([B, PrivateKey)},
 * {@code LoginKeyC2SPacket#decryptSecretKey(PrivateKey)},
 * {@code NetworkEncryptionUtils#cipherFromKey(int, Key)},
 * {@code NetworkEncryptionUtils#computeServerId(...)}.
 */
@Mixin(ServerLoginNetworkHandler.class)
public class ServerLoginStubDigestMixin {

    @Shadow
    @Final
    ClientConnection connection;

    @Redirect(method = "onHello",
            at = @At(value = "INVOKE", target = "Ljava/security/PublicKey;getEncoded()[B"))
    private byte[] lazuli$suppressPublicKey(PublicKey instance) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return instance.getEncoded();
        }
        if (connection.getAddress() instanceof SteamAddress) {
            return new byte[0];
        }
        return instance.getEncoded();
    }

    @Redirect(method = "onKey",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/packet/c2s/login/LoginKeyC2SPacket;verifySignedNonce([BLjava/security/PrivateKey;)Z"))
    private boolean lazuli$bypassNonce(LoginKeyC2SPacket instance, byte[] nonce, PrivateKey key) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return instance.verifySignedNonce(nonce, key);
        }
        if (connection.getAddress() instanceof SteamAddress) {
            return true;
        }
        return instance.verifySignedNonce(nonce, key);
    }

    @Redirect(method = "onKey",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/packet/c2s/login/LoginKeyC2SPacket;decryptSecretKey(Ljava/security/PrivateKey;)Ljavax/crypto/SecretKey;"))
    private SecretKey lazuli$nullifySecretKey(LoginKeyC2SPacket instance, PrivateKey key) throws NetworkEncryptionException {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return instance.decryptSecretKey(key);
        }
        if (connection.getAddress() instanceof SteamAddress) {
            return null;
        }
        return instance.decryptSecretKey(key);
    }

    @Redirect(method = "onKey",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/encryption/NetworkEncryptionUtils;cipherFromKey(ILjava/security/Key;)Ljavax/crypto/Cipher;"))
    private Cipher lazuli$nullifyCipher(int mode, Key key) throws NetworkEncryptionException {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return NetworkEncryptionUtils.cipherFromKey(mode, key);
        }
        if (connection.getAddress() instanceof SteamAddress) {
            return null;
        }
        return NetworkEncryptionUtils.cipherFromKey(mode, key);
    }

    @Redirect(method = "onKey",
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
