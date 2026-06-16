package sh.harold.fulcrum.control.queue;

public record QueueIntentId(String value) {
    public QueueIntentId {
        value = ControlQueueStrings.requireNonBlank(value, "queueIntentId");
    }
}
