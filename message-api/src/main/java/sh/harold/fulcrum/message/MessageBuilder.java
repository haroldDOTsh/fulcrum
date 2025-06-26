package sh.harold.fulcrum.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builder class for creating messages with tags and chaining support.
 * Allows developers to chain methods to add tags and then send the message.
 */
public class MessageBuilder {
    
    private final UUID playerId;
    private final MessageStyle style;
    private final String translationKey;
    private final Object[] args;
    private final List<MessageTag> tags;
    private final boolean isBroadcast;
    
    /**
     * Creates a new MessageBuilder for a specific player.
     * 
     * @param playerId The player UUID
     * @param style The message style
     * @param translationKey The translation key
     * @param args The message arguments
     */
    public MessageBuilder(UUID playerId, MessageStyle style, String translationKey, Object... args) {
        this.playerId = playerId;
        this.style = style;
        this.translationKey = translationKey;
        this.args = args;
        this.tags = new ArrayList<>();
        this.isBroadcast = false;
    }
    
    /**
     * Creates a new MessageBuilder for broadcasting.
     * 
     * @param style The message style
     * @param translationKey The translation key
     * @param args The message arguments
     */
    public MessageBuilder(MessageStyle style, String translationKey, Object... args) {
        this.playerId = null;
        this.style = style;
        this.translationKey = translationKey;
        this.args = args;
        this.tags = new ArrayList<>();
        this.isBroadcast = true;
    }
    
    // Tag chaining methods
    
    /**
     * Adds a STAFF tag to the message.
     * 
     * @return This builder for chaining
     */
    public MessageBuilder staff() {
        tags.add(MessageTag.STAFF);
        return this;
    }
      /**
     * Adds a DAEMON tag to the message.
     * 
     * @return This builder for chaining
     */
    public MessageBuilder daemon() {
        tags.add(MessageTag.DAEMON);
        return this;
    }
      
    /**
     * Adds a custom tag to the message.
     * 
     * @param tag The tag to add
     * @return This builder for chaining
     */
    public MessageBuilder tag(MessageTag tag) {
        tags.add(tag);
        return this;
    }
      /**
     * Sends the message with all configured tags.
     * This method automatically calls the appropriate MessageService method.
     */
    public void send() {
        MessageService service = Message.getService();
        
        if (tags.isEmpty()) {
            // Use regular styled message methods if no tags
            if (isBroadcast) {
                service.broadcastStyledMessage(style, translationKey, args);
            } else {
                service.sendStyledMessage(playerId, style, translationKey, args);
            }
        } else {
            // Use tagged message methods
            if (isBroadcast) {
                service.broadcastStyledMessageWithTags(style, translationKey, tags, args);
            } else {
                service.sendStyledMessageWithTags(playerId, style, translationKey, tags, args);
            }
        }
    }
      /**
     * Gets the formatted message string without sending it.
     * 
     * @return The formatted message with tags
     */
    public String build() {
        MessageService service = Message.getService();
        
        if (tags.isEmpty()) {
            // Use regular styled message methods if no tags
            if (isBroadcast) {
                return service.getStyledMessage(service.getDefaultLocale(), style, translationKey, args);
            } else {
                return service.getStyledMessage(playerId, style, translationKey, args);
            }
        } else {
            // Use tagged message methods
            if (isBroadcast) {
                return service.getStyledMessageWithTags(service.getDefaultLocale(), style, translationKey, tags, args);
            } else {
                return service.getStyledMessageWithTags(playerId, style, translationKey, tags, args);
            }
        }
    }
    
    // Getters for internal use
    
    public UUID getPlayerId() {
        return playerId;
    }
    
    public MessageStyle getStyle() {
        return style;
    }
    
    public String getTranslationKey() {
        return translationKey;
    }
    
    public Object[] getArgs() {
        return args;
    }
    
    public List<MessageTag> getTags() {
        return new ArrayList<>(tags);
    }
    
    public boolean isBroadcast() {
        return isBroadcast;
    }
}
