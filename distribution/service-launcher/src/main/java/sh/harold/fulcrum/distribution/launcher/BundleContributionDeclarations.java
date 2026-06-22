package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class BundleContributionDeclarations {
    private BundleContributionDeclarations() {
    }

    static List<ContributionDeclaration> parseAll(List<String> values) {
        List<ContributionDeclaration> declarations = new ArrayList<>();
        for (String value : values) {
            declarations.add(parse(value));
        }
        return List.copyOf(declarations);
    }

    static ContributionDeclaration parse(String value) {
        String checked = requireNonBlank(value, "contribution");
        int firstSeparator = checked.indexOf(':');
        int lastSeparator = checked.lastIndexOf(':');
        if (firstSeparator <= 0 || lastSeparator <= firstSeparator) {
            throw new IllegalArgumentException("contribution must use extensionPoint:scope:order");
        }
        String extensionPointValue = checked.substring(0, firstSeparator);
        String scopeValue = checked.substring(firstSeparator + 1, lastSeparator);
        String orderValue = checked.substring(lastSeparator + 1);
        return new ContributionDeclaration(
                extensionPoint(extensionPointValue),
                new CapabilityScope(scopeValue),
                Integer.parseInt(orderValue));
    }

    static String wire(ContributionDeclaration declaration) {
        return declaration.extensionPoint().wireName()
                + ":"
                + declaration.scope().value()
                + ":"
                + declaration.order();
    }

    private static CapabilityExtensionPoint extensionPoint(String value) {
        return Arrays.stream(CapabilityExtensionPoint.values())
                .filter(candidate -> candidate.wireName().equals(value) || candidate.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown extension point: " + value));
    }

    private static String requireNonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
