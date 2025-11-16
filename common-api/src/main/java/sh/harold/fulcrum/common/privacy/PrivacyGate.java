package sh.harold.fulcrum.common.privacy;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PrivacyGate {

    CompletionStage<PrivacyResult> canSendPartyInvite(UUID actorId, UUID targetId);

    CompletionStage<PrivacyResult> canSendFriendRequest(UUID actorId, UUID targetId);

    CompletionStage<PrivacyResult> canSendDirectMessage(UUID actorId, UUID targetId);

    CompletionStage<PrivacyResult> evaluate(UUID actorId, UUID targetId, PrivacyDomain domain);
}
