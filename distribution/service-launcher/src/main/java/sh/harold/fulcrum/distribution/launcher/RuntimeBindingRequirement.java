package sh.harold.fulcrum.distribution.launcher;

import java.util.Objects;
import java.util.function.Predicate;

record RuntimeBindingRequirement(
        String name,
        String description,
        Predicate<RuntimeEnvironment> condition) {
    RuntimeBindingRequirement(String name, String description) {
        this(name, description, environment -> environment.contains(name));
    }

    RuntimeBindingRequirement {
        name = requireNonBlank(name, "name");
        description = requireNonBlank(description, "description");
        condition = Objects.requireNonNull(condition, "condition");
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
