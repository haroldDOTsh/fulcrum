package sh.harold.fulcrum.api.message.scoreboard.registry;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents a complete scoreboard definition with modules and configuration.
 * This class holds all the information needed to render a scoreboard, including
 * the ordered list of modules, title, and metadata.
 *
 * <p>ScoreboardDefinition is immutable and thread-safe, making it suitable for
 * concurrent access by multiple players.
 *
 * <p>The modules are stored in a List to maintain insertion ordering, where
 * modules appear in the order they were added to the scoreboard.
 */
public class ScoreboardDefinition {

    private final String scoreboardId;
    private final String title;
    private final List<ScoreboardModule> modules;
    private final long createdTime;

    /**
     * Creates a new ScoreboardDefinition with the given parameters.
     *
     * @param scoreboardId the unique identifier for the scoreboard
     * @param title        the title of the scoreboard (supports color codes)
     * @param modules      the list of modules in insertion order
     * @throws IllegalArgumentException if scoreboardId is null/empty or modules is null
     */
    public ScoreboardDefinition(String scoreboardId, String title, List<ScoreboardModule> modules) {
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        if (modules == null) {
            throw new IllegalArgumentException("Modules cannot be null");
        }

        this.scoreboardId = scoreboardId;
        this.title = title;
        this.modules = new ArrayList<>(modules);
        this.createdTime = System.currentTimeMillis();
    }

    /**
     * Creates a new ScoreboardDefinition with the given parameters (legacy constructor for priority-based systems).
     *
     * @param scoreboardId the unique identifier for the scoreboard
     * @param title        the title of the scoreboard (supports color codes)
     * @param moduleMap    the map of modules ordered by priority (converted to insertion order)
     * @throws IllegalArgumentException if scoreboardId is null/empty or modules is null
     * @deprecated Use the List-based constructor instead
     */
    @Deprecated
    public ScoreboardDefinition(String scoreboardId, String title, Map<Integer, ScoreboardModule> moduleMap) {
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        if (moduleMap == null) {
            throw new IllegalArgumentException("Modules cannot be null");
        }

        this.scoreboardId = scoreboardId;
        this.title = title;
        // Convert TreeMap to List maintaining insertion order (sorted by key)
        this.modules = new ArrayList<>(moduleMap.values());
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
     * Gets an unmodifiable view of the modules in insertion order.
     *
     * @return an unmodifiable list of modules
     */
    public List<ScoreboardModule> getModules() {
        return Collections.unmodifiableList(modules);
    }

    /**
     * Gets the modules in insertion order (same as getModules()).
     *
     * @return an unmodifiable list of modules in insertion order
     * @deprecated Use getModules() instead - insertion order is the natural order
     */
    @Deprecated
    public List<ScoreboardModule> getModulesDescending() {
        return Collections.unmodifiableList(modules);
    }

    /**
     * Gets a specific module by index.
     *
     * @param index the index of the module to retrieve (0-based)
     * @return the module at the given index, or null if index is out of bounds
     */
    public ScoreboardModule getModule(int index) {
        if (index < 0 || index >= modules.size()) {
            return null;
        }
        return modules.get(index);
    }

    /**
     * Checks if a module exists at the given index.
     *
     * @param index the index to check (0-based)
     * @return true if a module exists at the given index, false otherwise
     */
    public boolean hasModule(int index) {
        return index >= 0 && index < modules.size();
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
     * Gets the highest index among all modules.
     *
     * @return the highest index (size - 1), or -1 if no modules exist
     */
    public int getHighestIndex() {
        return modules.isEmpty() ? -1 : modules.size() - 1;
    }

    /**
     * Gets the lowest index among all modules.
     *
     * @return the lowest index (0), or -1 if no modules exist
     */
    public int getLowestIndex() {
        return modules.isEmpty() ? -1 : 0;
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
        return "&6harold&lDOT&r&6sh"; // Default title
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
     * Creates a new ScoreboardDefinition with an additional module at the specified index.
     *
     * @param index  the index where to insert the new module (0-based)
     * @param module the module to add
     * @return a new ScoreboardDefinition with the added module
     * @throws IllegalArgumentException if module is null or index is out of bounds
     */
    public ScoreboardDefinition withModule(int index, ScoreboardModule module) {
        if (module == null) {
            throw new IllegalArgumentException("Module cannot be null");
        }
        if (index < 0 || index > modules.size()) {
            throw new IllegalArgumentException("Index " + index + " is out of bounds for module list of size " + modules.size());
        }

        List<ScoreboardModule> newModules = new ArrayList<>(modules);
        newModules.add(index, module);
        return new ScoreboardDefinition(scoreboardId, title, newModules);
    }

    /**
     * Creates a new ScoreboardDefinition with an additional module at the end.
     *
     * @param module the module to add
     * @return a new ScoreboardDefinition with the added module
     * @throws IllegalArgumentException if module is null
     */
    public ScoreboardDefinition withModule(ScoreboardModule module) {
        if (module == null) {
            throw new IllegalArgumentException("Module cannot be null");
        }

        List<ScoreboardModule> newModules = new ArrayList<>(modules);
        newModules.add(module);
        return new ScoreboardDefinition(scoreboardId, title, newModules);
    }

    /**
     * Creates a new ScoreboardDefinition without the module at the specified index.
     *
     * @param index the index of the module to remove (0-based)
     * @return a new ScoreboardDefinition without the specified module
     * @throws IllegalArgumentException if index is out of bounds
     */
    public ScoreboardDefinition withoutModule(int index) {
        if (index < 0 || index >= modules.size()) {
            throw new IllegalArgumentException("Index " + index + " is out of bounds for module list of size " + modules.size());
        }

        List<ScoreboardModule> newModules = new ArrayList<>(modules);
        newModules.remove(index);
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