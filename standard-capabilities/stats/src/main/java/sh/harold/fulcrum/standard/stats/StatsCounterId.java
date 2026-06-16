package sh.harold.fulcrum.standard.stats;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Locale;
import java.util.Objects;

public record StatsCounterId(SubjectId subjectId, String statKey) {
    public StatsCounterId {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        statKey = requireNonBlank(statKey, "statKey").toLowerCase(Locale.ROOT);
    }

    public String value() {
        return subjectId.value() + ":" + statKey;
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
