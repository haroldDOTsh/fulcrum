package sh.harold.fulcrum.distribution.launcher;

import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

record ProfileDescriptor(
        String resourcePath,
        String profileId,
        String semanticModel,
        String contractSet,
        String servicePlacement,
        String storageShape,
        Optional<SingleMachineTier> defaultTier,
        Set<SingleMachineTier> availableTiers,
        String agonesMode,
        String objectStorage
) {
    private static final Pattern STRING_FIELD = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");

    ProfileDescriptor {
        defaultTier = defaultTier == null ? Optional.empty() : defaultTier;
        availableTiers = Set.copyOf(availableTiers == null ? Set.of() : availableTiers);
        Optional<SingleMachineTier> checkedDefaultTier = defaultTier;
        Set<SingleMachineTier> checkedAvailableTiers = availableTiers;
        checkedDefaultTier.ifPresent(tier -> {
            if (!checkedAvailableTiers.contains(tier)) {
                throw new IllegalStateException("Deployment profile descriptor " + resourcePath
                        + " default tier " + tier.id() + " is not in availableTiers");
            }
        });
    }

    static ProfileDescriptor parse(String resourcePath, String json) {
        return new ProfileDescriptor(
                resourcePath,
                field(resourcePath, json, "profileId"),
                field(resourcePath, json, "semanticModel"),
                field(resourcePath, json, "contractSet"),
                field(resourcePath, json, "servicePlacement"),
                field(resourcePath, json, "storageShape"),
                optionalField(json, "defaultTier").map(SingleMachineTier::fromId),
                optionalField(json, "availableTiers").map(ProfileDescriptor::tiers).orElse(Set.of()),
                field(resourcePath, json, "agonesMode"),
                field(resourcePath, json, "objectStorage")
        );
    }

    boolean supportsTiers() {
        return !availableTiers.isEmpty();
    }

    SingleMachineTier defaultSingleMachineTier() {
        return defaultTier.orElse(SingleMachineTier.IN_MEMORY);
    }

    private static String field(String resourcePath, String json, String name) {
        var matcher = STRING_FIELD.matcher(json);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return matcher.group(2);
            }
        }
        throw new IllegalStateException("Deployment profile descriptor " + resourcePath
                + " is missing required field " + name);
    }

    private static Optional<String> optionalField(String json, String name) {
        var matcher = STRING_FIELD.matcher(json);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return Optional.of(matcher.group(2));
            }
        }
        return Optional.empty();
    }

    private static Set<SingleMachineTier> tiers(String value) {
        return Arrays.stream(value.split(","))
                .map(SingleMachineTier::fromId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
