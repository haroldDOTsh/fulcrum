package sh.harold.fulcrum.data.store.memory;

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
import sh.harold.fulcrum.data.authority.AuthorityCommandProcessor;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityMutationResult;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandSource;
import sh.harold.fulcrum.data.authority.runtime.AuthorityEmissionSinks;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InMemoryAuthorityAdaptersTest {
    private static final AggregateId AGGREGATE_ID = new AggregateId("cert:aggregate:memory");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-memory-certification");
    private static final long FENCING_EPOCH = 17;
    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    @Test
    void redeliversUntilOffsetIsCommittedThenResumesAfterCommit() {
        InMemoryAuthorityCommandLog<CertCommand> log = new InMemoryAuthorityCommandLog<>("cmd.memory-certification");
        AuthorityCommand<CertCommand> command = command("command-1", "idempotency-1", 3, 0, "cert:aggregate:memory:3");
        log.append(command);

        AuthorityCommandSource<CertCommand> firstSource = log.openSource();
        assertEquals(command, firstSource.poll().orElseThrow().command());

        AuthorityCommandSource<CertCommand> restartedBeforeCommit = log.openSource();
        var redelivered = restartedBeforeCommit.poll().orElseThrow();
        assertEquals(command, redelivered.command());

        log.committer().commit(redelivered.offset());
        assertTrue(log.openSource().poll().isEmpty());
    }

    @Test
    void inMemoryAdaptersRunTheAuthorityWorkerContract() {
        InMemoryAuthorityCommandLog<CertCommand> commandLog = new InMemoryAuthorityCommandLog<>("cmd.memory-certification");
        InMemoryAuthorityRecordStore<CertState> recordStore = new InMemoryAuthorityRecordStore<>(
                () -> new AuthorityRecord<>(new Revision(0), FENCING_EPOCH, new CertState(0)));
        InMemoryIdempotencyLedger<CertState, CertReceipt> idempotencyLedger = new InMemoryIdempotencyLedger<>();
        InMemoryAuthorityProjectionWriter<CertState, CertCommand, CertReceipt> projectionWriter =
                new InMemoryAuthorityProjectionWriter<>((command, decision) ->
                        new InMemoryAuthorityProjectionWriter.Projection(
                                command.envelope().aggregateId().value(),
                                command.envelope().aggregateId().value()
                                        + "|" + decision.state().total()
                                        + "|" + decision.revision().value()));
        InMemoryAuthorityEmissionSink emissionSink = new InMemoryAuthorityEmissionSink();
        InMemoryAuthorityDecisionRecorder<CertState, CertCommand, CertReceipt> decisionRecorder =
                new InMemoryAuthorityDecisionRecorder<>();
        AuthorityCommandProcessor<CertState, CertCommand, CertReceipt> processor = new AuthorityCommandProcessor<>(
                idempotencyLedger,
                reason -> new CertReceipt("REJECTED:" + reason.name(), -1),
                InMemoryAuthorityAdaptersTest::applyMutation);
        AuthorityRuntimeWorker<CertState, CertCommand, CertReceipt> worker = new AuthorityRuntimeWorker<>(
                commandLog.openSource(),
                recordStore,
                processor::process,
                projectionWriter,
                AuthorityEmissionSinks.composite(emissionSink),
                decisionRecorder,
                commandLog.committer());

        commandLog.append(command("command-1", "idempotency-1", 3, 0, "cert:aggregate:memory:3"));
        AuthorityRuntimeReceipt accepted = worker.handleNext().orElseThrow();
        assertEquals(AuthorityDecisionStatus.ACCEPTED, accepted.status());
        assertEquals(new Revision(1), accepted.revision());
        assertTrue(!accepted.replayed());

        commandLog.append(command("command-1", "idempotency-1", 3, 0, "cert:aggregate:memory:3"));
        AuthorityRuntimeReceipt duplicate = worker.handleNext().orElseThrow();
        assertEquals(AuthorityDecisionStatus.ACCEPTED, duplicate.status());
        assertTrue(duplicate.replayed());

        commandLog.append(command("command-conflict", "idempotency-1", 5, 1, "cert:aggregate:memory:5"));
        AuthorityRuntimeReceipt conflict = worker.handleNext().orElseThrow();
        assertEquals(AuthorityDecisionStatus.REJECTED, conflict.status());
        assertTrue(!conflict.replayed());

        assertEquals(new Revision(1), recordStore.load(AGGREGATE_ID).revision());
        assertEquals(3, recordStore.load(AGGREGATE_ID).state().total());
        assertEquals(1, idempotencyLedger.size());
        assertEquals(2, decisionRecorder.size());
        assertEquals("cert:aggregate:memory|3|1", projectionWriter.find(AGGREGATE_ID.value()).orElseThrow());
        assertEquals(List.of("accepted:3"), emissionSink.payloads(AuthorityEmissionKind.EVENT));
        assertEquals(List.of("total=3"), emissionSink.payloads(AuthorityEmissionKind.STATE));
        assertEquals(List.of("status=ACCEPTED;revision=1"), emissionSink.payloads(AuthorityEmissionKind.RESPONSE));
        assertEquals("total=3", emissionSink.latestPayload(AuthorityEmissionKind.CACHE_WRITE, AGGREGATE_ID.value()));
        assertEquals(AuthorityRejectionReason.IDEMPOTENCY_CONFLICT,
                decisionRecorder.find(new CommandId("command-conflict"))
                        .orElseThrow()
                        .decision()
                        .rejectionReason()
                        .orElseThrow());
    }

    private static AuthorityMutationResult<CertState, CertReceipt> applyMutation(
            AuthorityCommand<CertCommand> command,
            AuthorityRecord<CertState> current) {
        long nextRevision = current.revision().value() + 1;
        CertState state = new CertState(current.state().total() + command.envelope().payload().amount());
        CertReceipt receipt = new CertReceipt("ACCEPTED", state.total());
        return new AuthorityMutationResult<>(
                new Revision(nextRevision),
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, command.envelope().aggregateId().value(), "accepted:" + state.total()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, command.envelope().aggregateId().value(), "total=" + state.total()),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), "status=ACCEPTED;revision=" + nextRevision),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, command.envelope().aggregateId().value(), "total=" + state.total())));
    }

    private static AuthorityCommand<CertCommand> command(
            String commandId,
            String idempotencyKey,
            int amount,
            long expectedRevision,
            String payloadFingerprint) {
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        PRINCIPAL,
                        AGGREGATE_ID,
                        new ContractName("store-adapter-certification"),
                        new CommandName("apply-cert-total"),
                        trace(commandId),
                        Optional.empty(),
                        new CertCommand(amount)),
                PRINCIPAL,
                FENCING_EPOCH,
                Optional.of(new Revision(expectedRevision)),
                payloadFingerprint,
                NOW);
    }

    private static TraceEnvelope trace(String spanId) {
        return new TraceEnvelope(
                "trace-memory-store-certification",
                spanId,
                Optional.empty(),
                NOW,
                "store-memory-certification",
                new InstanceId("instance-memory-store-certification"));
    }

    private record CertCommand(int amount) implements CommandPayload {
    }

    private record CertState(int total) {
    }

    private record CertReceipt(String status, long total) {
    }
}
