package sh.harold.fulcrum.host.paper;

import java.util.Objects;

public final class PaperContributionBundleLoadException extends RuntimeException {
    private final PaperContributionLoadReceipt receipt;

    public PaperContributionBundleLoadException(PaperContributionLoadReceipt receipt, Throwable cause) {
        super(Objects.requireNonNull(receipt, "receipt")
                .refusalReason()
                .orElse("paper contribution bundle load refused"), cause);
        this.receipt = receipt;
    }

    public PaperContributionLoadReceipt receipt() {
        return receipt;
    }
}
