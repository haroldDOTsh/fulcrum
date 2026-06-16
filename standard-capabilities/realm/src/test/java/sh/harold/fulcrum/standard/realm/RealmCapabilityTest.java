package sh.harold.fulcrum.standard.realm;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.contracts.RealmContracts;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RealmCapabilityTest {
    @Test
    void realmDescriptorDeclaresSnapshotMetadataAuthorityAndProjection() {
        CapabilityValidationResult result = CapabilityDependencyGraphResolver.validate(List.of(RealmCapability.descriptor()));
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(RealmCapability.descriptor()));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertEquals(List.of("realm-by-id"),
                plan.authorities().stream()
                        .filter(resource -> resource.capabilityId().equals(RealmCapability.CAPABILITY_ID))
                        .map(resource -> resource.declaration().authorityDomain())
                        .toList());
        assertEquals(List.of(RealmContracts.SNAPSHOT_METADATA_PROJECTION),
                plan.projections().stream()
                        .filter(resource -> resource.capabilityId().equals(RealmCapability.CAPABILITY_ID))
                        .map(resource -> resource.declaration().relationName())
                        .toList());
    }
}
