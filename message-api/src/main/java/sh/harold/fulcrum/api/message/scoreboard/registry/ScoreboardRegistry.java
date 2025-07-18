package sh.harold.fulcrum.api.message.scoreboard.registry;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for managing scoreboard definitions.
 * This class provides thread-safe storage and retrieval of scoreboard definitions,
 * allowing multiple scoreboards to be registered and managed simultaneously.
 *
 * <p>The registry uses a ConcurrentHashMap for thread-safe operations and supports
 * typical CRUD operations for scoreboard definitions.
 *
 * <p>This class is typically used by the ScoreboardService implementation to
 * manage registered scoreboards.
 */
public class ScoreboardRegistry {

    private final Map<String, ScoreboardDefinition> scoreboards = new ConcurrentHashMap<>();

    /**
     * Registers a new scoreboard definition.
     *
     * @param definition the scoreboard definition to register
     * @throws IllegalArgumentException if definition is null
     * @throws IllegalStateException    if a scoreboard with the same ID is already registered
     */
    public void register(ScoreboardDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Scoreboard definition cannot be null");
        }

        String scoreboardId = definition.getScoreboardId();
        if (scoreboards.containsKey(scoreboardId)) {
            throw new IllegalStateException("Scoreboard with ID '" + scoreboardId + "' is already registered");
        }

        scoreboards.put(scoreboardId, definition);
    }

    /**
     * Registers a new scoreboard definition, replacing any existing one with the same ID.
     *
     * @param definition the scoreboard definition to register
     * @return the previously registered definition, or null if none existed
     * @throws IllegalArgumentException if definition is null
     */
    public ScoreboardDefinition registerOrReplace(ScoreboardDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Scoreboard definition cannot be null");
        }

        return scoreboards.put(definition.getScoreboardId(), definition);
    }

    /**
     * Unregisters a scoreboard definition.
     *
     * @param scoreboardId the ID of the scoreboard to unregister
     * @return the unregistered definition, or null if it wasn't registered
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    public ScoreboardDefinition unregister(String scoreboardId) {
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }

        return scoreboards.remove(scoreboardId);
    }

    /**
     * Gets a scoreboard definition by its ID.
     *
     * @param scoreboardId the ID of the scoreboard to retrieve
     * @return the scoreboard definition, or null if not found
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    public ScoreboardDefinition get(String scoreboardId) {
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }

        return scoreboards.get(scoreboardId);
    }

    /**
     * Checks if a scoreboard with the given ID is registered.
     *
     * @param scoreboardId the ID of the scoreboard to check
     * @return true if the scoreboard is registered, false otherwise
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    public boolean isRegistered(String scoreboardId) {
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }

        return scoreboards.containsKey(scoreboardId);
    }

    /**
     * Gets all registered scoreboard IDs.
     *
     * @return a set of all registered scoreboard IDs
     */
    public Set<String> getRegisteredIds() {
        return new java.util.HashSet<>(scoreboards.keySet());
    }

    /**
     * Gets all registered scoreboard definitions.
     *
     * @return a collection of all registered scoreboard definitions
     */
    public Collection<ScoreboardDefinition> getAll() {
        return new java.util.ArrayList<>(scoreboards.values());
    }

    /**
     * Gets the number of registered scoreboards.
     *
     * @return the number of registered scoreboards
     */
    public int size() {
        return scoreboards.size();
    }

    /**
     * Checks if the registry is empty.
     *
     * @return true if no scoreboards are registered, false otherwise
     */
    public boolean isEmpty() {
        return scoreboards.isEmpty();
    }

    /**
     * Clears all registered scoreboards.
     * This method should be used with caution as it will remove all scoreboards.
     */
    public void clear() {
        scoreboards.clear();
    }

    /**
     * Gets a copy of the internal scoreboard map.
     * This is useful for bulk operations or iteration.
     *
     * @return a copy of the internal scoreboard map
     */
    public Map<String, ScoreboardDefinition> getScoreboardMap() {
        return Map.copyOf(scoreboards);
    }

    /**
     * Checks if a scoreboard definition exists and has modules.
     *
     * @param scoreboardId the ID of the scoreboard to check
     * @return true if the scoreboard exists and has modules, false otherwise
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    public boolean hasValidScoreboard(String scoreboardId) {
        ScoreboardDefinition definition = get(scoreboardId);
        return definition != null && definition.hasModules();
    }

    /**
     * Gets the creation time of a registered scoreboard.
     *
     * @param scoreboardId the ID of the scoreboard
     * @return the creation time in milliseconds since epoch, or -1 if not found
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    public long getCreationTime(String scoreboardId) {
        ScoreboardDefinition definition = get(scoreboardId);
        return definition != null ? definition.getCreatedTime() : -1;
    }

    /**
     * Gets the module count for a registered scoreboard.
     *
     * @param scoreboardId the ID of the scoreboard
     * @return the number of modules, or -1 if the scoreboard is not found
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    public int getModuleCount(String scoreboardId) {
        ScoreboardDefinition definition = get(scoreboardId);
        return definition != null ? definition.getModuleCount() : -1;
    }

    /**
     * Validates that a scoreboard definition is properly configured.
     *
     * @param definition the definition to validate
     * @throws IllegalArgumentException if the definition is invalid
     */
    public void validateDefinition(ScoreboardDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Scoreboard definition cannot be null");
        }

        if (definition.getScoreboardId() == null || definition.getScoreboardId().trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }

        if (!definition.hasModules()) {
            throw new IllegalArgumentException("Scoreboard must have at least one module");
        }

        // Additional validation can be added here
    }

    @Override
    public String toString() {
        return "ScoreboardRegistry{" +
                "registeredCount=" + scoreboards.size() +
                ", scoreboardIds=" + scoreboards.keySet() +
                '}';
    }
}