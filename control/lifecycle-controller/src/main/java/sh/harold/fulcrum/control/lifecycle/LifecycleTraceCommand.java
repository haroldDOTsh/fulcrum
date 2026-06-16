package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.CommandPayload;

public sealed interface LifecycleTraceCommand extends CommandPayload permits RecordLifecycleObservation {
    LifecycleTraceId traceId();
}
