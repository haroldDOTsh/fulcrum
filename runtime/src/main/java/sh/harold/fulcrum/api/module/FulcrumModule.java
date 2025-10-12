package sh.harold.fulcrum.api.module;

/**
 * Interface for all external Fulcrum modules.
 */
public interface FulcrumModule {
    /**
     * Called when the module is enabled.
     */
    void onEnable();

    /**
     * Called when the module is disabled.
     */
    void onDisable();

    /**
     * Checks if the module is currently enabled.
     *
     * @return true if the module is enabled
     */
    boolean isEnabled();
}