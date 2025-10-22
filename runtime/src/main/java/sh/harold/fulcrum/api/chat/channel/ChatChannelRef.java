package sh.harold.fulcrum.api.chat.channel;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reference wrapper for a concrete chat channel selection.
 */
public record ChatChannelRef(ChatChannelType type, UUID partyId) {
    public static final String STAFF_CHANNEL_ID = "staff";
    private static final String PARTY_PREFIX = "party:";

    public ChatChannelRef {
        Objects.requireNonNull(type, "type");
        if (type == ChatChannelType.PARTY && partyId == null) {
            throw new IllegalArgumentException("partyId is required for PARTY channel");
        }
        if (type != ChatChannelType.PARTY && partyId != null) {
            throw new IllegalArgumentException("partyId must be null for non-PARTY channels");
        }
    }

    public static ChatChannelRef all() {
        return new ChatChannelRef(ChatChannelType.ALL, null);
    }

    public static ChatChannelRef staff() {
        return new ChatChannelRef(ChatChannelType.STAFF, null);
    }

    public static ChatChannelRef party(UUID partyId) {
        return new ChatChannelRef(ChatChannelType.PARTY, Objects.requireNonNull(partyId, "partyId"));
    }

    public static boolean isPartyChannel(String channelId) {
        return channelId != null && channelId.startsWith(PARTY_PREFIX);
    }

    public static Optional<UUID> parsePartyId(String channelId) {
        if (!isPartyChannel(channelId)) {
            return Optional.empty();
        }
        String value = channelId.substring(PARTY_PREFIX.length());
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public boolean isParty() {
        return type == ChatChannelType.PARTY;
    }

    public String channelId() {
        return switch (type) {
            case STAFF -> STAFF_CHANNEL_ID;
            case PARTY -> PARTY_PREFIX + partyId;
            case ALL -> "all";
        };
    }
}
