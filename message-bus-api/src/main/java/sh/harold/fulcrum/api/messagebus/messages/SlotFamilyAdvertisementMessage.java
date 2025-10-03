package sh.harold.fulcrum.api.messagebus.messages;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

/**
 * Broadcast by backends to declare which slot families they can host and how many concurrent instances.
 */
@MessageType("slot.family.advertisement")
public class SlotFamilyAdvertisementMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private String serverId;
    private final Map<String, Integer> familyCapacities = new HashMap<>();

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
