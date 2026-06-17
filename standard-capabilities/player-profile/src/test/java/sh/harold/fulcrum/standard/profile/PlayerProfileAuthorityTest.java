package sh.harold.fulcrum.standard.profile;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayerProfileAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T13:00:00Z");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000101"));
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-profile-client");

    @Test
    void upsertProfileAdvancesRevisionAndEmitsDecisionFacts() {
        PlayerProfileAuthority authority = new PlayerProfileAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PlayerProfileState, PlayerProfileReceipt> decision = authority.handle(
                command("command-profile-1", "profile-idem-1", SUBJECT, PRINCIPAL, PRINCIPAL, 3, 0, "Richer=Toast\nOne", "payload-1"),
                PlayerProfileAuthority.emptyRecord(3));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals("Richer=Toast\nOne", decision.state().current().orElseThrow().displayName());
        assertEquals(List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(
                PlayerProfileContracts.EFFECTIVE_PROJECTION + ":" + SUBJECT.value(),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.PROJECTION)
                        .findFirst()
                        .orElseThrow()
                        .key());
        assertEquals(PlayerProfileAuthority.cacheKey(SUBJECT),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
        PlayerProfileState cachedState = PlayerProfileState.parse(decision.emissions().stream()
                .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                .findFirst()
                .orElseThrow()
                .payload());
        assertEquals(decision.state(), cachedState);
    }

    @Test
    void duplicateCommandReplaysStoredProfileDecision() {
        PlayerProfileAuthority authority = new PlayerProfileAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<UpsertPlayerProfile> command = command(
                "command-profile-2",
                "profile-idem-2",
                SUBJECT,
                PRINCIPAL,
                PRINCIPAL,
                3,
                0,
                "RicherToast",
                "payload-2");

        AuthorityDecision<PlayerProfileState, PlayerProfileReceipt> first = authority.handle(command, PlayerProfileAuthority.emptyRecord(3));
        AuthorityDecision<PlayerProfileState, PlayerProfileReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(first.revision(), 3, first.state()));

        assertEquals(first.response(), replay.response());
        assertEquals(first.state(), replay.state());
        assertTrue(replay.replayed());
    }

    @Test
    void revisionMismatchRejectsBeforeProfileMutation() {
        PlayerProfileAuthority authority = new PlayerProfileAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PlayerProfileState, PlayerProfileReceipt> decision = authority.handle(
                command("command-profile-3", "profile-idem-3", SUBJECT, PRINCIPAL, PRINCIPAL, 3, 4, "RicherToast", "payload-3"),
                PlayerProfileAuthority.emptyRecord(3));

        assertRejected(decision, AuthorityRejectionReason.REVISION_MISMATCH);
        assertEquals(PlayerProfileState.empty(), decision.state());
    }

    @Test
    void principalMismatchRejectsBeforeProfileMutation() {
        PlayerProfileAuthority authority = new PlayerProfileAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PlayerProfileState, PlayerProfileReceipt> decision = authority.handle(
                command(
                        "command-profile-4",
                        "profile-idem-4",
                        SUBJECT,
                        PRINCIPAL,
                        new PrincipalId("transport-attacker"),
                        3,
                        0,
                        "RicherToast",
                        "payload-4"),
                PlayerProfileAuthority.emptyRecord(3));

        assertRejected(decision, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void staleFencingEpochRejectsBeforeProfileMutation() {
        PlayerProfileAuthority authority = new PlayerProfileAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PlayerProfileState, PlayerProfileReceipt> decision = authority.handle(
                command("command-profile-5", "profile-idem-5", SUBJECT, PRINCIPAL, PRINCIPAL, 2, 0, "RicherToast", "payload-5"),
                PlayerProfileAuthority.emptyRecord(3));

        assertRejected(decision, AuthorityRejectionReason.STALE_FENCING_EPOCH);
    }

    @Test
    void aggregateMustBeKeyedBySubject() {
        PlayerProfileAuthority authority = new PlayerProfileAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<UpsertPlayerProfile> command = command(
                "command-profile-6",
                "profile-idem-6",
                SUBJECT,
                PRINCIPAL,
                PRINCIPAL,
                3,
                0,
                "RicherToast",
                "payload-6",
                new AggregateId("player-profile:wrong-subject"));

        assertThrows(IllegalArgumentException.class, () -> authority.handle(command, PlayerProfileAuthority.emptyRecord(3)));
    }

    private static AuthorityCommand<UpsertPlayerProfile> command(
            String commandId,
            String idempotencyKey,
            SubjectId subjectId,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String displayName,
            String payloadFingerprint) {
        return command(
                commandId,
                idempotencyKey,
                subjectId,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                displayName,
                payloadFingerprint,
                PlayerProfileAuthority.aggregateId(subjectId));
    }

    private static AuthorityCommand<UpsertPlayerProfile> command(
            String commandId,
            String idempotencyKey,
            SubjectId subjectId,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String displayName,
            String payloadFingerprint,
            AggregateId aggregateId) {
        UpsertPlayerProfile payload = new UpsertPlayerProfile(subjectId, displayName, NOW, expectedRevision);
        CommandEnvelope<UpsertPlayerProfile> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                PlayerProfileContracts.CONTRACT,
                new CommandName("upsert-profile"),
                new TraceEnvelope(
                        "trace-profile-1",
                        "span-profile-1",
                        Optional.empty(),
                        NOW,
                        "standard-profile-test",
                        new InstanceId("instance-profile-test")),
                Optional.empty(),
                payload);
        return new AuthorityCommand<>(
                envelope,
                authenticatedPrincipal,
                fencingEpoch,
                Optional.of(new Revision(expectedRevision)),
                payloadFingerprint,
                NOW);
    }

    private static void assertRejected(
            AuthorityDecision<PlayerProfileState, PlayerProfileReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertFalse(decision.replayed());
        assertTrue(decision.response().rejectionReason().orElseThrow().contains(reason.name()));
    }
}
