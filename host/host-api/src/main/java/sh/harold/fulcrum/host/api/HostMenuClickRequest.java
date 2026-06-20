package sh.harold.fulcrum.host.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record HostMenuClickRequest(
        String viewerId,
        String sessionId,
        String menuId,
        String actionId,
        Map<String, String> attributes,
        String correlationId,
        Instant occurredAt) {
    public HostMenuClickRequest {
        viewerId = HostNames.requireNonBlank(viewerId, "viewerId");
        sessionId = HostNames.requireNonBlank(sessionId, "sessionId");
        menuId = HostNames.requireNonBlank(menuId, "menuId");
        actionId = HostNames.requireNonBlank(actionId, "actionId");
        attributes = checkedAttributes(attributes);
        correlationId = HostNames.requireNonBlank(correlationId, "correlationId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static Map<String, String> checkedAttributes(Map<String, String> attributes) {
        Map<String, String> checked = new LinkedHashMap<>();
        Objects.requireNonNull(attributes, "attributes").forEach((key, value) -> checked.put(
                HostNames.requireNonBlank(key, "attribute key"),
                HostNames.requireNonBlank(value, "attribute value")));
        return Map.copyOf(checked);
    }
}
