package sh.harold.fulcrum.npc.poi;

import java.util.List;

/**
 * Groups NPC assignments for a POI anchor.
 */
public interface PoiDescriptor {
    List<PoiNpcAssignment> npcAssignments();
}
