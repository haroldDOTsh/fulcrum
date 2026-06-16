package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.standard.contracts.AuctionContracts;
import sh.harold.fulcrum.standard.contracts.EconomyContracts;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;

import java.util.List;

public final class AuctionCapability {
    public static final CapabilityId CAPABILITY_ID = new CapabilityId("auction");
    public static final CapabilityVersion VERSION = new CapabilityVersion("1.0.0");

    private AuctionCapability() {
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                VERSION,
                List.of(PlayerProfileContracts.CONTRACT, EconomyContracts.CONTRACT),
                List.of(AuctionContracts.contract()),
                List.of(new CapabilityAuthorityDeclaration("auction-by-id", "standard", 64)),
                List.of(
                        new ContributionDeclaration(CapabilityExtensionPoint.PROXY_COMMANDS, CapabilityScope.NETWORK, 90),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_COMMANDS, CapabilityScope.NETWORK, 90),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_MENUS, CapabilityScope.NETWORK, 90),
                        new ContributionDeclaration(CapabilityExtensionPoint.EXPERIENCE_UI_SURFACE, CapabilityScope.NETWORK, 90)),
                List.of(CapabilityScope.NETWORK));
    }
}
