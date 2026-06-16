package sh.harold.fulcrum.control.capability;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityScope;

public interface CapabilityEnablementCommand extends CommandPayload {
    CapabilityScope scope();

    CapabilityId capabilityId();
}
