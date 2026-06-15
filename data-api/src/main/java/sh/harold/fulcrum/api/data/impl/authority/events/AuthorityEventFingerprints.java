package sh.harold.fulcrum.api.data.impl.authority.events;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class AuthorityEventFingerprints {
    private static final Gson GSON = new Gson();

    private AuthorityEventFingerprints() {
    }

    static String inputFingerprint(AuthorityEventEnvelope event) {
        return inputFingerprint(
            event.eventId().toString(),
            event.commandId().toString(),
            event.aggregateScope(),
            event.aggregateType(),
            event.aggregateId(),
            event.revision(),
            event.eventType(),
            event.createdAt().toString(),
            event.payload(),
            event.provenance()
        );
    }

    static String inputFingerprint(
        String eventId,
        String commandId,
        String aggregateScope,
        String aggregateType,
        String aggregateId,
        long revision,
        String eventType,
        String createdAt,
        Map<String, Object> payload,
        Map<String, Object> provenance
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eventId", eventId);
        values.put("commandId", commandId);
        values.put("aggregateScope", aggregateScope);
        values.put("aggregateType", aggregateType);
        values.put("aggregateId", aggregateId);
        values.put("revision", revision);
        values.put("eventType", eventType);
        values.put("createdAt", createdAt);
        values.put("payload", payload);
        values.put("provenance", provenance);
        return sha256(GSON.toJson(canonicalValue(values)));
    }

    static String outputFingerprint(AuthorityEventDispatchResult result, String inputFingerprint) {
        if (result.outputFingerprint() != null && !result.outputFingerprint().isBlank()) {
            return result.outputFingerprint();
        }
        return sha256("projection-output:" + result.projectionVersion() + ":" + inputFingerprint);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            return Integer.toHexString(Objects.hash(value));
        }
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> {
                if (key != null) {
                    sorted.put(key.toString(), canonicalValue(child));
                }
            });
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> values = new ArrayList<>();
            for (Object child : collection) {
                values.add(canonicalValue(child));
            }
            return values;
        }
        return value;
    }
}
