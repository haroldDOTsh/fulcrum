package sh.harold.fulcrum.data.store.memory;

import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandDelivery;
import sh.harold.fulcrum.data.authority.runtime.AuthorityDecisionRecorder;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffset;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryAuthorityDecisionRecorder<S, C extends CommandPayload, R>
        implements AuthorityDecisionRecorder<S, C, R> {
    private final Map<CommandId, RecordedDecision<S, R>> decisions = new LinkedHashMap<>();

    @Override
    public synchronized void record(AuthorityCommandDelivery<C> delivery, AuthorityDecision<S, R> decision) {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(decision, "decision");
        decisions.putIfAbsent(
                delivery.command().envelope().commandId(),
                new RecordedDecision<>(delivery.offset(), decision));
    }

    public synchronized Optional<RecordedDecision<S, R>> find(CommandId commandId) {
        Objects.requireNonNull(commandId, "commandId");
        return Optional.ofNullable(decisions.get(commandId));
    }

    public synchronized int size() {
        return decisions.size();
    }

    public record RecordedDecision<S, R>(
            AuthorityOffset offset,
            AuthorityDecision<S, R> decision) {
        public RecordedDecision {
            offset = Objects.requireNonNull(offset, "offset");
            decision = Objects.requireNonNull(decision, "decision");
        }
    }
}
