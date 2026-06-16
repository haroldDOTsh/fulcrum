package sh.harold.fulcrum.control.capability;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.capability.api.CapabilityScope;

public final class ControlCapabilityNames {
    public static final ContractName CONTRACT = new ContractName("control-capability-enablement");
    public static final CommandName ENABLE = new CommandName("enable-capability");
    public static final CommandName DISABLE = new CommandName("disable-capability");

    private ControlCapabilityNames() {
    }

    public static AggregateId aggregateId(CapabilityScope scope) {
        return new AggregateId("capability-enablement:" + scope.value());
    }

    public static String stateKey(CapabilityScope scope) {
        return "ctrl.state.capability-enablement:" + scope.value();
    }
}
