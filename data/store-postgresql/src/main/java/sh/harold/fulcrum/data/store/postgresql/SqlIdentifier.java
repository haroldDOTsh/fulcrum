package sh.harold.fulcrum.data.store.postgresql;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

final class SqlIdentifier {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SqlIdentifier() {
    }

    static String requireQualifiedIdentifier(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        boolean valid = Arrays.stream(checked.split("\\.", -1)).allMatch(part -> IDENTIFIER.matcher(part).matches());
        if (!valid) {
            throw new IllegalArgumentException(label + " must be a simple or qualified SQL identifier");
        }
        return checked;
    }
}
