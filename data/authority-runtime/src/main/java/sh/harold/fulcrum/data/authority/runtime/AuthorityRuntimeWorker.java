package sh.harold.fulcrum.data.authority.runtime;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityRecord;

import java.util.Objects;
import java.util.Optional;

public final class AuthorityRuntimeWorker<S, C extends CommandPayload, R> {
    private final AuthorityCommandSource<C> commandSource;
    private final AuthorityRecordStore<S> recordStore;
    private final AuthorityDomainHandler<S, C, R> domainHandler;
    private final AuthorityProjectionWriter<S, C, R> projectionWriter;
    private final AuthorityEmissionSink emissionSink;
    private final AuthorityDecisionRecorder<S, C, R> decisionRecorder;
    private final AuthorityOffsetCommitter offsetCommitter;

    public AuthorityRuntimeWorker(
            AuthorityCommandSource<C> commandSource,
            AuthorityRecordStore<S> recordStore,
            AuthorityDomainHandler<S, C, R> domainHandler,
            AuthorityProjectionWriter<S, C, R> projectionWriter,
            AuthorityEmissionSink emissionSink,
            AuthorityDecisionRecorder<S, C, R> decisionRecorder,
            AuthorityOffsetCommitter offsetCommitter) {
        this.commandSource = Objects.requireNonNull(commandSource, "commandSource");
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.domainHandler = Objects.requireNonNull(domainHandler, "domainHandler");
        this.projectionWriter = Objects.requireNonNull(projectionWriter, "projectionWriter");
        this.emissionSink = Objects.requireNonNull(emissionSink, "emissionSink");
        this.decisionRecorder = Objects.requireNonNull(decisionRecorder, "decisionRecorder");
        this.offsetCommitter = Objects.requireNonNull(offsetCommitter, "offsetCommitter");
    }

    public Optional<AuthorityRuntimeReceipt> handleNext() {
        Optional<AuthorityCommandDelivery<C>> maybeDelivery = commandSource.poll();
        if (maybeDelivery.isEmpty()) {
            return Optional.empty();
        }

        AuthorityCommandDelivery<C> delivery = maybeDelivery.orElseThrow();
        AggregateId aggregateId = delivery.command().envelope().aggregateId();
        AuthorityRecord<S> currentRecord = recordStore.load(aggregateId);
        AuthorityDecision<S, R> decision = domainHandler.handle(delivery.command(), currentRecord);

        if (decision.status() == AuthorityDecisionStatus.ACCEPTED && !decision.replayed()) {
            recordStore.store(aggregateId, new AuthorityRecord<>(
                    decision.revision(),
                    currentRecord.fencingEpoch(),
                    decision.state()));
            projectionWriter.write(delivery.command(), decision);
            decision.emissions().forEach(emissionSink::publish);
        }

        decisionRecorder.record(delivery, decision);
        offsetCommitter.commit(delivery.offset());
        return Optional.of(new AuthorityRuntimeReceipt(
                delivery.offset(),
                aggregateId,
                decision.status(),
                decision.revision(),
                decision.replayed()));
    }
}
