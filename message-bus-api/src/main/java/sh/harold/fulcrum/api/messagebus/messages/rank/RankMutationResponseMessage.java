package sh.harold.fulcrum.api.messagebus.messages.rank;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.util.*;

/**
 * Response message sent from the registry back to the originating server
 * once a rank mutation request has been processed.
 */
public final class RankMutationResponseMessage implements BaseMessage, Serializable {

    private static final long serialVersionUID = 1L;

    private UUID requestId;
    private boolean success;
    private String error;
    private String primaryRankId;
    private Set<String> rankIds = new HashSet<>();

    public RankMutationResponseMessage() {
        // for jackson
    }

    public RankMutationResponseMessage(UUID requestId,
                                       boolean success,
                                       String error,
                                       String primaryRankId,
                                       Set<String> rankIds) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.success = success;
        this.error = error;
        this.primaryRankId = primaryRankId;
        if (rankIds != null) {
            this.rankIds = new HashSet<>(rankIds);
        }
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.REGISTRY_RANK_MUTATION_RESPONSE;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
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
