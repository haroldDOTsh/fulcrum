package sh.harold.fulcrum.host.velocity;

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

public final class VelocityRouteBridgeCodec {
    private static final String TRANSFERRED = "transferred";
    private static final String NO_TRANSFER = "no-transfer";

    private VelocityRouteBridgeCodec() {
    }

    public static String encodeRequest(VelocityRouteBridgeRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("routeCommand", request.command().wireValue());
        fields.put("backendInstanceId", request.endpoint().instanceId().value());
        fields.put("backendHost", request.endpoint().host());
        fields.put("backendPort", Integer.toString(request.endpoint().port()));
        return lines(fields);
    }

    public static VelocityRouteBridgeRequest decodeRequest(String payload) {
        Map<String, String> fields = fields(payload);
        return new VelocityRouteBridgeRequest(
                VelocityProxyRouteCommand.parse(required(fields, "routeCommand")),
                new VelocityBackendEndpoint(
                        new InstanceId(required(fields, "backendInstanceId")),
                        required(fields, "backendHost"),
                        intValue(fields, "backendPort")));
    }

    public static String encodeResponse(Optional<VelocityRouteTransfer> transfer) {
        Objects.requireNonNull(transfer, "transfer");
        Map<String, String> fields = new LinkedHashMap<>();
        if (transfer.isEmpty()) {
            fields.put("status", NO_TRANSFER);
            return lines(fields);
        }
        VelocityRouteTransfer value = transfer.orElseThrow();
        fields.put("status", TRANSFERRED);
        fields.put("routeId", value.routeId().value());
        fields.put("subjectId", value.subjectId().value().toString());
        fields.put("targetSessionId", value.targetSessionId().value());
        fields.put("targetInstanceId", value.targetInstanceId().value());
        fields.put("acknowledgedAt", value.acknowledgedAt().toString());
        return lines(fields);
    }

    public static Optional<VelocityRouteTransfer> decodeResponse(String payload) {
        Map<String, String> fields = fields(payload);
        String status = required(fields, "status");
        if (NO_TRANSFER.equals(status)) {
            return Optional.empty();
        }
        if (!TRANSFERRED.equals(status)) {
            throw new IllegalArgumentException("Unsupported Velocity route bridge status " + status);
        }
        return Optional.of(new VelocityRouteTransfer(
                new RouteId(required(fields, "routeId")),
                new SubjectId(UUID.fromString(required(fields, "subjectId"))),
                new SessionId(required(fields, "targetSessionId")),
                new InstanceId(required(fields, "targetInstanceId")),
                Instant.parse(required(fields, "acknowledgedAt"))));
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed Velocity route bridge line: " + line);
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
            throw new IllegalArgumentException("Missing Velocity route bridge field " + key);
        }
        return value;
    }

    private static int intValue(Map<String, String> fields, String key) {
        return Integer.parseInt(required(fields, key));
    }
}
