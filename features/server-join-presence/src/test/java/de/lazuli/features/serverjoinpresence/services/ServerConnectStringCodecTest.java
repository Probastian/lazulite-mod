package de.lazuli.features.serverjoinpresence.services;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ServerConnectStringCodecTest {

    @Test
    void encodesAndDecodesRoundTrip() {
        String connect = ServerConnectStringCodec.encode("example.com", 25566);
        Optional<ServerConnectStringCodec.HostPort> decoded = ServerConnectStringCodec.decode(connect);

        assertThat(decoded).isPresent();
        assertThat(decoded.get().host()).isEqualTo("example.com");
        assertThat(decoded.get().port()).isEqualTo(25566);
    }

    @Test
    void nullBlankAndWrongPrefixInputAllDecodeToEmpty() {
        assertThat(ServerConnectStringCodec.decode(null)).isEmpty();
        assertThat(ServerConnectStringCodec.decode("")).isEmpty();
        assertThat(ServerConnectStringCodec.decode("   ")).isEmpty();
        assertThat(ServerConnectStringCodec.decode("In the nether biome")).isEmpty();
        assertThat(ServerConnectStringCodec.decode("+lazuli_join 123456789")).isEmpty();
    }

    @Test
    void toleratesTrailingContentAfterTheAddress() {
        Optional<ServerConnectStringCodec.HostPort> decoded =
                ServerConnectStringCodec.decode("+lazuli_connect example.com:25565 extra-stuff");

        assertThat(decoded).isPresent();
        assertThat(decoded.get().host()).isEqualTo("example.com");
        assertThat(decoded.get().port()).isEqualTo(25565);
    }

    @Test
    void missingPortDefaultsToVanillaDefaultPort() {
        Optional<ServerConnectStringCodec.HostPort> decoded =
                ServerConnectStringCodec.decode("+lazuli_connect example.com");

        assertThat(decoded).isPresent();
        assertThat(decoded.get().port()).isEqualTo(ServerConnectStringCodec.DEFAULT_PORT);
    }

    @Test
    void invalidPortDecodesToEmpty() {
        assertThat(ServerConnectStringCodec.decode("+lazuli_connect example.com:notaport")).isEmpty();
        assertThat(ServerConnectStringCodec.decode("+lazuli_connect example.com:0")).isEmpty();
        assertThat(ServerConnectStringCodec.decode("+lazuli_connect example.com:70000")).isEmpty();
    }

    @Test
    void normalizeAddsTheDefaultPortWhenMissing() {
        assertThat(ServerConnectStringCodec.normalize("example.com"))
                .isEqualTo("example.com:" + ServerConnectStringCodec.DEFAULT_PORT);
    }

    @Test
    void normalizeIsIdempotentWhenAPortIsAlreadyPresent() {
        assertThat(ServerConnectStringCodec.normalize("example.com:25566")).isEqualTo("example.com:25566");
    }

    @Test
    void encodedAndNormalizedFormsMatchForFriendCountLookups() {
        String encoded = ServerConnectStringCodec.encode("example.com", ServerConnectStringCodec.DEFAULT_PORT);
        String hostPort = ServerConnectStringCodec.decode(encoded).orElseThrow().asHostPort();

        assertThat(ServerConnectStringCodec.normalize("example.com")).isEqualTo(hostPort);
    }
}
