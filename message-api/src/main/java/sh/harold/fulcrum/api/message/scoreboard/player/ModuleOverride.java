package sh.harold.fulcrum.api.message.scoreboard.player;

/**
 * Represents a module override for a specific player.
 * This class allows individual players to have customized module behavior,
 * such as enabling/disabling specific modules or modifying their properties.
 *
 * <p>Module overrides are immutable and thread-safe.
 */
public class ModuleOverride {

    private final String moduleId;
    private final boolean enabled;
    private final String customTitle;
    private final int priorityOverride;
    private final long createdTime;

    /**
     * Creates a new ModuleOverride with the given parameters.
     *
     * @param moduleId the ID of the module this override applies to
     * @param enabled  whether the module should be enabled
     * @throws IllegalArgumentException if moduleId is null or empty
     */
    public ModuleOverride(String moduleId, boolean enabled) {
        this(moduleId, enabled, null, Integer.MIN_VALUE);
    }

    /**
     * Creates a new ModuleOverride with the given parameters.
     *
     * @param moduleId    the ID of the module this override applies to
     * @param enabled     whether the module should be enabled
     * @param customTitle a custom title for the module (optional)
     * @throws IllegalArgumentException if moduleId is null or empty
     */
    public ModuleOverride(String moduleId, boolean enabled, String customTitle) {
        this(moduleId, enabled, customTitle, Integer.MIN_VALUE);
    }

    /**
     * Creates a new ModuleOverride with the given parameters.
     *
     * @param moduleId         the ID of the module this override applies to
     * @param enabled          whether the module should be enabled
     * @param customTitle      a custom title for the module (optional)
     * @param priorityOverride a priority override for the module (Integer.MIN_VALUE for no override)
     * @throws IllegalArgumentException if moduleId is null or empty
     */
    public ModuleOverride(String moduleId, boolean enabled, String customTitle, int priorityOverride) {
        if (moduleId == null || moduleId.trim().isEmpty()) {
            throw new IllegalArgumentException("Module ID cannot be null or empty");
        }
        this.moduleId = moduleId;
        this.enabled = enabled;
        this.customTitle = customTitle;
        this.priorityOverride = priorityOverride;
        this.createdTime = System.currentTimeMillis();
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
     * Creates a new ModuleOverride with a custom title for the given module.
     *
     * @param moduleId    the ID of the module
     * @param customTitle the custom title
     * @return a new ModuleOverride with the custom title
     * @throws IllegalArgumentException if moduleId is null or empty
     */
    public static ModuleOverride withTitle(String moduleId, String customTitle) {
        return new ModuleOverride(moduleId, true, customTitle);
    }

    /**
     * Creates a new ModuleOverride with a priority override for the given module.
     *
     * @param moduleId         the ID of the module
     * @param priorityOverride the priority override
     * @return a new ModuleOverride with the priority override
     * @throws IllegalArgumentException if moduleId is null or empty
     */
    public static ModuleOverride withPriority(String moduleId, int priorityOverride) {
        return new ModuleOverride(moduleId, true, null, priorityOverride);
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

    /**
     * Gets the custom title for the module.
     *
     * @return the custom title, or null if no custom title is set
     */
    public String getCustomTitle() {
        return customTitle;
    }

    /**
     * Checks if this override has a custom title.
     *
     * @return true if a custom title is set, false otherwise
     */
    public boolean hasCustomTitle() {
        return customTitle != null && !customTitle.trim().isEmpty();
    }

    /**
     * Gets the priority override for the module.
     *
     * @return the priority override, or Integer.MIN_VALUE if no override is set
     */
    public int getPriorityOverride() {
        return priorityOverride;
    }

    /**
     * Checks if this override has a priority override.
     *
     * @return true if a priority override is set, false otherwise
     */
    public boolean hasPriorityOverride() {
        return priorityOverride != Integer.MIN_VALUE;
    }

    /**
     * Gets the time when this override was created.
     *
     * @return the creation time in milliseconds since epoch
     */
    public long getCreatedTime() {
        return createdTime;
    }

    /**
     * Creates a new ModuleOverride with the same settings but different enabled state.
     *
     * @param enabled the new enabled state
     * @return a new ModuleOverride with the updated enabled state
     */
    public ModuleOverride withEnabled(boolean enabled) {
        return new ModuleOverride(moduleId, enabled, customTitle, priorityOverride);
    }

    /**
     * Creates a new ModuleOverride with the same settings but different custom title.
     *
     * @param customTitle the new custom title
     * @return a new ModuleOverride with the updated custom title
     */
    public ModuleOverride withCustomTitle(String customTitle) {
        return new ModuleOverride(moduleId, enabled, customTitle, priorityOverride);
    }

    /**
     * Creates a new ModuleOverride with the same settings but different priority override.
     *
     * @param priorityOverride the new priority override
     * @return a new ModuleOverride with the updated priority override
     */
    public ModuleOverride withPriorityOverride(int priorityOverride) {
        return new ModuleOverride(moduleId, enabled, customTitle, priorityOverride);
    }

    @Override
    public String toString() {
        return "ModuleOverride{" +
                "moduleId='" + moduleId + '\'' +
                ", enabled=" + enabled +
                ", customTitle='" + customTitle + '\'' +
                ", priorityOverride=" + priorityOverride +
                ", createdTime=" + createdTime +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ModuleOverride that = (ModuleOverride) obj;
        return enabled == that.enabled &&
                priorityOverride == that.priorityOverride &&
                moduleId.equals(that.moduleId) &&
                (customTitle != null ? customTitle.equals(that.customTitle) : that.customTitle == null);
    }

    @Override
    public int hashCode() {
        int result = moduleId.hashCode();
        result = 31 * result + (enabled ? 1 : 0);
        result = 31 * result + (customTitle != null ? customTitle.hashCode() : 0);
        result = 31 * result + priorityOverride;
        return result;
    }
}