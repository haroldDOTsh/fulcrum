package sh.harold.fulcrum.standard.economy;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.standard.contracts.EconomyContracts;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;

import java.util.List;

public final class EconomyCapability {
    public static final CapabilityId CAPABILITY_ID = new CapabilityId("economy");
    public static final CapabilityVersion VERSION = new CapabilityVersion("1.0.0");

    private EconomyCapability() {
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                VERSION,
                List.of(PlayerProfileContracts.CONTRACT),
                List.of(EconomyContracts.contract()),
                List.of(new CapabilityAuthorityDeclaration("economy-account-by-subject-currency", "standard", 128)),
                List.of(
                        new ContributionDeclaration(CapabilityExtensionPoint.PROXY_COMMANDS, CapabilityScope.NETWORK, 70),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_COMMANDS, CapabilityScope.NETWORK, 70),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_MENUS, CapabilityScope.NETWORK, 70),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_SCOREBOARD, CapabilityScope.NETWORK, 70)),
                List.of(CapabilityScope.NETWORK));
    }
}
