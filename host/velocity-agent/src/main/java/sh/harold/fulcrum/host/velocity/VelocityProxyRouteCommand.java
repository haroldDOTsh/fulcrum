package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record VelocityProxyRouteCommand(
        String routeAttemptId,
        RouteId routeId,
        SubjectId subjectId,
        SessionId targetSessionId,
        InstanceId targetInstanceId,
        String traceId) {
    public VelocityProxyRouteCommand {
        routeAttemptId = requireNonBlank(routeAttemptId, "routeAttemptId");
        routeId = Objects.requireNonNull(routeId, "routeId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        targetSessionId = Objects.requireNonNull(targetSessionId, "targetSessionId");
        targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        traceId = requireNonBlank(traceId, "traceId");
    }

    public static VelocityProxyRouteCommand parse(String wireValue) {
        Objects.requireNonNull(wireValue, "wireValue");
        String[] parts = wireValue.split("\\|");
        if (parts.length == 0 || !"proxy.route".equals(parts[0])) {
            throw new IllegalArgumentException("Velocity proxy route command must start with proxy.route");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 1; index < parts.length; index++) {
            int separator = parts[index].indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed Velocity proxy route field " + parts[index]);
            }
            fields.put(parts[index].substring(0, separator), parts[index].substring(separator + 1));
        }
        return new VelocityProxyRouteCommand(
                required(fields, "routeAttemptId"),
                new RouteId(required(fields, "routeId")),
                new SubjectId(UUID.fromString(required(fields, "subjectId"))),
                new SessionId(required(fields, "sessionId")),
                new InstanceId(required(fields, "targetInstanceId")),
                required(fields, "traceId"));
    }

    public String wireValue() {
        return "proxy.route"
                + "|routeAttemptId=" + routeAttemptId
                + "|routeId=" + routeId.value()
                + "|subjectId=" + subjectId.value()
                + "|sessionId=" + targetSessionId.value()
                + "|targetInstanceId=" + targetInstanceId.value()
                + "|traceId=" + traceId;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Velocity proxy route field " + key);
        }
        return value;
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
