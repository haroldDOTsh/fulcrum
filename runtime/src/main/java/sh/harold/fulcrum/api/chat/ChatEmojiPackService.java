package sh.harold.fulcrum.api.chat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages which chat emoji packs a player has access to.
 */
public interface ChatEmojiPackService {

    /**
     * Resolves the emoji packs a player can currently use.
     *
     * @param playerId player identifier
     * @return set of unlocked packs (includes defaults)
     */
    Set<ChatEmojiPack> getUnlockedPacks(UUID playerId);

    /**
     * Checks if the player has access to the provided pack.
     *
     * @param playerId player identifier
     * @param pack     pack to check
     * @return true if the player can use the pack
     */
    default boolean hasPack(UUID playerId, ChatEmojiPack pack) {
        return pack != null && getUnlockedPacks(playerId).contains(pack);
    }

    /**
     * Grants the provided pack to the player.
     *
     * @param playerId player identifier
     * @param pack     pack to grant
     * @return future that completes when the pack is granted
     */
    CompletableFuture<Void> grantPack(UUID playerId, ChatEmojiPack pack);

    /**
     * Revokes the provided pack from the player.
     *
     * @param playerId player identifier
     * @param pack     pack to revoke
     * @return future that completes when the pack is revoked
     */
    CompletableFuture<Void> revokePack(UUID playerId, ChatEmojiPack pack);
}
