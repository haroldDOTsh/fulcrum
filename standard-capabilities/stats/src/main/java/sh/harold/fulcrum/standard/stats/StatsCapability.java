package sh.harold.fulcrum.standard.stats;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;
import sh.harold.fulcrum.standard.contracts.StatsContracts;

import java.util.List;

public final class StatsCapability {
    public static final CapabilityId CAPABILITY_ID = new CapabilityId("stats");
    public static final CapabilityVersion VERSION = new CapabilityVersion("1.0.0");

    private StatsCapability() {
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                VERSION,
                List.of(PlayerProfileContracts.CONTRACT),
                List.of(StatsContracts.contract()),
                List.of(new CapabilityAuthorityDeclaration("stats-counter-by-subject", "standard", 128)),
                List.of(
                        new ContributionDeclaration(CapabilityExtensionPoint.PROXY_COMMANDS, CapabilityScope.NETWORK, 80),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_COMMANDS, CapabilityScope.NETWORK, 80),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_MENUS, CapabilityScope.NETWORK, 80),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_SCOREBOARD, CapabilityScope.NETWORK, 80)),
                List.of(CapabilityScope.NETWORK));
    }
}
