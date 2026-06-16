package sh.harold.fulcrum.standard.friends;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.standard.contracts.FriendsContracts;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;

import java.util.List;

public final class FriendsCapability {
    public static final CapabilityId CAPABILITY_ID = new CapabilityId("friends");
    public static final CapabilityVersion VERSION = new CapabilityVersion("1.0.0");

    private FriendsCapability() {
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                VERSION,
                List.of(PlayerProfileContracts.CONTRACT),
                List.of(FriendsContracts.contract()),
                List.of(new CapabilityAuthorityDeclaration("friendship-by-pair", "standard", 64)),
                List.of(
                        new ContributionDeclaration(CapabilityExtensionPoint.PROXY_COMMANDS, CapabilityScope.NETWORK, 45),
                        new ContributionDeclaration(CapabilityExtensionPoint.PROXY_PLAYER_FANOUT, CapabilityScope.NETWORK, 45),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_TAB_LIST, CapabilityScope.NETWORK, 45)),
                List.of(CapabilityScope.NETWORK));
    }
}
