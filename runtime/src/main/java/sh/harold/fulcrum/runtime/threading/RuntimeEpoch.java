package sh.harold.fulcrum.runtime.threading;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public record RuntimeEpoch(long pluginEpoch) {
    private static final AtomicLong NEXT_EPOCH = new AtomicLong(1L);

    public RuntimeEpoch {
        if (pluginEpoch <= 0L) {
            throw new IllegalArgumentException("pluginEpoch must be positive");
        }
    }

    public static RuntimeEpoch create() {
        return new RuntimeEpoch(NEXT_EPOCH.getAndIncrement());
    }

    public boolean matches(RuntimeEpoch other) {
        return Objects.equals(this, other);
    }
}
