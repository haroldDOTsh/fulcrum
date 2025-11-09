package sh.harold.fulcrum.npc.samples;

import org.bukkit.util.Vector;
import sh.harold.fulcrum.npc.poi.PoiDescriptor;
import sh.harold.fulcrum.npc.poi.PoiNpcAssignment;

import java.util.List;

/**
 * Demonstrates how to bind NPC definitions to POI anchors.
 */
public final class SamplePoiDescriptors implements PoiDescriptor {
    public static final SamplePoiDescriptors INSTANCE = new SamplePoiDescriptors();

    private SamplePoiDescriptors() {
    }

    @Override
    public List<PoiNpcAssignment> npcAssignments() {
        return List.of(
                PoiNpcAssignment.builder()
                        .npcId(SampleNpcDefinitions.GREETER_ID)
                        .poiAnchor("npc.greeter")
                        .relativeOffset(new Vector(0, 0, 0))
                        .yawOffset(180f)
                        .build()
        );
    }
}
