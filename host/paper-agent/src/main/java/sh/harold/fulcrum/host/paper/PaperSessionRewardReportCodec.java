package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PaperSessionRewardReportCodec {
    private PaperSessionRewardReportCodec() {
    }

    public static String encode(PaperSessionRewardReport report) {
        Objects.requireNonNull(report, "report");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("instanceId", report.instanceId().value());
        fields.put("sessionId", report.sessionId().value());
        fields.put("routeId", report.routeId().value());
        fields.put("subjectId", report.subjectId().value().toString());
        fields.put("occurredAt", report.occurredAt().toString());
        encodeTrace(fields, report.traceEnvelope());
        return lines(fields);
    }

    public static PaperSessionRewardReport decode(String payload) {
        Map<String, String> fields = fields(payload);
        return new PaperSessionRewardReport(
                new InstanceId(required(fields, "instanceId")),
                new SessionId(required(fields, "sessionId")),
                new RouteId(required(fields, "routeId")),
                new SubjectId(UUID.fromString(required(fields, "subjectId"))),
                decodeTrace(fields),
                instant(fields, "occurredAt"));
    }

    private static void encodeTrace(Map<String, String> fields, TraceEnvelope trace) {
        fields.put("traceId", trace.traceId());
        fields.put("spanId", trace.spanId());
        fields.put("parentSpanId", trace.parentSpanId().orElse(""));
        fields.put("traceCreatedAt", trace.createdAt().toString());
        fields.put("originService", trace.originService());
        fields.put("originInstanceId", trace.originInstanceId().value());
    }

    private static TraceEnvelope decodeTrace(Map<String, String> fields) {
        return new TraceEnvelope(
                required(fields, "traceId"),
                required(fields, "spanId"),
                optional(fields, "parentSpanId"),
                instant(fields, "traceCreatedAt"),
                required(fields, "originService"),
                new InstanceId(required(fields, "originInstanceId")));
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed Paper reward bridge line: " + line);
            }
            fields.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return fields;
    }

    private static String lines(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        fields.forEach((key, value) -> builder.append(key).append('=').append(value == null ? "" : value).append('\n'));
        return builder.toString();
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Paper reward bridge field " + key);
        }
        return value;
    }

    private static Optional<String> optional(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Instant instant(Map<String, String> fields, String key) {
        return Instant.parse(required(fields, key));
    }
}
