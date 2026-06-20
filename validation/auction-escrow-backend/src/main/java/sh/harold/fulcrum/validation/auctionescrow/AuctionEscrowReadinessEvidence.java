package sh.harold.fulcrum.validation.auctionescrow;

import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

record AuctionEscrowReadinessEvidence(
        String schema,
        String status,
        String authorityDomain,
        String resourceClass,
        String receiptId,
        long fencingEpoch,
        String registrationSignature,
        String descriptorDigest,
        String bundleDigest,
        String grantFingerprint,
        String principalId,
        String storeBindingFingerprint,
        String bootNonce,
        String appliedOffsetSource,
        int appliedOffsetPartition,
        long appliedOffsetPosition,
        long appliedThrough,
        long requiredReplayWatermark,
        long applyCount,
        String aggregateId,
        long revision,
        String runtimeStatus,
        boolean replayed,
        Instant generatedAt) {
    private static final String SCHEMA = "auction-escrow-readiness/v1";

    AuctionEscrowReadinessEvidence {
        schema = requireNonBlank(schema, "schema");
        status = requireNonBlank(status, "status");
        authorityDomain = requireNonBlank(authorityDomain, "authorityDomain");
        resourceClass = requireNonBlank(resourceClass, "resourceClass");
        receiptId = requireNonBlank(receiptId, "receiptId");
        registrationSignature = requireNonBlank(registrationSignature, "registrationSignature");
        descriptorDigest = requireNonBlank(descriptorDigest, "descriptorDigest");
        bundleDigest = requireNonBlank(bundleDigest, "bundleDigest");
        grantFingerprint = requireNonBlank(grantFingerprint, "grantFingerprint");
        principalId = requireNonBlank(principalId, "principalId");
        storeBindingFingerprint = requireNonBlank(storeBindingFingerprint, "storeBindingFingerprint");
        bootNonce = requireNonBlank(bootNonce, "bootNonce");
        appliedOffsetSource = requireNonBlank(appliedOffsetSource, "appliedOffsetSource");
        if (appliedOffsetPartition < 0) {
            throw new IllegalArgumentException("appliedOffsetPartition must be non-negative");
        }
        if (appliedOffsetPosition < 0) {
            throw new IllegalArgumentException("appliedOffsetPosition must be non-negative");
        }
        if (appliedThrough < 0) {
            throw new IllegalArgumentException("appliedThrough must be non-negative");
        }
        if (requiredReplayWatermark < 0) {
            throw new IllegalArgumentException("requiredReplayWatermark must be non-negative");
        }
        if (applyCount <= 0) {
            throw new IllegalArgumentException("applyCount must be positive");
        }
        aggregateId = requireNonBlank(aggregateId, "aggregateId");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        runtimeStatus = requireNonBlank(runtimeStatus, "runtimeStatus");
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
    }

    static AuctionEscrowReadinessEvidence from(
            AuctionEscrowBackendConfig config,
            AuthorityBackendRegistrationReceipt registrationReceipt,
            AuthorityRuntimeReceipt runtimeReceipt,
            String bootNonce,
            long applyCount,
            Instant generatedAt) {
        return from(
                config,
                registrationReceipt,
                registrationReceipt.fencingEpoch(),
                runtimeReceipt,
                bootNonce,
                applyCount,
                generatedAt);
    }

    static AuctionEscrowReadinessEvidence from(
            AuctionEscrowBackendConfig config,
            AuthorityBackendRegistrationReceipt registrationReceipt,
            long fencingEpoch,
            AuthorityRuntimeReceipt runtimeReceipt,
            String bootNonce,
            long applyCount,
            Instant generatedAt) {
        Objects.requireNonNull(config, "config");
        AuthorityBackendRegistrationReceipt receipt = Objects.requireNonNull(registrationReceipt, "registrationReceipt");
        AuthorityRuntimeReceipt applied = Objects.requireNonNull(runtimeReceipt, "runtimeReceipt");
        if (!receipt.admitted()) {
            throw new IllegalStateException("escrow readiness requires admitted registration receipt");
        }
        if (applied.status() != AuthorityDecisionStatus.ACCEPTED) {
            throw new IllegalStateException("escrow readiness requires an accepted boot probe");
        }
        if (applied.replayed()) {
            throw new IllegalStateException("escrow readiness requires a boot-current apply receipt");
        }
        long appliedThrough = applied.committedOffset().position() + 1;
        if (appliedThrough < config.replayWatermark()) {
            throw new IllegalStateException("escrow readiness replay watermark is behind required watermark");
        }
        return new AuctionEscrowReadinessEvidence(
                SCHEMA,
                "ready",
                config.authorityDomain(),
                config.resourceClass(),
                receipt.receiptId(),
                fencingEpoch,
                receipt.signature(),
                receipt.descriptorDigest(),
                receipt.bundleDigest(),
                receipt.grantFingerprint().orElseThrow(() ->
                        new IllegalStateException("escrow readiness requires receipt grant fingerprint")),
                receipt.principalId().orElseThrow(() ->
                        new IllegalStateException("escrow readiness requires receipt principal")).value(),
                config.storeBindingFingerprint(),
                bootNonce,
                applied.committedOffset().source(),
                applied.committedOffset().partition(),
                applied.committedOffset().position(),
                appliedThrough,
                config.replayWatermark(),
                applyCount,
                applied.aggregateId().value(),
                applied.revision().value(),
                applied.status().name(),
                applied.replayed(),
                generatedAt);
    }

    String document() {
        String body = canonicalFields().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(System.lineSeparator()));
        return body
                + System.lineSeparator()
                + "evidenceDigest=" + evidenceDigest()
                + System.lineSeparator();
    }

    String evidenceDigest() {
        return AuthorityBackendDescriptorDigests.sha256Hex(canonicalFields().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n")));
    }

    private Map<String, String> canonicalFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schema", schema);
        fields.put("status", status);
        fields.put("authorityDomain", authorityDomain);
        fields.put("resourceClass", resourceClass);
        fields.put("receiptId", receiptId);
        fields.put("fencingEpoch", Long.toString(fencingEpoch));
        fields.put("registrationSignature", registrationSignature);
        fields.put("descriptorDigest", descriptorDigest);
        fields.put("bundleDigest", bundleDigest);
        fields.put("grantFingerprint", grantFingerprint);
        fields.put("principalId", principalId);
        fields.put("storeBindingFingerprint", storeBindingFingerprint);
        fields.put("bootNonce", bootNonce);
        fields.put("appliedOffsetSource", appliedOffsetSource);
        fields.put("appliedOffsetPartition", Integer.toString(appliedOffsetPartition));
        fields.put("appliedOffsetPosition", Long.toString(appliedOffsetPosition));
        fields.put("appliedThrough", Long.toString(appliedThrough));
        fields.put("requiredReplayWatermark", Long.toString(requiredReplayWatermark));
        fields.put("applyCount", Long.toString(applyCount));
        fields.put("aggregateId", aggregateId);
        fields.put("revision", Long.toString(revision));
        fields.put("runtimeStatus", runtimeStatus);
        fields.put("replayed", Boolean.toString(replayed));
        fields.put("generatedAt", generatedAt.toString());
        return fields;
    }

    private static String requireNonBlank(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
