package sh.harold.fulcrum.api.message;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MessageBuilder {

    private final MessageStyle style;
    private final MessageType type;
    private final String messageIdentifier;
    private final Object[] args;
    private final List<MessageTag> tags = new ArrayList<>();

    private MessageBuilder(MessageStyle style, MessageType type, String messageIdentifier, Object... args) {
        this.style = style;
        this.type = type;
        this.messageIdentifier = messageIdentifier;
        this.args = args;
    }

    public static MessageBuilder key(MessageStyle style, String key, Object... args) {
        return new MessageBuilder(style, MessageType.KEY, key, args);
    }

    public static MessageBuilder macro(MessageStyle style, String macroKey, Object... args) {
        return new MessageBuilder(style, MessageType.MACRO, macroKey, args);
    }

    public MessageBuilder staff() {
        tags.add(new MessageTag("tag", "staff"));
        return this;
    }

    public MessageBuilder daemon() {
        tags.add(new MessageTag("tag", "daemon"));
        return this;
    }

    public MessageBuilder system() {
        tags.add(new MessageTag("tag", "system"));
        return this;
    }

    public MessageBuilder debug() {
        tags.add(new MessageTag("tag", "debug"));
        return this;
    }

    public void send(UUID playerUuid) {
        if (type == MessageType.KEY) {
            Message.getService().sendStyledMessageWithTags(playerUuid, style, messageIdentifier, tags, args);
        } else if (type == MessageType.MACRO) {
            Message.getService().sendMacroMessageWithTags(playerUuid, style, messageIdentifier, tags, args);
        }
    }

    public void broadcast() {
        if (type == MessageType.KEY) {
            Message.getService().broadcastStyledMessageWithTags(style, messageIdentifier, tags, args);
        } else if (type == MessageType.MACRO) {
            Message.getService().broadcastMacroMessageWithTags(style, messageIdentifier, tags, args);
        }
    }

    public Component get(UUID playerUuid) {
        if (type == MessageType.KEY) {
            return Message.getService().getStyledMessageWithTags(playerUuid, style, messageIdentifier, tags, args);
        } else if (type == MessageType.MACRO) {
            return Message.getService().getMacroMessageWithTags(playerUuid, style, messageIdentifier, tags, args);
        }
        return null; // Should not happen
    }
}