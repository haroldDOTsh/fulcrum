package sh.harold.fulcrum.data.store.memory;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class InMemoryAuthorityRecordStore<S> implements AuthorityRecordStore<S> {
    private final Supplier<AuthorityRecord<S>> emptyRecord;
    private final Map<AggregateId, AuthorityRecord<S>> records = new LinkedHashMap<>();

    public InMemoryAuthorityRecordStore(Supplier<AuthorityRecord<S>> emptyRecord) {
        this.emptyRecord = Objects.requireNonNull(emptyRecord, "emptyRecord");
    }

    @Override
    public synchronized AuthorityRecord<S> load(AggregateId aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId");
        return records.getOrDefault(aggregateId, emptyRecord.get());
    }

    @Override
    public synchronized void store(AggregateId aggregateId, AuthorityRecord<S> record) {
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(record, "record");
        AuthorityRecord<S> current = records.getOrDefault(aggregateId, emptyRecord.get());
        if (record.revision().value() <= current.revision().value()) {
            throw new IllegalStateException("stored authority record must advance revision");
        }
        records.put(aggregateId, record);
    }

    public synchronized boolean compareAndSet(
            AggregateId aggregateId,
            Revision expectedRevision,
            AuthorityRecord<S> record) {
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Objects.requireNonNull(record, "record");
        AuthorityRecord<S> current = records.getOrDefault(aggregateId, emptyRecord.get());
        if (!current.revision().equals(expectedRevision)) {
            return false;
        }
        records.put(aggregateId, record);
        return true;
    }

    public synchronized Optional<AuthorityRecord<S>> findStored(AggregateId aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId");
        return Optional.ofNullable(records.get(aggregateId));
    }
}
