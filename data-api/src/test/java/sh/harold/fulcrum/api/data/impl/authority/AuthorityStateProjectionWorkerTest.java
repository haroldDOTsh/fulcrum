package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRecord;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRestoreResult;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRestoreTarget;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorityStateProjectionWorkerTest {
    @Test
    void restoresStateTopicRecordAndAdvancesProjectionCursor() {
        UUID playerId = UUID.randomUUID();
        AuthorityLogRecord stateRecord = stateRecord(playerId, 4L, 12L);
        CapturingRestoreTarget target = new CapturingRestoreTarget(validFingerprintResult());
        AuthorityStateProjectionWorker worker = new AuthorityStateProjectionWorker(new InMemoryAuthorityLog(), target);
        InMemoryAuthorityStateProjectionCursorStore cursorStore = new InMemoryAuthorityStateProjectionCursorStore();

        AuthorityStateProjectionWorker.PartitionResult result = worker.processRecords(
            "rank",
            stateRecord.partition(),
            List.of(stateRecord)
        );
        cursorStore.recordApplied(result);

        assertThat(result.projectionName()).isEqualTo("test-hot-state");
        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.restoredCount()).isEqualTo(1);
        assertThat(result.idempotentSkipCount()).isZero();
        assertThat(result.lastProcessedOffset()).isEqualTo(12L);
        assertThat(target.record().get()).satisfies(record -> {
            assertThat(record.commandDomain()).isEqualTo("rank");
            assertThat(record.stateTopic()).isEqualTo("state.rank");
            assertThat(record.sourcePartition()).isEqualTo(stateRecord.partition());
            assertThat(record.sourceOffset()).isEqualTo(12L);
            assertThat(record.hasValidStateFingerprint()).isTrue();
        });
        assertThat(cursorStore.cursor("test-hot-state", "test-hot-state-v1", "rank", stateRecord.partition()))
            .hasValueSatisfying(cursor -> {
                assertThat(cursor.stateTopic()).isEqualTo("state.rank");
                assertThat(cursor.committedOffset()).isEqualTo(12L);
                assertThat(cursor.lastRestoreApplied()).isTrue();
                assertThat(cursor.lastRestoreMessage()).isEqualTo("restored");
            });
    }

    @Test
    void duplicateOrNewerProjectionSkipsAdvanceCursor() {
        UUID playerId = UUID.randomUUID();
        AuthorityLogRecord stateRecord = stateRecord(playerId, 5L, 13L);
        AuthorityStateProjectionWorker worker = new AuthorityStateProjectionWorker(
            new InMemoryAuthorityLog(),
            new CapturingRestoreTarget(record -> AuthorityStateRestoreResult.skipped(
                "test-hot-state-v1",
                record,
                "existing projection is newer or equal"
            ))
        );
        InMemoryAuthorityStateProjectionCursorStore cursorStore = new InMemoryAuthorityStateProjectionCursorStore();

        AuthorityStateProjectionWorker.PartitionResult result = worker.processRecords(
            "rank",
            stateRecord.partition(),
            List.of(stateRecord)
        );
        cursorStore.recordApplied(result);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.restoredCount()).isZero();
        assertThat(result.idempotentSkipCount()).isEqualTo(1);
        assertThat(cursorStore.committedOffset("test-hot-state", "test-hot-state-v1", "rank", stateRecord.partition()))
            .isEqualTo(13L);
    }

    @Test
    void unsafeRestoreSkipFailsClosedWithoutCursorAdvance() {
        UUID playerId = UUID.randomUUID();
        AuthorityLogRecord stateRecord = stateRecord(playerId, 6L, 14L);
        AuthorityStateProjectionWorker worker = new AuthorityStateProjectionWorker(
            new InMemoryAuthorityLog(),
            new CapturingRestoreTarget(record -> AuthorityStateRestoreResult.skipped(
                "test-hot-state-v1",
                record,
                "state fingerprint mismatch"
            ))
        );
        InMemoryAuthorityStateProjectionCursorStore cursorStore = new InMemoryAuthorityStateProjectionCursorStore();

        assertThatThrownBy(() -> {
            AuthorityStateProjectionWorker.PartitionResult result = worker.processRecords(
                "rank",
                stateRecord.partition(),
                List.of(stateRecord)
            );
            cursorStore.recordApplied(result);
        })
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("state fingerprint mismatch");

        assertThat(cursorStore.committedOffset("test-hot-state", "test-hot-state-v1", "rank", stateRecord.partition()))
            .isEqualTo(-1L);
    }

    @Test
    void rejectsStateRecordForWrongDomainTopic() {
        UUID playerId = UUID.randomUUID();
        AuthorityLogRecord record = stateRecord(playerId, 7L, 15L);
        AuthorityLogRecord wrongTopic = new AuthorityLogRecord(
            "state.player",
            record.key(),
            record.partition(),
            record.offset(),
            record.kind(),
            record.payload(),
            record.headers(),
            record.appendedAtEpochMillis()
        );
        AuthorityStateProjectionWorker worker = new AuthorityStateProjectionWorker(
            new InMemoryAuthorityLog(),
            new CapturingRestoreTarget(validFingerprintResult())
        );

        assertThatThrownBy(() -> worker.processRecords("rank", wrongTopic.partition(), List.of(wrongTopic)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match domain state topic");
    }

    @Test
    void loopResumesStateTopicProjectionFromCursor() {
        UUID playerId = UUID.randomUUID();
        String partitionKey = "rank:player:" + playerId;
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        AuthorityCommandRoute route = AuthorityCommandRoute.fromDeclarationId(
            "GRANT_RANK",
            partitionKey
        );
        log.append(route, AuthorityLogTopicKind.STATE, statePayload(playerId, partitionKey, 1L));
        log.append(route, AuthorityLogTopicKind.STATE, statePayload(playerId, partitionKey, 2L));
        AtomicInteger restoreCount = new AtomicInteger();
        AuthorityStateProjectionWorker worker = new AuthorityStateProjectionWorker(
            log,
            new CapturingRestoreTarget(record -> {
                restoreCount.incrementAndGet();
                return AuthorityStateRestoreResult.restored("test-hot-state-v1", record);
            })
        );
        InMemoryAuthorityStateProjectionCursorStore cursorStore = new InMemoryAuthorityStateProjectionCursorStore();
        int partition = AuthorityLogTopology.partition(route);
        AuthorityStateProjectionWorkerLoop loop = new AuthorityStateProjectionWorkerLoop(
            worker,
            "rank",
            List.of(partition),
            1,
            cursorStore
        );

        AuthorityStateProjectionWorkerLoop.PollResult first = loop.pollOnce();
        AuthorityStateProjectionWorkerLoop.PollResult second = loop.pollOnce();
        AuthorityStateProjectionWorkerLoop.PollResult empty = loop.pollOnce();

        assertThat(first.processedCount()).isEqualTo(1);
        assertThat(second.processedCount()).isEqualTo(1);
        assertThat(empty.processedCount()).isZero();
        assertThat(restoreCount.get()).isEqualTo(2);
        assertThat(loop.committedOffset(partition)).isEqualTo(1L);
    }

    private static RestoreBehavior validFingerprintResult() {
        return record -> AuthorityStateRestoreResult.restored("test-hot-state-v1", record);
    }

    private static AuthorityLogRecord stateRecord(
        UUID playerId,
        long revision,
        long offset
    ) {
        String partitionKey = "rank:player:" + playerId;
        int partition = AuthorityLogTopology.partition("rank", partitionKey);
        Map<String, Object> statePayload = rankStatePayload(playerId, revision);
        Map<String, Object> payload = statePayload(playerId, partitionKey, revision, statePayload);
        return new AuthorityLogRecord(
            "state.rank",
            partitionKey,
            partition,
            offset,
            AuthorityLogTopicKind.STATE,
            payload,
            Map.of(),
            Instant.now().toEpochMilli()
        );
    }

    private static Map<String, Object> statePayload(UUID playerId, String partitionKey, long revision) {
        return statePayload(playerId, partitionKey, revision, rankStatePayload(playerId, revision));
    }

    private static Map<String, Object> rankStatePayload(UUID playerId, long revision) {
        return Map.of(
            "playerId", playerId.toString(),
            "primaryRank", "ADMIN",
            "ranks", List.of("DEFAULT", "ADMIN"),
            "revision", revision
        );
    }

    private static Map<String, Object> statePayload(
        UUID playerId,
        String partitionKey,
        long revision,
        Map<String, Object> statePayload
    ) {
        return Map.ofEntries(
            Map.entry("frameType", "STATE"),
            Map.entry("commandId", UUID.randomUUID().toString()),
            Map.entry("aggregateScope", partitionKey),
            Map.entry("aggregateType", "player_rank"),
            Map.entry("aggregateId", playerId.toString()),
            Map.entry("revision", revision),
            Map.entry("commandDomain", "rank"),
            Map.entry("stateTopic", "state.rank"),
            Map.entry("partitionKey", partitionKey),
            Map.entry("eventId", UUID.randomUUID().toString()),
            Map.entry("eventCreatedEpochMillis", Instant.now().toEpochMilli()),
            Map.entry("stateFingerprint", AuthorityStateRecord.stateFingerprint(statePayload)),
            Map.entry("eventChainHash", "a".repeat(64)),
            Map.entry("statePayload", statePayload)
        );
    }

    private interface RestoreBehavior {
        AuthorityStateRestoreResult restore(AuthorityStateRecord record);
    }

    private static final class CapturingRestoreTarget implements AuthorityStateRestoreTarget {
        private final AtomicReference<AuthorityStateRecord> record = new AtomicReference<>();
        private final RestoreBehavior restoreBehavior;

        private CapturingRestoreTarget(RestoreBehavior restoreBehavior) {
            this.restoreBehavior = restoreBehavior;
        }

        private AtomicReference<AuthorityStateRecord> record() {
            return record;
        }

        @Override
        public String projectionName() {
            return "test-hot-state";
        }

        @Override
        public String projectionVersion() {
            return "test-hot-state-v1";
        }

        @Override
        public AuthorityStateRestoreResult restore(AuthorityStateRecord stateRecord) {
            record.set(stateRecord);
            return restoreBehavior.restore(stateRecord);
        }
    }
}
