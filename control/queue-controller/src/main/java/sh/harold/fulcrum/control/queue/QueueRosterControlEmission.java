package sh.harold.fulcrum.control.queue;

import java.util.Objects;

public record QueueRosterControlEmission(
        QueueRosterControlEmissionKind kind,
        String key,
        String value) {
    public QueueRosterControlEmission {
        kind = Objects.requireNonNull(kind, "kind");
        key = ControlQueueStrings.requireNonBlank(key, "key");
        value = ControlQueueStrings.requireNonBlank(value, "value");
    }
}
