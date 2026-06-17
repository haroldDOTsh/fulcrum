package sh.harold.fulcrum.distribution.launcher;

import java.util.Objects;

record RuntimeBindingRequirement(String name, String description) {
    RuntimeBindingRequirement {
        name = requireNonBlank(name, "name");
        description = requireNonBlank(description, "description");
    }

    boolean satisfiedBy(RuntimeEnvironment environment) {
        return environment.contains(name);
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
