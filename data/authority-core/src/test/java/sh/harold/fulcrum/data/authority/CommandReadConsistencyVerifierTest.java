package sh.harold.fulcrum.data.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CommandReadConsistencyVerifierTest {
    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");
    private static final CommandReadConsistencyVerifier VERIFIER = new CommandReadConsistencyVerifier();

    @Test
    void syncCommandResultCarriesPostWriteProjectionAtAcceptedRevision() {
        AuthorityDecision<GreetingState, GreetingResponse> decision = acceptedDecision(
                new Revision(2),
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, "aggregate-1", "projection-after"),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, "aggregate-1", "cache-after"),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, "aggregate-1", "response-after")));
        ProjectionSnapshot<GreetingProjection> projection = projection(new Revision(2));

        CommandReadConsistencyReceipt<GreetingProjection> receipt =
                VERIFIER.verify(decision, CommandReadContract.syncReadYourWrites(projection));

        assertTrue(receipt.valid());
        assertTrue(receipt.readYourWritesSatisfied());
        assertEquals(CommandReadConsistency.SYNC_READ_YOUR_WRITES, receipt.consistency());
        assertEquals(Optional.of(projection), receipt.postWriteProjection());
    }

    @Test
    void syncCommandResultRejectsStalePostWriteProjectionRevision() {
        AuthorityDecision<GreetingState, GreetingResponse> decision = acceptedDecision(
                new Revision(2),
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, "aggregate-1", "projection-after"),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, "aggregate-1", "cache-after")));

        CommandReadConsistencyReceipt<GreetingProjection> receipt =
                VERIFIER.verify(decision, CommandReadContract.syncReadYourWrites(projection(new Revision(1))));

        assertFalse(receipt.valid());
        assertFalse(receipt.readYourWritesSatisfied());
        assertEquals(Optional.of(CommandReadConsistencyViolation.SYNC_PROJECTION_REVISION_MISMATCH), receipt.violation());
    }

    @Test
    void syncCommandResultRequiresProjectionAndCacheWriteEmissionsBeforeReturning() {
        AuthorityDecision<GreetingState, GreetingResponse> missingProjection = acceptedDecision(
                new Revision(2),
                List.of(new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, "aggregate-1", "cache-after")));
        AuthorityDecision<GreetingState, GreetingResponse> missingCacheWrite = acceptedDecision(
                new Revision(2),
                List.of(new AuthorityEmission(AuthorityEmissionKind.PROJECTION, "aggregate-1", "projection-after")));

        CommandReadConsistencyReceipt<GreetingProjection> projectionReceipt =
                VERIFIER.verify(missingProjection, CommandReadContract.syncReadYourWrites(projection(new Revision(2))));
        CommandReadConsistencyReceipt<GreetingProjection> cacheReceipt =
                VERIFIER.verify(missingCacheWrite, CommandReadContract.syncReadYourWrites(projection(new Revision(2))));

        assertEquals(Optional.of(CommandReadConsistencyViolation.SYNC_PROJECTION_EMISSION_MISSING), projectionReceipt.violation());
        assertEquals(Optional.of(CommandReadConsistencyViolation.SYNC_CACHE_WRITE_MISSING), cacheReceipt.violation());
    }

    @Test
    void rejectedDecisionCannotSatisfyReadYourWrites() {
        AuthorityDecision<GreetingState, GreetingResponse> decision = AuthorityDecision.rejected(
                AuthorityRejectionReason.REVISION_MISMATCH,
                new Revision(2),
                new GreetingState("before"),
                new GreetingResponse("rejected"),
                trace());

        CommandReadConsistencyReceipt<GreetingProjection> receipt =
                VERIFIER.verify(decision, CommandReadContract.syncReadYourWrites(projection(new Revision(2))));

        assertFalse(receipt.valid());
        assertEquals(Optional.of(CommandReadConsistencyViolation.REJECTED_DECISION_HAS_NO_POST_WRITE_STATE), receipt.violation());
    }

    @Test
    void asyncCommandContractDoesNotPromiseImmediateProjectionVisibility() {
        AuthorityDecision<GreetingState, GreetingResponse> decision = acceptedDecision(
                new Revision(2),
                List.of(new AuthorityEmission(AuthorityEmissionKind.EVENT, "aggregate-1", "changed")));

        CommandReadContract<GreetingProjection> contract = CommandReadContract.asyncEventual();
        CommandReadConsistencyReceipt<GreetingProjection> receipt = VERIFIER.verify(decision, contract);

        assertTrue(receipt.valid());
        assertFalse(contract.promisesImmediateProjectionVisibility());
        assertFalse(receipt.readYourWritesSatisfied());
        assertTrue(receipt.postWriteProjection().isEmpty());
    }

    @Test
    void asyncCommandContractCannotCarryPostWriteProjection() {
        ProjectionSnapshot<GreetingProjection> projection = projection(new Revision(2));

        assertThrows(IllegalArgumentException.class, () -> new CommandReadContract<>(
                CommandReadConsistency.ASYNC_EVENTUAL,
                Optional.of(projection)));
    }

    private static AuthorityDecision<GreetingState, GreetingResponse> acceptedDecision(
            Revision revision,
            List<AuthorityEmission> emissions) {
        return AuthorityDecision.accepted(
                revision,
                new GreetingState("after"),
                new GreetingResponse("accepted"),
                emissions,
                trace());
    }

    private static ProjectionSnapshot<GreetingProjection> projection(Revision revision) {
        return new ProjectionSnapshot<>(
                "greeting_projection",
                "aggregate-1",
                revision,
                new GreetingProjection("after"));
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-1",
                "span-1",
                Optional.empty(),
                NOW,
                "authority-core-test",
                new InstanceId("instance-authority-core-test"));
    }

    private record GreetingState(String greeting) {
    }

    private record GreetingResponse(String message) {
    }

    private record GreetingProjection(String greeting) {
    }
}
