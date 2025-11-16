package sh.harold.fulcrum.fundamentals.playerdata;

import sh.harold.fulcrum.common.privacy.PrivacySignals;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class RuntimePrivacySignals implements PrivacySignals {

    @Override
    public CompletionStage<Boolean> shareServer(UUID actorId, UUID targetId) {
        // Paper runtime only sees players on the same server, so treat server checks as satisfied.
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletionStage<Boolean> shareParty(UUID actorId, UUID targetId) {
        // Placeholder until the runtime can resolve cross-server party membership.
        return CompletableFuture.completedFuture(false);
    }
}
