package sh.harold.fulcrum.data.presence;

public record PresenceOwnerToken(String value) {
    public PresenceOwnerToken {
        value = PresenceNames.requireNonBlank(value, "ownerToken");
    }
}
