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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthorityCommandProcessorTest {
    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("authority-client");
    private static final AuthorityRecord<GreetingState> INITIAL = new AuthorityRecord<>(
            new Revision(0),
            7,
            new GreetingState("initial"));

    @Test
    void duplicateCommandReplaysStoredDecisionWithoutRunningMutationAgain() {
        AtomicInteger mutationRuns = new AtomicInteger();
        AuthorityCommandProcessor<GreetingState, SetGreeting, String> processor = processor(mutationRuns);
        AuthorityCommand<SetGreeting> command = command(
                "command-1",
                "idem-1",
                PRINCIPAL,
                PRINCIPAL,
                7,
                Optional.of(new Revision(0)),
                Optional.empty(),
                "hello",
                "payload-a");

        AuthorityDecision<GreetingState, String> first = processor.process(command, INITIAL);
        AuthorityDecision<GreetingState, String> replay = processor.process(command, new AuthorityRecord<>(first.revision(), 7, first.state()));

        assertEquals(1, mutationRuns.get());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, first.status());
        assertEquals(first.response(), replay.response());
        assertEquals(first.revision(), replay.revision());
        assertEquals(first.state(), replay.state());
        assertTrue(replay.replayed());
    }

    @Test
    void staleFencingEpochRejectsBeforeMutation() {
        AtomicInteger mutationRuns = new AtomicInteger();
        AuthorityDecision<GreetingState, String> decision = processor(mutationRuns).process(
                command("command-2", "idem-2", PRINCIPAL, PRINCIPAL, 6, Optional.empty(), Optional.empty(), "hello", "payload-b"),
                INITIAL);

        assertRejected(decision, AuthorityRejectionReason.STALE_FENCING_EPOCH);
        assertEquals(0, mutationRuns.get());
    }

    @Test
    void revisionMismatchRejectsBeforeMutation() {
        AtomicInteger mutationRuns = new AtomicInteger();
        AuthorityDecision<GreetingState, String> decision = processor(mutationRuns).process(
                command("command-3", "idem-3", PRINCIPAL, PRINCIPAL, 7, Optional.of(new Revision(3)), Optional.empty(), "hello", "payload-c"),
                INITIAL);

        assertRejected(decision, AuthorityRejectionReason.REVISION_MISMATCH);
        assertEquals(0, mutationRuns.get());
    }

    @Test
    void principalMismatchRejectsBeforeMutation() {
        AtomicInteger mutationRuns = new AtomicInteger();
        AuthorityDecision<GreetingState, String> decision = processor(mutationRuns).process(
                command(
                        "command-4",
                        "idem-4",
                        PRINCIPAL,
                        new PrincipalId("transport-attacker"),
                        7,
                        Optional.empty(),
                        Optional.empty(),
                        "hello",
                        "payload-d"),
                INITIAL);

        assertRejected(decision, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
        assertEquals(0, mutationRuns.get());
    }

    @Test
    void expiredDeadlineRejectsBeforeMutation() {
        AtomicInteger mutationRuns = new AtomicInteger();
        AuthorityDecision<GreetingState, String> decision = processor(mutationRuns).process(
                command(
                        "command-5",
                        "idem-5",
                        PRINCIPAL,
                        PRINCIPAL,
                        7,
                        Optional.empty(),
                        Optional.of(NOW.minusSeconds(1)),
                        "hello",
                        "payload-e"),
                INITIAL);

        assertRejected(decision, AuthorityRejectionReason.DEADLINE_EXPIRED);
        assertEquals(0, mutationRuns.get());
    }

    @Test
    void idempotencyConflictRejectsWithoutReplacingStoredDecision() {
        AtomicInteger mutationRuns = new AtomicInteger();
        AuthorityCommandProcessor<GreetingState, SetGreeting, String> processor = processor(mutationRuns);
        AuthorityCommand<SetGreeting> original = command(
                "command-6",
                "idem-6",
                PRINCIPAL,
                PRINCIPAL,
                7,
                Optional.of(new Revision(0)),
                Optional.empty(),
                "hello",
                "payload-f");
        AuthorityCommand<SetGreeting> conflicting = command(
                "command-7",
                "idem-6",
                PRINCIPAL,
                PRINCIPAL,
                7,
                Optional.of(new Revision(1)),
                Optional.empty(),
                "different",
                "payload-g");

        AuthorityDecision<GreetingState, String> accepted = processor.process(original, INITIAL);
        AuthorityDecision<GreetingState, String> conflict = processor.process(conflicting, new AuthorityRecord<>(accepted.revision(), 7, accepted.state()));
        AuthorityDecision<GreetingState, String> replay = processor.process(original, new AuthorityRecord<>(accepted.revision(), 7, accepted.state()));

        assertEquals(1, mutationRuns.get());
        assertRejected(conflict, AuthorityRejectionReason.IDEMPOTENCY_CONFLICT);
        assertEquals(accepted.response(), replay.response());
        assertTrue(replay.replayed());
    }

    @Test
    void storedDecisionDoesNotLetStaleOwnerBypassFencing() {
        AtomicInteger mutationRuns = new AtomicInteger();
        AuthorityCommandProcessor<GreetingState, SetGreeting, String> processor = processor(mutationRuns);
        AuthorityCommand<SetGreeting> original = command(
                "command-9",
                "idem-9",
                PRINCIPAL,
                PRINCIPAL,
                7,
                Optional.of(new Revision(0)),
                Optional.empty(),
                "hello",
                "payload-i");
        AuthorityDecision<GreetingState, String> accepted = processor.process(original, INITIAL);

        AuthorityDecision<GreetingState, String> staleOwner = processor.process(
                command(
                        "command-9",
                        "idem-9",
                        PRINCIPAL,
                        PRINCIPAL,
                        6,
                        Optional.of(new Revision(0)),
                        Optional.empty(),
                        "hello",
                        "payload-i"),
                new AuthorityRecord<>(accepted.revision(), 7, accepted.state()));

        assertEquals(1, mutationRuns.get());
        assertRejected(staleOwner, AuthorityRejectionReason.STALE_FENCING_EPOCH);
    }

    @Test
    void storedDecisionDoesNotLetPrincipalMismatchBypassTransportVerification() {
        AtomicInteger mutationRuns = new AtomicInteger();
        AuthorityCommandProcessor<GreetingState, SetGreeting, String> processor = processor(mutationRuns);
        AuthorityCommand<SetGreeting> original = command(
                "command-10",
                "idem-10",
                PRINCIPAL,
                PRINCIPAL,
                7,
                Optional.of(new Revision(0)),
                Optional.empty(),
                "hello",
                "payload-j");
        AuthorityDecision<GreetingState, String> accepted = processor.process(original, INITIAL);

        AuthorityDecision<GreetingState, String> mismatchedPrincipal = processor.process(
                command(
                        "command-10",
                        "idem-10",
                        PRINCIPAL,
                        new PrincipalId("transport-attacker"),
                        7,
                        Optional.of(new Revision(0)),
                        Optional.empty(),
                        "hello",
                        "payload-j"),
                new AuthorityRecord<>(accepted.revision(), 7, accepted.state()));

        assertEquals(1, mutationRuns.get());
        assertRejected(mismatchedPrincipal, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void acceptedDecisionCarriesTraceAndEmissions() {
        AuthorityDecision<GreetingState, String> decision = processor(new AtomicInteger()).process(
                command("command-8", "idem-8", PRINCIPAL, PRINCIPAL, 7, Optional.of(new Revision(0)), Optional.empty(), "hello", "payload-h"),
                INITIAL);

        assertEquals("trace-1", decision.traceEnvelope().traceId());
        assertFalse(decision.emissions().isEmpty());
        assertEquals(AuthorityEmissionKind.EVENT, decision.emissions().getFirst().kind());
    }

    private static AuthorityCommandProcessor<GreetingState, SetGreeting, String> processor(AtomicInteger mutationRuns) {
        return new AuthorityCommandProcessor<>(
                new InMemoryIdempotencyLedger<>(),
                reason -> "rejected:" + reason.name(),
                (command, current) -> {
                    mutationRuns.incrementAndGet();
                    GreetingState state = new GreetingState(command.envelope().payload().greeting());
                    Revision nextRevision = new Revision(current.revision().value() + 1);
                    return new AuthorityMutationResult<>(
                            nextRevision,
                            state,
                            "accepted:" + state.greeting() + ":" + nextRevision.value(),
                            List.of(new AuthorityEmission(AuthorityEmissionKind.EVENT, command.envelope().aggregateId().value(), state.greeting())));
                });
    }

    private static AuthorityCommand<SetGreeting> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            Optional<Instant> deadlineAt,
            String greeting,
            String payloadFingerprint) {
        CommandEnvelope<SetGreeting> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                new AggregateId("aggregate-1"),
                new ContractName("authority-test"),
                new CommandName("set-greeting"),
                new TraceEnvelope(
                        "trace-1",
                        "span-1",
                        Optional.empty(),
                        NOW,
                        "authority-test",
                        new InstanceId("instance-authority-test")),
                deadlineAt,
                new SetGreeting(greeting));
        return new AuthorityCommand<>(
                envelope,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payloadFingerprint,
                NOW);
    }

    private static void assertRejected(AuthorityDecision<GreetingState, String> decision, AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertEquals("rejected:" + reason.name(), decision.response());
        assertFalse(decision.replayed());
    }

    private record SetGreeting(String greeting) implements CommandPayload {
    }

    private record GreetingState(String greeting) {
    }
}
