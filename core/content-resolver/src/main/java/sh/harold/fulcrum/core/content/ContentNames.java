package sh.harold.fulcrum.core.content;

import sh.harold.fulcrum.core.manifest.ContractPin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class ContentNames {
    private static final Pattern DIGEST_REFERENCE = Pattern.compile("([a-z0-9][a-z0-9-]{1,31}:)?[a-f0-9]{32,128}");

    private ContentNames() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    static void requireDigestReference(String value, String label) {
        String checked = requireNonBlank(value, label);
        if (!DIGEST_REFERENCE.matcher(checked).matches()) {
            throw new IllegalArgumentException(label + " must be a stable digest reference");
        }
    }

    static Optional<String> copyOptionalString(Optional<String> value, String label) {
        return value == null
                ? Optional.empty()
                : value.map(candidate -> requireNonBlank(candidate, label));
    }

    static Set<String> copyStringSet(Set<String> values, String label) {
        Objects.requireNonNull(values, label);
        TreeSet<String> checked = new TreeSet<>();
        for (String value : values) {
            checked.add(requireNonBlank(value, label));
        }
        return Set.copyOf(checked);
    }

    static List<ContractPin> copyContractPins(List<ContractPin> values, String label) {
        Objects.requireNonNull(values, label);
        Set<String> names = new HashSet<>();
        ArrayList<ContractPin> checked = new ArrayList<>();
        for (ContractPin value : values) {
            ContractPin pin = Objects.requireNonNull(value, label + " entry");
            if (!names.add(pin.contractName().value())) {
                throw new IllegalArgumentException(label + " must not repeat contract names");
            }
            checked.add(pin);
        }
        checked.sort((left, right) -> {
            int byName = left.contractName().value().compareTo(right.contractName().value());
            return byName != 0 ? byName : left.version().compareTo(right.version());
        });
        return List.copyOf(checked);
    }
}
