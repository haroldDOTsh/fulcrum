package sh.harold.fulcrum.standard.profile;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;

import java.util.List;

public final class PlayerProfileCapability {
    public static final CapabilityId CAPABILITY_ID = new CapabilityId("player-profile");
    public static final CapabilityVersion VERSION = new CapabilityVersion("1.0.0");

    private PlayerProfileCapability() {
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                VERSION,
                List.of(),
                List.of(PlayerProfileContracts.contract()),
                List.of(new CapabilityAuthorityDeclaration("profile-by-subject", "standard", 32)),
                List.of(),
                List.of(CapabilityScope.NETWORK));
    }
}
