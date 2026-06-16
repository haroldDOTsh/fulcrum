package sh.harold.fulcrum.data.artifact;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityCommandProcessor;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityMutationResult;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;

import java.util.List;
import java.util.Objects;

public final class ArtifactMetadataAuthority {
    private static final String CONTRACT_NAME = "artifact-metadata";

    private final AuthorityCommandProcessor<ArtifactMetadataState, PublishArtifactMetadata, ArtifactMetadataReceipt> processor;

    public ArtifactMetadataAuthority(IdempotencyLedger<ArtifactMetadataState, ArtifactMetadataReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                ArtifactMetadataAuthority::rejectionReceipt,
                this::publish);
    }

    public AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> handle(
            AuthorityCommand<PublishArtifactMetadata> command,
            AuthorityRecord<ArtifactMetadataState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<ArtifactMetadataState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, ArtifactMetadataState.empty());
    }

    public static AggregateId aggregateId(ArtifactDigest digest) {
        return new AggregateId(digest.aggregateKey());
    }

    private AuthorityMutationResult<ArtifactMetadataState, ArtifactMetadataReceipt> publish(
            AuthorityCommand<PublishArtifactMetadata> command,
            AuthorityRecord<ArtifactMetadataState> currentRecord) {
        PublishArtifactMetadata payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.digest()))) {
            throw new IllegalArgumentException("artifact metadata aggregate must be keyed by digest");
        }
        if (currentRecord.state().metadata().isPresent()) {
            throw new IllegalStateException("artifact metadata is immutable once published");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        ArtifactMetadata metadata = new ArtifactMetadata(
                payload.digest(),
                payload.kind(),
                payload.byteLength(),
                payload.contentAddress(),
                command.authenticatedPrincipal(),
                payload.provenance(),
                command.receivedAt());
        ArtifactMetadataState state = new ArtifactMetadataState(metadata);
        ArtifactMetadataReceipt receipt = ArtifactMetadataReceipt.accepted(
                payload.digest(),
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        String eventPayload = ArtifactMetadataPublished.from(metadata, nextRevision).wireValue();
        return new AuthorityMutationResult<>(
                nextRevision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, payload.digest().aggregateKey(), eventPayload),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, payload.digest().aggregateKey(), state.wireValue(nextRevision)),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(payload.digest()), state.wireValue(nextRevision))));
    }

    private static ArtifactMetadataReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return ArtifactMetadataReceipt.rejected(reason.name());
    }

    private static String cacheKey(ArtifactDigest digest) {
        return CONTRACT_NAME + ":" + digest.aggregateKey();
    }
}
