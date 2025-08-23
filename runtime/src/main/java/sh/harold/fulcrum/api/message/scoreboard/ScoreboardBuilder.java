package sh.harold.fulcrum.api.message.scoreboard;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for creating scoreboard definitions.
 * This class provides a convenient way to configure scoreboards with modules,
 * titles, and other settings before registering them with the service.
 *
 * <p>Usage example:
 * <pre>{@code
 * Scoreboard.define("lobby")
 *     .title("&6&lLobby Server")
 *     .module(StatsModule.create())
 *     .module(RankModule.create())
 *     .module(ServerInfoModule.create())
 *     .register();
 * }</pre>
 */
public class ScoreboardBuilder {

    private final String scoreboardId;
    private final List<ScoreboardModule> modules = new ArrayList<>();
    private String title;

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
     * Adds a module to the scoreboard at the end of the list.
     * Modules are displayed in insertion order.
     *
     * @param module the module to add
     * @return this builder instance for method chaining
     * @throws IllegalArgumentException if module is null
     */
    public ScoreboardBuilder module(ScoreboardModule module) {
        if (module == null) {
            throw new IllegalArgumentException("Module cannot be null");
        }
        modules.add(module);
        return this;
    }

    /**
     * Adds a module to the scoreboard at the specified index.
     * Modules are displayed in insertion order.
     *
     * @param index  the index where to insert the module (0-based)
     * @param module the module to add
     * @return this builder instance for method chaining
     * @throws IllegalArgumentException  if module is null
     * @throws IndexOutOfBoundsException if index is out of bounds
     */
    public ScoreboardBuilder module(int index, ScoreboardModule module) {
        if (module == null) {
            throw new IllegalArgumentException("Module cannot be null");
        }
        modules.add(index, module);
        return this;
    }

    /**
     * Builds a ScoreboardDefinition from the current configuration.
     * Note: This method only builds the definition. Registration must be done
     * separately through the ScoreboardService.
     *
     * @deprecated Use build() method and register through ScoreboardService directly
     */
    @Deprecated
    public void register() {
        // This method is deprecated - users should get ScoreboardService from their plugin
        // and call registerScoreboard directly
        throw new UnsupportedOperationException(
            "Direct registration is no longer supported. " +
            "Please use build() to create the definition and register it through ScoreboardService"
        );
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
     * Gets a copy of the current modules list.
     *
     * @return a copy of the modules list
     */
    public List<ScoreboardModule> getModules() {
        return new ArrayList<>(modules);
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
     * Checks if a module exists at the given index.
     *
     * @param index the index to check (0-based)
     * @return true if a module exists at the given index, false otherwise
     */
    public boolean hasModule(int index) {
        return index >= 0 && index < modules.size();
    }

    /**
     * Removes a module at the given index.
     *
     * @param index the index of the module to remove (0-based)
     * @return this builder instance for method chaining
     * @throws IndexOutOfBoundsException if index is out of bounds
     */
    public ScoreboardBuilder removeModule(int index) {
        modules.remove(index);
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