package de.lazuli.features.richpresence.services;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Real implementation of {@link LocalPresenceTracker}, composing
 * {@link PresenceStatusResolver} with an injected {@link TierTextFormatter}
 * (plan Decision 5). Free of {@code net.minecraft.*} import itself -- only
 * its injected {@code signalsSupplier}/{@code formatter}, both supplied by
 * platform code, ever touch Minecraft classes.
 *
 * <p>{@code signalsSupplier} is expected to be a cheap, non-blocking read of
 * whatever {@code PresenceSignals} the platform module's own per-tick
 * gatherer most recently computed (e.g. a {@code volatile} field getter) --
 * this class does not itself gather signals or own any tick loop.
 */
public final class LocalPresenceTrackerImpl implements LocalPresenceTracker {

    private final Supplier<PresenceSignals> signalsSupplier;
    private final TierTextFormatter formatter;
    private final PresenceStatusResolver resolver = new PresenceStatusResolver();

    public LocalPresenceTrackerImpl(Supplier<PresenceSignals> signalsSupplier, TierTextFormatter formatter) {
        this.signalsSupplier = signalsSupplier;
        this.formatter = formatter;
    }

    @Override
    public Optional<String> currentStatus() {
        PresenceSignals signals = signalsSupplier.get();
        PresenceTier tier = resolver.resolve(signals);
        if (tier.kind() == TierKind.MAIN_MENU) {
            return Optional.empty(); // FR-RP7: no session active, nothing to publish.
        }
        return Optional.of(formatter.format(tier));
    }

    @Override
    public Optional<LocalPresenceTierSnapshot> currentTier() {
        PresenceSignals signals = signalsSupplier.get();
        PresenceTier tier = resolver.resolve(signals);
        if (tier.kind() == TierKind.MAIN_MENU) {
            return Optional.empty(); // FR-RP7: no session active, nothing to publish.
        }
        return Optional.of(new LocalPresenceTierSnapshot(
                tier.kind(), formatter.localizeBiome(tier), tier.nether(), tier.end()));
    }
}
