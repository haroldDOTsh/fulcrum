package sh.harold.fulcrum.adapters.agones.allocator;

import sh.harold.fulcrum.host.api.HostAllocationRequest;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AgonesAllocatorJson {
    static final String FLEET_LABEL = "agones.dev/fleet";
    static final String INSTANCE_ID_ANNOTATION = "sh.harold.fulcrum/instance-id";
    static final String INSTANCE_KIND_ANNOTATION = "sh.harold.fulcrum/instance-kind";
    static final String PRINCIPAL_ID_ANNOTATION = "sh.harold.fulcrum/principal-id";
    static final String SLOT_ID_ANNOTATION = "sh.harold.fulcrum/slot-id";
    static final String SESSION_ID_ANNOTATION = "sh.harold.fulcrum/session-id";
    static final String RESOLVED_MANIFEST_ID_ANNOTATION = "sh.harold.fulcrum/resolved-manifest-id";
    static final String TRACE_ID_ANNOTATION = "sh.harold.fulcrum/trace-id";

    private AgonesAllocatorJson() {
    }

    static String allocationRequest(String namespace, HostAllocationRequest request) {
        Objects.requireNonNull(request, "request");
        String checkedNamespace = requireNonBlank(namespace, "namespace");
        return "{"
                + "\"namespace\":\"" + escape(checkedNamespace) + "\","
                + "\"selectors\":[{"
                + "\"matchLabels\":{\"" + FLEET_LABEL + "\":\"" + escape(request.poolId().value()) + "\"},"
                + "\"gameServerState\":\"Ready\""
                + "}],"
                + "\"metadata\":{\"annotations\":{"
                + "\"" + SESSION_ID_ANNOTATION + "\":\"" + escape(request.sessionId().value()) + "\","
                + "\"" + RESOLVED_MANIFEST_ID_ANNOTATION + "\":\"" + escape(request.resolvedManifestId().value()) + "\","
                + "\"" + TRACE_ID_ANNOTATION + "\":\"" + escape(request.traceEnvelope().traceId()) + "\""
                + "}}"
                + "}";
    }

    static AgonesAllocationResponse allocationResponse(String json) {
        String responseBody = Objects.requireNonNull(json, "json");
        return new AgonesAllocationResponse(
                stringField(responseBody, "gameServerName"),
                stringField(responseBody, "nodeName"),
                stringField(responseBody, INSTANCE_ID_ANNOTATION),
                stringField(responseBody, SLOT_ID_ANNOTATION),
                stringField(responseBody, INSTANCE_KIND_ANNOTATION),
                stringField(responseBody, PRINCIPAL_ID_ANNOTATION));
    }

    static String requireNonBlank(String value, String fieldName) {
        String checked = Objects.requireNonNull(value, fieldName).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return checked;
    }

    private static String stringField(String json, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("Agones allocation response missing field: " + fieldName);
        }
        return requireNonBlank(unescape(matcher.group(1)), fieldName);
    }

    private static String escape(String value) {
        String checked = Objects.requireNonNull(value, "value");
        StringBuilder escaped = new StringBuilder(checked.length());
        for (int index = 0; index < checked.length(); index++) {
            char character = checked.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u");
                        String hex = Integer.toHexString(character);
                        for (int padding = hex.length(); padding < 4; padding++) {
                            escaped.append('0');
                        }
                        escaped.append(hex);
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String unescape(String value) {
        StringBuilder unescaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\') {
                unescaped.append(character);
                continue;
            }
            if (index + 1 >= value.length()) {
                throw new IllegalStateException("Invalid JSON escape sequence");
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case '"', '\\', '/' -> unescaped.append(escaped);
                case 'b' -> unescaped.append('\b');
                case 'f' -> unescaped.append('\f');
                case 'n' -> unescaped.append('\n');
                case 'r' -> unescaped.append('\r');
                case 't' -> unescaped.append('\t');
                case 'u' -> {
                    if (index + 4 >= value.length()) {
                        throw new IllegalStateException("Invalid JSON unicode escape sequence");
                    }
                    String hex = value.substring(index + 1, index + 5);
                    unescaped.append((char) Integer.parseInt(hex, 16));
                    index += 4;
                }
                default -> throw new IllegalStateException("Invalid JSON escape sequence");
            }
        }
        return unescaped.toString();
    }
}

record AgonesAllocationResponse(
        String gameServerName,
        String machineRef,
        String instanceId,
        String slotId,
        String instanceKind,
        String principalId) {
    AgonesAllocationResponse {
        gameServerName = AgonesAllocatorJson.requireNonBlank(gameServerName, "gameServerName");
        machineRef = AgonesAllocatorJson.requireNonBlank(machineRef, "machineRef");
        instanceId = AgonesAllocatorJson.requireNonBlank(instanceId, "instanceId");
        slotId = AgonesAllocatorJson.requireNonBlank(slotId, "slotId");
        instanceKind = AgonesAllocatorJson.requireNonBlank(instanceKind, "instanceKind");
        principalId = AgonesAllocatorJson.requireNonBlank(principalId, "principalId");
    }
}
