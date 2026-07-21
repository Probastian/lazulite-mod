package de.lazuli.features.worldhosting.services;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.LongPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain-JVM coverage of {@link HostGateway#forPolicy(JoinGatePolicy, LongPredicate)}
 * (v1.3 amendment, implementation plan Decision 5, FR7.8-FR7.10).
 */
class HostGatewayForPolicyTest {

    @Test
    void nobodyAlwaysRejectsRegardlessOfInjectedPredicate() {
        HostGateway gate = HostGateway.forPolicy(JoinGatePolicy.NOBODY, id -> true);
        assertThat(gate.canJoin(123L)).isFalse();
    }

    @Test
    void friendsDelegatesExactlyToTheInjectedPredicate() {
        Set<Long> friends = Set.of(10L, 20L, 30L);
        LongPredicate isFriend = friends::contains;
        HostGateway gate = HostGateway.forPolicy(JoinGatePolicy.FRIENDS, isFriend);

        assertThat(gate.canJoin(20L)).isTrue();
        assertThat(gate.canJoin(999L)).isFalse();
    }

    @Test
    void everyoneAlwaysAcceptsRegardlessOfInjectedPredicate() {
        HostGateway gate = HostGateway.forPolicy(JoinGatePolicy.EVERYONE, id -> false);
        assertThat(gate.canJoin(123L)).isTrue();
    }
}
