package sh.harold.fulcrum.api.message.scoreboard.registry;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Represents a complete scoreboard definition with modules and configuration.
 * This class holds all the information needed to render a scoreboard, including
 * the ordered list of modules, title, and metadata.
 * 
 * <p>ScoreboardDefinition is immutable and thread-safe, making it suitable for
 * concurrent access by multiple players.
 * 
 * <p>The modules are stored in a TreeMap to maintain priority ordering, where
 * higher priority values appear first on the scoreboard.
 */
public class ScoreboardDefinition {

    private final String scoreboardId;
    private final String title;
    private final TreeMap<Integer, ScoreboardModule> modules;
    private final long createdTime;

    /**
     * Creates a new ScoreboardDefinition with the given parameters.
     * 
     * @param scoreboardId the unique identifier for the scoreboard
     * @param title the title of the scoreboard (supports color codes)
     * @param modules the map of modules ordered by priority
     * @throws IllegalArgumentException if scoreboardId is null/empty or modules is null
     */
    public ScoreboardDefinition(String scoreboardId, String title, Map<Integer, ScoreboardModule> modules) {
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        if (modules == null) {
            throw new IllegalArgumentException("Modules cannot be null");
        }
        
        this.scoreboardId = scoreboardId;
        this.title = title;
        this.modules = new TreeMap<>(modules);
        this.createdTime = System.currentTimeMillis();
    }

    /**
     * Gets the unique identifier for this scoreboard.
     * 
     * @return the scoreboard ID
     */
    public String getScoreboardId() {
        return scoreboardId;
    }

    /**
     * Gets the title of the scoreboard.
     * 
     * @return the title, or null if not set
     */
    public String getTitle() {
        return title;
    }

    /**
     * Gets an unmodifiable view of the modules ordered by priority.
     * Higher priority values appear first.
     * 
     * @return an unmodifiable map of modules
     */
    public Map<Integer, ScoreboardModule> getModules() {
        return Collections.unmodifiableMap(modules);
    }

    /**
     * Gets the modules in descending priority order (highest priority first).
     * 
     * @return an unmodifiable map of modules in descending order
     */
    public Map<Integer, ScoreboardModule> getModulesDescending() {
        return Collections.unmodifiableMap(modules.descendingMap());
    }

    /**
     * Gets a specific module by priority.
     * 
     * @param priority the priority of the module to retrieve
     * @return the module at the given priority, or null if not found
     */
    public ScoreboardModule getModule(int priority) {
        return modules.get(priority);
    }

    /**
     * Checks if a module exists at the given priority.
     * 
     * @param priority the priority to check
     * @return true if a module exists at the given priority, false otherwise
     */
    public boolean hasModule(int priority) {
        return modules.containsKey(priority);
    }

    /**
     * Gets the number of modules in this scoreboard.
     * 
     * @return the number of modules
     */
    public int getModuleCount() {
        return modules.size();
    }

    /**
     * Checks if this scoreboard has any modules.
     * 
     * @return true if the scoreboard has modules, false otherwise
     */
    public boolean hasModules() {
        return !modules.isEmpty();
    }

    /**
     * Gets the highest priority value among all modules.
     * 
     * @return the highest priority, or Integer.MIN_VALUE if no modules exist
     */
    public int getHighestPriority() {
        return modules.isEmpty() ? Integer.MIN_VALUE : modules.lastKey();
    }

    /**
     * Gets the lowest priority value among all modules.
     * 
     * @return the lowest priority, or Integer.MAX_VALUE if no modules exist
     */
    public int getLowestPriority() {
        return modules.isEmpty() ? Integer.MAX_VALUE : modules.firstKey();
    }

    /**
     * Gets the time when this scoreboard definition was created.
     * 
     * @return the creation time in milliseconds since epoch
     */
    public long getCreatedTime() {
        return createdTime;
    }

    /**
     * Checks if this scoreboard has a title.
     * 
     * @return true if the scoreboard has a title, false otherwise
     */
    public boolean hasTitle() {
        return title != null && !title.trim().isEmpty();
    }

    /**
     * Gets the effective title for this scoreboard.
     * If no title is set, returns a default title based on the scoreboard ID.
     * 
     * @return the effective title
     */
    public String getEffectiveTitle() {
        if (hasTitle()) {
            return title;
        }
        return "&7Scoreboard"; // Default title
    }

    /**
     * Creates a new ScoreboardDefinition with the same configuration but a different title.
     * 
     * @param newTitle the new title
     * @return a new ScoreboardDefinition with the updated title
     */
    public ScoreboardDefinition withTitle(String newTitle) {
        return new ScoreboardDefinition(scoreboardId, newTitle, modules);
    }

    /**
     * Creates a new ScoreboardDefinition with an additional module.
     * 
     * @param priority the priority of the new module
     * @param module the module to add
     * @return a new ScoreboardDefinition with the added module
     * @throws IllegalArgumentException if module is null or priority already exists
     */
    public ScoreboardDefinition withModule(int priority, ScoreboardModule module) {
        if (module == null) {
            throw new IllegalArgumentException("Module cannot be null");
        }
        if (modules.containsKey(priority)) {
            throw new IllegalArgumentException("A module with priority " + priority + " already exists");
        }
        
        TreeMap<Integer, ScoreboardModule> newModules = new TreeMap<>(modules);
        newModules.put(priority, module);
        return new ScoreboardDefinition(scoreboardId, title, newModules);
    }

    /**
     * Creates a new ScoreboardDefinition without the module at the specified priority.
     * 
     * @param priority the priority of the module to remove
     * @return a new ScoreboardDefinition without the specified module
     */
    public ScoreboardDefinition withoutModule(int priority) {
        TreeMap<Integer, ScoreboardModule> newModules = new TreeMap<>(modules);
        newModules.remove(priority);
        return new ScoreboardDefinition(scoreboardId, title, newModules);
    }

    @Override
    public String toString() {
        return "ScoreboardDefinition{" +
                "scoreboardId='" + scoreboardId + '\'' +
                ", title='" + title + '\'' +
                ", moduleCount=" + modules.size() +
                ", createdTime=" + createdTime +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ScoreboardDefinition that = (ScoreboardDefinition) obj;
        return scoreboardId.equals(that.scoreboardId) &&
                (title != null ? title.equals(that.title) : that.title == null) &&
                modules.equals(that.modules);
    }

    @Override
    public int hashCode() {
        int result = scoreboardId.hashCode();
        result = 31 * result + (title != null ? title.hashCode() : 0);
        result = 31 * result + modules.hashCode();
        return result;
    }
}