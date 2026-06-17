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
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;
import sh.harold.fulcrum.data.session.OpenSession;
import sh.harold.fulcrum.data.session.SessionAuthority;
import sh.harold.fulcrum.data.session.SessionCommand;
import sh.harold.fulcrum.data.session.SessionLifecycleStatus;
import sh.harold.fulcrum.data.session.SessionOwnerToken;
import sh.harold.fulcrum.data.session.SessionReceipt;
import sh.harold.fulcrum.data.session.SessionState;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SessionAuthorityWireCodecTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final SessionId SESSION = new SessionId("session-codec-test");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-session-runtime");

    @Test
    void commandPayloadRoundTripsThroughKafkaRecordWireFormat() {
        AuthorityCommand<SessionCommand> command = openCommand();

        AuthorityCommand<SessionCommand> decoded = SessionAuthorityWireCodec.decodeCommand(
                new ConsumerRecord<>(
                        "cmd.session",
                        0,
                        12L,
                        command.envelope().aggregateId().value(),
                        SessionAuthorityWireCodec.encodeCommand(command)));

        assertEquals(command.envelope().commandId(), decoded.envelope().commandId());
        assertEquals(command.envelope().idempotencyKey(), decoded.envelope().idempotencyKey());
        assertEquals(command.envelope().aggregateId(), decoded.envelope().aggregateId());
        assertEquals(command.authenticatedPrincipal(), decoded.authenticatedPrincipal());
        assertEquals(command.fencingEpoch(), decoded.fencingEpoch());
        assertEquals(command.expectedRevision(), decoded.expectedRevision());
        assertEquals(command.payloadFingerprint(), decoded.payloadFingerprint());
        assertEquals(command.receivedAt(), decoded.receivedAt());
        assertEquals(command.envelope().payload(), decoded.envelope().payload());
    }

    @Test
    void storedDecisionRoundTripsSessionStateAndReceipt() {
        var decision = new SessionAuthority(new InMemoryIdempotencyLedger<SessionState, SessionReceipt>())
                .handle(openCommand(), SessionAuthority.emptyRecord(7));
        StoredAuthorityDecision<SessionState, SessionReceipt> stored =
                new StoredAuthorityDecision<>("payload-session", decision);

        StoredAuthorityDecision<SessionState, SessionReceipt> decoded =
                SessionAuthorityWireCodec.decodeStoredDecision(SessionAuthorityWireCodec.encodeStoredDecision(stored));

        assertEquals("payload-session", decoded.payloadFingerprint());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, decoded.decision().status());
        assertEquals(new Revision(1), decoded.decision().revision());
        assertEquals(SESSION, decoded.decision().state().current().orElseThrow().sessionId());
        assertEquals(SessionLifecycleStatus.PREPARING, decoded.decision().state().current().orElseThrow().status());
        assertEquals(Optional.of(SESSION), decoded.decision().response().sessionId());
        assertEquals("trace-session-runtime", decoded.decision().traceEnvelope().traceId());
    }

    private static AuthorityCommand<SessionCommand> openCommand() {
        OpenSession payload = new OpenSession(
                SESSION,
                new ExperienceId("experience-codec-test"),
                new SlotId("slot-codec-test"),
                new InstanceId("instance-paper-codec-test"),
                new SessionOwnerToken("session-owner-token-codec-test"),
                new ResolvedManifestId("manifest-codec-test"),
                NOW,
                NOW.plusSeconds(30));
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-open-session"),
                        new IdempotencyKey("idem-open-session"),
                        PRINCIPAL,
                        SessionAuthority.aggregateId(SESSION),
                        new ContractName(SessionAuthorityWireCodec.CONTRACT),
                        new CommandName(SessionAuthorityWireCodec.OPEN_COMMAND),
                        new TraceEnvelope(
                                "trace-session-runtime",
                                "span-session-runtime",
                                Optional.empty(),
                                NOW,
                                "authority-service",
                                new InstanceId("instance-authority-service")),
                        Optional.of(NOW.plusSeconds(30)),
                        payload),
                PRINCIPAL,
                7,
                Optional.of(new Revision(0)),
                "payload-open-session",
                NOW);
    }
}
