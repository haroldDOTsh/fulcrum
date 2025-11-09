package sh.harold.fulcrum.npc.adapter;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import sh.harold.fulcrum.npc.NpcDefinition;

import java.util.UUID;

/**
 * Represents a spawned NPC instance from the adapter.
 */
public interface NpcHandle {
    UUID instanceId();

    NpcDefinition definition();

    Location lastKnownLocation();

    Entity bukkitEntity();

    UUID adapterId();
}
