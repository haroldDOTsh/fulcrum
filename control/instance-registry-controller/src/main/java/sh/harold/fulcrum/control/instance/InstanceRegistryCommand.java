package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.InstanceId;

public interface InstanceRegistryCommand extends CommandPayload {
    InstanceId instanceId();
}
