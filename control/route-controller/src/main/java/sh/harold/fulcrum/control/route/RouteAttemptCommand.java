package sh.harold.fulcrum.control.route;

import sh.harold.fulcrum.api.contract.CommandPayload;

public sealed interface RouteAttemptCommand extends CommandPayload permits
        RequestRouteAttempt,
        IssueProxyRoute,
        PrepareHostRoute,
        ObserveHostAttach,
        AcknowledgeRouteAttempt,
        TimeoutRouteAttempt,
        RetryRouteAttempt {
    RouteAttemptId routeAttemptId();
}
