package sh.harold.fulcrum.standard.rank;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record EffectiveRankProjection(Map<SubjectId, EffectiveRankProjectionRow> rows) {
    public EffectiveRankProjection {
        Objects.requireNonNull(rows, "rows");
        LinkedHashMap<SubjectId, EffectiveRankProjectionRow> checked = new LinkedHashMap<>();
        rows.forEach((subjectId, row) -> {
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(row, "row");
            if (!subjectId.equals(row.subjectId())) {
                throw new IllegalArgumentException("effective rank projection row key must match row Subject");
            }
            checked.put(subjectId, row);
        });
        rows = Collections.unmodifiableMap(checked);
    }

    public static EffectiveRankProjection empty() {
        return new EffectiveRankProjection(Map.of());
    }

    public static EffectiveRankProjection rebuild(List<RankGranted> events) {
        Objects.requireNonNull(events, "events");
        LinkedHashMap<SubjectId, EffectiveRankProjectionRow> rows = new LinkedHashMap<>();
        for (RankGranted event : events) {
            EffectiveRankProjectionRow next = new EffectiveRankProjectionRow(
                    Objects.requireNonNull(event, "event").snapshot(),
                    event.revision());
            EffectiveRankProjectionRow current = rows.get(next.subjectId());
            if (current != null && next.revision().value() <= current.revision().value()) {
                throw new IllegalArgumentException("rank projection replay requires increasing revisions per Subject");
            }
            rows.put(next.subjectId(), next);
        }
        return new EffectiveRankProjection(rows);
    }

    public Optional<EffectiveRankProjectionRow> row(SubjectId subjectId) {
        return Optional.ofNullable(rows.get(Objects.requireNonNull(subjectId, "subjectId")));
    }
}
