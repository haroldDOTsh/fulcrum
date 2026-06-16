package sh.harold.fulcrum.data.route.contract;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.RouteId;

import java.util.Objects;

public final class RouteContracts {
    public static final ContractName CONTRACT_NAME = new ContractName("route");
    public static final String COMMAND_TOPIC = "cmd.route";

    private RouteContracts() {
    }

    public static AggregateId aggregateId(RouteId routeId) {
        Objects.requireNonNull(routeId, "routeId");
        return new AggregateId("route:" + routeId.value());
    }

    public static String cacheKey(RouteId routeId) {
        return CONTRACT_NAME.value() + ":" + aggregateId(routeId).value();
    }

    public static CommandName commandName(RouteCommand payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload instanceof OpenRoute) {
            return new CommandName("open-route");
        }
        if (payload instanceof AcknowledgeRoute) {
            return new CommandName("acknowledge-route");
        }
        return new CommandName("timeout-route");
    }
}
