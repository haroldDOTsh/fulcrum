package sh.harold.fulcrum.standard.party;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.standard.contracts.PartyContracts;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;

import java.util.List;

public final class PartyCapability {
    public static final CapabilityId CAPABILITY_ID = new CapabilityId("party");
    public static final CapabilityVersion VERSION = new CapabilityVersion("1.0.0");

    private PartyCapability() {
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                VERSION,
                List.of(PlayerProfileContracts.CONTRACT),
                List.of(PartyContracts.contract()),
                List.of(new CapabilityAuthorityDeclaration("party-by-party", "standard", 64)),
                List.of(
                        new ContributionDeclaration(CapabilityExtensionPoint.EXPERIENCE_QUEUE_POLICY, CapabilityScope.NETWORK, 30),
                        new ContributionDeclaration(CapabilityExtensionPoint.EXPERIENCE_ROSTER_POLICY, CapabilityScope.NETWORK, 30),
                        new ContributionDeclaration(CapabilityExtensionPoint.PROXY_COMMANDS, CapabilityScope.NETWORK, 40)),
                List.of(CapabilityScope.NETWORK));
    }
}
