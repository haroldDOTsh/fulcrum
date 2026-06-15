package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Objects;

/**
 * Internal custody proof for the authority lane that owns a routed command.
 */
record AuthorityWriteCustody(
    AuthorityCommandRoute route,
    AuthorityCommandLane lane
) {
    AuthorityWriteCustody {
        route = Objects.requireNonNull(route, "route");
        lane = Objects.requireNonNull(lane, "lane");
    }

    static AuthorityWriteCustody fromCommand(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        return fromRoute(AuthorityCommandRoute.fromCommand(command));
    }

    static AuthorityWriteCustody fromRoute(AuthorityCommandRoute route) {
        Objects.requireNonNull(route, "route");
        return new AuthorityWriteCustody(
            route,
            AuthorityCommandLane.fromRoute(route, AuthorityCommandLane.DEFAULT_LANE_COUNT)
        );
    }

    String commandDomain() {
        return route.domain();
    }

    String commandTopic() {
        return route.commandTopic();
    }

    String routePartitionKey() {
        return route.partitionKey();
    }

    String ownershipPartitionKey() {
        return lane.fencingScope();
    }

    int ownershipPartition() {
        return lane.lane();
    }

    int ownershipPartitionCount() {
        return lane.laneCount();
    }
}
