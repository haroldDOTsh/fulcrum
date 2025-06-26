package sh.harold.fulcrum.message;

import java.util.UUID;
import java.util.Locale;

/**
 * Service interface for managing styled messages in the Fulcrum system.
 * This service handles message localization, styling, and formatting 
 * for consistent player communication using the new styled messaging system.
 */
public interface MessageService {    
    /**
     * Sets the preferred locale for a player.
     * 
     * @param playerId The UUID of the player
     * @param locale The preferred locale
     */
    void setPlayerLocale(UUID playerId, Locale locale);
    
    /**
     * Gets the preferred locale for a player.
     * 
     * @param playerId The UUID of the player
     * @return The player's preferred locale, or default if not set
     */
    Locale getPlayerLocale(UUID playerId);
    
    /**
     * Reloads all message templates from configuration files.
     */
    void reloadMessages();
    
    /**
     * Adds a custom message template.
     * 
     * @param key The message key
     * @param locale The locale for this template
     * @param template The message template
     */
    void addCustomMessage(String key, Locale locale, String template);
    
    /**
     * Removes a custom message template.
     * 
     * @param key The message key to remove
     * @param locale The locale to remove
     * @return True if the message was found and removed
     */
    boolean removeCustomMessage(String key, Locale locale);
    
    // Styled messaging methods
    
    /**
     * Sends a styled message to a player with translation key and arguments.
     * 
     * @param playerId The UUID of the player to send the message to
     * @param style The message style to apply
     * @param translationKey The translation key (e.g., "feature.detail.detail")
     * @param args Arguments to replace in the message
     */
    void sendStyledMessage(UUID playerId, MessageStyle style, String translationKey, Object... args);
    
    /**
     * Sends a predefined generic response message to a player.
     * 
     * @param playerId The UUID of the player to send the message to
     * @param response The generic response to send
     */
    void sendGenericResponse(UUID playerId, GenericResponse response);
    
    /**
     * Broadcasts a styled message to all online players.
     * 
     * @param style The message style to apply
     * @param translationKey The translation key
     * @param args Arguments to replace in the message
     */
    void broadcastStyledMessage(MessageStyle style, String translationKey, Object... args);
    
    /**
     * Gets a styled message string without sending it.
     * 
     * @param playerId The UUID of the player (for localization)
     * @param style The message style to apply
     * @param translationKey The translation key
     * @param args Arguments to replace in the message
     * @return The formatted message string
     */
    String getStyledMessage(UUID playerId, MessageStyle style, String translationKey, Object... args);
    
    /**
     * Gets a styled message string for a specific locale without sending it.
     * 
     * @param locale The locale to use
     * @param style The message style to apply
     * @param translationKey The translation key
     * @param args Arguments to replace in the message
     * @return The formatted message string
     */
    String getStyledMessage(Locale locale, MessageStyle style, String translationKey, Object... args);
    
    /**
     * Gets the default locale used by the message service.
     * 
     * @return The default locale
     */
    Locale getDefaultLocale();
    
    // Tagged message methods
    
    /**
     * Sends a styled message with tags to a player.
     * 
     * @param playerId The UUID of the player to send the message to
     * @param style The message style to apply
     * @param translationKey The translation key
     * @param tags The tags to prefix to the message
     * @param args Arguments to replace in the message
     */
    void sendStyledMessageWithTags(UUID playerId, MessageStyle style, String translationKey, java.util.List<MessageTag> tags, Object... args);
    
    /**
     * Broadcasts a styled message with tags to all online players.
     * 
     * @param style The message style to apply
     * @param translationKey The translation key
     * @param tags The tags to prefix to the message
     * @param args Arguments to replace in the message
     */
    void broadcastStyledMessageWithTags(MessageStyle style, String translationKey, java.util.List<MessageTag> tags, Object... args);
    
    /**
     * Gets a styled message string with tags for a player without sending it.
     * 
     * @param playerId The UUID of the player (for localization)
     * @param style The message style to apply
     * @param translationKey The translation key
     * @param tags The tags to prefix to the message
     * @param args Arguments to replace in the message
     * @return The formatted message string with tags
     */
    String getStyledMessageWithTags(UUID playerId, MessageStyle style, String translationKey, java.util.List<MessageTag> tags, Object... args);
    
    /**
     * Gets a styled message string with tags for a specific locale without sending it.
     * 
     * @param locale The locale to use
     * @param style The message style to apply
     * @param translationKey The translation key
     * @param tags The tags to prefix to the message
     * @param args Arguments to replace in the message
     * @return The formatted message string with tags
     */
    String getStyledMessageWithTags(Locale locale, MessageStyle style, String translationKey, java.util.List<MessageTag> tags, Object... args);
}
