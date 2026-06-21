package sh.harold.fulcrum.core.artifact;

import java.util.Objects;

public final class ArtifactVerificationException extends RuntimeException {
    private final ArtifactVerificationReceipt receipt;

    public ArtifactVerificationException(ArtifactVerificationReceipt receipt) {
        super(Objects.requireNonNull(receipt, "receipt").refusalReason().orElse("artifact verification refused"));
        this.receipt = receipt;
    }

    public ArtifactVerificationReceipt receipt() {
        return receipt;
    }
}
