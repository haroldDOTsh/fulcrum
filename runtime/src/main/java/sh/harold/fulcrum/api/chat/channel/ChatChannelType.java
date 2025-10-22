package sh.harold.fulcrum.api.chat.channel;

/**
 * Enumeration of supported chat channel types.
 */
public enum ChatChannelType {
    ALL("ALL"),
    PARTY("PARTY"),
    STAFF("STAFF");

    private final String displayName;

    ChatChannelType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
