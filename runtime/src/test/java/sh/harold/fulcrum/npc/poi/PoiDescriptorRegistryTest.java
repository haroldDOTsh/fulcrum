package sh.harold.fulcrum.npc.poi;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.npc.samples.SampleNpcDefinitions;
import sh.harold.fulcrum.npc.samples.SamplePoiDescriptors;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoiDescriptorRegistryTest {

    @Test
    void registersAssignmentsFromDescriptor() {
        PoiDescriptorRegistry registry = new PoiDescriptorRegistry();
        registry.register(SamplePoiDescriptors.INSTANCE);

        List<PoiNpcAssignment> assignments = registry.resolve("npc.greeter");
        assertEquals(1, assignments.size());
        assertEquals(SampleNpcDefinitions.GREETER_ID, assignments.get(0).npcId());

        assertTrue(registry.resolve("missing").isEmpty());
    }
}
