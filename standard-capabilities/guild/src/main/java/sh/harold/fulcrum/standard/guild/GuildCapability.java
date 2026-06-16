package sh.harold.fulcrum.standard.guild;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.standard.contracts.GuildContracts;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;

import java.util.List;

public final class GuildCapability {
    public static final CapabilityId CAPABILITY_ID = new CapabilityId("guild");
    public static final CapabilityVersion VERSION = new CapabilityVersion("1.0.0");

    private GuildCapability() {
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                VERSION,
                List.of(PlayerProfileContracts.CONTRACT),
                List.of(GuildContracts.contract()),
                List.of(new CapabilityAuthorityDeclaration("guild-by-id", "standard", 64)),
                List.of(
                        new ContributionDeclaration(CapabilityExtensionPoint.PROXY_COMMANDS, CapabilityScope.NETWORK, 50),
                        new ContributionDeclaration(CapabilityExtensionPoint.PROXY_PLAYER_FANOUT, CapabilityScope.NETWORK, 50),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_TAB_LIST, CapabilityScope.NETWORK, 50),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_MENUS, CapabilityScope.NETWORK, 50)),
                List.of(CapabilityScope.NETWORK));
    }
}
