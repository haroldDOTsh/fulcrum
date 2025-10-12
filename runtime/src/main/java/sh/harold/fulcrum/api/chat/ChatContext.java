package sh.harold.fulcrum.api.chat;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Context object containing player, message, and optional game data.
 * Used to pass additional information to chat formatters.
 */
public class ChatContext {
    private final Player player;
    private final Component message;
    private final Map<String, Object> gameData;

    /**
     * Creates a new chat context with player and message.
     *
     * @param player  The player sending the message
     * @param message The message being sent
     */
    public ChatContext(Player player, Component message) {
        this(player, message, new HashMap<>());
    }

    /**
     * Creates a new chat context with player, message, and game data.
     *
     * @param player   The player sending the message
     * @param message  The message being sent
     * @param gameData Optional game-specific data
     */
    public ChatContext(Player player, Component message, Map<String, Object> gameData) {
        this.player = player;
        this.message = message;
        this.gameData = gameData != null ? gameData : new HashMap<>();
    }

    /**
     * Gets the player sending the message.
     *
     * @return The player
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the message being sent.
     *
     * @return The message
     */
    public Component getMessage() {
        return message;
    }

    /**
     * Gets game-specific data from the context.
     *
     * @param key  The data key
     * @param type The expected type
     * @param <T>  The type parameter
     * @return Optional containing the data if present and of correct type
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getGameData(String key, Class<T> type) {
        Object value = gameData.get(key);
        if (value != null && type.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }

    /**
     * Adds game-specific data to the context.
     *
     * @param key   The data key
     * @param value The data value
     */
    public void setGameData(String key, Object value) {
        gameData.put(key, value);
    }

    /**
     * Gets all game data.
     *
     * @return Map of all game data
     */
    public Map<String, Object> getAllGameData() {
        return new HashMap<>(gameData);
    }
}