package de.lazuli.features.worldhosting.services;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.LongPredicate;

import static org.assertj.core.api.Assertions.assertThat;

class HostGatewayTest {

    @Test
    void canJoinDelegatesToInjectedPredicateTrue() {
        HostGateway gate = new HostGateway(id -> true);
        assertThat(gate.canJoin(123L)).isTrue();
    }

    @Test
    void canJoinDelegatesToInjectedPredicateFalse() {
        HostGateway gate = new HostGateway(id -> false);
        assertThat(gate.canJoin(123L)).isFalse();
    }

    @Test
    void canJoinReflectsAFriendAllowList() {
        Set<Long> friends = Set.of(10L, 20L, 30L);
        LongPredicate isFriend = friends::contains;
        HostGateway gate = new HostGateway(isFriend);

        assertThat(gate.canJoin(20L)).isTrue();
        assertThat(gate.canJoin(999L)).isFalse();
    }

    @Test
    void canJoinPassesTheExactIdThrough() {
        long[] seen = {0L};
        HostGateway gate = new HostGateway(id -> {
            seen[0] = id;
            return true;
        });
        gate.canJoin(76561198123456789L);
        assertThat(seen[0]).isEqualTo(76561198123456789L);
    }
}
