package sh.harold.fulcrum.control.fault;

import sh.harold.fulcrum.api.contract.CommandPayload;

public sealed interface FaultCommand extends CommandPayload permits RecordFault, ReleaseFault {
    FaultId faultId();
}
