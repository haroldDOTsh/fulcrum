package sh.harold.fulcrum.api.message.scoreboard.player;

/**
 * Represents a module override for a specific player.
 * This class allows individual players to have customized module behavior,
 * such as enabling/disabling specific modules.
 */
public class ModuleOverride {

    private final String moduleId;
    private final boolean enabled;

    /**
     * Creates a new ModuleOverride with the given parameters.
     *
     * @param moduleId the ID of the module this override applies to
     * @param enabled  whether the module should be enabled
     * @throws IllegalArgumentException if moduleId is null or empty
     */
    public ModuleOverride(String moduleId, boolean enabled) {
        if (moduleId == null || moduleId.trim().isEmpty()) {
            throw new IllegalArgumentException("Module ID cannot be null or empty");
        }
        this.moduleId = moduleId;
        this.enabled = enabled;
    }

    /**
     * Creates a new enabled ModuleOverride for the given module.
     *
     * @param moduleId the ID of the module
     * @return a new enabled ModuleOverride
     * @throws IllegalArgumentException if moduleId is null or empty
     */
    public static ModuleOverride enabled(String moduleId) {
        return new ModuleOverride(moduleId, true);
    }

    /**
     * Creates a new disabled ModuleOverride for the given module.
     *
     * @param moduleId the ID of the module
     * @return a new disabled ModuleOverride
     * @throws IllegalArgumentException if moduleId is null or empty
     */
    public static ModuleOverride disabled(String moduleId) {
        return new ModuleOverride(moduleId, false);
    }

    /**
     * Gets the ID of the module this override applies to.
     *
     * @return the module ID
     */
    public String getModuleId() {
        return moduleId;
    }

    /**
     * Checks if the module should be enabled.
     *
     * @return true if the module should be enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "ModuleOverride{" +
                "moduleId='" + moduleId + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}