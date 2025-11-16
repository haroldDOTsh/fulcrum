package sh.harold.fulcrum.common.privacy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Provides live presence signals (shared server, shared party, etc.) used by the privacy gate.
 */
public interface PrivacySignals {

    static PrivacySignals none() {
        return new PrivacySignals() {
            @Override
            public CompletionStage<Boolean> shareServer(UUID actorId, UUID targetId) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletionStage<Boolean> shareParty(UUID actorId, UUID targetId) {
                return CompletableFuture.completedFuture(false);
            }
        };
    }

    CompletionStage<Boolean> shareServer(UUID actorId, UUID targetId);

    CompletionStage<Boolean> shareParty(UUID actorId, UUID targetId);

    default CompletionStage<Boolean> shareSmpWorld(UUID actorId, UUID targetId) {
        return CompletableFuture.completedFuture(false);
    }
}
