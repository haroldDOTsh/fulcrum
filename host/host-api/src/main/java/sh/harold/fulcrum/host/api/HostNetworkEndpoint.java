package sh.harold.fulcrum.host.api;

import java.util.Objects;

public record HostNetworkEndpoint(String host, int port) {
    public HostNetworkEndpoint {
        host = Objects.requireNonNull(host, "host").trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }
}
