package sh.harold.fulcrum.api.network;

import java.util.Optional;

/**
 * Facade for accessing the currently active network configuration.
 */
public interface NetworkConfigService {

    /**
     * @return the active profile snapshot cached locally.
     */
    NetworkProfileView getActiveProfile();

    /**
     * Convenience helper for retrieving string values by key from the active profile.
     */
    default Optional<String> getString(String path) {
        return getActiveProfile().getString(path);
    }

    /**
     * Generic helper for retrieving typed values from the active profile.
     */
    default <T> Optional<T> getValue(String path, Class<T> type) {
        return getActiveProfile().getValue(path, type);
    }

    /**
     * Look up a specific rank visual by identifier.
     *
     * @param rankId canonical rank identifier (e.g. DEFAULT, HELPER)
     * @return the visual metadata if available
     */
    Optional<RankVisualView> getRankVisual(String rankId);

    /**
     * Synchronously refresh the active profile from Redis or a backing source.
     */
    void refreshActiveProfile();
}
