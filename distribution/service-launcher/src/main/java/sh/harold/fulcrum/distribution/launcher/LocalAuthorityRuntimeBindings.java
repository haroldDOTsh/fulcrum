package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandDelivery;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandSource;
import sh.harold.fulcrum.data.authority.runtime.AuthorityDecisionRecorder;
import sh.harold.fulcrum.data.authority.runtime.AuthorityEmissionSink;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffset;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffsetCommitter;
import sh.harold.fulcrum.data.authority.runtime.AuthorityProjectionWriter;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore;
import sh.harold.fulcrum.data.store.memory.InMemoryAuthorityCommandLog;
import sh.harold.fulcrum.data.store.memory.InMemoryAuthorityDecisionRecorder;
import sh.harold.fulcrum.data.store.memory.InMemoryAuthorityEmissionSink;
import sh.harold.fulcrum.data.store.memory.InMemoryAuthorityProjectionWriter;
import sh.harold.fulcrum.data.store.memory.InMemoryAuthorityRecordStore;
import sh.harold.fulcrum.data.store.memory.InMemoryIdempotencyLedger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

final class LocalAuthorityRuntimeBindings implements AuthorityRuntimeBindings {
    private final Map<String, InMemoryAuthorityCommandLog<? extends CommandPayload>> commandLogs = new ConcurrentHashMap<>();
    private final Map<String, InMemoryAuthorityRecordStore<?>> recordStores = new ConcurrentHashMap<>();
    private final Map<String, InMemoryAuthorityProjectionWriter<?, ?, ?>> projectionWriters = new ConcurrentHashMap<>();
    private final Map<String, InMemoryAuthorityEmissionSink> emissionSinks = new ConcurrentHashMap<>();
    private final Map<String, InMemoryAuthorityDecisionRecorder<?, ?, ?>> decisionRecorders = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyLedger<?, ?>> ledgers = new ConcurrentHashMap<>();
    private final Map<String, Queue<AuthorityOffset>> committedOffsets = new ConcurrentHashMap<>();

    <C extends CommandPayload> void enqueue(String authorityDomain, AuthorityCommandDelivery<C> delivery) {
        this.<C>commandLog(authorityDomain).append(Objects.requireNonNull(delivery, "delivery").command());
    }

    @SuppressWarnings("unchecked")
    <S> Optional<AuthorityRecord<S>> storedRecord(String authorityDomain, AggregateId aggregateId) {
        InMemoryAuthorityRecordStore<S> store = (InMemoryAuthorityRecordStore<S>) recordStores.get(requireDomain(authorityDomain));
        return store == null ? Optional.empty() : store.findStored(aggregateId);
    }

    List<AuthorityOffset> committedOffsets(String authorityDomain) {
        Queue<AuthorityOffset> offsets = committedOffsets.get(requireDomain(authorityDomain));
        return offsets == null ? List.of() : List.copyOf(offsets);
    }

    @Override
    public <C extends CommandPayload> AuthorityCommandSource<C> commandSource(String authorityDomain) {
        return this.<C>commandLog(authorityDomain).openSource();
    }

    @Override
    public <S> AuthorityRecordStore<S> recordStore(
            String authorityDomain,
            Supplier<AuthorityRecord<S>> emptyRecord) {
        String domain = requireDomain(authorityDomain);
        Objects.requireNonNull(emptyRecord, "emptyRecord");
        @SuppressWarnings("unchecked")
        InMemoryAuthorityRecordStore<S> store = (InMemoryAuthorityRecordStore<S>) recordStores.computeIfAbsent(
                domain,
                ignored -> new InMemoryAuthorityRecordStore<>(emptyRecord));
        return store;
    }

    @Override
    public <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> projectionWriter(String authorityDomain) {
        @SuppressWarnings("unchecked")
        InMemoryAuthorityProjectionWriter<S, C, R> writer =
                (InMemoryAuthorityProjectionWriter<S, C, R>) projectionWriters.computeIfAbsent(
                        requireDomain(authorityDomain),
                        ignored -> new InMemoryAuthorityProjectionWriter<>((command, decision) ->
                                new InMemoryAuthorityProjectionWriter.Projection(
                                        command.envelope().aggregateId().value(),
                                        "revision=" + decision.revision().value())));
        return writer;
    }

    @Override
    public AuthorityEmissionSink emissionSink(String authorityDomain) {
        return emissionSinks.computeIfAbsent(requireDomain(authorityDomain), ignored -> new InMemoryAuthorityEmissionSink());
    }

    @Override
    public <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> decisionRecorder(String authorityDomain) {
        @SuppressWarnings("unchecked")
        InMemoryAuthorityDecisionRecorder<S, C, R> recorder =
                (InMemoryAuthorityDecisionRecorder<S, C, R>) decisionRecorders.computeIfAbsent(
                        requireDomain(authorityDomain),
                        ignored -> new InMemoryAuthorityDecisionRecorder<>());
        return recorder;
    }

    @Override
    public AuthorityOffsetCommitter offsetCommitter(String authorityDomain) {
        String domain = requireDomain(authorityDomain);
        return offset -> {
            commandLog(domain).committer().commit(offset);
            committedOffsets.computeIfAbsent(domain, ignored -> new ConcurrentLinkedQueue<>()).add(offset);
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S, R> IdempotencyLedger<S, R> idempotencyLedger(String authorityDomain) {
        return (IdempotencyLedger<S, R>) ledgers.computeIfAbsent(
                requireDomain(authorityDomain),
                ignored -> new InMemoryIdempotencyLedger<>());
    }

    @SuppressWarnings("unchecked")
    private <C extends CommandPayload> InMemoryAuthorityCommandLog<C> commandLog(String authorityDomain) {
        String domain = requireDomain(authorityDomain);
        return (InMemoryAuthorityCommandLog<C>) commandLogs.computeIfAbsent(
                domain,
                ignored -> new InMemoryAuthorityCommandLog<>("cmd." + domain));
    }

    private static String requireDomain(String value) {
        String checked = Objects.requireNonNull(value, "authorityDomain").trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("authorityDomain must not be blank");
        }
        return checked;
    }
}
