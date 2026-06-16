package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.SessionId;

public final class ControlLifecycleNames {
    public static final ContractName TRACE_CONTRACT = new ContractName("control.lifecycle-trace");
    public static final ContractName SESSION_CONTRACT = new ContractName("control.experience-session");
    public static final CommandName RECORD_LIFECYCLE_OBSERVATION = new CommandName("ctrl.lifecycle.record-observation");
    public static final CommandName REQUEST_EXPERIENCE_SESSION = new CommandName("ctrl.session.request");
    public static final CommandName PLACE_EXPERIENCE_SESSION = new CommandName("ctrl.session.place");
    public static final CommandName ACTIVATE_EXPERIENCE_SESSION = new CommandName("ctrl.session.activate");
    public static final CommandName END_EXPERIENCE_SESSION = new CommandName("ctrl.session.end");

    private ControlLifecycleNames() {
    }

    public static AggregateId traceAggregateId(LifecycleTraceId traceId) {
        return new AggregateId("lifecycle-trace:" + traceId.value());
    }

    public static AggregateId sessionAggregateId(SessionId sessionId) {
        return new AggregateId("experience-session:" + sessionId.value());
    }

    public static String traceStateKey(LifecycleTraceId traceId) {
        return "ctrl.state.lifecycle-trace:" + traceId.value();
    }

    public static String sessionStateKey(SessionId sessionId) {
        return "ctrl.state.experience-session:" + sessionId.value();
    }
}
