package sh.harold.fulcrum.data.authority.runtime;

import java.util.Objects;

public record AuthorityOffset(
        String source,
        int partition,
        long position) {
    public AuthorityOffset {
        source = requireNonBlank(source, "source");
        if (partition < 0) {
            throw new IllegalArgumentException("partition must be non-negative");
        }
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
