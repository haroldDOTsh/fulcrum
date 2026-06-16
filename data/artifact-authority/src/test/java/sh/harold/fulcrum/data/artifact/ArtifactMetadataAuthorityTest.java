package sh.harold.fulcrum.data.artifact;

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
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArtifactMetadataAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T12:30:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("artifact-publisher");
    private static final ArtifactDigest DIGEST = new ArtifactDigest(
            "sha-256",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

    @Test
    void publishesDigestKeyedMetadataWithTransitionReceiptAndEmissions() {
        ArtifactMetadataAuthority authority = authority();

        AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision = authority.handle(
                command("command-1", "idempotency-1", PRINCIPAL, PRINCIPAL, 4, Optional.of(new Revision(0)), payload(DIGEST), "payload-1"),
                ArtifactMetadataAuthority.emptyRecord(4));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertTrue(decision.response().digest().isPresent());
        assertEquals(DIGEST, decision.response().digest().orElseThrow());
        assertEquals(Optional.of(4L), decision.response().fencingEpoch());
        assertEquals(Optional.of("idempotency-1"), decision.response().idempotencyKey());
        assertTrue(decision.state().metadata().isPresent());
        assertEquals(PRINCIPAL, decision.state().metadata().orElseThrow().producerPrincipal());
        assertEquals("trace-artifact", decision.traceEnvelope().traceId());
        assertEquals(
                java.util.List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
    }

    @Test
    void duplicateCommandReplaysAcceptedReceipt() {
        ArtifactMetadataAuthority authority = authority();
        AuthorityRecord<ArtifactMetadataState> initial = ArtifactMetadataAuthority.emptyRecord(4);
        AuthorityCommand<PublishArtifactMetadata> command = command(
                "command-2",
                "idempotency-2",
                PRINCIPAL,
                PRINCIPAL,
                4,
                Optional.of(new Revision(0)),
                payload(DIGEST),
                "payload-2");

        AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> accepted = authority.handle(command, initial);
        AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(accepted.revision(), 4, accepted.state()));

        assertEquals(accepted.response(), replay.response());
        assertTrue(replay.replayed());
    }

    @Test
    void conflictingIdempotencyPayloadRejectsWithoutReplacingStoredReceipt() {
        ArtifactMetadataAuthority authority = authority();
        AuthorityCommand<PublishArtifactMetadata> original = command(
                "command-3",
                "idempotency-3",
                PRINCIPAL,
                PRINCIPAL,
                4,
                Optional.of(new Revision(0)),
                payload(DIGEST),
                "payload-3");
        AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> accepted = authority.handle(
                original,
                ArtifactMetadataAuthority.emptyRecord(4));

        AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> conflict = authority.handle(
                command(
                        "command-4",
                        "idempotency-3",
                        PRINCIPAL,
                        PRINCIPAL,
                        4,
                        Optional.of(new Revision(1)),
                        payload(new ArtifactDigest("sha-256", "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd")),
                        "payload-4"),
                new AuthorityRecord<>(accepted.revision(), 4, accepted.state()));

        assertEquals(AuthorityDecisionStatus.REJECTED, conflict.status());
        assertEquals(Optional.of(AuthorityRejectionReason.IDEMPOTENCY_CONFLICT), conflict.rejectionReason());
        assertEquals(ArtifactMetadataReceiptStatus.REJECTED, conflict.response().status());

        AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> replay = authority.handle(
                original,
                new AuthorityRecord<>(accepted.revision(), 4, accepted.state()));
        assertEquals(accepted.response(), replay.response());
    }

    @Test
    void staleFencingRevisionAndPrincipalMismatchesRejectBeforeMetadataMutation() {
        ArtifactMetadataAuthority authority = authority();

        assertRejected(
                authority.handle(
                        command("command-5", "idempotency-5", PRINCIPAL, PRINCIPAL, 3, Optional.of(new Revision(0)), payload(DIGEST), "payload-5"),
                        ArtifactMetadataAuthority.emptyRecord(4)),
                AuthorityRejectionReason.STALE_FENCING_EPOCH);
        assertRejected(
                authority.handle(
                        command("command-6", "idempotency-6", PRINCIPAL, PRINCIPAL, 4, Optional.of(new Revision(7)), payload(DIGEST), "payload-6"),
                        ArtifactMetadataAuthority.emptyRecord(4)),
                AuthorityRejectionReason.REVISION_MISMATCH);
        assertRejected(
                authority.handle(
                        command("command-7", "idempotency-7", PRINCIPAL, new PrincipalId("different-transport"), 4, Optional.of(new Revision(0)), payload(DIGEST), "payload-7"),
                        ArtifactMetadataAuthority.emptyRecord(4)),
                AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void metadataPayloadDoesNotRestateProducerPrincipal() {
        assertEquals(
                java.util.Set.of("digest", "kind", "byteLength", "contentAddress", "provenance"),
                java.util.Arrays.stream(PublishArtifactMetadata.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void artifactMetadataIsImmutableAfterPublication() {
        ArtifactMetadataAuthority authority = authority();
        AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> accepted = authority.handle(
                command("command-8", "idempotency-8", PRINCIPAL, PRINCIPAL, 4, Optional.of(new Revision(0)), payload(DIGEST), "payload-8"),
                ArtifactMetadataAuthority.emptyRecord(4));

        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command("command-9", "idempotency-9", PRINCIPAL, PRINCIPAL, 4, Optional.of(new Revision(1)), payload(DIGEST), "payload-9"),
                        new AuthorityRecord<>(accepted.revision(), 4, accepted.state())));
    }

    private static ArtifactMetadataAuthority authority() {
        return new ArtifactMetadataAuthority(new InMemoryIdempotencyLedger<>());
    }

    private static PublishArtifactMetadata payload(ArtifactDigest digest) {
        return new PublishArtifactMetadata(
                digest,
                ArtifactKind.CAPABILITY_BUNDLE,
                4096,
                new ContentAddress("object://artifact-store/capability/test"),
                new ProvenanceRef("build:artifact-pipeline:42"));
    }

    private static AuthorityCommand<PublishArtifactMetadata> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            PublishArtifactMetadata payload,
            String payloadFingerprint) {
        CommandEnvelope<PublishArtifactMetadata> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                ArtifactMetadataAuthority.aggregateId(payload.digest()),
                new ContractName("artifact-metadata"),
                new CommandName("publish-artifact-metadata"),
                new TraceEnvelope(
                        "trace-artifact",
                        "span-artifact",
                        Optional.empty(),
                        NOW,
                        "artifact-authority-test",
                        new InstanceId("instance-artifact-authority-test")),
                Optional.empty(),
                payload);
        return new AuthorityCommand<>(
                envelope,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payloadFingerprint,
                NOW);
    }

    private static void assertRejected(
            AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertEquals(ArtifactMetadataReceiptStatus.REJECTED, decision.response().status());
        assertFalse(decision.state().metadata().isPresent());
    }
}
