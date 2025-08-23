package sh.harold.fulcrum.api.message.scoreboard.module;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Implementation of ContentProvider for dynamic content.
 * This provider uses a Supplier to generate content that can change over time.
 */
public class DynamicContentProvider implements ContentProvider {

    private final Supplier<List<String>> globalSupplier;
    private final Function<UUID, List<String>> playerSupplier;
    private final long refreshInterval;
    private final boolean playerSpecific;

    /**
     * Creates a new DynamicContentProvider with a global supplier.
     *
     * @param supplier the supplier that provides content
     * @throws IllegalArgumentException if supplier is null
     */
    public DynamicContentProvider(Supplier<List<String>> supplier) {
        this(supplier, 5000); // Default 5 second refresh interval
    }

    /**
     * Creates a new DynamicContentProvider with a global supplier and custom refresh interval.
     *
     * @param supplier        the supplier that provides content
     * @param refreshInterval the refresh interval in milliseconds
     * @throws IllegalArgumentException if supplier is null or refreshInterval is negative
     */
    public DynamicContentProvider(Supplier<List<String>> supplier, long refreshInterval) {
        if (supplier == null) {
            throw new IllegalArgumentException("Supplier cannot be null");
        }
        if (refreshInterval < 0) {
            throw new IllegalArgumentException("Refresh interval cannot be negative");
        }
        this.globalSupplier = supplier;
        this.playerSupplier = null;
        this.refreshInterval = refreshInterval;
        this.playerSpecific = false;
    }

    /**
     * Creates a new DynamicContentProvider with a player-specific supplier.
     *
     * @param playerSupplier the function that provides content for specific players
     * @throws IllegalArgumentException if playerSupplier is null
     */
    public DynamicContentProvider(Function<UUID, List<String>> playerSupplier) {
        this(playerSupplier, 5000); // Default 5 second refresh interval
    }

    /**
     * Creates a new DynamicContentProvider with a player-specific supplier and custom refresh interval.
     *
     * @param playerSupplier  the function that provides content for specific players
     * @param refreshInterval the refresh interval in milliseconds
     * @throws IllegalArgumentException if playerSupplier is null or refreshInterval is negative
     */
    public DynamicContentProvider(Function<UUID, List<String>> playerSupplier, long refreshInterval) {
        if (playerSupplier == null) {
            throw new IllegalArgumentException("Player supplier cannot be null");
        }
        if (refreshInterval < 0) {
            throw new IllegalArgumentException("Refresh interval cannot be negative");
        }
        this.globalSupplier = null;
        this.playerSupplier = playerSupplier;
        this.refreshInterval = refreshInterval;
        this.playerSpecific = true;
    }

    @Override
    public List<String> getContent(UUID playerId) {
        List<String> content;

        try {
            if (playerSpecific) {
                content = playerSupplier.apply(playerId);
            } else {
                content = globalSupplier.get();
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }

        return content != null ? new ArrayList<>(content) : new ArrayList<>();
    }

    @Override
    public boolean isPlayerSpecific() {
        return playerSpecific;
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public long getRefreshInterval() {
        return refreshInterval;
    }

    @Override
    public boolean isContentAvailable(UUID playerId) {
        try {
            List<String> content = getContent(playerId);
            return content != null && !content.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getCacheKey(UUID playerId) {
        if (playerSpecific) {
            return "dynamic_player_" + playerId.toString();
        } else {
            return "dynamic_global";
        }
    }

    @Override
    public long getCacheDuration() {
        return refreshInterval / 2;
    }

    @Override
    public String toString() {
        return "DynamicContentProvider{" +
                "refreshInterval=" + refreshInterval +
                ", playerSpecific=" + playerSpecific +
                '}';
    }
}