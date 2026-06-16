package sh.harold.fulcrum.capability.runtime;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.data.contract.ContractDeclaration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CapabilityContributionComposerTest {
    @Test
    void composesApplicableContributionsByExtensionPointAndOrder() {
        CapabilityScope poolScope = CapabilityScope.pool(new PoolId("paper-main"));
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(
                descriptor(
                        "chat-decoration",
                        contribution(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, CapabilityScope.NETWORK, 20)),
                descriptor(
                        "rank",
                        contribution(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, poolScope, 10)),
                descriptor(
                        "punishment",
                        contribution(CapabilityExtensionPoint.PROXY_LOGIN_GATE, CapabilityScope.NETWORK, 5))));

        CapabilityContributionComposition composition = CapabilityContributionComposer.compose(plan, poolScope);

        assertEquals(List.of(new CapabilityId("rank"), new CapabilityId("chat-decoration")),
                composition.registrationsFor(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE).stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(new CapabilityId("punishment")),
                composition.registrationsFor(CapabilityExtensionPoint.PROXY_LOGIN_GATE).stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
    }

    @Test
    void compositionExcludesContributionsFromUnrelatedNarrowScopes() {
        CapabilityScope paperMain = CapabilityScope.pool(new PoolId("paper-main"));
        CapabilityScope paperEvents = CapabilityScope.pool(new PoolId("paper-events"));
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(
                descriptor(
                        "network-chat",
                        contribution(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, CapabilityScope.NETWORK, 10)),
                descriptor(
                        "main-chat",
                        contribution(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, paperMain, 20)),
                descriptor(
                        "event-chat",
                        contribution(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, paperEvents, 30))));

        CapabilityContributionComposition composition = CapabilityContributionComposer.compose(plan, paperMain);

        assertEquals(List.of(new CapabilityId("network-chat"), new CapabilityId("main-chat")),
                composition.registrationsFor(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE).stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
    }

    @Test
    void duplicateDeclaredContributionSlotsAreRejectedAcrossCapabilities() {
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(
                descriptor(
                        "chat-decoration",
                        contribution(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, CapabilityScope.NETWORK, 10)),
                descriptor(
                        "rank",
                        contribution(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, CapabilityScope.NETWORK, 10))));

        CapabilityValidationResult result = CapabilityContributionComposer.validate(plan);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "composition.contribution.slot.duplicate"));
        assertThrows(IllegalArgumentException.class, () ->
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK));
    }

    @Test
    void duplicateEffectiveOrderIsRejectedWhenNetworkAndNarrowContributionsBothApply() {
        CapabilityScope poolScope = CapabilityScope.pool(new PoolId("paper-main"));
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(
                descriptor(
                        "chat-decoration",
                        contribution(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, CapabilityScope.NETWORK, 10)),
                descriptor(
                        "rank",
                        contribution(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, poolScope, 10))));

        CapabilityValidationResult result = CapabilityContributionComposer.validate(plan, poolScope);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "composition.contribution.effective-order.duplicate"));
        assertThrows(IllegalArgumentException.class, () -> CapabilityContributionComposer.compose(plan, poolScope));
    }

    private static CapabilityDescriptor descriptor(String capabilityId, ContributionDeclaration contribution) {
        return new CapabilityDescriptor(
                new CapabilityId(capabilityId),
                new CapabilityVersion("1.0.0"),
                List.of(),
                List.<ContractDeclaration>of(),
                List.of(),
                List.of(contribution),
                List.of(CapabilityScope.NETWORK));
    }

    private static ContributionDeclaration contribution(
            CapabilityExtensionPoint extensionPoint,
            CapabilityScope scope,
            int order) {
        return new ContributionDeclaration(extensionPoint, scope, order);
    }

    private static boolean hasCode(CapabilityValidationResult result, String code) {
        return result.errors().stream().anyMatch(error -> error.code().equals(code));
    }
}
