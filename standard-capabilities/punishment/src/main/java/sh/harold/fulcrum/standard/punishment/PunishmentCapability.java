package sh.harold.fulcrum.standard.punishment;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;

import java.util.List;

public final class PunishmentCapability {
    public static final CapabilityId CAPABILITY_ID = new CapabilityId("punishment");
    public static final CapabilityVersion VERSION = new CapabilityVersion("1.0.0");

    private PunishmentCapability() {
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                VERSION,
                List.of(),
                List.of(PunishmentContracts.contract()),
                List.of(new CapabilityAuthorityDeclaration("punishment-by-subject", "standard", 64)),
                List.of(new ContributionDeclaration(CapabilityExtensionPoint.PROXY_LOGIN_GATE, CapabilityScope.NETWORK, 10)),
                List.of(CapabilityScope.NETWORK));
    }
}
