package sh.harold.fulcrum.sdk.authority;

import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.data.contract.ContractDeclaration;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostResourceGrant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;

public final class AuthorityBackendDescriptorDigests {
    private AuthorityBackendDescriptorDigests() {
    }

    public static String descriptorDigest(CapabilityDescriptor descriptor) {
        return sha256Hex(canonicalDescriptor(descriptor));
    }

    public static String grantFingerprint(HostCredentialScope scope) {
        Objects.requireNonNull(scope, "scope");
        String canonical = scope.grants().stream()
                .sorted(Comparator
                        .comparing((HostResourceGrant grant) -> grant.resourceFamily().name())
                        .thenComparing(grant -> grant.accessMode().name())
                        .thenComparing(HostResourceGrant::resourceName))
                .map(grant -> grant.resourceFamily().name() + ":" + grant.accessMode().name() + ":" + grant.resourceName())
                .collect(Collectors.joining("|"));
        return sha256Hex(canonical);
    }

    public static String sha256Hex(String value) {
        String checked = Objects.requireNonNull(value, "value");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(checked.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest unavailable", exception);
        }
    }

    static String canonicalDescriptor(CapabilityDescriptor descriptor) {
        CapabilityDescriptor checked = Objects.requireNonNull(descriptor, "descriptor");
        return "capabilityId=" + checked.capabilityId().value()
                + "\nversion=" + checked.version().value()
                + "\nrequiredContracts=" + sortedJoin(checked.requiredContracts(), ContractName::value)
                + "\ndeclaredContracts=" + sortedJoin(checked.declaredContracts(), AuthorityBackendDescriptorDigests::contractKey)
                + "\nauthorityDomains=" + sortedJoin(checked.authorityDomains(), AuthorityBackendDescriptorDigests::authorityKey)
                + "\ncontributions=" + sortedJoin(checked.contributions(), AuthorityBackendDescriptorDigests::contributionKey)
                + "\nallowedScopes=" + checked.allowedScopes().stream()
                .map(scope -> scope.value())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static String contractKey(ContractDeclaration declaration) {
        return declaration.name().value() + ":" + declaration;
    }

    private static String authorityKey(CapabilityAuthorityDeclaration declaration) {
        return declaration.authorityDomain() + ":" + declaration.resourceClass() + ":" + declaration.partitions();
    }

    private static String contributionKey(ContributionDeclaration declaration) {
        return declaration.extensionPoint().wireName() + ":" + declaration.scope().value() + ":" + declaration.order();
    }

    private static <T> String sortedJoin(Collection<T> values, java.util.function.Function<T, String> mapper) {
        return values.stream()
                .map(mapper)
                .sorted()
                .collect(Collectors.joining(","));
    }
}
