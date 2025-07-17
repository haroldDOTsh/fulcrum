package sh.harold.fulcrum.api.message.scoreboard;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardDefinition;

import java.util.TreeMap;

/**
 * Fluent builder for creating scoreboard definitions.
 * This class provides a convenient way to configure scoreboards with modules,
 * titles, and other settings before registering them with the service.
 * 
 * <p>Usage example:
 * <pre>{@code
 * Scoreboard.define("lobby")
 *     .title("&6&lLobby Server")
 *     .module(0, StatsModule.create())
 *     .module(1, RankModule.create())
 *     .module(2, ServerInfoModule.create())
 *     .register();
 * }</pre>
 */
public class ScoreboardBuilder {

    private final String scoreboardId;
    private String title;
    private final TreeMap<Integer, ScoreboardModule> modules = new TreeMap<>();

    /**
     * Creates a new ScoreboardBuilder with the given scoreboard ID.
     * 
     * @param scoreboardId the unique identifier for the scoreboard
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    public ScoreboardBuilder(String scoreboardId) {
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        this.scoreboardId = scoreboardId;
    }

    /**
     * Sets the title for the scoreboard.
     * The title supports color codes and will be displayed at the top of the scoreboard.
     * 
     * @param title the title to set (supports color codes)
     * @return this builder instance for method chaining
     */
    public ScoreboardBuilder title(String title) {
        this.title = title;
        return this;
    }

    /**
     * Adds a module to the scoreboard with the specified priority.
     * Modules are displayed in priority order (higher values appear first).
     * 
     * @param priority the priority of the module (higher values appear first)
     * @param module the module to add
     * @return this builder instance for method chaining
     * @throws IllegalArgumentException if module is null
     * @throws IllegalStateException if a module with the same priority already exists
     */
    public ScoreboardBuilder module(int priority, ScoreboardModule module) {
        if (module == null) {
            throw new IllegalArgumentException("Module cannot be null");
        }
        if (modules.containsKey(priority)) {
            throw new IllegalStateException("A module with priority " + priority + " already exists");
        }
        modules.put(priority, module);
        return this;
    }

    /**
     * Registers the scoreboard with the ScoreboardService.
     * This method creates a ScoreboardDefinition from the current configuration
     * and registers it with the service.
     * 
     * @throws IllegalStateException if the ScoreboardService is not initialized
     * @throws IllegalArgumentException if the scoreboard configuration is invalid
     */
    public void register() {
        ScoreboardDefinition definition = new ScoreboardDefinition(scoreboardId, title, modules);
        Scoreboard.getService().registerScoreboard(scoreboardId, definition);
    }

    /**
     * Builds a ScoreboardDefinition from the current configuration without registering it.
     * This is useful for testing or when manual registration is needed.
     * 
     * @return a new ScoreboardDefinition instance
     */
    public ScoreboardDefinition build() {
        return new ScoreboardDefinition(scoreboardId, title, modules);
    }

    /**
     * Gets the scoreboard ID for this builder.
     * 
     * @return the scoreboard ID
     */
    public String getScoreboardId() {
        return scoreboardId;
    }

    /**
     * Gets the current title for this builder.
     * 
     * @return the title, or null if not set
     */
    public String getTitle() {
        return title;
    }

    /**
     * Gets a copy of the current modules map.
     * 
     * @return a copy of the modules map
     */
    public TreeMap<Integer, ScoreboardModule> getModules() {
        return new TreeMap<>(modules);
    }

    /**
     * Gets the number of modules currently configured.
     * 
     * @return the number of modules
     */
    public int getModuleCount() {
        return modules.size();
    }

    /**
     * Checks if a module with the given priority exists.
     * 
     * @param priority the priority to check
     * @return true if a module with the given priority exists, false otherwise
     */
    public boolean hasModule(int priority) {
        return modules.containsKey(priority);
    }

    /**
     * Removes a module with the given priority.
     * 
     * @param priority the priority of the module to remove
     * @return this builder instance for method chaining
     */
    public ScoreboardBuilder removeModule(int priority) {
        modules.remove(priority);
        return this;
    }

    /**
     * Clears all modules from the builder.
     * 
     * @return this builder instance for method chaining
     */
    public ScoreboardBuilder clearModules() {
        modules.clear();
        return this;
    }

    @Override
    public String toString() {
        return "ScoreboardBuilder{" +
                "scoreboardId='" + scoreboardId + '\'' +
                ", title='" + title + '\'' +
                ", moduleCount=" + modules.size() +
                '}';
    }
}