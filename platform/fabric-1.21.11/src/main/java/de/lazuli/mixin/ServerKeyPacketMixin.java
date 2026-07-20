package de.lazuli.mixin;

import net.minecraft.network.encryption.NetworkEncryptionException;
import net.minecraft.network.encryption.NetworkEncryptionUtils;
import net.minecraft.network.packet.c2s.login.LoginKeyC2SPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.security.Key;

/**
 * 1.21.11 (Yarn-mapped) variant making {@code LoginKeyC2SPacket}'s constructor
 * null-key-safe, so the client can build the key packet with a suppressed
 * (null) RSA public key on a Steam P2P connection without an NPE inside
 * {@code NetworkEncryptionUtils.encrypt}. Ported from the prototype's
 * {@code ServerboundKeyPacketMixin}.
 *
 * <p>Target confirmed via {@code javap -c} against the resolved Yarn jar: the
 * {@code (SecretKey, PublicKey, byte[])} constructor calls
 * {@code NetworkEncryptionUtils#encrypt(Key, byte[])}.
 */
@Mixin(LoginKeyC2SPacket.class)
public class ServerKeyPacketMixin {

    @Redirect(method = "<init>(Ljavax/crypto/SecretKey;Ljava/security/PublicKey;[B)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/encryption/NetworkEncryptionUtils;encrypt(Ljava/security/Key;[B)[B"))
    private static byte[] lazuli$nullSafeEncrypt(Key key, byte[] data) throws NetworkEncryptionException {
        if (key == null) {
            return new byte[0];
        }
        return NetworkEncryptionUtils.encrypt(key, data);
    }
}
