package sh.harold.fulcrum.api.message;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import sh.harold.fulcrum.api.message.util.MessageTag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MessageBuilder {

    private final MessageStyle style;
    private final String messageIdentifier;
    private final Object[] args;
    private final List<MessageTag> tags = new ArrayList<>();

    private MessageBuilder(MessageStyle style, String messageIdentifier, Object... args) {
        this.style = style;
        this.messageIdentifier = messageIdentifier;
        this.args = args;
        // Pass argument count to MessageService for placeholder generation if needed
        Message.getService().setArgCountContext(args != null ? args.length : 0);
    }

    public static MessageBuilder key(MessageStyle style, String key, Object... args) {
        return new MessageBuilder(style, key, args);
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
        Message.getService().sendStyledMessageWithTags(playerUuid, style, messageIdentifier, tags, args);
    }

    public void broadcast() {
        Message.getService().broadcastStyledMessageWithTags(style, messageIdentifier, tags, args);
    }

    public Component get(UUID playerUuid) {
        return Message.getService().getStyledMessageWithTags(playerUuid, style, messageIdentifier, tags, args);
    }

    public void send(Audience audience) {
        Message.getService().sendStyledMessageWithTags(audience, style, messageIdentifier, tags, args);
    }

    public Component get(Audience audience) {
        return Message.getService().getStyledMessageWithTags(audience, style, messageIdentifier, tags, args);
    }
}