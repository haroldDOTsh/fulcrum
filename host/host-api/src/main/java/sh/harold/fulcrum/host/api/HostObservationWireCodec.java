package sh.harold.fulcrum.host.api;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class HostObservationWireCodec {
    private static final String ATTRIBUTE_PREFIX = "attribute.";

    private HostObservationWireCodec() {
    }

    public static String encode(HostObservation observation) {
        Objects.requireNonNull(observation, "observation");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("instanceId", observation.instanceId().value());
        fields.put("observationType", observation.observationType());
        fields.put("observedAt", observation.observedAt().toString());
        encodeTrace(fields, observation.traceEnvelope());
        observation.attributes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> fields.put(ATTRIBUTE_PREFIX + entry.getKey(), entry.getValue()));
        return lines(fields);
    }

    public static HostObservation decode(String payload) {
        Map<String, String> fields = fields(payload);
        Map<String, String> attributes = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key.startsWith(ATTRIBUTE_PREFIX)) {
                attributes.put(key.substring(ATTRIBUTE_PREFIX.length()), value);
            }
        });
        return new HostObservation(
                new InstanceId(required(fields, "instanceId")),
                required(fields, "observationType"),
                decodeTrace(fields),
                instant(fields, "observedAt"),
                attributes);
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
                throw new IllegalArgumentException("Malformed host observation wire line: " + line);
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
            throw new IllegalArgumentException("Missing host observation wire field " + key);
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
