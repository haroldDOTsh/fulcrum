package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.InstanceId;

public final class ControlInstanceNames {
    public static final ContractName CONTRACT = new ContractName("control-instance-registry");
    public static final CommandName REGISTER = new CommandName("register-instance");
    public static final CommandName MARK_READY = new CommandName("mark-instance-ready");
    public static final CommandName MARK_DRAINING = new CommandName("mark-instance-draining");
    public static final CommandName MARK_OFFLINE = new CommandName("mark-instance-offline");

    private ControlInstanceNames() {
    }

    public static AggregateId aggregateId(InstanceId instanceId) {
        return new AggregateId("instance:" + instanceId.value());
    }

    public static String stateKey(InstanceId instanceId) {
        return "ctrl.state.instance:" + instanceId.value();
    }
}
