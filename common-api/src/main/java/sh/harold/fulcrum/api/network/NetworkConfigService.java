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
