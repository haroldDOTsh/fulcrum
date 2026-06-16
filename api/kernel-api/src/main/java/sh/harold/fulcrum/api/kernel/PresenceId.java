package sh.harold.fulcrum.api.kernel;

public record PresenceId(String value) {
    public PresenceId {
        value = Ids.requireNonBlank(value, "presenceId");
    }
}
