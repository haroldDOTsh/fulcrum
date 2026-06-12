package sh.harold.fulcrum.runtime.threading;

import java.util.Objects;

public record NamedRuntimeIntent(String operation,
                                 RuntimeEpoch epoch,
                                 RuntimeAction action) implements RuntimeIntent {
    public NamedRuntimeIntent {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation is required");
        }
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(action, "action");
    }

    @Override
    public void run(PaperRuntime runtime) throws Exception {
        action.run(runtime);
    }
}
