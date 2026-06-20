package sh.harold.fulcrum.capability.bundle;

import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public record ContributionBundleManifest(
        String bundleId,
        String descriptorDigest,
        String bundleDigest,
        List<String> providerClassNames,
        List<ContributionRequirement> contributions) {
    public static final String RESOURCE_PATH = "META-INF/fulcrum/bundle.properties";

    public ContributionBundleManifest {
        bundleId = BundleNames.requireNonBlank(bundleId, "bundleId");
        descriptorDigest = BundleNames.requireNonBlank(descriptorDigest, "descriptorDigest");
        bundleDigest = BundleNames.requireNonBlank(bundleDigest, "bundleDigest");
        providerClassNames = copyNonBlank(providerClassNames, "providerClassNames");
        contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
        if (providerClassNames.isEmpty()) {
            throw new IllegalArgumentException("providerClassNames must not be empty");
        }
        if (contributions.isEmpty()) {
            throw new IllegalArgumentException("contributions must not be empty");
        }
    }

    public static ContributionBundleManifest read(InputStream inputStream) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream");
        Properties properties = new Properties();
        properties.load(inputStream);
        return new ContributionBundleManifest(
                required(properties, "bundle.id"),
                required(properties, "descriptor.digest"),
                required(properties, "bundle.digest"),
                split(required(properties, "providers")),
                split(required(properties, "contributions")).stream()
                        .map(ContributionRequirement::parse)
                        .toList());
    }

    private static String required(Properties properties, String key) {
        return BundleNames.requireNonBlank(properties.getProperty(key), key);
    }

    private static List<String> split(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }

    private static List<String> copyNonBlank(List<String> values, String label) {
        return List.copyOf(Objects.requireNonNull(values, label).stream()
                .map(value -> BundleNames.requireNonBlank(value, label + " entry"))
                .toList());
    }

    public record ContributionRequirement(
            CapabilityExtensionPoint extensionPoint,
            CapabilityScope scope,
            int order) {
        public ContributionRequirement {
            extensionPoint = Objects.requireNonNull(extensionPoint, "extensionPoint");
            scope = Objects.requireNonNull(scope, "scope");
            if (order < 0) {
                throw new IllegalArgumentException("order must be non-negative");
            }
        }

        static ContributionRequirement parse(String value) {
            String checked = BundleNames.requireNonBlank(value, "contribution");
            int firstSeparator = checked.indexOf(':');
            int lastSeparator = checked.lastIndexOf(':');
            if (firstSeparator <= 0 || lastSeparator <= firstSeparator) {
                throw new IllegalArgumentException("contribution must use extensionPoint:scope:order");
            }
            String extensionPointValue = checked.substring(0, firstSeparator);
            String scopeValue = checked.substring(firstSeparator + 1, lastSeparator);
            String orderValue = checked.substring(lastSeparator + 1);
            CapabilityExtensionPoint extensionPoint = Arrays.stream(CapabilityExtensionPoint.values())
                    .filter(candidate -> candidate.wireName().equals(extensionPointValue) || candidate.name().equals(extensionPointValue))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown extension point: " + extensionPointValue));
            return new ContributionRequirement(
                    extensionPoint,
                    new CapabilityScope(scopeValue),
                    Integer.parseInt(orderValue));
        }
    }
}
