package sh.harold.fulcrum.standard.punishment;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraph;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.capability.runtime.CapabilityContributionComposer;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PunishmentCapabilityTest {
    @Test
    void punishmentMaterializesAuthorityProjectionAndLoginGateContribution() {
        var punishment = PunishmentCapability.descriptor();

        CapabilityValidationResult graphResult = CapabilityDependencyGraphResolver.validate(List.of(punishment));
        CapabilityDependencyGraph graph = CapabilityDependencyGraphResolver.resolve(List.of(punishment));
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(graph);

        assertTrue(graphResult.valid(), () -> graphResult.errors().toString());
        assertTrue(graph.dependenciesFor(PunishmentCapability.CAPABILITY_ID).isEmpty());
        assertEquals(List.of("punishment-by-subject"),
                plan.authorities().stream()
                        .filter(resource -> resource.capabilityId().equals(PunishmentCapability.CAPABILITY_ID))
                        .map(resource -> resource.declaration().authorityDomain())
                        .toList());
        assertEquals(List.of(PunishmentContracts.ACTIVE_PROJECTION),
                plan.projections().stream()
                        .filter(resource -> resource.capabilityId().equals(PunishmentCapability.CAPABILITY_ID))
                        .map(resource -> resource.declaration().relationName())
                        .toList());
        assertEquals(List.of(PunishmentCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PROXY_LOGIN_GATE)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
    }
}
