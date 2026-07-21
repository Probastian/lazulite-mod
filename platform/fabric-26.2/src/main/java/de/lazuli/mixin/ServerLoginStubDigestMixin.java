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
 * Server-side auth bypass for Steam P2P connections, debug environments only
 * (mirror of {@link ClientHandshakeStubDigestMixin}).
 *
 * <p><strong>Auth-Mode Fix, follow-up correction:</strong> the four
 * {@code handleKey}-side redirects below (challenge/secret-key/cipher/digest
 * stubs) alone do <em>not</em> prevent real Mojang session verification --
 * confirmed via {@code javap -c} against the real {@code handleHello} bytecode:
 * whether the server ever enters the RSA/challenge ({@code KEY}) state at all
 * is gated entirely by {@code MinecraftServer#usesAuthentication()} (checked
 * *before* any of the digest/cipher calls below ever run). When that returns
 * {@code true}, {@code handleHello} sends a real {@code ClientboundHelloPacket}
 * and the connection is committed to a real Mojang {@code hasJoinedServer}
 * verification later, regardless of what {@code Crypt.digestData} is
 * redirected to return -- a fixed/fake digest submitted to a *real* session
 * check simply fails as "Invalid session," exactly the bug this whole fix
 * exists to prevent. When {@code usesAuthentication()} is {@code false}, the
 * server instead calls {@code UUIDUtil.createOfflineProfile(...)} and proceeds
 * straight to {@code finishLoginAndWaitForClient} -- no RSA exchange, no
 * Mojang HTTP round-trip, no possible "Invalid session" outcome. The
 * {@code lazuli$bypassAuthentication} redirect below is therefore the
 * necessary entry point; the four `handleKey`-side redirects are retained
 * (never removed) since, once this redirect is in place, `handleKey` is
 * simply never reached for a debug-environment Steam connection at all.
 *
 * <p>Targets confirmed via {@code javap -c} against the resolved 26.2 jar:
 * {@code handleHello} calls {@code MinecraftServer#usesAuthentication()} and
 * {@code PublicKey#getEncoded()}; {@code handleKey}
 * calls {@code ServerboundKeyPacket#isChallengeValid([B, PrivateKey)},
 * {@code ServerboundKeyPacket#getSecretKey(PrivateKey)},
 * {@code Crypt#getCipher(int, Key)}, {@code Crypt#digestData(...)}.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public class ServerLoginStubDigestMixin {

    @Shadow
    @Final
    Connection connection;

    @Shadow
    @Final
    private net.minecraft.server.MinecraftServer server;

    @Redirect(method = "handleHello",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"))
    private boolean lazuli$bypassAuthentication(net.minecraft.server.MinecraftServer instance) {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()
                && connection.getRemoteAddress() instanceof SteamAddress) {
            return false;
        }
        return instance.usesAuthentication();
    }

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
