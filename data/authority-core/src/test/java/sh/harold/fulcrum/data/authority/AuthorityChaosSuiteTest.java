package sh.harold.fulcrum.data.authority;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthorityChaosSuiteTest {
    private static final Instant NOW = Instant.parse("2026-06-16T22:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("authority-chaos-client");

    @Test
    void redeliveryAfterAcceptedStoreWriteReplaysWithoutDuplicateProjection() {
        ChaosHarness harness = new ChaosHarness(9);
        AuthorityCommand<AddValue> command = command("command-chaos-1", "idem-chaos-1", 9, Optional.of(new Revision(0)), 10, "payload-10");

        AuthorityDecision<ChaosState, ChaosReceipt> first = harness.processAndProject(command);
        AuthorityDecision<ChaosState, ChaosReceipt> redelivery = harness.processAndProject(command);

        assertEquals(AuthorityDecisionStatus.ACCEPTED, first.status());
        assertTrue(redelivery.replayed());
        assertEquals(first.response(), redelivery.response());
        assertEquals(first.revision(), redelivery.revision());
        assertEquals(new ChaosState(10), harness.record.state());
        assertEquals(1, harness.mutationRuns.get());
        assertEquals(List.of("aggregate-chaos-1:1:10"), harness.projectionWrites);
    }

    @Test
    void staleOwnerAfterFencingHandoffCannotReplayStoredDecision() {
        ChaosHarness harness = new ChaosHarness(12);
        AuthorityCommand<AddValue> acceptedCommand = command(
                "command-chaos-2",
                "idem-chaos-2",
                12,
                Optional.of(new Revision(0)),
                5,
                "payload-5");
        AuthorityDecision<ChaosState, ChaosReceipt> accepted = harness.processAndProject(acceptedCommand);

        AuthorityCommand<AddValue> staleOwnerRedelivery = command(
                "command-chaos-2",
                "idem-chaos-2",
                11,
                Optional.of(new Revision(0)),
                5,
                "payload-5");
        AuthorityDecision<ChaosState, ChaosReceipt> staleOwner = harness.processAndProject(staleOwnerRedelivery);

        assertEquals(AuthorityDecisionStatus.ACCEPTED, accepted.status());
        assertEquals(AuthorityDecisionStatus.REJECTED, staleOwner.status());
        assertEquals(Optional.of(AuthorityRejectionReason.STALE_FENCING_EPOCH), staleOwner.rejectionReason());
        assertFalse(staleOwner.replayed());
        assertEquals(1, harness.mutationRuns.get());
        assertEquals(List.of("aggregate-chaos-1:1:5"), harness.projectionWrites);
    }

    @Test
    void idempotencyStormConvergesToSingleStatePerDistinctCommand() {
        ChaosHarness harness = new ChaosHarness(21);
        AuthorityCommand<AddValue> original = command(
                "command-chaos-3",
                "idem-chaos-3",
                21,
                Optional.of(new Revision(0)),
                10,
                "payload-10");
        AuthorityCommand<AddValue> conflictingDuplicate = command(
                "command-chaos-4",
                "idem-chaos-3",
                21,
                Optional.of(new Revision(1)),
                30,
                "payload-30");
        AuthorityCommand<AddValue> staleRevision = command(
                "command-chaos-5",
                "idem-chaos-5",
                21,
                Optional.of(new Revision(0)),
                7,
                "payload-7");
        AuthorityCommand<AddValue> followUp = command(
                "command-chaos-6",
                "idem-chaos-6",
                21,
                Optional.of(new Revision(1)),
                5,
                "payload-5");

        AuthorityDecision<ChaosState, ChaosReceipt> first = harness.processAndProject(original);
        AuthorityDecision<ChaosState, ChaosReceipt> duplicateOne = harness.processAndProject(original);
        AuthorityDecision<ChaosState, ChaosReceipt> duplicateTwo = harness.processAndProject(original);
        AuthorityDecision<ChaosState, ChaosReceipt> conflict = harness.processAndProject(conflictingDuplicate);
        AuthorityDecision<ChaosState, ChaosReceipt> stale = harness.processAndProject(staleRevision);
        AuthorityDecision<ChaosState, ChaosReceipt> acceptedFollowUp = harness.processAndProject(followUp);

        assertEquals(AuthorityDecisionStatus.ACCEPTED, first.status());
        assertTrue(duplicateOne.replayed());
        assertTrue(duplicateTwo.replayed());
        assertEquals(Optional.of(AuthorityRejectionReason.IDEMPOTENCY_CONFLICT), conflict.rejectionReason());
        assertEquals(Optional.of(AuthorityRejectionReason.REVISION_MISMATCH), stale.rejectionReason());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, acceptedFollowUp.status());
        assertEquals(new ChaosState(15), harness.record.state());
        assertEquals(2, harness.mutationRuns.get());
        assertEquals(List.of(
                "aggregate-chaos-1:1:10",
                "aggregate-chaos-1:2:15"), harness.projectionWrites);
    }

    private static AuthorityCommand<AddValue> command(
            String commandId,
            String idempotencyKey,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            int amount,
            String payloadFingerprint) {
        CommandEnvelope<AddValue> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                PRINCIPAL,
                new AggregateId("aggregate-chaos-1"),
                new ContractName("authority-chaos"),
                new CommandName("add-value"),
                trace(),
                Optional.empty(),
                new AddValue(amount));
        return new AuthorityCommand<>(
                envelope,
                PRINCIPAL,
                fencingEpoch,
                expectedRevision,
                payloadFingerprint,
                NOW);
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-chaos",
                "span-chaos",
                Optional.empty(),
                NOW,
                "authority-chaos-suite",
                new InstanceId("instance-authority-chaos"));
    }

    private static final class ChaosHarness {
        private final AtomicInteger mutationRuns = new AtomicInteger();
        private final List<String> projectionWrites = new ArrayList<>();
        private final AuthorityCommandProcessor<ChaosState, AddValue, ChaosReceipt> processor;
        private AuthorityRecord<ChaosState> record;

        private ChaosHarness(long fencingEpoch) {
            record = new AuthorityRecord<>(new Revision(0), fencingEpoch, new ChaosState(0));
            processor = new AuthorityCommandProcessor<>(
                    new InMemoryIdempotencyLedger<>(),
                    reason -> ChaosReceipt.rejected(reason),
                    (command, current) -> {
                        mutationRuns.incrementAndGet();
                        Revision nextRevision = new Revision(current.revision().value() + 1);
                        ChaosState nextState = new ChaosState(current.state().value() + command.envelope().payload().amount());
                        String aggregateKey = command.envelope().aggregateId().value();
                        return new AuthorityMutationResult<>(
                                nextRevision,
                                nextState,
                                ChaosReceipt.accepted(nextState.value(), nextRevision),
                                List.of(
                                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, nextState.wireValue(nextRevision)),
                                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, nextState.wireValue(nextRevision)),
                                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, aggregateKey, nextState.wireValue(nextRevision)),
                                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, aggregateKey, nextState.wireValue(nextRevision))));
                    });
        }

        private AuthorityDecision<ChaosState, ChaosReceipt> processAndProject(AuthorityCommand<AddValue> command) {
            AuthorityDecision<ChaosState, ChaosReceipt> decision = processor.process(command, record);
            if (decision.status() == AuthorityDecisionStatus.ACCEPTED && !decision.replayed()) {
                record = new AuthorityRecord<>(decision.revision(), record.fencingEpoch(), decision.state());
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.PROJECTION)
                        .forEach(emission -> projectionWrites.add(emission.key()
                                + ":" + decision.revision().value()
                                + ":" + decision.state().value()));
            }
            return decision;
        }
    }

    private record AddValue(int amount) implements CommandPayload {
    }

    private record ChaosState(int value) {
        private String wireValue(Revision revision) {
            return "value=" + value + "\nrevision=" + revision.value();
        }
    }

    private record ChaosReceipt(boolean accepted, Optional<AuthorityRejectionReason> rejectionReason, int value, Revision revision) {
        private ChaosReceipt {
            rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        }

        private static ChaosReceipt accepted(int value, Revision revision) {
            return new ChaosReceipt(true, Optional.empty(), value, revision);
        }

        private static ChaosReceipt rejected(AuthorityRejectionReason reason) {
            return new ChaosReceipt(false, Optional.of(reason), 0, new Revision(0));
        }
    }
}
