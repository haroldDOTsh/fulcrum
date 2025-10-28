package sh.harold.fulcrum.api.punishment;

/**
 * Represents the independent ladders we track when escalating punishments.
 */
public enum PunishmentLadder {
    CHAT_MINOR("chat_minor", "Chat (Minor)"),
    CHAT_MAJOR("chat_major", "Chat (Major)"),
    GAMEPLAY("gameplay", "Gameplay"),
    MISC("misc", "Miscellaneous");

    private final String id;
    private final String displayName;

    PunishmentLadder(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }
}
