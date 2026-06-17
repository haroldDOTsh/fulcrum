package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
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
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;
import sh.harold.fulcrum.data.subject.RegisterSubject;
import sh.harold.fulcrum.data.subject.SubjectAuthority;
import sh.harold.fulcrum.data.subject.SubjectCommand;
import sh.harold.fulcrum.data.subject.SubjectExternalIdentity;
import sh.harold.fulcrum.data.subject.SubjectIdentityProvider;
import sh.harold.fulcrum.data.subject.SubjectReceipt;
import sh.harold.fulcrum.data.subject.SubjectState;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SubjectAuthorityWireCodecTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final SubjectId SUBJECT =
            new SubjectId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-subject-runtime");

    @Test
    void commandPayloadRoundTripsThroughKafkaRecordWireFormat() {
        AuthorityCommand<SubjectCommand> command = registerCommand();

        AuthorityCommand<SubjectCommand> decoded = SubjectAuthorityWireCodec.decodeCommand(
                new ConsumerRecord<>(
                        "cmd.subject",
                        0,
                        12L,
                        command.envelope().aggregateId().value(),
                        SubjectAuthorityWireCodec.encodeCommand(command)));

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
    void storedDecisionRoundTripsSubjectStateAndReceipt() {
        var decision = new SubjectAuthority(new InMemoryIdempotencyLedger<SubjectState, SubjectReceipt>())
                .handle(registerCommand(), SubjectAuthority.emptyRecord(7));
        StoredAuthorityDecision<SubjectState, SubjectReceipt> stored =
                new StoredAuthorityDecision<>("payload-subject", decision);

        StoredAuthorityDecision<SubjectState, SubjectReceipt> decoded =
                SubjectAuthorityWireCodec.decodeStoredDecision(
                        SubjectAuthorityWireCodec.encodeStoredDecision(stored));

        assertEquals("payload-subject", decoded.payloadFingerprint());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, decoded.decision().status());
        assertEquals(new Revision(1), decoded.decision().revision());
        assertEquals(SUBJECT, decoded.decision().state().current().orElseThrow().subjectId());
        assertEquals(Optional.of(SUBJECT), decoded.decision().response().subjectId());
        assertEquals("trace-subject-runtime", decoded.decision().traceEnvelope().traceId());
    }

    private static AuthorityCommand<SubjectCommand> registerCommand() {
        RegisterSubject payload = new RegisterSubject(
                SUBJECT,
                SubjectIdentityProvider.MINECRAFT_ACCOUNT,
                new SubjectExternalIdentity("minecraft:" + SUBJECT.value()),
                NOW);
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-register-subject"),
                        new IdempotencyKey("idem-register-subject"),
                        PRINCIPAL,
                        SubjectAuthority.aggregateId(SUBJECT),
                        new ContractName(SubjectAuthorityWireCodec.CONTRACT),
                        new CommandName(SubjectAuthorityWireCodec.REGISTER_COMMAND),
                        new TraceEnvelope(
                                "trace-subject-runtime",
                                "span-subject-runtime",
                                Optional.empty(),
                                NOW,
                                "authority-service",
                                new InstanceId("instance-authority-service")),
                        Optional.of(NOW.plusSeconds(30)),
                        payload),
                PRINCIPAL,
                7,
                Optional.of(new Revision(0)),
                "payload-register-subject",
                NOW);
    }
}
