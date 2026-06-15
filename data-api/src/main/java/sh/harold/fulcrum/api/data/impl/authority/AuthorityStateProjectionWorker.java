package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRecord;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRestoreResult;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRestoreTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Rebuilds hot projections from compacted authority state-topic records.
 */
public final class AuthorityStateProjectionWorker {
    private static final String IDEMPOTENT_SKIP_MESSAGE = "existing projection is newer or equal";

    private final AuthorityLog log;
    private final AuthorityStateRestoreTarget target;

    public AuthorityStateProjectionWorker(
        AuthorityLog log,
        AuthorityStateRestoreTarget target
    ) {
        this.log = Objects.requireNonNull(log, "log");
        this.target = Objects.requireNonNull(target, "target");
    }

    public String projectionName() {
        return target.projectionName();
    }

    public String projectionVersion() {
        return target.projectionVersion();
    }

    public PartitionResult processPartition(
        String domain,
        int partition,
        long afterOffset,
        int maxRecords
    ) {
        AuthorityDomainTopology.DomainTopology topology = AuthorityDomainTopology.domain(domain);
        validatePartition(topology, partition);
        List<AuthorityLogRecord> records = log.records(topology.stateTopic(), partition, afterOffset, maxRecords);
        return processRecords(topology, partition, records);
    }

    PartitionResult processRecords(
        String domain,
        int partition,
        List<AuthorityLogRecord> records
    ) {
        AuthorityDomainTopology.DomainTopology topology = AuthorityDomainTopology.domain(domain);
        validatePartition(topology, partition);
        return processRecords(topology, partition, records);
    }

    private PartitionResult processRecords(
        AuthorityDomainTopology.DomainTopology topology,
        int partition,
        List<AuthorityLogRecord> records
    ) {
        List<AuthorityLogRecord> safeRecords = records == null ? List.of() : List.copyOf(records);
        List<RecordResult> processed = new ArrayList<>();
        int restoredCount = 0;
        int idempotentSkipCount = 0;
        long lastProcessedOffset = -1L;

        for (AuthorityLogRecord record : safeRecords) {
            RecordResult result = processRecord(topology, partition, record);
            processed.add(result);
            if (result.restoreResult().restored()) {
                restoredCount++;
            } else {
                idempotentSkipCount++;
            }
            lastProcessedOffset = record.offset();
        }

        return new PartitionResult(
            target.projectionName(),
            target.projectionVersion(),
            topology.domain(),
            topology.stateTopic(),
            partition,
            safeRecords.size(),
            processed.size(),
            restoredCount,
            idempotentSkipCount,
            lastProcessedOffset,
            processed
        );
    }

    private RecordResult processRecord(
        AuthorityDomainTopology.DomainTopology topology,
        int partition,
        AuthorityLogRecord record
    ) {
        Objects.requireNonNull(record, "record");
        if (record.kind() != AuthorityLogTopicKind.STATE) {
            throw new IllegalArgumentException("Authority state projection worker expected STATE record");
        }
        if (!topology.stateTopic().equals(record.topic())) {
            throw new IllegalArgumentException("Authority state record topic " + record.topic()
                + " does not match domain state topic " + topology.stateTopic());
        }
        if (record.partition() != partition) {
            throw new IllegalArgumentException("Authority state record partition " + record.partition()
                + " does not match assigned partition " + partition);
        }

        AuthorityStateRecord stateRecord = AuthorityStateRecord.fromLogRecord(record);
        validateStateRecord(topology, partition, record, stateRecord);
        AuthorityStateRestoreResult restoreResult = target.restore(stateRecord);
        if (!canAdvance(restoreResult)) {
            throw new IllegalStateException("Authority state projection " + target.projectionName()
                + " refused " + stateRecord.aggregateScope() + " at " + record.topic()
                + "-" + record.partition() + "@" + record.offset() + ": " + restoreResult.message());
        }
        return new RecordResult(record, stateRecord, restoreResult);
    }

    private void validateStateRecord(
        AuthorityDomainTopology.DomainTopology topology,
        int partition,
        AuthorityLogRecord logRecord,
        AuthorityStateRecord stateRecord
    ) {
        if (!topology.domain().equals(stateRecord.commandDomain())) {
            throw new IllegalArgumentException("State record command domain " + stateRecord.commandDomain()
                + " does not match topology domain " + topology.domain());
        }
        if (!topology.stateTopic().equals(stateRecord.stateTopic())) {
            throw new IllegalArgumentException("State record topic " + stateRecord.stateTopic()
                + " does not match topology state topic " + topology.stateTopic());
        }
        if (stateRecord.sourcePartition() != partition) {
            throw new IllegalArgumentException("State record source partition " + stateRecord.sourcePartition()
                + " does not match assigned partition " + partition);
        }
        if (stateRecord.sourceOffset() != logRecord.offset()) {
            throw new IllegalArgumentException("State record source offset " + stateRecord.sourceOffset()
                + " does not match log offset " + logRecord.offset());
        }
        if (!Objects.equals(logRecord.key(), stateRecord.partitionKey())) {
            throw new IllegalArgumentException("State record partition key " + stateRecord.partitionKey()
                + " does not match log key " + logRecord.key());
        }
    }

    private static boolean canAdvance(AuthorityStateRestoreResult result) {
        if (result == null) {
            return false;
        }
        return result.restored() || IDEMPOTENT_SKIP_MESSAGE.equals(result.message());
    }

    private static void validatePartition(AuthorityDomainTopology.DomainTopology topology, int partition) {
        if (partition < 0 || partition >= topology.partitionCount()) {
            throw new IllegalArgumentException("Authority state projection partition " + partition
                + " is outside domain " + topology.domain() + " partition count " + topology.partitionCount());
        }
    }

    public record PartitionResult(
        String projectionName,
        String projectionVersion,
        String domain,
        String stateTopic,
        int partition,
        int scannedCount,
        int processedCount,
        int restoredCount,
        int idempotentSkipCount,
        long lastProcessedOffset,
        List<RecordResult> processed
    ) {
        public PartitionResult {
            projectionName = requireText(projectionName, "projectionName");
            projectionVersion = requireText(projectionVersion, "projectionVersion");
            domain = requireText(domain, "domain");
            stateTopic = requireText(stateTopic, "stateTopic");
            if (partition < 0) {
                throw new IllegalArgumentException("partition must be non-negative");
            }
            if (scannedCount < 0) {
                throw new IllegalArgumentException("scannedCount must not be negative");
            }
            if (processedCount < 0 || processedCount > scannedCount) {
                throw new IllegalArgumentException("processedCount must be within scannedCount");
            }
            if (restoredCount < 0 || idempotentSkipCount < 0
                || restoredCount + idempotentSkipCount != processedCount) {
                throw new IllegalArgumentException("restored and idempotent counts must sum to processedCount");
            }
            if (lastProcessedOffset < -1L) {
                throw new IllegalArgumentException("lastProcessedOffset must be -1 or greater");
            }
            processed = processed == null ? List.of() : List.copyOf(processed);
        }
    }

    public record RecordResult(
        AuthorityLogRecord logRecord,
        AuthorityStateRecord stateRecord,
        AuthorityStateRestoreResult restoreResult
    ) {
        public RecordResult {
            logRecord = Objects.requireNonNull(logRecord, "logRecord");
            stateRecord = Objects.requireNonNull(stateRecord, "stateRecord");
            restoreResult = Objects.requireNonNull(restoreResult, "restoreResult");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
