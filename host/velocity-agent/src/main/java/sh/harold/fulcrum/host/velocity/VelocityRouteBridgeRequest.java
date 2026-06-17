package sh.harold.fulcrum.host.velocity;

import java.util.Objects;

public record VelocityRouteBridgeRequest(
        VelocityProxyRouteCommand command,
        VelocityBackendEndpoint endpoint) {
    public VelocityRouteBridgeRequest {
        command = Objects.requireNonNull(command, "command");
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }
}
