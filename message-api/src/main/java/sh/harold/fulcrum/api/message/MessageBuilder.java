package sh.harold.fulcrum.api.message;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

import java.util.UUID;

public class MessageBuilder {

    private final MessageStyle style;
    private final String messageIdentifier;
    private final Object[] args;

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

    public void send(UUID playerUuid) {
        Message.getService().sendStyledMessage(playerUuid, style, messageIdentifier, args);
    }

    public void broadcast() {
        Message.getService().broadcastStyledMessage(style, messageIdentifier, args);
    }

    public Component get(UUID playerUuid) {
        return Message.getService().getStyledMessage(playerUuid, style, messageIdentifier, args);
    }

    public void send(Audience audience) {
        Message.getService().sendStyledMessage(audience, style, messageIdentifier, args);
    }

    public Component get(Audience audience) {
        return Message.getService().getStyledMessage(audience, style, messageIdentifier, args);
    }
}