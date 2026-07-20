package de.lazuli.features.worldhosting.services;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectStringCodecTest {

    @Test
    void encodeProducesPrefixedUnsignedDecimal() {
        assertThat(ConnectStringCodec.encode(76561198000000000L))
                .isEqualTo("+lazuli_join 76561198000000000");
    }

    @Test
    void encodeHandlesFullUnsignedRange() {
        long maxUnsigned = -1L; // 18446744073709551615 unsigned
        assertThat(ConnectStringCodec.encode(maxUnsigned))
                .isEqualTo("+lazuli_join 18446744073709551615");
    }

    @Test
    void roundTripPreservesSteamId() {
        long id = 76561198123456789L;
        OptionalLong decoded = ConnectStringCodec.decode(ConnectStringCodec.encode(id));
        assertThat(decoded).hasValue(id);
    }

    @Test
    void roundTripPreservesHighBitSteamId() {
        long id = -1L; // exercises unsigned parse/format symmetry
        OptionalLong decoded = ConnectStringCodec.decode(ConnectStringCodec.encode(id));
        assertThat(decoded).hasValue(id);
    }

    @Test
    void decodeToleratesTrailingContent() {
        assertThat(ConnectStringCodec.decode("+lazuli_join 76561198123456789 extra ignored"))
                .hasValue(76561198123456789L);
    }

    @Test
    void decodeToleratesSurroundingWhitespace() {
        assertThat(ConnectStringCodec.decode("  +lazuli_join 42  ")).hasValue(42L);
    }

    @Test
    void decodeReturnsEmptyForNull() {
        assertThat(ConnectStringCodec.decode(null)).isEmpty();
    }

    @Test
    void decodeReturnsEmptyForBlank() {
        assertThat(ConnectStringCodec.decode("")).isEmpty();
        assertThat(ConnectStringCodec.decode("   ")).isEmpty();
    }

    @Test
    void decodeReturnsEmptyForWrongPrefix() {
        assertThat(ConnectStringCodec.decode("+steamshare_join 42")).isEmpty();
        assertThat(ConnectStringCodec.decode("join 42")).isEmpty();
        assertThat(ConnectStringCodec.decode("steamid:42")).isEmpty();
    }

    @Test
    void decodeReturnsEmptyForMissingId() {
        assertThat(ConnectStringCodec.decode("+lazuli_join ")).isEmpty();
        assertThat(ConnectStringCodec.decode("+lazuli_join")).isEmpty();
    }

    @Test
    void decodeReturnsEmptyForNonNumericId() {
        assertThat(ConnectStringCodec.decode("+lazuli_join notanumber")).isEmpty();
        assertThat(ConnectStringCodec.decode("+lazuli_join 12ab34")).isEmpty();
    }

    @Test
    void decodeNeverThrows() {
        // A grab-bag of hostile inputs; none may throw.
        for (String hostile : new String[] {"+lazuli_join -1", "+lazuli_join 999999999999999999999999",
                "+lazuli_join\t", "+lazuli_join  \n"}) {
            ConnectStringCodec.decode(hostile);
        }
    }
}
