package sh.harold.fulcrum.control.fault;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;

public final class ControlFaultNames {
    public static final ContractName CONTRACT = new ContractName("control.fault");
    public static final CommandName RECORD_FAULT = new CommandName("ctrl.fault.record");
    public static final CommandName RELEASE_FAULT = new CommandName("ctrl.fault.release");

    private ControlFaultNames() {
    }

    public static AggregateId aggregateId(FaultId faultId) {
        return new AggregateId("fault:" + faultId.value());
    }

    public static String stateKey(FaultId faultId) {
        return "ctrl.state.fault:" + faultId.value();
    }
}
