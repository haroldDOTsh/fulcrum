package sh.harold.fulcrum.common.cooldown;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Shared registry for reserving and querying cooldown windows.
 */
public interface CooldownRegistry extends AutoCloseable {

    CompletionStage<CooldownAcquisition> acquire(CooldownKey key, CooldownSpec spec);

    Optional<Duration> remaining(CooldownKey key);

    void clear(CooldownKey key);

    void link(CooldownKey primary, CooldownKey... aliases);

    default void pauseReaper() {
        // no-op by default
    }

    default void resumeReaper() {
        // no-op by default
    }

    default int drainOnce(int maxBatch) {
        if (maxBatch < 0) {
            throw new IllegalArgumentException("maxBatch must be >= 0");
        }
        return 0;
    }

    default int trackedCount() {
        return 0;
    }

    @Override
    void close();
}
