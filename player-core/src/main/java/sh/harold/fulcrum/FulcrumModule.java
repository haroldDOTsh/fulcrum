package sh.harold.fulcrum;

/**
 * Interface for all external Fulcrum modules.
 */
public interface FulcrumModule {
    void onEnable(FulcrumPlatform platform);
    void onDisable();
}
