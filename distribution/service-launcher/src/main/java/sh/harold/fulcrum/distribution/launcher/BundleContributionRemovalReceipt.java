package sh.harold.fulcrum.distribution.launcher;

record BundleContributionRemovalReceipt(String status, String reason) {
    BundleContributionRemovalReceipt {
        status = requireNonBlank(status, "status");
        reason = requireNonBlank(reason, "reason");
    }

    static BundleContributionRemovalReceipt removed(String reason) {
        return new BundleContributionRemovalReceipt("REMOVED", reason);
    }

    static BundleContributionRemovalReceipt blocked(String reason) {
        return new BundleContributionRemovalReceipt("BLOCKED", reason);
    }

    boolean removed() {
        return status.equals("REMOVED");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
