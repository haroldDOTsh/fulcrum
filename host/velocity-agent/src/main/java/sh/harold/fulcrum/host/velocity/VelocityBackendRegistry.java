package sh.harold.fulcrum.host.velocity;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import sh.harold.fulcrum.api.kernel.SubjectId;

public final class VelocityBackendRegistry {
    private final VelocityProxyGateway gateway;
    private final Map<String, VelocityBackendEndpoint> registeredEndpoints = new ConcurrentHashMap<>();

    public VelocityBackendRegistry(VelocityProxyGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    public String ensureRegistered(VelocityBackendEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String backendName = endpoint.backendName();
        VelocityBackendEndpoint previous = registeredEndpoints.put(backendName, endpoint);
        if (!endpoint.equals(previous)) {
            gateway.registerBackend(backendName, endpoint.socketAddress());
        }
        return backendName;
    }

    public CompletionStage<Boolean> transfer(SubjectId subjectId, String backendName) {
        return gateway.transfer(subjectId, backendName);
    }
}
