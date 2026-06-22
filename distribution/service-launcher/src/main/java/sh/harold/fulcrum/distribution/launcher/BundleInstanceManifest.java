package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

record BundleInstanceManifest(
        String schema,
        String bundleId,
        String artifactRef,
        String bundleDigest,
        String backendImageRef,
        String backendImageDigest,
        String kind,
        String scope,
        String placementProfile,
        Optional<String> placementTier,
        List<String> authorityDomains,
        List<String> resourceClasses,
        String instanceId,
        String instanceKind,
        String poolId,
        String machineRef,
        String principalId,
        String credentialRef,
        String grantFingerprint,
        String artifactVerificationEvidence,
        String registrationEndpoint) {
    static final String SCHEMA = "fulcrum.bundle-instance-manifest/v1";
    static final String SINGLE_MACHINE_REGISTRATION_ENDPOINT =
            "http://controller-service:18085/authority-backends/register";
    static final String CLUSTER_REGISTRATION_ENDPOINT =
            "http://fulcrum-controller-service:18085/authority-backends/register";

    BundleInstanceManifest {
        schema = requireNonBlank(schema, "schema");
        bundleId = requireNonBlank(bundleId, "bundleId");
        artifactRef = requireNonBlank(artifactRef, "artifactRef");
        bundleDigest = requireNonBlank(bundleDigest, "bundleDigest");
        backendImageRef = requireNonBlank(backendImageRef, "backendImageRef");
        backendImageDigest = requireNonBlank(backendImageDigest, "backendImageDigest");
        kind = requireNonBlank(kind, "kind");
        scope = requireNonBlank(scope, "scope");
        placementProfile = requireNonBlank(placementProfile, "placementProfile");
        placementTier = placementTier == null
                ? Optional.empty()
                : placementTier.map(value -> requireNonBlank(value, "placementTier"));
        authorityDomains = List.copyOf(Objects.requireNonNull(authorityDomains, "authorityDomains"));
        resourceClasses = List.copyOf(Objects.requireNonNull(resourceClasses, "resourceClasses"));
        instanceId = requireNonBlank(instanceId, "instanceId");
        instanceKind = requireNonBlank(instanceKind, "instanceKind");
        poolId = requireNonBlank(poolId, "poolId");
        machineRef = requireNonBlank(machineRef, "machineRef");
        principalId = requireNonBlank(principalId, "principalId");
        credentialRef = requireNonBlank(credentialRef, "credentialRef");
        grantFingerprint = requireNonBlank(grantFingerprint, "grantFingerprint");
        artifactVerificationEvidence = requireNonBlank(artifactVerificationEvidence, "artifactVerificationEvidence");
        registrationEndpoint = requireNonBlank(registrationEndpoint, "registrationEndpoint");
    }

    static BundleInstanceManifest from(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            AuthorityArtifactVerificationEvidence artifactVerification) {
        HostSecurityContext securityContext = grant.securityContext();
        return new BundleInstanceManifest(
                SCHEMA,
                bundle.id(),
                bundle.artifactRef(),
                bundle.digest(),
                bundle.backendImageRef().orElseThrow(),
                bundle.backendImageDigest().orElseThrow(),
                bundle.kind(),
                bundle.scope(),
                bundle.placementProfile(),
                bundle.placementTier(),
                bundle.authorityDomains(),
                bundle.resourceClasses(),
                securityContext.identity().instanceId().value(),
                securityContext.identity().instanceKind(),
                securityContext.identity().poolId().value(),
                securityContext.identity().machineRef().value(),
                securityContext.identity().principalId().value(),
                securityContext.credentialRef(),
                grant.grantFingerprint(),
                artifactVerification.wireValue(),
                registrationEndpoint(bundle.placementProfile()));
    }

    String manifestHash() {
        return AuthorityBackendDescriptorDigests.sha256Hex(canonicalJson());
    }

    String canonicalJson() {
        return "{"
                + "\"schema\":\"" + escape(schema) + "\","
                + "\"bundleId\":\"" + escape(bundleId) + "\","
                + "\"artifactRef\":\"" + escape(artifactRef) + "\","
                + "\"bundleDigest\":\"" + escape(bundleDigest) + "\","
                + "\"backendImageRef\":\"" + escape(backendImageRef) + "\","
                + "\"backendImageDigest\":\"" + escape(backendImageDigest) + "\","
                + "\"kind\":\"" + escape(kind) + "\","
                + "\"scope\":\"" + escape(scope) + "\","
                + "\"placementProfile\":\"" + escape(placementProfile) + "\","
                + "\"placementTier\":\"" + escape(placementTier.orElse("none")) + "\","
                + "\"authorityDomains\":" + listJson(authorityDomains) + ","
                + "\"resourceClasses\":" + listJson(resourceClasses) + ","
                + "\"instanceId\":\"" + escape(instanceId) + "\","
                + "\"instanceKind\":\"" + escape(instanceKind) + "\","
                + "\"poolId\":\"" + escape(poolId) + "\","
                + "\"machineRef\":\"" + escape(machineRef) + "\","
                + "\"principalId\":\"" + escape(principalId) + "\","
                + "\"credentialRef\":\"" + escape(credentialRef) + "\","
                + "\"grantFingerprint\":\"" + escape(grantFingerprint) + "\","
                + "\"artifactVerificationEvidence\":\"" + escape(artifactVerificationEvidence) + "\","
                + "\"registrationEndpoint\":\"" + escape(registrationEndpoint) + "\""
                + "}";
    }

    private static String listJson(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(escape(values.get(index))).append('"');
        }
        return builder.append(']').toString();
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static String registrationEndpoint(String placementProfile) {
        return DeploymentProfile.SINGLE_MACHINE.id().equals(placementProfile)
                ? SINGLE_MACHINE_REGISTRATION_ENDPOINT
                : CLUSTER_REGISTRATION_ENDPOINT;
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
