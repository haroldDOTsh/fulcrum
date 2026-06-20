package sh.harold.fulcrum.host.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record HostMenuSlot(
        int slot,
        String itemKey,
        String label,
        boolean enabled,
        Optional<String> actionId,
        Map<String, String> attributes,
        Optional<String> refusalReason) {
    public HostMenuSlot {
        if (slot < 0 || slot > 53) {
            throw new IllegalArgumentException("slot must be between 0 and 53");
        }
        itemKey = HostNames.requireNonBlank(itemKey, "itemKey");
        label = HostNames.requireNonBlank(label, "label");
        actionId = actionId == null ? Optional.empty() : actionId.map(value ->
                HostNames.requireNonBlank(value, "actionId"));
        attributes = checkedAttributes(attributes);
        refusalReason = refusalReason == null
                ? Optional.empty()
                : refusalReason.map(value -> HostNames.requireNonBlank(value, "refusalReason"));
    }

    private static Map<String, String> checkedAttributes(Map<String, String> attributes) {
        Map<String, String> checked = new LinkedHashMap<>();
        Objects.requireNonNull(attributes, "attributes").forEach((key, value) -> checked.put(
                HostNames.requireNonBlank(key, "attribute key"),
                HostNames.requireNonBlank(value, "attribute value")));
        return Map.copyOf(checked);
    }
}
