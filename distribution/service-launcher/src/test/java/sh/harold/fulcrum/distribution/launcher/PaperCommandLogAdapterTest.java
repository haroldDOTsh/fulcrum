package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.session.ActivateSession;
import sh.harold.fulcrum.data.session.OpenSession;
import sh.harold.fulcrum.data.session.SessionCommand;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationFactory;
import sh.harold.fulcrum.host.api.HostObservationWireCodec;
import sh.harold.fulcrum.host.api.HostReadinessReport;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.paper.PaperSessionRewardReport;
import sh.harold.fulcrum.host.paper.PaperSessionActivationRequest;
import sh.harold.fulcrum.host.paper.PaperSessionOpenRequest;
import sh.harold.fulcrum.standard.contracts.EconomyContracts;
import sh.harold.fulcrum.standard.contracts.StatsContracts;
import sh.harold.fulcrum.standard.economy.PostLedgerEntry;
import sh.harold.fulcrum.standard.stats.RecordStatDelta;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PaperCommandLogAdapterTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final SessionId SESSION = new SessionId("session-paper-command-log");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final String OWNER_TOKEN = "owner-token-paper-command-log";

    @Test
    void sessionLifecyclePortPublishesOpenAndActivateCommandsToSessionCommandTopic() {
        MockProducer<String, String> producer = producer();
        KafkaPaperSessionLifecyclePort port = new KafkaPaperSessionLifecyclePort(
                securityContext(sessionCommandGrant(), observationGrant()),
                producer);

        port.openSession(openRequest());
        port.activateSession(activationRequest());

        List<ProducerRecord<String, String>> history = producer.history();
        assertEquals(2, history.size());
        assertEquals(KafkaPaperSessionLifecyclePort.SESSION_COMMAND_TOPIC, history.getFirst().topic());
        assertEquals("session:" + SESSION.value(), history.getFirst().key());
        assertEquals("session:" + SESSION.value(), history.get(1).key());

        AuthorityCommand<SessionCommand> open = decode(history.getFirst());
        AuthorityCommand<SessionCommand> activate = decode(history.get(1));

        assertEquals(SessionAuthorityWireCodec.OPEN_COMMAND, open.envelope().commandName().value());
        assertEquals(Optional.of(new Revision(0)), open.expectedRevision());
        assertEquals(1, open.fencingEpoch());
        assertEquals(securityContext(sessionCommandGrant(), observationGrant()).identity().principalId(), open.authenticatedPrincipal());
        assertEquals(SESSION, ((OpenSession) open.envelope().payload()).sessionId());

        assertEquals(SessionAuthorityWireCodec.ACTIVATE_COMMAND, activate.envelope().commandName().value());
        assertEquals(Optional.of(new Revision(1)), activate.expectedRevision());
        assertEquals(1, ((ActivateSession) activate.envelope().payload()).ownerEpoch());
    }

    @Test
    void sessionLifecyclePortRejectsMissingSessionCommandGrantBeforePublishing() {
        MockProducer<String, String> producer = producer();
        KafkaPaperSessionLifecyclePort port = new KafkaPaperSessionLifecyclePort(
                securityContext(observationGrant()),
                producer);

        assertThrows(SecurityException.class, () -> port.openSession(openRequest()));
        assertEquals(List.of(), producer.history());
    }

    @Test
    void observationSinkPublishesEncodedHostObservation() {
        MockProducer<String, String> producer = producer();
        KafkaPaperObservationSink sink = new KafkaPaperObservationSink(
                securityContext(sessionCommandGrant(), observationGrant()),
                producer,
                "host.observation");
        HostObservation observation = HostObservationFactory.readiness(new HostReadinessReport(
                securityContext(sessionCommandGrant(), observationGrant()).identity(),
                new ResolvedManifestId("manifest-paper-command-log"),
                trace(),
                NOW));

        sink.publish(observation);

        ProducerRecord<String, String> record = producer.history().getFirst();
        assertEquals("host.observation", record.topic());
        assertEquals("instance-paper-command-log", record.key());

        HostObservation decoded = HostObservationWireCodec.decode(record.value());
        assertEquals(observation.instanceId(), decoded.instanceId());
        assertEquals(observation.observationType(), decoded.observationType());
        assertEquals("manifest-paper-command-log", decoded.attributes().get("resolvedManifestId"));
    }

    @Test
    void observationSinkRejectsUnscopedTopicBeforePublishing() {
        MockProducer<String, String> producer = producer();
        KafkaPaperObservationSink sink = new KafkaPaperObservationSink(
                securityContext(sessionCommandGrant()),
                producer,
                "host.observation");

        assertThrows(SecurityException.class, () -> sink.publish(HostObservationFactory.readiness(new HostReadinessReport(
                securityContext(sessionCommandGrant()).identity(),
                new ResolvedManifestId("manifest-paper-command-log"),
                trace(),
                NOW))));
        assertEquals(List.of(), producer.history());
    }

    @Test
    void rewardPublisherPublishesEconomyAndStatsCommands() {
        MockProducer<String, String> producer = producer();
        PaperRewardCommandPublisher publisher = new PaperRewardCommandPublisher(
                securityContext(economyRewardGrant(), statsRewardGrant()),
                producer,
                EconomyContracts.COMMAND_TOPIC,
                StatsContracts.COMMAND_TOPIC,
                new ExperienceId("experience-paper-command-log"),
                "coins",
                250,
                "session-completions");

        publisher.publish(rewardReport());

        List<ProducerRecord<String, String>> history = producer.history();
        assertEquals(2, history.size());
        assertEquals(EconomyContracts.COMMAND_TOPIC, history.getFirst().topic());
        assertEquals(StatsContracts.COMMAND_TOPIC, history.get(1).topic());

        AuthorityCommand<PostLedgerEntry> economy = StandardCapabilityAuthorityWireCodec.decodeEconomyCommand(
                consumerRecord(history.getFirst()));
        AuthorityCommand<RecordStatDelta> stats = StandardCapabilityAuthorityWireCodec.decodeStatsCommand(
                consumerRecord(history.get(1)));

        assertEquals(SUBJECT, economy.envelope().payload().subjectId());
        assertEquals("coins", economy.envelope().payload().currencyKey());
        assertEquals(250, economy.envelope().payload().deltaMinorUnits());
        assertEquals(Optional.empty(), economy.expectedRevision());
        assertEquals(1, economy.fencingEpoch());
        assertEquals(securityContext(economyRewardGrant(), statsRewardGrant()).identity().principalId(),
                economy.authenticatedPrincipal());

        assertEquals(SUBJECT, stats.envelope().payload().subjectId());
        assertEquals(new ExperienceId("experience-paper-command-log"), stats.envelope().payload().experienceId());
        assertEquals("session-completions", stats.envelope().payload().statKey());
        assertEquals(1, stats.envelope().payload().delta());
        assertEquals(Optional.empty(), stats.expectedRevision());
        assertEquals(1, stats.fencingEpoch());
    }

    @Test
    void rewardPublisherRejectsMissingTopicGrantBeforePublishing() {
        MockProducer<String, String> producer = producer();
        PaperRewardCommandPublisher publisher = new PaperRewardCommandPublisher(
                securityContext(economyRewardGrant()),
                producer,
                EconomyContracts.COMMAND_TOPIC,
                StatsContracts.COMMAND_TOPIC,
                new ExperienceId("experience-paper-command-log"),
                "coins",
                250,
                "session-completions");

        assertThrows(SecurityException.class, () -> publisher.publish(rewardReport()));
        assertEquals(List.of(), producer.history());
    }

    private static AuthorityCommand<SessionCommand> decode(ProducerRecord<String, String> record) {
        return SessionAuthorityWireCodec.decodeCommand(new ConsumerRecord<>(
                record.topic(),
                0,
                0,
                record.key(),
                record.value()));
    }

    private static ConsumerRecord<String, String> consumerRecord(ProducerRecord<String, String> record) {
        return new ConsumerRecord<>(record.topic(), 0, 0, record.key(), record.value());
    }

    private static PaperSessionOpenRequest openRequest() {
        return new PaperSessionOpenRequest(
                SESSION,
                new ExperienceId("experience-paper-command-log"),
                new SlotId("slot-paper-command-log"),
                new InstanceId("instance-paper-command-log"),
                OWNER_TOKEN,
                new ResolvedManifestId("manifest-paper-command-log"),
                NOW,
                NOW.plusSeconds(300),
                trace());
    }

    private static PaperSessionActivationRequest activationRequest() {
        return new PaperSessionActivationRequest(
                SESSION,
                OWNER_TOKEN,
                1,
                NOW.plusSeconds(1),
                NOW.plusSeconds(300),
                trace());
    }

    private static PaperSessionRewardReport rewardReport() {
        return new PaperSessionRewardReport(
                new InstanceId("instance-paper-command-log"),
                SESSION,
                new RouteId("route-paper-command-log"),
                SUBJECT,
                trace(),
                NOW);
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-paper-command-log",
                "span-paper-command-log",
                Optional.empty(),
                NOW,
                "paper-agent",
                new InstanceId("instance-paper-command-log"));
    }

    private static HostSecurityContext securityContext(HostResourceGrant... grants) {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-paper-command-log"),
                        HostInstanceKinds.PAPER,
                        new PoolId("pool-paper-command-log"),
                        new MachineRef("machine-paper-command-log"),
                        new PrincipalId("principal-paper-command-log")),
                "service-account:paper-agent",
                HostCredentialScope.of(grants));
    }

    private static HostResourceGrant sessionCommandGrant() {
        return new HostResourceGrant(
                HostResourceFamily.TOPIC,
                HostAccessMode.PRODUCE,
                KafkaPaperSessionLifecyclePort.SESSION_COMMAND_TOPIC);
    }

    private static HostResourceGrant observationGrant() {
        return new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "host.observation");
    }

    private static HostResourceGrant economyRewardGrant() {
        return new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, EconomyContracts.COMMAND_TOPIC);
    }

    private static HostResourceGrant statsRewardGrant() {
        return new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, StatsContracts.COMMAND_TOPIC);
    }

    private static MockProducer<String, String> producer() {
        return new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    }
}
