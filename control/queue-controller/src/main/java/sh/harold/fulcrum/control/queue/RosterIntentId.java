package sh.harold.fulcrum.control.queue;

public record RosterIntentId(String value) {
    public RosterIntentId {
        value = ControlQueueStrings.requireNonBlank(value, "rosterIntentId");
    }
}
