package sh.harold.fulcrum.npc.adapter;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import sh.harold.fulcrum.npc.NpcDefinition;

import java.util.Objects;
import java.util.UUID;

record CitizensNpcHandle(UUID instanceId, NpcDefinition definition, NPC npc) implements NpcHandle {
    CitizensNpcHandle(UUID instanceId, NpcDefinition definition, NPC npc) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.npc = Objects.requireNonNull(npc, "npc");
    }

    @Override
    public Location lastKnownLocation() {
        Location stored = npc.getStoredLocation();
        return stored != null ? stored.clone() : null;
    }

    @Override
    public Entity bukkitEntity() {
        return npc.getEntity();
    }

    @Override
    public UUID adapterId() {
        return npc.getUniqueId();
    }
}
