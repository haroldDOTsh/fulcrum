package sh.harold.fulcrum.distribution.launcher;

import java.util.Optional;

record BundleContributionInstallReceipt(
        String status,
        String reason,
        Optional<String> cachePath,
        Optional<String> loadEvidence) {
    BundleContributionInstallReceipt {
        status = requireNonBlank(status, "status");
        reason = requireNonBlank(reason, "reason");
        cachePath = cachePath == null ? Optional.empty() : cachePath.map(value -> requireNonBlank(value, "cachePath"));
        loadEvidence = loadEvidence == null
                ? Optional.empty()
                : loadEvidence.map(value -> requireNonBlank(value, "loadEvidence"));
    }

    static BundleContributionInstallReceipt staged(String cachePath, String loadEvidence) {
        return staged("contribution-artifact-verified-and-staged", cachePath, loadEvidence);
    }

    static BundleContributionInstallReceipt staged(String reason, String cachePath, String loadEvidence) {
        return new BundleContributionInstallReceipt(
                "STAGED",
                reason,
                Optional.of(cachePath),
                Optional.of(loadEvidence));
    }

    static BundleContributionInstallReceipt blocked(String reason, Optional<String> loadEvidence) {
        return new BundleContributionInstallReceipt(
                "STAGE_BLOCKED",
                reason,
                Optional.empty(),
                loadEvidence);
    }

    boolean staged() {
        return status.equals("STAGED") && cachePath.isPresent() && loadEvidence.isPresent();
    }

    private static String requireNonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
