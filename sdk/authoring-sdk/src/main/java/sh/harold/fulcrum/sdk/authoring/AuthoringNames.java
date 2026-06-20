package sh.harold.fulcrum.sdk.authoring;

import java.util.Objects;

final class AuthoringNames {
    private AuthoringNames() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    static String requireJavaPackage(String value, String label) {
        String checked = requireNonBlank(value, label);
        for (String segment : checked.split("\\.")) {
            requireJavaIdentifier(segment, label);
        }
        return checked;
    }

    static String requireJavaIdentifier(String value, String label) {
        String checked = requireNonBlank(value, label);
        if (!Character.isJavaIdentifierStart(checked.charAt(0))) {
            throw new IllegalArgumentException(label + " must be a Java identifier");
        }
        for (int index = 1; index < checked.length(); index++) {
            if (!Character.isJavaIdentifierPart(checked.charAt(index))) {
                throw new IllegalArgumentException(label + " must be a Java identifier");
            }
        }
        return checked;
    }
}
