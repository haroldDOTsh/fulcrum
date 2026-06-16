package sh.harold.fulcrum.standard.realm;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.standard.contracts.RealmContracts;

import java.util.List;

public final class RealmCapability {
    public static final CapabilityId CAPABILITY_ID = new CapabilityId("realm");
    public static final CapabilityVersion VERSION = new CapabilityVersion("1.0.0");

    private RealmCapability() {
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                VERSION,
                List.of(),
                List.of(RealmContracts.contract()),
                List.of(new CapabilityAuthorityDeclaration("realm-by-id", "standard", 64)),
                List.of(),
                List.of(CapabilityScope.NETWORK));
    }
}
