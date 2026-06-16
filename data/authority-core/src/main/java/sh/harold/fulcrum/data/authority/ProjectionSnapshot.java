package sh.harold.fulcrum.data.authority;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record ProjectionSnapshot<T>(
        String projectionName,
        String aggregateId,
        Revision revision,
        T value) {
    public ProjectionSnapshot {
        projectionName = requireNonBlank(projectionName, "projectionName");
        aggregateId = requireNonBlank(aggregateId, "aggregateId");
        revision = Objects.requireNonNull(revision, "revision");
        value = Objects.requireNonNull(value, "value");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
