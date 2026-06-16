package sh.harold.fulcrum.data.route;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.RouteId;

public sealed interface RouteCommand extends CommandPayload
        permits OpenRoute, AcknowledgeRoute, TimeoutRoute {
    RouteId routeId();
}
