package sh.harold.fulcrum.message;

import java.util.UUID;

/**
 * Main message class providing static methods for consistent message formatting.
 * This class serves as the primary interface for developers to send styled messages.
 */
public class Message {
    
    private static MessageService messageService;
    
    /**
     * Sets the message service implementation to use.
     * This should be called during plugin initialization.
     * 
     * @param service The message service implementation
     */
    public static void setMessageService(MessageService service) {
        messageService = service;
    }
      /**
     * Gets the current message service instance.
     * 
     * @return The message service instance
     * @throws IllegalStateException if no message service is set
     */
    public static MessageService getService() {
        if (messageService == null) {
            throw new IllegalStateException("MessageService not initialized. Call Message.setMessageService() first.");
        }
        return messageService;
    }    /**
     * Creates a success message builder for chaining tags.
     * Format: &aSuccessfully did &esomething &ato &esomething &ausers!
     * 
     * @param playerId The player to send the message to
     * @param translationKey The translation key (e.g., "feature.detail.detail")
     * @param args Arguments to replace in the message
     * @return MessageBuilder for chaining tags
     */
    public static MessageBuilder success(UUID playerId, String translationKey, Object... args) {
        return new MessageBuilder(playerId, MessageStyle.SUCCESS, translationKey, args);
    }
    
    /**
     * Creates an info message builder for chaining tags.
     * Format: &7You deposited &b1000 &7coins!
     * 
     * @param playerId The player to send the message to
     * @param translationKey The translation key (e.g., "feature.detail.detail")
     * @param args Arguments to replace in the message
     * @return MessageBuilder for chaining tags
     */
    public static MessageBuilder info(UUID playerId, String translationKey, Object... args) {
        return new MessageBuilder(playerId, MessageStyle.INFO, translationKey, args);
    }
    
    /**
     * Creates a debug message builder for chaining tags.
     * Format: &8On profile pear!
     * 
     * @param playerId The player to send the message to
     * @param translationKey The translation key (e.g., "feature.detail.detail")
     * @param args Arguments to replace in the message
     * @return MessageBuilder for chaining tags
     */
    public static MessageBuilder debug(UUID playerId, String translationKey, Object... args) {
        return new MessageBuilder(playerId, MessageStyle.DEBUG, translationKey, args);
    }
    
    /**
     * Creates an error message builder for chaining tags.
     * Format: &cYou can't do that here!
     * 
     * @param playerId The player to send the message to
     * @param translationKey The translation key (e.g., "feature.detail.detail")
     * @param args Arguments to replace in the message
     * @return MessageBuilder for chaining tags
     */
    public static MessageBuilder error(UUID playerId, String translationKey, Object... args) {
        return new MessageBuilder(playerId, MessageStyle.ERROR, translationKey, args);
    }
    
    /**
     * Sends a predefined generic error message to a player.
     * Format: &cAn error occurred! &8(GENERIC_ERROR)
     * 
     * @param playerId The player to send the message to
     * @param response The generic response to send
     */
    public static void error(UUID playerId, GenericResponse response) {
        getService().sendGenericResponse(playerId, response);
    }
      /**
     * Creates a raw message builder for chaining tags.
     * 
     * @param playerId The player to send the message to
     * @param translationKey The translation key (e.g., "feature.detail.detail")
     * @param args Arguments to replace in the message
     * @return MessageBuilder for chaining tags
     */
    public static MessageBuilder raw(UUID playerId, String translationKey, Object... args) {
        return new MessageBuilder(playerId, MessageStyle.RAW, translationKey, args);
    }
      // Broadcast versions of the methods
    
    /**
     * Creates a success broadcast message builder for chaining tags.
     * 
     * @param translationKey The translation key
     * @param args Arguments to replace in the message
     * @return MessageBuilder for chaining tags
     */
    public static MessageBuilder broadcastSuccess(String translationKey, Object... args) {
        return new MessageBuilder(MessageStyle.SUCCESS, translationKey, args);
    }
    
    /**
     * Creates an info broadcast message builder for chaining tags.
     * 
     * @param translationKey The translation key
     * @param args Arguments to replace in the message
     * @return MessageBuilder for chaining tags
     */
    public static MessageBuilder broadcastInfo(String translationKey, Object... args) {
        return new MessageBuilder(MessageStyle.INFO, translationKey, args);
    }
    
    /**
     * Creates a debug broadcast message builder for chaining tags.
     * 
     * @param translationKey The translation key
     * @param args Arguments to replace in the message
     * @return MessageBuilder for chaining tags
     */
    public static MessageBuilder broadcastDebug(String translationKey, Object... args) {
        return new MessageBuilder(MessageStyle.DEBUG, translationKey, args);
    }
    
    /**
     * Creates an error broadcast message builder for chaining tags.
     * 
     * @param translationKey The translation key
     * @param args Arguments to replace in the message
     * @return MessageBuilder for chaining tags
     */
    public static MessageBuilder broadcastError(String translationKey, Object... args) {
        return new MessageBuilder(MessageStyle.ERROR, translationKey, args);
    }
    
    /**
     * Creates a raw broadcast message builder for chaining tags.
     * 
     * @param translationKey The translation key
     * @param args Arguments to replace in the message
     * @return MessageBuilder for chaining tags
     */
    public static MessageBuilder broadcastRaw(String translationKey, Object... args) {
        return new MessageBuilder(MessageStyle.RAW, translationKey, args);
    }
}
