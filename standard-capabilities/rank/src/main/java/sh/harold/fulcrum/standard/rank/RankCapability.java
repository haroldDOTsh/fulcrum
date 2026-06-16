package sh.harold.fulcrum.standard.rank;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;
import sh.harold.fulcrum.standard.contracts.RankContracts;

import java.util.List;

public final class RankCapability {
    public static final CapabilityId CAPABILITY_ID = new CapabilityId("rank");
    public static final CapabilityVersion VERSION = new CapabilityVersion("1.0.0");

    private RankCapability() {
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                VERSION,
                List.of(PlayerProfileContracts.CONTRACT),
                List.of(RankContracts.contract()),
                List.of(new CapabilityAuthorityDeclaration("rank-by-subject", "standard", 64)),
                List.of(
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, CapabilityScope.NETWORK, 20),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_TAB_LIST, CapabilityScope.NETWORK, 20),
                        new ContributionDeclaration(CapabilityExtensionPoint.PROXY_COMMANDS, CapabilityScope.NETWORK, 20),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_COMMANDS, CapabilityScope.NETWORK, 20)),
                List.of(CapabilityScope.NETWORK));
    }
}
