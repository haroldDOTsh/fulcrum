package sh.harold.fulcrum.capability.api;

public record CapabilityAuthorityDeclaration(
        String authorityDomain,
        String resourceClass,
        int partitions) {
    public CapabilityAuthorityDeclaration {
        authorityDomain = CapabilityNames.requireNonBlank(authorityDomain, "authorityDomain");
        resourceClass = CapabilityNames.requireNonBlank(resourceClass, "resourceClass");
        if (partitions <= 0) {
            throw new IllegalArgumentException("partitions must be positive");
        }
    }
}
