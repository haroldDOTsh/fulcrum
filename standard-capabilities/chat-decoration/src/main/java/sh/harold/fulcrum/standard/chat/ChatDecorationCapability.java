package sh.harold.fulcrum.standard.chat;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.standard.contracts.RankContracts;

import java.util.List;

public final class ChatDecorationCapability {
    public static final CapabilityId CAPABILITY_ID = new CapabilityId("chat-decoration");
    public static final CapabilityVersion VERSION = new CapabilityVersion("1.0.0");

    private ChatDecorationCapability() {
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                VERSION,
                List.of(RankContracts.CONTRACT),
                List.of(),
                List.of(),
                List.of(new ContributionDeclaration(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, CapabilityScope.NETWORK, 30)),
                List.of(CapabilityScope.NETWORK));
    }
}
