package sh.harold.fulcrum.data.session;

public record SessionOwnerToken(String value) {
    public SessionOwnerToken {
        value = SessionNames.requireNonBlank(value, "sessionOwnerToken");
    }
}
