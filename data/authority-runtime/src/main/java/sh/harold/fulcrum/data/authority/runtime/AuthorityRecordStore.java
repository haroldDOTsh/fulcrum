package sh.harold.fulcrum.data.authority.runtime;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.data.authority.AuthorityRecord;

public interface AuthorityRecordStore<S> {
    AuthorityRecord<S> load(AggregateId aggregateId);

    void store(AggregateId aggregateId, AuthorityRecord<S> record);
}
