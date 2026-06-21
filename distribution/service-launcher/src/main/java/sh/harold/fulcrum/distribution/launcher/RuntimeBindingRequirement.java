package sh.harold.fulcrum.distribution.launcher;

import java.util.Optional;
import java.util.Objects;
import java.util.function.Predicate;

record RuntimeBindingRequirement(
        String name,
        String description,
        Predicate<RuntimeEnvironment> condition,
        boolean externalStoreBinding) {
    RuntimeBindingRequirement(String name, String description) {
        this(name, description, environment -> environment.contains(name), false);
    }

    RuntimeBindingRequirement {
        name = requireNonBlank(name, "name");
        description = requireNonBlank(description, "description");
        condition = Objects.requireNonNull(condition, "condition");
    }

    RuntimeBindingRequirement(String name, String description, Predicate<RuntimeEnvironment> condition) {
        this(name, description, condition, false);
    }

    static RuntimeBindingRequirement externalStore(String name, String description) {
        return new RuntimeBindingRequirement(name, description, environment -> environment.contains(name), true);
    }

    boolean requiredFor(Optional<SingleMachineTier> storageTier) {
        return storageTier.isEmpty()
                || storageTier.orElseThrow() != SingleMachineTier.IN_MEMORY
                || !externalStoreBinding;
    }

    boolean satisfiedBy(RuntimeEnvironment environment) {
        return condition.test(environment);
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
