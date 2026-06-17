package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class VelocityLoginGateBridgeCodec {
    private VelocityLoginGateBridgeCodec() {
    }

    public static String encodeRequest(VelocityLoginGateRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("subjectId", request.subjectId().value().toString());
        fields.put("username", encode(request.username()));
        fields.put("loginGateScope", encode(request.loginGateScope()));
        fields.put("attemptedAt", request.attemptedAt().toString());
        return lines(fields);
    }

    public static VelocityLoginGateRequest decodeRequest(String payload) {
        Map<String, String> fields = fields(payload);
        return new VelocityLoginGateRequest(
                new SubjectId(UUID.fromString(required(fields, "subjectId"))),
                decode(required(fields, "username")),
                decode(required(fields, "loginGateScope")),
                Instant.parse(required(fields, "attemptedAt")));
    }

    public static String encodeDecision(VelocityLoginGateDecision decision) {
        Objects.requireNonNull(decision, "decision");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("subjectId", decision.subjectId().value().toString());
        fields.put("allowed", Boolean.toString(decision.allowed()));
        decision.denialReason().ifPresent(reason -> fields.put("denialReason", encode(reason)));
        return lines(fields);
    }

    public static VelocityLoginGateDecision decodeDecision(String payload) {
        Map<String, String> fields = fields(payload);
        SubjectId subjectId = new SubjectId(UUID.fromString(required(fields, "subjectId")));
        boolean allowed = Boolean.parseBoolean(required(fields, "allowed"));
        Optional<String> denialReason = Optional.ofNullable(fields.get("denialReason")).map(VelocityLoginGateBridgeCodec::decode);
        return new VelocityLoginGateDecision(subjectId, allowed, denialReason);
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed Velocity login gate bridge line: " + line);
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
            throw new IllegalArgumentException("Missing Velocity login gate bridge field " + key);
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
