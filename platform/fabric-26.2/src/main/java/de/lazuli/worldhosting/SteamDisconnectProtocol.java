package de.lazuli.worldhosting;

import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamNetworking;

import de.lazuli.LazuliMod;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight explicit-disconnect protocol on Steam P2P channel 2, letting
 * either side signal a clean close before tearing down the P2P session rather
 * than relying purely on Steam's own P2P timeout (spec Networking). Ported from
 * the prototype's own {@code SteamDisconnectProtocol}.
 *
 * <p>Packet format (channel 2): byte 0 = {@link #FIN} sentinel; bytes 1..N =
 * optional UTF-8 translation-key reason (resolved on the receiving client).
 */
public final class SteamDisconnectProtocol {

    /** P2P channel reserved for disconnect signals. */
    public static final int DISCONNECT_CHANNEL = 2;

    /** Magic byte identifying a FIN packet. */
    public static final byte FIN = (byte) 0xFE;

    private SteamDisconnectProtocol() {
    }

    /** Sends a plain FIN with no reason. */
    public static void sendFin(SteamNetworking networking, SteamID peer) {
        sendFin(networking, peer, null);
    }

    /**
     * Sends a FIN with an optional i18n reason key.
     *
     * @param reasonKey e.g. {@code "lazuli.worldhosting.disconnect.host_closed"}, or {@code null}
     */
    public static void sendFin(SteamNetworking networking, SteamID peer, String reasonKey) {
        if (networking == null || peer == null) {
            return;
        }
        try {
            byte[] reasonBytes = (reasonKey != null && !reasonKey.isEmpty())
                    ? reasonKey.getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            ByteBuffer buf = ByteBuffer.allocateDirect(1 + reasonBytes.length);
            buf.put(FIN);
            if (reasonBytes.length > 0) {
                buf.put(reasonBytes);
            }
            buf.flip();
            networking.sendP2PPacket(peer, buf, SteamNetworking.P2PSend.Reliable, DISCONNECT_CHANNEL);
        } catch (SteamException e) {
            LazuliMod.LOGGER.warn("[WorldHosting] Failed to send FIN to peer {}: {}",
                    Long.toUnsignedString(SteamNativeHandle.getNativeHandle(peer)), e.getMessage());
        }
    }

    /**
     * Result of {@link #pollFinWithReason}: {@code received} is {@code true}
     * when a FIN was found; {@code reasonKey} is the embedded i18n key, or
     * {@code null}.
     */
    public record FinResult(boolean received, String reasonKey) {
        public static final FinResult NONE = new FinResult(false, null);
    }

    /**
     * Drains all available packets on the disconnect channel, returning the
     * first FIN from {@code expectedPeerId} (0 = any peer) plus any embedded
     * reason key.
     */
    public static FinResult pollFinWithReason(SteamNetworking networking, long expectedPeerId) {
        if (networking == null) {
            return FinResult.NONE;
        }
        int[] size = new int[1];
        while (networking.isP2PPacketAvailable(DISCONNECT_CHANNEL, size)) {
            ByteBuffer buf = ByteBuffer.allocateDirect(Math.max(size[0], 1));
            SteamID sender = new SteamID();
            try {
                int read = networking.readP2PPacket(sender, buf, DISCONNECT_CHANNEL);
                if (read <= 0) {
                    break;
                }
                long senderId = SteamNativeHandle.getNativeHandle(sender);
                if (buf.get(0) == FIN && (expectedPeerId == 0L || senderId == expectedPeerId)) {
                    String reason = null;
                    if (read > 1) {
                        byte[] reasonBytes = new byte[read - 1];
                        buf.position(1);
                        buf.get(reasonBytes);
                        reason = new String(reasonBytes, StandardCharsets.UTF_8).trim();
                        if (reason.isEmpty()) {
                            reason = null;
                        }
                    }
                    return new FinResult(true, reason);
                }
            } catch (SteamException e) {
                LazuliMod.LOGGER.warn("[WorldHosting] pollFinWithReason error: {}", e.getMessage());
                break;
            }
        }
        return FinResult.NONE;
    }
}
