package sh.harold.fulcrum.api.message.scoreboard.registry;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a complete scoreboard definition with modules and configuration.
 * This class holds all the information needed to render a scoreboard, including
 * the ordered list of modules and title.
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
     * Gets the modules in insertion order.
     *
     * @return a list of modules
     */
    public List<ScoreboardModule> getModules() {
        return new ArrayList<>(modules);
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

    @Override
    public String toString() {
        return "ScoreboardDefinition{" +
                "scoreboardId='" + scoreboardId + '\'' +
                ", title='" + title + '\'' +
                ", moduleCount=" + modules.size() +
                ", createdTime=" + createdTime +
                '}';
    }
}