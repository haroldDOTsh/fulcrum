package sh.harold.fulcrum.api.messagebus.messages;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;
import java.util.*;

/**
 * Broadcast by backends to declare which slot families they can host and how many concurrent instances.
 */
@MessageType(value = "slot.family.advertisement", version = 1)
public class SlotFamilyAdvertisementMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;
    private final Map<String, Integer> familyCapacities = new HashMap<>();
    private final Map<String, List<String>> familyVariants = new HashMap<>();
    private String serverId;

    public SlotFamilyAdvertisementMessage() {
        // jackson
    }

    public SlotFamilyAdvertisementMessage(String serverId, Map<String, Integer> familyCapacities) {
        this.serverId = serverId;
        setFamilyCapacities(familyCapacities);
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    @JsonProperty("familyCapacities")
    public Map<String, Integer> getFamilyCapacities() {
        return Collections.unmodifiableMap(familyCapacities);
    }

    @JsonSetter("familyCapacities")
    public void setFamilyCapacities(Map<String, Integer> capacities) {
        familyCapacities.clear();
        if (capacities != null) {
            capacities.forEach((family, cap) -> familyCapacities.put(family, Math.max(1, cap)));
        }
    }

    @JsonProperty("familyVariants")
    public Map<String, List<String>> getFamilyVariants() {
        Map<String, List<String>> snapshot = new HashMap<>();
        familyVariants.forEach((family, variants) -> snapshot.put(family, List.copyOf(variants)));
        return Collections.unmodifiableMap(snapshot);
    }

    @JsonSetter("familyVariants")
    public void setFamilyVariants(Map<String, ? extends Collection<String>> variants) {
        familyVariants.clear();
        if (variants == null) {
            return;
        }
        variants.forEach((family, values) -> {
            if (family == null || family.isBlank() || values == null) {
                return;
            }
            List<String> sanitized = new ArrayList<>();
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    sanitized.add(trimmed);
                }
            }
            if (!sanitized.isEmpty()) {
                familyVariants.put(family, List.copyOf(sanitized));
            }
        });
    }

    public void addFamilyVariants(String family, Collection<String> variants) {
        if (family == null || family.isBlank() || variants == null) {
            return;
        }
        List<String> sanitized = new ArrayList<>();
        for (String value : variants) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                sanitized.add(trimmed);
            }
        }
        if (!sanitized.isEmpty()) {
            familyVariants.put(family, List.copyOf(sanitized));
        }
    }

    @Override
    public void validate() {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalStateException("serverId is required for SlotFamilyAdvertisementMessage");
        }
        if (familyCapacities.isEmpty()) {
            throw new IllegalStateException("familyCapacities must contain at least one entry");
        }
    }
}
