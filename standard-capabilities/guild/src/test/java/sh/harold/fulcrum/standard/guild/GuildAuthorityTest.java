package sh.harold.fulcrum.standard.guild;

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
import sh.harold.fulcrum.standard.contracts.GuildContracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GuildAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T22:30:00Z");
    private static final GuildId GUILD_ID = new GuildId("guild-authority-1");
    private static final SubjectId OWNER = subject("00000000-0000-0000-0000-000000001011");
    private static final SubjectId MEMBER = subject("00000000-0000-0000-0000-000000001012");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-guild-client");

    @Test
    void createGuildAdvancesRevisionAndEmitsRosterProjectionFact() {
        GuildAuthority authority = new GuildAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<GuildState, GuildReceipt> decision = authority.handle(
                command("command-guild-1", "guild-idem-1", GUILD_ID, PRINCIPAL, PRINCIPAL, 5, 0, "payload-1"),
                GuildAuthority.emptyRecord(5));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals(GUILD_ID, decision.state().current().orElseThrow().guildId());
        assertEquals(List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(
                GuildContracts.ROSTER_PROJECTION + ":" + GUILD_ID.value(),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.PROJECTION)
                        .findFirst()
                        .orElseThrow()
                        .key());
        assertEquals(GuildAuthority.cacheKey(GUILD_ID),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
    }

    @Test
    void duplicateCommandReplaysStoredGuildDecision() {
        GuildAuthority authority = new GuildAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<CreateGuild> command = command(
                "command-guild-2",
                "guild-idem-2",
                GUILD_ID,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-2");

        AuthorityDecision<GuildState, GuildReceipt> first = authority.handle(command, GuildAuthority.emptyRecord(5));
        AuthorityDecision<GuildState, GuildReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(first.revision(), 5, first.state()));

        assertEquals(first.response(), replay.response());
        assertEquals(first.state(), replay.state());
        assertTrue(replay.replayed());
    }

    @Test
    void revisionMismatchRejectsBeforeGuildMutation() {
        GuildAuthority authority = new GuildAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<GuildState, GuildReceipt> decision = authority.handle(
                command("command-guild-3", "guild-idem-3", GUILD_ID, PRINCIPAL, PRINCIPAL, 5, 9, "payload-3"),
                GuildAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.REVISION_MISMATCH);
        assertEquals(GuildState.empty(), decision.state());
    }

    @Test
    void principalMismatchRejectsBeforeGuildMutation() {
        GuildAuthority authority = new GuildAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<GuildState, GuildReceipt> decision = authority.handle(
                command(
                        "command-guild-4",
                        "guild-idem-4",
                        GUILD_ID,
                        PRINCIPAL,
                        new PrincipalId("transport-attacker"),
                        5,
                        0,
                        "payload-4"),
                GuildAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void staleFencingEpochRejectsBeforeGuildMutation() {
        GuildAuthority authority = new GuildAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<GuildState, GuildReceipt> decision = authority.handle(
                command("command-guild-5", "guild-idem-5", GUILD_ID, PRINCIPAL, PRINCIPAL, 4, 0, "payload-5"),
                GuildAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.STALE_FENCING_EPOCH);
    }

    @Test
    void aggregateMustBeKeyedByGuild() {
        GuildAuthority authority = new GuildAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<CreateGuild> command = command(
                "command-guild-6",
                "guild-idem-6",
                GUILD_ID,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-6",
                new AggregateId("guild:wrong-guild"));

        assertThrows(IllegalArgumentException.class, () -> authority.handle(command, GuildAuthority.emptyRecord(5)));
    }

    private static AuthorityCommand<CreateGuild> command(
            String commandId,
            String idempotencyKey,
            GuildId guildId,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint) {
        return command(
                commandId,
                idempotencyKey,
                guildId,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payloadFingerprint,
                GuildAuthority.aggregateId(guildId));
    }

    private static AuthorityCommand<CreateGuild> command(
            String commandId,
            String idempotencyKey,
            GuildId guildId,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint,
            AggregateId aggregateId) {
        CreateGuild payload = new CreateGuild(guildId, OWNER, List.of(OWNER, MEMBER), "Authority Guild", NOW, expectedRevision);
        CommandEnvelope<CreateGuild> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                GuildContracts.CONTRACT,
                new CommandName("create-guild"),
                new TraceEnvelope(
                        "trace-guild-1",
                        "span-guild-1",
                        Optional.empty(),
                        NOW,
                        "standard-guild-test",
                        new InstanceId("instance-guild-test")),
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
            AuthorityDecision<GuildState, GuildReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertFalse(decision.replayed());
        assertTrue(decision.response().rejectionReason().orElseThrow().contains(reason.name()));
    }

    private static SubjectId subject(String uuid) {
        return new SubjectId(UUID.fromString(uuid));
    }
}
