package sh.harold.fulcrum.api.player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.status.PlayerStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Shared directory for resolving lightweight player profiles across the network.
 * Implementations should lean on local caches first, then defer to session snapshots
 * or persistent storage when necessary.
 */
public interface PlayerDirectory {

    CompletionStage<PlayerProfile> getProfile(UUID playerId, ProfileQuery query);

    default CompletionStage<PlayerProfile> getProfile(UUID playerId) {
        return getProfile(playerId, ProfileQuery.DEFAULT);
    }

    CompletionStage<Map<UUID, PlayerProfile>> getProfiles(Collection<UUID> playerIds, ProfileQuery query);

    default CompletionStage<Map<UUID, PlayerProfile>> getProfiles(Collection<UUID> playerIds) {
        return getProfiles(playerIds, ProfileQuery.DEFAULT);
    }

    CompletionStage<Optional<PlayerProfile>> findProfileByName(String username, ProfileQuery query);

    default CompletionStage<Optional<PlayerProfile>> findProfileByName(String username) {
        return findProfileByName(username, ProfileQuery.DEFAULT);
    }

    /**
     * Fetch the current status snapshot for a single player.
     */
    CompletionStage<PlayerStatus> getStatus(UUID playerId);

    /**
     * Fetch status snapshots for a batch of players.
     */
    CompletionStage<Map<UUID, PlayerStatus>> getStatuses(Collection<UUID> playerIds);

    /**
     * Evicts any cached representation for the given player.
     */
    default void invalidate(UUID playerId) {
        // no-op
    }

    /**
     * Convenience helper for invalidating a batch of player ids.
     */
    default void invalidateAll(Collection<UUID> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return;
        }
        for (UUID playerId : playerIds) {
            invalidate(playerId);
        }
    }

    /**
     * Returns the cached profile if available without triggering any I/O.
     */
    default Optional<PlayerProfile> peek(UUID playerId) {
        return Optional.empty();
    }

    /**
     * Query parameters describing how fresh the directory response should be.
     *
     * @param requireFresh    when true, bypasses any cached entries.
     * @param includeRankData when false, implementations may skip any heavy rank lookups.
     */
    record ProfileQuery(boolean requireFresh, boolean includeRankData) {
        public static final ProfileQuery DEFAULT = new ProfileQuery(false, true);
    }

    /**
     * Lightweight snapshot describing a player's identity at a point in time.
     *
     * @param playerId      unique identifier of the player.
     * @param username      last known username (null when unknown).
     * @param primaryRank   primary rank selection (defaults to {@link Rank#DEFAULT}).
     * @param effectiveRank highest priority rank after layering (defaults to {@link Rank#DEFAULT}).
     * @param lastSeen      last observed connection timestamp.
     * @param formattedName Component ready for UI surfaces (rank prefix + coloured name).
     */
    record PlayerProfile(UUID playerId,
                         String username,
                         Rank primaryRank,
                         Rank effectiveRank,
                         Instant lastSeen,
                         Component formattedName) {

        public static PlayerProfile missing(UUID playerId) {
            return new PlayerProfile(
                    playerId,
                    null,
                    Rank.DEFAULT,
                    Rank.DEFAULT,
                    Instant.EPOCH,
                    Component.text("Unknown", NamedTextColor.GRAY)
            );
        }

        public boolean exists() {
            return username != null && !username.isBlank();
        }
    }
}
