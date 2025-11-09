package sh.harold.fulcrum.npc.adapter;

import org.bukkit.Location;
import sh.harold.fulcrum.npc.NpcDefinition;
import sh.harold.fulcrum.npc.poi.PoiActivatedEvent;
import sh.harold.fulcrum.npc.poi.PoiNpcAssignment;
import sh.harold.fulcrum.npc.skin.NpcSkinPayload;

import java.util.Objects;
import java.util.UUID;

/**
 * Payload provided to adapters when spawning an NPC.
 */
public record NpcSpawnRequest(UUID instanceId, NpcDefinition definition, Location location,
                              PoiActivatedEvent activationEvent, PoiNpcAssignment assignment, NpcSkinPayload skin) {
    public NpcSpawnRequest(UUID instanceId,
                           NpcDefinition definition,
                           Location location,
                           PoiActivatedEvent activationEvent,
                           PoiNpcAssignment assignment,
                           NpcSkinPayload skin) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.location = Objects.requireNonNull(location, "location").clone();
        this.activationEvent = Objects.requireNonNull(activationEvent, "activationEvent");
        this.assignment = Objects.requireNonNull(assignment, "assignment");
        this.skin = Objects.requireNonNull(skin, "skin");
    }

    @Override
    public Location location() {
        return location.clone();
    }
}
