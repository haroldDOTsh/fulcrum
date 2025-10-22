package sh.harold.fulcrum.api.messagebus.messages.rank;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.rank.RankChangeContext;
import sh.harold.fulcrum.api.rank.RankMutationType;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Request message sent to the registry to mutate a player's rank state.
 */
public final class RankMutationRequestMessage implements BaseMessage, Serializable {

    private static final long serialVersionUID = 1L;

    private UUID requestId;
    private UUID playerId;
    private String playerName;
    private RankMutationType mutationType;
    private String rankId;
    private RankChangeContext context;

    public RankMutationRequestMessage() {
        // for jackson
    }

    public RankMutationRequestMessage(UUID requestId,
                                      UUID playerId,
                                      RankMutationType mutationType,
                                      String rankId,
                                      RankChangeContext context,
                                      String playerName) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.mutationType = Objects.requireNonNull(mutationType, "mutationType");
        this.rankId = rankId;
        this.context = Objects.requireNonNull(context, "context");
        this.playerName = playerName;
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.REGISTRY_RANK_MUTATION_REQUEST;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public RankMutationType getMutationType() {
        return mutationType;
    }

    public void setMutationType(RankMutationType mutationType) {
        this.mutationType = mutationType;
    }

    public String getRankId() {
        return rankId;
    }

    public void setRankId(String rankId) {
        this.rankId = rankId;
    }

    public RankChangeContext getContext() {
        return context;
    }

    public void setContext(RankChangeContext context) {
        this.context = context;
    }
}
