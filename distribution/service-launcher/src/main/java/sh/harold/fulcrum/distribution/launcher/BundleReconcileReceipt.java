package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.time.Instant;
import java.util.Optional;

record BundleReconcileReceipt(
        String bundleId,
        String status,
        String reason,
        String digest,
        Optional<String> grantFingerprint,
        Optional<String> grantState,
        Optional<String> grantEvidence,
        Optional<String> artifactVerificationEvidence,
        Optional<String> instanceId,
        Optional<String> shapeFingerprint,
        Optional<String> manifestHash,
        Optional<String> manifestPath,
        Optional<String> launchNonce,
        Optional<String> runtimeEvidence,
        Optional<String> registrationReceiptId,
        Optional<String> registrationEvidence,
        Optional<String> contributionCachePath,
        Optional<String> contributionEvidence,
        Instant reconciledAt) {
    static BundleReconcileReceipt installed(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            AuthorityArtifactVerificationEvidence evidence,
            BundleInstanceStartReceipt startReceipt,
            BundleInstallGrantLifecycleReceipt grantLifecycle,
            Instant now) {
        if (!startReceipt.running()) {
            return new BundleReconcileReceipt(
                    bundle.id(),
                    nonRunningStatus(startReceipt),
                    startReceipt.reason(),
                    bundle.digest(),
                    Optional.of(grant.grantFingerprint()),
                    Optional.of(grantLifecycle.status()),
                    grantLifecycle.evidence(),
                    Optional.of(evidence.wireValue()),
                    startReceipt.instanceId(),
                    startReceipt.shapeFingerprint(),
                    startReceipt.manifestHash(),
                    startReceipt.manifestPath(),
                    startReceipt.launchNonce(),
                    startReceipt.runtimeEvidence(),
                    startReceipt.registrationReceiptId(),
                    startReceipt.registrationEvidence(),
                    Optional.empty(),
                    Optional.empty(),
                    now);
        }
        return new BundleReconcileReceipt(
                bundle.id(),
                "RUNNING",
                startReceipt.reason(),
                bundle.digest(),
                Optional.of(grant.grantFingerprint()),
                Optional.of(grantLifecycle.status()),
                grantLifecycle.evidence(),
                Optional.of(evidence.wireValue()),
                startReceipt.instanceId(),
                startReceipt.shapeFingerprint(),
                startReceipt.manifestHash(),
                startReceipt.manifestPath(),
                startReceipt.launchNonce(),
                startReceipt.runtimeEvidence(),
                startReceipt.registrationReceiptId(),
                startReceipt.registrationEvidence(),
                Optional.empty(),
                Optional.empty(),
                now);
    }

    static BundleReconcileReceipt contributionInstalled(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            AuthorityArtifactVerificationEvidence evidence,
            BundleContributionInstallReceipt installReceipt,
            BundleInstallGrantLifecycleReceipt grantLifecycle,
            Instant now) {
        return new BundleReconcileReceipt(
                bundle.id(),
                installReceipt.status(),
                installReceipt.reason(),
                bundle.digest(),
                Optional.of(grant.grantFingerprint()),
                Optional.of(grantLifecycle.status()),
                grantLifecycle.evidence(),
                Optional.of(evidence.wireValue()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                installReceipt.cachePath(),
                installReceipt.loadEvidence(),
                now);
    }

    static BundleReconcileReceipt denied(DeclaredBundle bundle, String reason, Instant now) {
        return new BundleReconcileReceipt(
                bundle.id(),
                "DENIED",
                reason,
                bundle.digest(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                now);
    }

    static BundleReconcileReceipt removed(String bundleId, BundleInstanceRemovalReceipt removalReceipt, Instant now) {
        return removed(
                bundleId,
                removalReceipt,
                BundleInstallGrantLifecycleReceipt.absent("grant-already-absent"),
                now);
    }

    static BundleReconcileReceipt removed(
            String bundleId,
            BundleInstanceRemovalReceipt removalReceipt,
            BundleInstallGrantLifecycleReceipt grantRevocation,
            Instant now) {
        return new BundleReconcileReceipt(
                bundleId,
                removalReceipt.removed() ? "REMOVED" : "REMOVAL_BLOCKED",
                removalReceipt.reason(),
                "none",
                grantRevocation.grantFingerprint(),
                Optional.of(grantRevocation.status()),
                grantRevocation.evidence(),
                Optional.empty(),
                removalReceipt.instanceId(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                removalReceipt.registrationReceiptId(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                now);
    }

    static BundleReconcileReceipt contributionRemoved(
            String bundleId,
            BundleContributionRemovalReceipt removalReceipt,
            BundleInstallGrantLifecycleReceipt grantRevocation,
            Instant now) {
        return new BundleReconcileReceipt(
                bundleId,
                removalReceipt.removed() ? "REMOVED" : "REMOVAL_BLOCKED",
                removalReceipt.reason(),
                "none",
                grantRevocation.grantFingerprint(),
                Optional.of(grantRevocation.status()),
                grantRevocation.evidence(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                now);
    }

    static BundleReconcileReceipt removed(
            String bundleId,
            BundleInstanceRemovalReceipt instanceRemoval,
            BundleContributionRemovalReceipt contributionRemoval,
            BundleInstallGrantLifecycleReceipt grantRevocation,
            Instant now) {
        return new BundleReconcileReceipt(
                bundleId,
                instanceRemoval.removed() && contributionRemoval.removed() && grantRevocation.revokedOrAbsent()
                        ? "REMOVED"
                        : "REMOVAL_BLOCKED",
                combinedRemovalReason(instanceRemoval, contributionRemoval),
                "none",
                grantRevocation.grantFingerprint(),
                Optional.of(grantRevocation.status()),
                grantRevocation.evidence(),
                Optional.empty(),
                instanceRemoval.instanceId(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                instanceRemoval.registrationReceiptId(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                now);
    }

    private static String combinedRemovalReason(
            BundleInstanceRemovalReceipt instanceRemoval,
            BundleContributionRemovalReceipt contributionRemoval) {
        if (contributionRemoval.reason().equals("contribution-already-absent")) {
            return instanceRemoval.reason();
        }
        if (instanceRemoval.reason().equals("instance-already-absent")) {
            return contributionRemoval.reason();
        }
        return instanceRemoval.reason() + ";" + contributionRemoval.reason();
    }

    private static String nonRunningStatus(BundleInstanceStartReceipt startReceipt) {
        if (startReceipt.rendered()) {
            return "RENDERED";
        }
        if (startReceipt.started()) {
            return "STARTED";
        }
        if (startReceipt.startFailed()) {
            return "START_FAILED";
        }
        return "DENIED";
    }

    String toJson() {
        return "{"
                + "\"bundleId\":\"" + escape(bundleId) + "\","
                + "\"status\":\"" + escape(status) + "\","
                + "\"reason\":\"" + escape(reason) + "\","
                + "\"digest\":\"" + escape(digest) + "\","
                + "\"grantFingerprint\":\"" + escape(grantFingerprint.orElse("none")) + "\","
                + "\"grantState\":\"" + escape(grantState.orElse("none")) + "\","
                + "\"grantEvidence\":\"" + escape(grantEvidence.orElse("none")) + "\","
                + "\"artifactVerificationEvidence\":\"" + escape(artifactVerificationEvidence.orElse("none")) + "\","
                + "\"instanceId\":\"" + escape(instanceId.orElse("none")) + "\","
                + "\"shapeFingerprint\":\"" + escape(shapeFingerprint.orElse("none")) + "\","
                + "\"manifestHash\":\"" + escape(manifestHash.orElse("none")) + "\","
                + "\"manifestPath\":\"" + escape(manifestPath.orElse("none")) + "\","
                + "\"launchNonce\":\"" + escape(launchNonce.orElse("none")) + "\","
                + "\"runtimeEvidence\":\"" + escape(runtimeEvidence.orElse("none")) + "\","
                + "\"registrationReceiptId\":\"" + escape(registrationReceiptId.orElse("none")) + "\","
                + "\"registrationEvidence\":\"" + escape(registrationEvidence.orElse("none")) + "\","
                + "\"contributionCachePath\":\"" + escape(contributionCachePath.orElse("none")) + "\","
                + "\"contributionEvidence\":\"" + escape(contributionEvidence.orElse("none")) + "\","
                + "\"reconciledAt\":\"" + reconciledAt + "\""
                + "}";
    }

    static BundleReconcileReceipt fromJson(String json) {
        return new BundleReconcileReceipt(
                field(json, "bundleId"),
                field(json, "status"),
                field(json, "reason"),
                field(json, "digest"),
                optionalField(json, "grantFingerprint"),
                optionalField(json, "grantState"),
                optionalField(json, "grantEvidence"),
                optionalField(json, "artifactVerificationEvidence"),
                optionalField(json, "instanceId"),
                optionalField(json, "shapeFingerprint"),
                optionalField(json, "manifestHash"),
                optionalField(json, "manifestPath"),
                optionalField(json, "launchNonce"),
                optionalField(json, "runtimeEvidence"),
                optionalField(json, "registrationReceiptId"),
                optionalField(json, "registrationEvidence"),
                optionalField(json, "contributionCachePath"),
                optionalField(json, "contributionEvidence"),
                Instant.parse(field(json, "reconciledAt")));
    }

    boolean running() {
        return status.equals("RUNNING")
                && grantFingerprint.isPresent()
                && launchNonce.isPresent()
                && runtimeEvidence.isPresent()
                && registrationReceiptId.isPresent()
                && instanceId.isPresent();
    }

    boolean staged() {
        return status.equals("STAGED")
                && grantFingerprint.isPresent()
                && artifactVerificationEvidence.isPresent()
                && contributionCachePath.isPresent()
                && contributionEvidence.isPresent();
    }

    boolean satisfied() {
        return running() || staged();
    }

    private static Optional<String> optionalField(String json, String name) {
        String value = optionalRawField(json, name);
        return value.equals("none") ? Optional.empty() : Optional.of(value);
    }

    private static String optionalRawField(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return "none";
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        if (end < 0) {
            throw new IllegalArgumentException("receipt has unterminated field: " + name);
        }
        return unescape(json.substring(valueStart, end));
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("receipt missing field: " + name);
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        if (end < 0) {
            throw new IllegalArgumentException("receipt has unterminated field: " + name);
        }
        return unescape(json.substring(valueStart, end));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
