package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.List;
import java.util.Objects;

public record LifecycleTraceRecord(
        LifecycleTraceId traceId,
        List<LifecycleTraceEntry> entries) {
    public LifecycleTraceRecord {
        traceId = Objects.requireNonNull(traceId, "traceId");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        for (LifecycleTraceEntry entry : entries) {
            if (!traceId.value().equals(entry.traceEnvelope().traceId())) {
                throw new IllegalArgumentException("entry trace must match record trace");
            }
        }
    }

    public static LifecycleTraceRecord empty(LifecycleTraceId traceId) {
        return new LifecycleTraceRecord(traceId, List.of());
    }

    LifecycleTraceRecord append(RecordLifecycleObservation command) {
        if (!traceId.equals(command.traceId())) {
            throw new IllegalArgumentException("command trace must match record trace");
        }
        List<LifecycleTraceEntry> next = new java.util.ArrayList<>(entries);
        next.add(LifecycleTraceEntry.from(command, entries.size() + 1));
        return new LifecycleTraceRecord(traceId, next);
    }

    public String wireValue(Revision revision) {
        return "traceId=" + traceId.value()
                + "|entryCount=" + entries.size()
                + "|lastPhase=" + entries.stream().reduce((first, second) -> second).map(entry -> entry.phase().name()).orElse("none")
                + "|revision=" + revision.value();
    }
}
