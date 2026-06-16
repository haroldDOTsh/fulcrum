package sh.harold.fulcrum.data.artifact;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record ArtifactMetadataReceipt(
        ArtifactMetadataReceiptStatus status,
        Optional<String> rejectionReason,
        Optional<ArtifactDigest> digest,
        Optional<Revision> revision,
        Optional<Long> fencingEpoch,
        Optional<String> idempotencyKey,
        Optional<String> commandId) {
    public ArtifactMetadataReceipt {
        status = Objects.requireNonNull(status, "status");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        digest = digest == null ? Optional.empty() : digest;
        revision = revision == null ? Optional.empty() : revision;
        fencingEpoch = fencingEpoch == null ? Optional.empty() : fencingEpoch;
        idempotencyKey = idempotencyKey == null ? Optional.empty() : idempotencyKey.map(ArtifactNames::requireNonBlank);
        commandId = commandId == null ? Optional.empty() : commandId.map(ArtifactNames::requireNonBlank);
    }

    static ArtifactMetadataReceipt accepted(
            ArtifactDigest digest,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new ArtifactMetadataReceipt(
                ArtifactMetadataReceiptStatus.ACCEPTED,
                Optional.empty(),
                Optional.of(digest),
                Optional.of(revision),
                Optional.of(fencingEpoch),
                Optional.of(idempotencyKey),
                Optional.of(commandId));
    }

    static ArtifactMetadataReceipt rejected(String reason) {
        return new ArtifactMetadataReceipt(
                ArtifactMetadataReceiptStatus.REJECTED,
                Optional.of(ArtifactNames.requireNonBlank(reason)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    String wireValue() {
        return "status=" + status.name()
                + "\nreason=" + rejectionReason.orElse("")
                + "\ndigest=" + digest.map(ArtifactDigest::aggregateKey).orElse("")
                + "\nrevision=" + revision.map(value -> Long.toString(value.value())).orElse("")
                + "\nfencingEpoch=" + fencingEpoch.map(Object::toString).orElse("")
                + "\nidempotencyKey=" + idempotencyKey.orElse("")
                + "\ncommandId=" + commandId.orElse("");
    }
}
