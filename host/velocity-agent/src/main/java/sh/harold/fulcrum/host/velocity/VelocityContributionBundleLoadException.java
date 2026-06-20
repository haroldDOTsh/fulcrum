package sh.harold.fulcrum.host.velocity;

import java.util.Objects;

public final class VelocityContributionBundleLoadException extends RuntimeException {
    private final VelocityContributionLoadReceipt receipt;

    public VelocityContributionBundleLoadException(VelocityContributionLoadReceipt receipt, Throwable cause) {
        super(Objects.requireNonNull(receipt, "receipt")
                .refusalReason()
                .orElse("velocity contribution bundle load refused"), cause);
        this.receipt = receipt;
    }

    public VelocityContributionLoadReceipt receipt() {
        return receipt;
    }
}
