package sh.harold.fulcrum.data.authority.runtime;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthorityRuntimeWorkerTest {
    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("authority-runtime-client");
    private static final AggregateId AGGREGATE = new AggregateId("authority-runtime-aggregate");
    private static final AuthorityOffset OFFSET = new AuthorityOffset("authority-command-log", 2, 12);
    private static final AuthorityRecord<TestState> INITIAL =
            new AuthorityRecord<>(new Revision(0), 7, new TestState("initial"));

    @Test
    void acceptedCommandPersistsOutputsThenCommitsOffset() {
        List<String> sequence = new ArrayList<>();
        RecordingRecordStore recordStore = new RecordingRecordStore(INITIAL, sequence);
        RecordingProjectionWriter projectionWriter = new RecordingProjectionWriter(sequence);
        RecordingEmissionSink emissionSink = new RecordingEmissionSink(sequence);
        RecordingDecisionRecorder decisionRecorder = new RecordingDecisionRecorder(sequence);
        RecordingOffsetCommitter offsetCommitter = new RecordingOffsetCommitter(sequence);
        AuthorityCommandDelivery<SetValue> delivery = new AuthorityCommandDelivery<>(
                command("command-1", "idem-1", Optional.of(new Revision(0)), "accepted"),
                OFFSET);

        AuthorityRuntimeWorker<TestState, SetValue, TestReceipt> worker = new AuthorityRuntimeWorker<>(
                () -> {
                    sequence.add("poll");
                    return Optional.of(delivery);
                },
                recordStore,
                (command, currentRecord) -> {
                    sequence.add("handle:" + currentRecord.state().value());
                    return AuthorityDecision.accepted(
                            new Revision(1),
                            new TestState(command.envelope().payload().value()),
                            new TestReceipt("accepted"),
                            emissions(command.envelope().aggregateId().value()),
                            trace());
                },
                projectionWriter,
                emissionSink,
                decisionRecorder,
                offsetCommitter);

        Optional<AuthorityRuntimeReceipt> receipt = worker.handleNext();

        assertTrue(receipt.isPresent());
        assertEquals(OFFSET, receipt.orElseThrow().committedOffset());
        assertEquals(AGGREGATE, receipt.orElseThrow().aggregateId());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, receipt.orElseThrow().status());
        assertEquals(new Revision(1), receipt.orElseThrow().revision());
        assertFalse(receipt.orElseThrow().replayed());
        assertEquals(new AuthorityRecord<>(new Revision(1), 7, new TestState("accepted")), recordStore.storedRecord);
        assertEquals(
                List.of(
                        "poll",
                        "load:" + AGGREGATE.value(),
                        "handle:initial",
                        "store:1:accepted",
                        "projection:1",
                        "emit:EVENT",
                        "emit:STATE",
                        "emit:RESPONSE",
                        "emit:CACHE_WRITE",
                        "decision:ACCEPTED",
                        "commit:12"),
                sequence);
        assertEquals(4, emissionSink.published.size());
        assertEquals(1, projectionWriter.written.size());
        assertEquals(1, decisionRecorder.recorded.size());
        assertEquals(List.of(OFFSET), offsetCommitter.committed);
    }

    @Test
    void rejectedCommandRecordsDecisionAndCommitsWithoutAcceptedOutputs() {
        List<String> sequence = new ArrayList<>();
        RecordingRecordStore recordStore = new RecordingRecordStore(INITIAL, sequence);
        RecordingProjectionWriter projectionWriter = new RecordingProjectionWriter(sequence);
        RecordingEmissionSink emissionSink = new RecordingEmissionSink(sequence);
        RecordingDecisionRecorder decisionRecorder = new RecordingDecisionRecorder(sequence);
        RecordingOffsetCommitter offsetCommitter = new RecordingOffsetCommitter(sequence);
        AuthorityCommandDelivery<SetValue> delivery = new AuthorityCommandDelivery<>(
                command("command-2", "idem-2", Optional.of(new Revision(3)), "rejected"),
                OFFSET);

        AuthorityRuntimeWorker<TestState, SetValue, TestReceipt> worker = new AuthorityRuntimeWorker<>(
                () -> {
                    sequence.add("poll");
                    return Optional.of(delivery);
                },
                recordStore,
                (command, currentRecord) -> {
                    sequence.add("handle:" + currentRecord.state().value());
                    return AuthorityDecision.rejected(
                            AuthorityRejectionReason.REVISION_MISMATCH,
                            currentRecord.revision(),
                            currentRecord.state(),
                            new TestReceipt("rejected"),
                            trace());
                },
                projectionWriter,
                emissionSink,
                decisionRecorder,
                offsetCommitter);

        Optional<AuthorityRuntimeReceipt> receipt = worker.handleNext();

        assertTrue(receipt.isPresent());
        assertEquals(AuthorityDecisionStatus.REJECTED, receipt.orElseThrow().status());
        assertEquals(new Revision(0), receipt.orElseThrow().revision());
        assertFalse(recordStore.stored);
        assertTrue(projectionWriter.written.isEmpty());
        assertTrue(emissionSink.published.isEmpty());
        assertEquals(1, decisionRecorder.recorded.size());
        assertEquals(List.of(OFFSET), offsetCommitter.committed);
        assertEquals(
                List.of(
                        "poll",
                        "load:" + AGGREGATE.value(),
                        "handle:initial",
                        "decision:REJECTED",
                        "commit:12"),
                sequence);
    }

    @Test
    void emptyPollDoesNotTouchRuntimePorts() {
        List<String> sequence = new ArrayList<>();
        AuthorityRuntimeWorker<TestState, SetValue, TestReceipt> worker = new AuthorityRuntimeWorker<>(
                () -> {
                    sequence.add("poll");
                    return Optional.empty();
                },
                new AuthorityRecordStore<>() {
                    @Override
                    public AuthorityRecord<TestState> load(AggregateId aggregateId) {
                        throw new AssertionError("record store should not be loaded");
                    }

                    @Override
                    public void store(AggregateId aggregateId, AuthorityRecord<TestState> record) {
                        throw new AssertionError("record store should not be written");
                    }
                },
                (command, currentRecord) -> {
                    throw new AssertionError("domain handler should not be called");
                },
                (command, decision) -> {
                    throw new AssertionError("projection writer should not be called");
                },
                emission -> {
                    throw new AssertionError("emission sink should not be called");
                },
                (delivery, decision) -> {
                    throw new AssertionError("decision recorder should not be called");
                },
                offset -> {
                    throw new AssertionError("offset committer should not be called");
                });

        Optional<AuthorityRuntimeReceipt> receipt = worker.handleNext();

        assertTrue(receipt.isEmpty());
        assertEquals(List.of("poll"), sequence);
    }

    @Test
    void durablePortFailurePreventsDecisionRecordingAndOffsetCommit() {
        List<String> sequence = new ArrayList<>();
        RecordingRecordStore recordStore = new RecordingRecordStore(INITIAL, sequence);
        RecordingEmissionSink emissionSink = new RecordingEmissionSink(sequence);
        RecordingDecisionRecorder decisionRecorder = new RecordingDecisionRecorder(sequence);
        RecordingOffsetCommitter offsetCommitter = new RecordingOffsetCommitter(sequence);
        AuthorityCommandDelivery<SetValue> delivery = new AuthorityCommandDelivery<>(
                command("command-3", "idem-3", Optional.of(new Revision(0)), "accepted"),
                OFFSET);

        AuthorityRuntimeWorker<TestState, SetValue, TestReceipt> worker = new AuthorityRuntimeWorker<>(
                () -> {
                    sequence.add("poll");
                    return Optional.of(delivery);
                },
                recordStore,
                (command, currentRecord) -> {
                    sequence.add("handle:" + currentRecord.state().value());
                    return AuthorityDecision.accepted(
                            new Revision(1),
                            new TestState(command.envelope().payload().value()),
                            new TestReceipt("accepted"),
                            emissions(command.envelope().aggregateId().value()),
                            trace());
                },
                (command, decision) -> {
                    sequence.add("projection:boom");
                    throw new IllegalStateException("projection unavailable");
                },
                emissionSink,
                decisionRecorder,
                offsetCommitter);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, worker::handleNext);

        assertEquals("projection unavailable", thrown.getMessage());
        assertTrue(recordStore.stored);
        assertTrue(emissionSink.published.isEmpty());
        assertTrue(decisionRecorder.recorded.isEmpty());
        assertTrue(offsetCommitter.committed.isEmpty());
        assertEquals(
                List.of(
                        "poll",
                        "load:" + AGGREGATE.value(),
                        "handle:initial",
                        "store:1:accepted",
                        "projection:boom"),
                sequence);
    }

    private static AuthorityCommand<SetValue> command(
            String commandId,
            String idempotencyKey,
            Optional<Revision> expectedRevision,
            String value) {
        CommandEnvelope<SetValue> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                PRINCIPAL,
                AGGREGATE,
                new ContractName("authority-runtime-test"),
                new CommandName("set-value"),
                trace(),
                Optional.empty(),
                new SetValue(value));
        return new AuthorityCommand<>(
                envelope,
                PRINCIPAL,
                7,
                expectedRevision,
                "payload-" + value,
                NOW);
    }

    private static List<AuthorityEmission> emissions(String key) {
        return List.of(
                new AuthorityEmission(AuthorityEmissionKind.EVENT, key, "event"),
                new AuthorityEmission(AuthorityEmissionKind.STATE, key, "state"),
                new AuthorityEmission(AuthorityEmissionKind.RESPONSE, key, "response"),
                new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, key, "cache"));
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-runtime",
                "span-runtime",
                Optional.empty(),
                NOW,
                "authority-runtime-test",
                new InstanceId("instance-authority-runtime-test"));
    }

    private static final class RecordingRecordStore implements AuthorityRecordStore<TestState> {
        private final AuthorityRecord<TestState> loadedRecord;
        private final List<String> sequence;
        private boolean stored;
        private AuthorityRecord<TestState> storedRecord;

        private RecordingRecordStore(AuthorityRecord<TestState> loadedRecord, List<String> sequence) {
            this.loadedRecord = loadedRecord;
            this.sequence = sequence;
        }

        @Override
        public AuthorityRecord<TestState> load(AggregateId aggregateId) {
            sequence.add("load:" + aggregateId.value());
            return loadedRecord;
        }

        @Override
        public void store(AggregateId aggregateId, AuthorityRecord<TestState> record) {
            sequence.add("store:" + record.revision().value() + ":" + record.state().value());
            stored = true;
            storedRecord = record;
        }
    }

    private static final class RecordingProjectionWriter implements AuthorityProjectionWriter<TestState, SetValue, TestReceipt> {
        private final List<String> sequence;
        private final List<AuthorityDecision<TestState, TestReceipt>> written = new ArrayList<>();

        private RecordingProjectionWriter(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public void write(AuthorityCommand<SetValue> command, AuthorityDecision<TestState, TestReceipt> decision) {
            sequence.add("projection:" + decision.revision().value());
            written.add(decision);
        }
    }

    private static final class RecordingEmissionSink implements AuthorityEmissionSink {
        private final List<String> sequence;
        private final List<AuthorityEmission> published = new ArrayList<>();

        private RecordingEmissionSink(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public void publish(AuthorityEmission emission) {
            sequence.add("emit:" + emission.kind());
            published.add(emission);
        }
    }

    private static final class RecordingDecisionRecorder implements AuthorityDecisionRecorder<TestState, SetValue, TestReceipt> {
        private final List<String> sequence;
        private final List<AuthorityDecision<TestState, TestReceipt>> recorded = new ArrayList<>();

        private RecordingDecisionRecorder(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public void record(AuthorityCommandDelivery<SetValue> delivery, AuthorityDecision<TestState, TestReceipt> decision) {
            sequence.add("decision:" + decision.status());
            recorded.add(decision);
        }
    }

    private static final class RecordingOffsetCommitter implements AuthorityOffsetCommitter {
        private final List<String> sequence;
        private final List<AuthorityOffset> committed = new ArrayList<>();

        private RecordingOffsetCommitter(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public void commit(AuthorityOffset offset) {
            sequence.add("commit:" + offset.position());
            committed.add(offset);
        }
    }

    private record SetValue(String value) implements CommandPayload {
    }

    private record TestState(String value) {
    }

    private record TestReceipt(String value) {
    }
}
