package sh.harold.fulcrum.api.messagebus.messages.rank;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.util.*;

/**
 * Broadcast message emitted by the registry whenever a player's rank state changes.
 * Used by runtimes/proxies to refresh local caches.
 */
public final class RankSyncMessage implements BaseMessage, Serializable {

    private static final long serialVersionUID = 1L;

    private UUID playerId;
    private String primaryRankId;
    private Set<String> rankIds = new HashSet<>();

    public RankSyncMessage() {
        // for jackson
    }

    public RankSyncMessage(UUID playerId, String primaryRankId, Set<String> rankIds) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.primaryRankId = primaryRankId;
        if (rankIds != null) {
            this.rankIds = new HashSet<>(rankIds);
        }
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.REGISTRY_RANK_UPDATE;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getPrimaryRankId() {
        return primaryRankId;
    }

    public void setPrimaryRankId(String primaryRankId) {
        this.primaryRankId = primaryRankId;
    }

    public Set<String> getRankIds() {
        return Collections.unmodifiableSet(rankIds);
    }

    public void setRankIds(Set<String> rankIds) {
        this.rankIds = rankIds != null ? new HashSet<>(rankIds) : new HashSet<>();
    }
}
