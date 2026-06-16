package sh.harold.fulcrum.api.kernel;

public record SessionId(String value) {
    public SessionId {
        value = Ids.requireNonBlank(value, "sessionId");
    }
}
