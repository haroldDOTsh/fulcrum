package sh.harold.fulcrum.api.message.scoreboard.module;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Implementation of ContentProvider for dynamic content.
 * This provider uses a Supplier to generate content that can change over time.
 *
 * <p>Dynamic content providers are ideal for:
 * <ul>
 *   <li>Live statistics (player count, server status)</li>
 *   <li>Time-based information (current time, countdowns)</li>
 *   <li>Real-time data (player rankings, scores)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Simple dynamic content
 * ContentProvider provider = new DynamicContentProvider(() -> Arrays.asList(
 *     "&7Time: &a" + getCurrentTime(),
 *     "&7Players: &a" + getOnlinePlayerCount()
 * ));
 *
 * // Player-specific dynamic content
 * ContentProvider playerProvider = new DynamicContentProvider(playerId -> Arrays.asList(
 *     "&7Welcome, &a" + getPlayerName(playerId),
 *     "&7Your rank: &6" + getPlayerRank(playerId)
 * ));
 * }</pre>
 */
public class DynamicContentProvider implements ContentProvider {

    private final Supplier<List<String>> globalSupplier;
    private final Function<UUID, List<String>> playerSupplier;
    private final long refreshInterval;
    private final int maxLines;
    private final boolean playerSpecific;

    /**
     * Creates a new DynamicContentProvider with a global supplier.
     *
     * @param supplier the supplier that provides content
     * @throws IllegalArgumentException if supplier is null
     */
    public DynamicContentProvider(Supplier<List<String>> supplier) {
        this(supplier, 5000, -1); // Default 5 second refresh interval
    }

    /**
     * Creates a new DynamicContentProvider with a global supplier and custom refresh interval.
     *
     * @param supplier        the supplier that provides content
     * @param refreshInterval the refresh interval in milliseconds
     * @throws IllegalArgumentException if supplier is null or refreshInterval is negative
     */
    public DynamicContentProvider(Supplier<List<String>> supplier, long refreshInterval) {
        this(supplier, refreshInterval, -1);
    }

    /**
     * Creates a new DynamicContentProvider with a global supplier, refresh interval, and line limit.
     *
     * @param supplier        the supplier that provides content
     * @param refreshInterval the refresh interval in milliseconds
     * @param maxLines        the maximum number of lines to return, or -1 for no limit
     * @throws IllegalArgumentException if supplier is null, refreshInterval is negative, or maxLines is less than -1
     */
    public DynamicContentProvider(Supplier<List<String>> supplier, long refreshInterval, int maxLines) {
        if (supplier == null) {
            throw new IllegalArgumentException("Supplier cannot be null");
        }
        if (refreshInterval < 0) {
            throw new IllegalArgumentException("Refresh interval cannot be negative");
        }
        if (maxLines < -1) {
            throw new IllegalArgumentException("Max lines cannot be less than -1");
        }
        this.globalSupplier = supplier;
        this.playerSupplier = null;
        this.refreshInterval = refreshInterval;
        this.maxLines = maxLines;
        this.playerSpecific = false;
    }

    /**
     * Creates a new DynamicContentProvider with a player-specific supplier.
     *
     * @param playerSupplier the function that provides content for specific players
     * @throws IllegalArgumentException if playerSupplier is null
     */
    public DynamicContentProvider(Function<UUID, List<String>> playerSupplier) {
        this(playerSupplier, 5000, -1); // Default 5 second refresh interval
    }

    /**
     * Creates a new DynamicContentProvider with a player-specific supplier and custom refresh interval.
     *
     * @param playerSupplier  the function that provides content for specific players
     * @param refreshInterval the refresh interval in milliseconds
     * @throws IllegalArgumentException if playerSupplier is null or refreshInterval is negative
     */
    public DynamicContentProvider(Function<UUID, List<String>> playerSupplier, long refreshInterval) {
        this(playerSupplier, refreshInterval, -1);
    }

    /**
     * Creates a new DynamicContentProvider with a player-specific supplier, refresh interval, and line limit.
     *
     * @param playerSupplier  the function that provides content for specific players
     * @param refreshInterval the refresh interval in milliseconds
     * @param maxLines        the maximum number of lines to return, or -1 for no limit
     * @throws IllegalArgumentException if playerSupplier is null, refreshInterval is negative, or maxLines is less than -1
     */
    public DynamicContentProvider(Function<UUID, List<String>> playerSupplier, long refreshInterval, int maxLines) {
        if (playerSupplier == null) {
            throw new IllegalArgumentException("Player supplier cannot be null");
        }
        if (refreshInterval < 0) {
            throw new IllegalArgumentException("Refresh interval cannot be negative");
        }
        if (maxLines < -1) {
            throw new IllegalArgumentException("Max lines cannot be less than -1");
        }
        this.globalSupplier = null;
        this.playerSupplier = playerSupplier;
        this.refreshInterval = refreshInterval;
        this.maxLines = maxLines;
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
            // If content generation fails, return empty list
            return new ArrayList<>();
        }

        if (content == null) {
            return new ArrayList<>();
        }

        if (maxLines == -1 || content.size() <= maxLines) {
            return new ArrayList<>(content);
        }

        return new ArrayList<>(content.subList(0, maxLines));
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
    public int getMaxLines() {
        return maxLines;
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
        // Dynamic content should use shorter cache durations
        if (playerSpecific) {
            return "dynamic_player_" + playerId.toString() + "_" + hashCode();
        } else {
            return "dynamic_global_" + hashCode();
        }
    }

    @Override
    public long getCacheDuration() {
        // Cache for half the refresh interval to ensure freshness
        return refreshInterval / 2;
    }

    /**
     * Gets the current refresh interval.
     *
     * @return the refresh interval in milliseconds
     */
    public long getCurrentRefreshInterval() {
        return refreshInterval;
    }

    /**
     * Checks if this provider is player-specific.
     *
     * @return true if the provider uses player-specific content, false otherwise
     */
    public boolean hasPlayerSpecificContent() {
        return playerSpecific;
    }

    @Override
    public String toString() {
        return "DynamicContentProvider{" +
                "refreshInterval=" + refreshInterval +
                ", maxLines=" + maxLines +
                ", playerSpecific=" + playerSpecific +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        DynamicContentProvider that = (DynamicContentProvider) obj;
        return refreshInterval == that.refreshInterval &&
                maxLines == that.maxLines &&
                playerSpecific == that.playerSpecific;
    }

    @Override
    public int hashCode() {
        int result = (int) (refreshInterval ^ (refreshInterval >>> 32));
        result = 31 * result + maxLines;
        result = 31 * result + (playerSpecific ? 1 : 0);
        return result;
    }
}