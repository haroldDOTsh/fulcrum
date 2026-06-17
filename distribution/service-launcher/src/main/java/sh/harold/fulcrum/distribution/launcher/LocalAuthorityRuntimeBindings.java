package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandDelivery;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandSource;
import sh.harold.fulcrum.data.authority.runtime.AuthorityDecisionRecorder;
import sh.harold.fulcrum.data.authority.runtime.AuthorityEmissionSink;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffset;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffsetCommitter;
import sh.harold.fulcrum.data.authority.runtime.AuthorityProjectionWriter;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

final class LocalAuthorityRuntimeBindings implements AuthorityRuntimeBindings {
    private final Map<String, Queue<AuthorityCommandDelivery<? extends CommandPayload>>> commandQueues = new ConcurrentHashMap<>();
    private final Map<String, AuthorityRecord<?>> records = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyLedger<?, ?>> ledgers = new ConcurrentHashMap<>();
    private final Map<String, Queue<AuthorityOffset>> committedOffsets = new ConcurrentHashMap<>();

    <C extends CommandPayload> void enqueue(String authorityDomain, AuthorityCommandDelivery<C> delivery) {
        commandQueues.computeIfAbsent(requireDomain(authorityDomain), ignored -> new ConcurrentLinkedQueue<>())
                .add(Objects.requireNonNull(delivery, "delivery"));
    }

    @SuppressWarnings("unchecked")
    <S> Optional<AuthorityRecord<S>> storedRecord(String authorityDomain, AggregateId aggregateId) {
        return Optional.ofNullable((AuthorityRecord<S>) records.get(recordKey(authorityDomain, aggregateId)));
    }

    List<AuthorityOffset> committedOffsets(String authorityDomain) {
        Queue<AuthorityOffset> offsets = committedOffsets.get(requireDomain(authorityDomain));
        return offsets == null ? List.of() : List.copyOf(offsets);
    }

    @Override
    public <C extends CommandPayload> AuthorityCommandSource<C> commandSource(String authorityDomain) {
        String domain = requireDomain(authorityDomain);
        return () -> {
            Queue<AuthorityCommandDelivery<? extends CommandPayload>> queue = commandQueues.get(domain);
            if (queue == null) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            AuthorityCommandDelivery<C> delivery = (AuthorityCommandDelivery<C>) queue.poll();
            return Optional.ofNullable(delivery);
        };
    }

    @Override
    public <S> AuthorityRecordStore<S> recordStore(
            String authorityDomain,
            Supplier<AuthorityRecord<S>> emptyRecord) {
        String domain = requireDomain(authorityDomain);
        Objects.requireNonNull(emptyRecord, "emptyRecord");
        return new AuthorityRecordStore<>() {
            @Override
            @SuppressWarnings("unchecked")
            public AuthorityRecord<S> load(AggregateId aggregateId) {
                return (AuthorityRecord<S>) records.computeIfAbsent(
                        recordKey(domain, aggregateId),
                        ignored -> emptyRecord.get());
            }

            @Override
            public void store(AggregateId aggregateId, AuthorityRecord<S> record) {
                records.put(recordKey(domain, aggregateId), Objects.requireNonNull(record, "record"));
            }
        };
    }

    @Override
    public <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> projectionWriter(String authorityDomain) {
        requireDomain(authorityDomain);
        return (command, decision) -> {
        };
    }

    @Override
    public AuthorityEmissionSink emissionSink(String authorityDomain) {
        requireDomain(authorityDomain);
        return emission -> {
        };
    }

    @Override
    public <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> decisionRecorder(String authorityDomain) {
        requireDomain(authorityDomain);
        return (delivery, decision) -> {
        };
    }

    @Override
    public AuthorityOffsetCommitter offsetCommitter(String authorityDomain) {
        String domain = requireDomain(authorityDomain);
        return offset -> committedOffsets.computeIfAbsent(domain, ignored -> new ConcurrentLinkedQueue<>()).add(offset);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S, R> IdempotencyLedger<S, R> idempotencyLedger(String authorityDomain) {
        return (IdempotencyLedger<S, R>) ledgers.computeIfAbsent(
                requireDomain(authorityDomain),
                ignored -> new InMemoryIdempotencyLedger<>());
    }

    private static String recordKey(String authorityDomain, AggregateId aggregateId) {
        return requireDomain(authorityDomain) + "|" + Objects.requireNonNull(aggregateId, "aggregateId").value();
    }

    private static String requireDomain(String value) {
        String checked = Objects.requireNonNull(value, "authorityDomain").trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("authorityDomain must not be blank");
        }
        return checked;
    }
}
