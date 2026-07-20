package de.lazuli.mixin;

import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.security.Key;

/**
 * Makes {@code ServerboundKeyPacket}'s constructor null-key-safe, so the client
 * can build the key packet with a suppressed (null) RSA public key on a Steam
 * P2P connection ({@link ClientHandshakeStubDigestMixin}) without an NPE inside
 * {@code Crypt.encryptUsingKey}. Ported from the prototype's
 * {@code ServerboundKeyPacketMixin}.
 *
 * <p>Target confirmed via {@code javap -c} against the resolved 26.2 jar: the
 * {@code (SecretKey, PublicKey, byte[])} constructor calls
 * {@code Crypt#encryptUsingKey(Key, byte[])}.
 */
@Mixin(ServerboundKeyPacket.class)
public class ServerKeyPacketMixin {

    @Redirect(method = "<init>(Ljavax/crypto/SecretKey;Ljava/security/PublicKey;[B)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Crypt;encryptUsingKey(Ljava/security/Key;[B)[B"))
    private static byte[] lazuli$nullSafeEncrypt(Key key, byte[] data) throws CryptException {
        if (key == null) {
            return new byte[0];
        }
        return Crypt.encryptUsingKey(key, data);
    }
}
