package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PaperCapabilityBridgeCodec {
    private PaperCapabilityBridgeCodec() {
    }

    public static String encodeSubjectRequest(PaperSubjectCapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("subjectId", request.subjectId().value().toString());
        fields.put("username", encode(request.username()));
        return lines(fields);
    }

    public static PaperSubjectCapabilityRequest decodeSubjectRequest(String payload) {
        Map<String, String> fields = fields(payload);
        return new PaperSubjectCapabilityRequest(
                subjectId(required(fields, "subjectId")),
                decode(required(fields, "username")));
    }

    public static String encodeSubjectView(PaperSubjectCapabilityView view) {
        Objects.requireNonNull(view, "view");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("subjectId", view.subjectId().value().toString());
        fields.put("displayName", encode(view.displayName()));
        view.rankLabel().ifPresent(rankLabel -> fields.put("rankLabel", encode(rankLabel)));
        return lines(fields);
    }

    public static PaperSubjectCapabilityView decodeSubjectView(String payload) {
        Map<String, String> fields = fields(payload);
        return new PaperSubjectCapabilityView(
                subjectId(required(fields, "subjectId")),
                decode(required(fields, "displayName")),
                Optional.ofNullable(fields.get("rankLabel")).map(PaperCapabilityBridgeCodec::decode));
    }

    public static String encodeChatRequest(PaperChatDecorationRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("subjectId", request.subjectId().value().toString());
        fields.put("username", encode(request.username()));
        fields.put("message", encode(request.message()));
        return lines(fields);
    }

    public static PaperChatDecorationRequest decodeChatRequest(String payload) {
        Map<String, String> fields = fields(payload);
        return new PaperChatDecorationRequest(
                subjectId(required(fields, "subjectId")),
                decode(required(fields, "username")),
                decode(required(fields, "message")));
    }

    public static String encodeChatResponse(PaperChatDecorationResponse response) {
        Objects.requireNonNull(response, "response");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("subjectId", response.subjectId().value().toString());
        fields.put("decoratedMessage", encode(response.decoratedMessage()));
        return lines(fields);
    }

    public static PaperChatDecorationResponse decodeChatResponse(String payload) {
        Map<String, String> fields = fields(payload);
        return new PaperChatDecorationResponse(
                subjectId(required(fields, "subjectId")),
                decode(required(fields, "decoratedMessage")));
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed Paper capability bridge line: " + line);
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
            throw new IllegalArgumentException("Missing Paper capability bridge field " + key);
        }
        return value;
    }

    private static SubjectId subjectId(String value) {
        return new SubjectId(UUID.fromString(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
