package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;
import sh.harold.fulcrum.standard.contracts.RankContracts;
import sh.harold.fulcrum.standard.profile.PlayerProfileAuthority;
import sh.harold.fulcrum.standard.profile.PlayerProfileReceipt;
import sh.harold.fulcrum.standard.profile.PlayerProfileState;
import sh.harold.fulcrum.standard.profile.UpsertPlayerProfile;
import sh.harold.fulcrum.standard.punishment.IssuePunishment;
import sh.harold.fulcrum.standard.punishment.PunishmentAuthority;
import sh.harold.fulcrum.standard.punishment.PunishmentReceipt;
import sh.harold.fulcrum.standard.punishment.PunishmentState;
import sh.harold.fulcrum.standard.rank.GrantRank;
import sh.harold.fulcrum.standard.rank.RankAuthority;
import sh.harold.fulcrum.standard.rank.RankReceipt;
import sh.harold.fulcrum.standard.rank.RankState;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StandardCapabilityAuthorityWireCodecTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final SubjectId SUBJECT =
            new SubjectId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-standard-capability-runtime");

    @Test
    void playerProfileCommandAndStoredDecisionRoundTrip() {
        AuthorityCommand<UpsertPlayerProfile> command = profileCommand();

        AuthorityCommand<UpsertPlayerProfile> decoded =
                StandardCapabilityAuthorityWireCodec.decodePlayerProfileCommand(new ConsumerRecord<>(
                        PlayerProfileContracts.COMMAND_TOPIC,
                        0,
                        12L,
                        command.envelope().aggregateId().value(),
                        StandardCapabilityAuthorityWireCodec.encodePlayerProfileCommand(command)));

        assertEquals(command.envelope().payload(), decoded.envelope().payload());
        assertEquals(command.envelope().contractName(), decoded.envelope().contractName());
        assertEquals(command.expectedRevision(), decoded.expectedRevision());

        var decision = new PlayerProfileAuthority(
                new InMemoryIdempotencyLedger<PlayerProfileState, PlayerProfileReceipt>())
                .handle(command, PlayerProfileAuthority.emptyRecord(7));
        StoredAuthorityDecision<PlayerProfileState, PlayerProfileReceipt> stored =
                new StoredAuthorityDecision<>("payload-profile", decision);
        StoredAuthorityDecision<PlayerProfileState, PlayerProfileReceipt> storedDecoded =
                StandardCapabilityAuthorityWireCodec.decodePlayerProfileStoredDecision(
                        StandardCapabilityAuthorityWireCodec.encodePlayerProfileStoredDecision(stored));

        assertEquals("payload-profile", storedDecoded.payloadFingerprint());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, storedDecoded.decision().status());
        assertEquals(new Revision(1), storedDecoded.decision().revision());
        assertEquals("Builder=One", storedDecoded.decision().state().current().orElseThrow().displayName());
        assertEquals(SUBJECT, storedDecoded.decision().response().snapshot().orElseThrow().subjectId());
    }

    @Test
    void rankCommandAndStoredDecisionRoundTrip() {
        AuthorityCommand<GrantRank> command = rankCommand();

        AuthorityCommand<GrantRank> decoded =
                StandardCapabilityAuthorityWireCodec.decodeRankCommand(new ConsumerRecord<>(
                        RankContracts.COMMAND_TOPIC,
                        0,
                        13L,
                        command.envelope().aggregateId().value(),
                        StandardCapabilityAuthorityWireCodec.encodeRankCommand(command)));

        assertEquals(command.envelope().payload(), decoded.envelope().payload());
        assertEquals(command.envelope().contractName(), decoded.envelope().contractName());
        assertEquals(command.expectedRevision(), decoded.expectedRevision());

        var decision = new RankAuthority(new InMemoryIdempotencyLedger<RankState, RankReceipt>())
                .handle(command, RankAuthority.emptyRecord(7));
        StoredAuthorityDecision<RankState, RankReceipt> stored =
                new StoredAuthorityDecision<>("payload-rank", decision);
        StoredAuthorityDecision<RankState, RankReceipt> storedDecoded =
                StandardCapabilityAuthorityWireCodec.decodeRankStoredDecision(
                        StandardCapabilityAuthorityWireCodec.encodeRankStoredDecision(stored));

        assertEquals("payload-rank", storedDecoded.payloadFingerprint());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, storedDecoded.decision().status());
        assertEquals(new Revision(1), storedDecoded.decision().revision());
        assertEquals("Admin=Root", storedDecoded.decision().state().current().orElseThrow().primaryRankKey());
        assertEquals(SUBJECT, storedDecoded.decision().response().snapshot().orElseThrow().subjectId());
    }

    @Test
    void punishmentCommandAndStoredDecisionRoundTrip() {
        AuthorityCommand<IssuePunishment> command = punishmentCommand();

        AuthorityCommand<IssuePunishment> decoded =
                StandardCapabilityAuthorityWireCodec.decodePunishmentCommand(new ConsumerRecord<>(
                        PunishmentContracts.COMMAND_TOPIC,
                        0,
                        14L,
                        command.envelope().aggregateId().value(),
                        StandardCapabilityAuthorityWireCodec.encodePunishmentCommand(command)));

        assertEquals(command.envelope().payload(), decoded.envelope().payload());
        assertEquals(command.envelope().contractName(), decoded.envelope().contractName());
        assertEquals(command.expectedRevision(), decoded.expectedRevision());

        var decision = new PunishmentAuthority(
                new InMemoryIdempotencyLedger<PunishmentState, PunishmentReceipt>())
                .handle(command, PunishmentAuthority.emptyRecord(7));
        StoredAuthorityDecision<PunishmentState, PunishmentReceipt> stored =
                new StoredAuthorityDecision<>("payload-punishment", decision);
        StoredAuthorityDecision<PunishmentState, PunishmentReceipt> storedDecoded =
                StandardCapabilityAuthorityWireCodec.decodePunishmentStoredDecision(
                        StandardCapabilityAuthorityWireCodec.encodePunishmentStoredDecision(stored));

        assertEquals("payload-punishment", storedDecoded.payloadFingerprint());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, storedDecoded.decision().status());
        assertEquals(new Revision(1), storedDecoded.decision().revision());
        assertEquals("test=ban", storedDecoded.decision().state().active().orElseThrow().reason());
        assertEquals(SUBJECT, storedDecoded.decision().response().snapshot().orElseThrow().subjectId());
    }

    private static AuthorityCommand<UpsertPlayerProfile> profileCommand() {
        UpsertPlayerProfile payload = new UpsertPlayerProfile(SUBJECT, "Builder=One", NOW, 0);
        return command(
                new CommandId("command-upsert-profile"),
                new IdempotencyKey("idem-upsert-profile"),
                PlayerProfileAuthority.aggregateId(SUBJECT),
                PlayerProfileContracts.CONTRACT,
                new CommandName(StandardCapabilityAuthorityWireCodec.UPSERT_PROFILE_COMMAND),
                payload,
                "payload-upsert-profile");
    }

    private static AuthorityCommand<GrantRank> rankCommand() {
        GrantRank payload = new GrantRank(SUBJECT, "Admin=Root", NOW, 0);
        return command(
                new CommandId("command-grant-rank"),
                new IdempotencyKey("idem-grant-rank"),
                RankAuthority.aggregateId(SUBJECT),
                RankContracts.CONTRACT,
                new CommandName(StandardCapabilityAuthorityWireCodec.GRANT_RANK_COMMAND),
                payload,
                "payload-grant-rank");
    }

    private static AuthorityCommand<IssuePunishment> punishmentCommand() {
        IssuePunishment payload = new IssuePunishment(
                SUBJECT,
                "punishment-1",
                "test=ban",
                NOW,
                NOW.plusSeconds(3600),
                0);
        return command(
                new CommandId("command-issue-punishment"),
                new IdempotencyKey("idem-issue-punishment"),
                PunishmentAuthority.aggregateId(SUBJECT),
                PunishmentContracts.CONTRACT,
                new CommandName(StandardCapabilityAuthorityWireCodec.ISSUE_PUNISHMENT_COMMAND),
                payload,
                "payload-issue-punishment");
    }

    private static <C extends sh.harold.fulcrum.api.contract.CommandPayload> AuthorityCommand<C> command(
            CommandId commandId,
            IdempotencyKey idempotencyKey,
            sh.harold.fulcrum.api.contract.AggregateId aggregateId,
            ContractName contract,
            CommandName commandName,
            C payload,
            String payloadFingerprint) {
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        commandId,
                        idempotencyKey,
                        PRINCIPAL,
                        aggregateId,
                        contract,
                        commandName,
                        new TraceEnvelope(
                                "trace-standard-capability-runtime",
                                "span-standard-capability-runtime",
                                Optional.empty(),
                                NOW,
                                "authority-service",
                                new InstanceId("instance-authority-service")),
                        Optional.of(NOW.plusSeconds(30)),
                        payload),
                PRINCIPAL,
                7,
                Optional.of(new Revision(0)),
                payloadFingerprint,
                NOW);
    }
}
