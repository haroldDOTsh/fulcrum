package sh.harold.fulcrum.control.route;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;

public final class ControlRouteNames {
    public static final ContractName CONTRACT = new ContractName("control.route-attempt");
    public static final CommandName REQUEST_ROUTE_ATTEMPT = new CommandName("ctrl.route.request-attempt");
    public static final CommandName ISSUE_PROXY_ROUTE = new CommandName("ctrl.route.issue-proxy");
    public static final CommandName PREPARE_HOST_ROUTE = new CommandName("ctrl.route.prepare-host");
    public static final CommandName OBSERVE_HOST_ATTACH = new CommandName("ctrl.route.observe-host-attach");
    public static final CommandName ACKNOWLEDGE_ROUTE_ATTEMPT = new CommandName("ctrl.route.acknowledge");
    public static final CommandName TIMEOUT_ROUTE_ATTEMPT = new CommandName("ctrl.route.timeout");
    public static final CommandName RETRY_ROUTE_ATTEMPT = new CommandName("ctrl.route.retry");

    private ControlRouteNames() {
    }

    public static AggregateId aggregateId(RouteAttemptId routeAttemptId) {
        return new AggregateId("route-attempt:" + routeAttemptId.value());
    }

    public static String stateKey(RouteAttemptId routeAttemptId) {
        return "ctrl.state.route-attempt:" + routeAttemptId.value();
    }
}
