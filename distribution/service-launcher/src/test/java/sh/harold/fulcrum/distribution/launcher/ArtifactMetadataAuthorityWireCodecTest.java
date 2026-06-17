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
import sh.harold.fulcrum.data.artifact.ArtifactDigest;
import sh.harold.fulcrum.data.artifact.ArtifactKind;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataAuthority;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataReceipt;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataState;
import sh.harold.fulcrum.data.artifact.ContentAddress;
import sh.harold.fulcrum.data.artifact.ProvenanceRef;
import sh.harold.fulcrum.data.artifact.PublishArtifactMetadata;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ArtifactMetadataAuthorityWireCodecTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final ArtifactDigest DIGEST = new ArtifactDigest(
            "sha256",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-artifact-runtime");

    @Test
    void commandPayloadRoundTripsThroughKafkaRecordWireFormat() {
        AuthorityCommand<PublishArtifactMetadata> command = publishCommand();

        AuthorityCommand<PublishArtifactMetadata> decoded = ArtifactMetadataAuthorityWireCodec.decodeCommand(
                new ConsumerRecord<>(
                        "cmd.artifact-metadata",
                        0,
                        12L,
                        command.envelope().aggregateId().value(),
                        ArtifactMetadataAuthorityWireCodec.encodeCommand(command)));

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
    void storedDecisionRoundTripsArtifactMetadataStateAndReceipt() {
        var decision = new ArtifactMetadataAuthority(
                new InMemoryIdempotencyLedger<ArtifactMetadataState, ArtifactMetadataReceipt>())
                .handle(publishCommand(), ArtifactMetadataAuthority.emptyRecord(7));
        StoredAuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> stored =
                new StoredAuthorityDecision<>("payload-artifact", decision);

        StoredAuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decoded =
                ArtifactMetadataAuthorityWireCodec.decodeStoredDecision(
                        ArtifactMetadataAuthorityWireCodec.encodeStoredDecision(stored));

        assertEquals("payload-artifact", decoded.payloadFingerprint());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, decoded.decision().status());
        assertEquals(new Revision(1), decoded.decision().revision());
        assertEquals(DIGEST, decoded.decision().state().metadata().orElseThrow().digest());
        assertEquals(PRINCIPAL, decoded.decision().state().metadata().orElseThrow().producerPrincipal());
        assertEquals(Optional.of(DIGEST), decoded.decision().response().digest());
        assertEquals("trace-artifact-runtime", decoded.decision().traceEnvelope().traceId());
    }

    private static AuthorityCommand<PublishArtifactMetadata> publishCommand() {
        PublishArtifactMetadata payload = new PublishArtifactMetadata(
                DIGEST,
                ArtifactKind.CONTENT_PACK_ARTIFACT,
                4096,
                new ContentAddress("object://content/sha256/aa"),
                new ProvenanceRef("build:codec-test"));
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-publish-artifact"),
                        new IdempotencyKey("idem-publish-artifact"),
                        PRINCIPAL,
                        ArtifactMetadataAuthority.aggregateId(DIGEST),
                        new ContractName(ArtifactMetadataAuthorityWireCodec.CONTRACT),
                        new CommandName(ArtifactMetadataAuthorityWireCodec.PUBLISH_COMMAND),
                        new TraceEnvelope(
                                "trace-artifact-runtime",
                                "span-artifact-runtime",
                                Optional.empty(),
                                NOW,
                                "authority-service",
                                new InstanceId("instance-authority-service")),
                        Optional.of(NOW.plusSeconds(30)),
                        payload),
                PRINCIPAL,
                7,
                Optional.of(new Revision(0)),
                "payload-publish-artifact",
                NOW);
    }
}
