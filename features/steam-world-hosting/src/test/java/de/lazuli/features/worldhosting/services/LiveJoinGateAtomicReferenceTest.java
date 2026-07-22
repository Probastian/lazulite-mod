package de.lazuli.features.worldhosting.services;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolates the {@code AtomicReference<LongPredicate>} live-indirection
 * mechanism used by {@code WorldHostingHookHolder}/{@code SteamSession}/
 * {@code SteamServerChannel} (platform modules, not JVM-unit-testable
 * directly since they depend on Steamworks-native types) to fix the
 * frozen-join-gate-predicate defect
 * (specification-invite-policy-gating.md FR-IPG4).
 *
 * <p>This test does not touch any platform/Steamworks class; it proves the
 * plain-JDK mechanism itself: a {@code LongPredicate} published into an
 * {@link AtomicReference} and read back via {@code .get().test(id)} reflects
 * the most recently {@code .set(...)} value immediately, unlike a
 * {@code final LongPredicate} captured once at construction time (the exact
 * bug FR-IPG4 fixes).
 */
class LiveJoinGateAtomicReferenceTest {

    @Test
    void getReflectsInitiallySetPredicate() {
        LongPredicate p1 = id -> id == 1L;
        AtomicReference<LongPredicate> canJoin = new AtomicReference<>(p1);

        assertThat(canJoin.get().test(1L)).isTrue();
        assertThat(canJoin.get().test(2L)).isFalse();
    }

    @Test
    void setIsObservedByASubsequentGetImmediately() {
        LongPredicate p1 = id -> id == 1L;
        LongPredicate p2 = id -> id == 2L;
        AtomicReference<LongPredicate> canJoin = new AtomicReference<>(p1);

        assertThat(canJoin.get().test(1L)).isTrue();
        assertThat(canJoin.get().test(2L)).isFalse();

        canJoin.set(p2);

        // The exact defect FR-IPG4 fixes: a `final LongPredicate` captured
        // before this `.set(p2)` would still show p1's answer here. A
        // correct AtomicReference-backed read must not.
        assertThat(canJoin.get().test(2L)).isTrue();
        assertThat(canJoin.get().test(1L)).isFalse();
    }

    @Test
    void unsetReferenceHoldsNullAndSetMakesItNonNull() {
        AtomicReference<LongPredicate> canJoin = new AtomicReference<>();

        // isEnabled()-equivalent semantics (Risk 2 of
        // implementation-plan-invite-policy-gating.md): the box itself is
        // never null once declared, but its held value starts null.
        assertThat(canJoin.get()).isNull();

        canJoin.set(id -> true);

        assertThat(canJoin.get()).isNotNull();
        assertThat(canJoin.get().test(42L)).isTrue();
    }
}
