package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;
import sh.harold.fulcrum.standard.contracts.RankContracts;
import sh.harold.fulcrum.standard.profile.PlayerProfileAuthority;
import sh.harold.fulcrum.standard.profile.UpsertPlayerProfile;
import sh.harold.fulcrum.standard.punishment.IssuePunishment;
import sh.harold.fulcrum.standard.punishment.PunishmentAuthority;
import sh.harold.fulcrum.standard.rank.GrantRank;
import sh.harold.fulcrum.standard.rank.RankAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LobbyCapabilitySeedProvisionerTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final Instant SEEDED_AT = Instant.parse("2026-02-03T04:05:06Z");
    private static final Instant PUNISHMENT_EXPIRES_AT = Instant.parse("2099-02-03T04:05:06Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-test-capability-seed");
    private static final InstanceId INSTANCE = new InstanceId("instance-test-capability-seed");
    private static final SubjectId ACCEPTED_SUBJECT = new SubjectId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final SubjectId SECOND_ACCEPTED_SUBJECT = new SubjectId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private static final SubjectId SCALE_OUT_ACCEPTED_SUBJECT = new SubjectId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
    private static final SubjectId DENIED_SUBJECT = new SubjectId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

    @Test
    void publishesProfileRankAndPunishmentAuthorityCommands() throws Exception {
        LobbyCapabilitySeedProvisioner.Config config = config();
        MockProducer<String, String> producer = producer();

        LobbyCapabilitySeedProvisioner.Result result = LobbyCapabilitySeedProvisioner.provision(
                config,
                producer,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(4, result.seededSubjects().size());
        assertEquals(9, result.publishedCommandCount());
        assertFalse(result.seededSubjects().getFirst().punishmentCommand().isPresent());
        assertFalse(result.seededSubjects().get(1).punishmentCommand().isPresent());
        assertFalse(result.seededSubjects().get(2).punishmentCommand().isPresent());
        assertTrue(result.seededSubjects().getLast().punishmentCommand().isPresent());

        List<ProducerRecord<String, String>> history = producer.history();
        assertEquals(PlayerProfileContracts.COMMAND_TOPIC, history.get(0).topic());
        assertEquals(RankContracts.COMMAND_TOPIC, history.get(1).topic());
        assertEquals(PlayerProfileContracts.COMMAND_TOPIC, history.get(2).topic());
        assertEquals(RankContracts.COMMAND_TOPIC, history.get(3).topic());
        assertEquals(PlayerProfileContracts.COMMAND_TOPIC, history.get(4).topic());
        assertEquals(RankContracts.COMMAND_TOPIC, history.get(5).topic());
        assertEquals(PlayerProfileContracts.COMMAND_TOPIC, history.get(6).topic());
        assertEquals(RankContracts.COMMAND_TOPIC, history.get(7).topic());
        assertEquals(PunishmentContracts.COMMAND_TOPIC, history.get(8).topic());

        AuthorityCommand<UpsertPlayerProfile> acceptedProfile = decodeProfile(history.get(0));
        assertEquals(PlayerProfileAuthority.aggregateId(ACCEPTED_SUBJECT).value(), history.get(0).key());
        assertEquals(ACCEPTED_SUBJECT, acceptedProfile.envelope().payload().subjectId());
        assertEquals("Fulcrum Bot", acceptedProfile.envelope().payload().displayName());
        assertEquals(SEEDED_AT, acceptedProfile.envelope().payload().observedAt());
        assertCommonEnvelope(acceptedProfile);

        AuthorityCommand<GrantRank> acceptedRank = decodeRank(history.get(1));
        assertEquals(RankAuthority.aggregateId(ACCEPTED_SUBJECT).value(), history.get(1).key());
        assertEquals(ACCEPTED_SUBJECT, acceptedRank.envelope().payload().subjectId());
        assertEquals("Admin", acceptedRank.envelope().payload().rankKey());
        assertEquals(SEEDED_AT, acceptedRank.envelope().payload().grantedAt());
        assertCommonEnvelope(acceptedRank);

        AuthorityCommand<UpsertPlayerProfile> secondAcceptedProfile = decodeProfile(history.get(2));
        assertEquals(PlayerProfileAuthority.aggregateId(SECOND_ACCEPTED_SUBJECT).value(), history.get(2).key());
        assertEquals(SECOND_ACCEPTED_SUBJECT, secondAcceptedProfile.envelope().payload().subjectId());
        assertEquals("Fulcrum Bot Two", secondAcceptedProfile.envelope().payload().displayName());

        AuthorityCommand<GrantRank> secondAcceptedRank = decodeRank(history.get(3));
        assertEquals(RankAuthority.aggregateId(SECOND_ACCEPTED_SUBJECT).value(), history.get(3).key());
        assertEquals(SECOND_ACCEPTED_SUBJECT, secondAcceptedRank.envelope().payload().subjectId());
        assertEquals("Admin", secondAcceptedRank.envelope().payload().rankKey());

        AuthorityCommand<UpsertPlayerProfile> scaleOutAcceptedProfile = decodeProfile(history.get(4));
        assertEquals(PlayerProfileAuthority.aggregateId(SCALE_OUT_ACCEPTED_SUBJECT).value(), history.get(4).key());
        assertEquals(SCALE_OUT_ACCEPTED_SUBJECT, scaleOutAcceptedProfile.envelope().payload().subjectId());
        assertEquals("Fulcrum Bot Four", scaleOutAcceptedProfile.envelope().payload().displayName());

        AuthorityCommand<GrantRank> scaleOutAcceptedRank = decodeRank(history.get(5));
        assertEquals(RankAuthority.aggregateId(SCALE_OUT_ACCEPTED_SUBJECT).value(), history.get(5).key());
        assertEquals(SCALE_OUT_ACCEPTED_SUBJECT, scaleOutAcceptedRank.envelope().payload().subjectId());
        assertEquals("Admin", scaleOutAcceptedRank.envelope().payload().rankKey());

        AuthorityCommand<UpsertPlayerProfile> deniedProfile = decodeProfile(history.get(6));
        assertEquals(PlayerProfileAuthority.aggregateId(DENIED_SUBJECT).value(), history.get(6).key());
        assertEquals(DENIED_SUBJECT, deniedProfile.envelope().payload().subjectId());
        assertEquals("Denied Bot", deniedProfile.envelope().payload().displayName());

        AuthorityCommand<GrantRank> deniedRank = decodeRank(history.get(7));
        assertEquals(RankAuthority.aggregateId(DENIED_SUBJECT).value(), history.get(7).key());
        assertEquals(DENIED_SUBJECT, deniedRank.envelope().payload().subjectId());
        assertEquals("Member", deniedRank.envelope().payload().rankKey());

        AuthorityCommand<IssuePunishment> punishment = decodePunishment(history.get(8));
        assertEquals(PunishmentAuthority.aggregateId(DENIED_SUBJECT).value(), history.get(8).key());
        assertEquals(DENIED_SUBJECT, punishment.envelope().payload().subjectId());
        assertEquals("punishment-denied", punishment.envelope().payload().punishmentId());
        assertEquals("Denied by test seed", punishment.envelope().payload().reason());
        assertEquals(SEEDED_AT, punishment.envelope().payload().issuedAt());
        assertEquals(PUNISHMENT_EXPIRES_AT, punishment.envelope().payload().expiresAt());
        assertCommonEnvelope(punishment);
    }

    @Test
    void commandIdentityAndPayloadFingerprintAreStableAcrossRetries() throws Exception {
        LobbyCapabilitySeedProvisioner.Config config = config();

        LobbyCapabilitySeedProvisioner.Result first = LobbyCapabilitySeedProvisioner.provision(
                config,
                producer(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        LobbyCapabilitySeedProvisioner.Result second = LobbyCapabilitySeedProvisioner.provision(
                config,
                producer(),
                Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC));

        for (int index = 0; index < first.seededSubjects().size(); index++) {
            LobbyCapabilitySeedProvisioner.SeededSubject left = first.seededSubjects().get(index);
            LobbyCapabilitySeedProvisioner.SeededSubject right = second.seededSubjects().get(index);
            assertStableIdentity(left.playerProfileCommand(), right.playerProfileCommand());
            assertStableIdentity(left.rankCommand(), right.rankCommand());
            if (left.punishmentCommand().isPresent()) {
                assertStableIdentity(left.punishmentCommand().orElseThrow(), right.punishmentCommand().orElseThrow());
            }
        }
    }

    @Test
    void configDefaultsToAcceptedOfflineModeSubjectAndStandardTopics() {
        LobbyCapabilitySeedProvisioner.Config config = LobbyCapabilitySeedProvisioner.Config.fromEnvironment(
                RuntimeEnvironment.of(Map.of("FULCRUM_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")));

        assertEquals("kafka:9092", config.kafkaBootstrapServers());
        assertEquals(PlayerProfileContracts.COMMAND_TOPIC, config.playerProfileCommandTopic());
        assertEquals(RankContracts.COMMAND_TOPIC, config.rankCommandTopic());
        assertEquals(PunishmentContracts.COMMAND_TOPIC, config.punishmentCommandTopic());
        assertEquals(LobbyCapabilitySeedProvisioner.DEFAULT_SEEDED_AT, config.seededAt());
        assertEquals(3, config.subjects().size());
        assertEquals("FulcrumBotOne", config.subjects().getFirst().username());
        assertEquals(
                LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne"),
                config.subjects().getFirst().subjectId());
        assertEquals("Admin", config.subjects().getFirst().rankKey());
        assertFalse(config.subjects().getFirst().punishment().isPresent());
        assertEquals("FulcrumBotTwo", config.subjects().get(1).username());
        assertEquals(
                LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotTwo"),
                config.subjects().get(1).subjectId());
        assertEquals("Admin", config.subjects().get(1).rankKey());
        assertFalse(config.subjects().get(1).punishment().isPresent());
        assertEquals("FulcrumBotFour", config.subjects().get(2).username());
        assertEquals(
                LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotFour"),
                config.subjects().get(2).subjectId());
        assertEquals("Admin", config.subjects().get(2).rankKey());
        assertFalse(config.subjects().get(2).punishment().isPresent());
    }

    @Test
    void configAddsDeniedSubjectWhenDeniedUsernameIsBound() {
        LobbyCapabilitySeedProvisioner.Config config = LobbyCapabilitySeedProvisioner.Config.fromEnvironment(
                RuntimeEnvironment.of(Map.of(
                        "FULCRUM_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092",
                        "FULCRUM_LOBBY_DENIED_USERNAME", "FulcrumBannedOne",
                        "FULCRUM_LOBBY_DENIED_PUNISHMENT_REASON", "No entry")));

        assertEquals(4, config.subjects().size());
        LobbyCapabilitySeedProvisioner.SubjectSeed denied = config.subjects().getLast();
        assertEquals("FulcrumBannedOne", denied.username());
        assertEquals(
                LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBannedOne"),
                denied.subjectId());
        assertEquals("No entry", denied.punishment().orElseThrow().reason());
        assertEquals(
                LobbyCapabilitySeedProvisioner.DEFAULT_DENIED_PUNISHMENT_EXPIRES_AT,
                denied.punishment().orElseThrow().expiresAt());
    }

    private static LobbyCapabilitySeedProvisioner.Config config() {
        return new LobbyCapabilitySeedProvisioner.Config(
                "unused:9092",
                PlayerProfileContracts.COMMAND_TOPIC,
                RankContracts.COMMAND_TOPIC,
                PunishmentContracts.COMMAND_TOPIC,
                PRINCIPAL,
                INSTANCE,
                SEEDED_AT,
                List.of(
                        new LobbyCapabilitySeedProvisioner.SubjectSeed(
                                ACCEPTED_SUBJECT,
                                "FulcrumBot",
                                "Fulcrum Bot",
                                "Admin",
                                Optional.empty()),
                        new LobbyCapabilitySeedProvisioner.SubjectSeed(
                                SECOND_ACCEPTED_SUBJECT,
                                "FulcrumBotTwo",
                                "Fulcrum Bot Two",
                                "Admin",
                                Optional.empty()),
                        new LobbyCapabilitySeedProvisioner.SubjectSeed(
                                SCALE_OUT_ACCEPTED_SUBJECT,
                                "FulcrumBotFour",
                                "Fulcrum Bot Four",
                                "Admin",
                                Optional.empty()),
                        new LobbyCapabilitySeedProvisioner.SubjectSeed(
                                DENIED_SUBJECT,
                                "DeniedBot",
                                "Denied Bot",
                                "Member",
                                Optional.of(new LobbyCapabilitySeedProvisioner.PunishmentSeed(
                                        "punishment-denied",
                                        "Denied by test seed",
                                        PUNISHMENT_EXPIRES_AT)))));
    }

    private static AuthorityCommand<UpsertPlayerProfile> decodeProfile(ProducerRecord<String, String> record) {
        return StandardCapabilityAuthorityWireCodec.decodePlayerProfileCommand(
                new ConsumerRecord<>(record.topic(), 0, 0L, record.key(), record.value()));
    }

    private static AuthorityCommand<GrantRank> decodeRank(ProducerRecord<String, String> record) {
        return StandardCapabilityAuthorityWireCodec.decodeRankCommand(
                new ConsumerRecord<>(record.topic(), 0, 0L, record.key(), record.value()));
    }

    private static AuthorityCommand<IssuePunishment> decodePunishment(ProducerRecord<String, String> record) {
        return StandardCapabilityAuthorityWireCodec.decodePunishmentCommand(
                new ConsumerRecord<>(record.topic(), 0, 0L, record.key(), record.value()));
    }

    private static void assertCommonEnvelope(AuthorityCommand<?> command) {
        assertEquals(PRINCIPAL, command.authenticatedPrincipal());
        assertEquals(PRINCIPAL, command.envelope().principalId());
        assertEquals(1, command.fencingEpoch());
        assertEquals(new Revision(0), command.expectedRevision().orElseThrow());
        assertEquals(NOW, command.receivedAt());
        assertEquals(NOW.plusSeconds(30), command.envelope().deadlineAt().orElseThrow());
        assertEquals(INSTANCE, command.envelope().traceEnvelope().originInstanceId());
    }

    private static void assertStableIdentity(AuthorityCommand<?> first, AuthorityCommand<?> second) {
        assertEquals(first.envelope().commandId(), second.envelope().commandId());
        assertEquals(first.envelope().idempotencyKey(), second.envelope().idempotencyKey());
        assertEquals(first.payloadFingerprint(), second.payloadFingerprint());
    }

    private static MockProducer<String, String> producer() {
        return new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    }
}
