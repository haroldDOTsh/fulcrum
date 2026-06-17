package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record VelocityPluginRuntimeConfiguration(
        HostSecurityContext securityContext,
        Path velocityServerRoot,
        URI routeBridgeUrl,
        URI loginGateBridgeUrl,
        String proxyRouteCommandTopic,
        String routeCommandTopic,
        String loginGateScope) {
    public VelocityPluginRuntimeConfiguration {
        securityContext = Objects.requireNonNull(securityContext, "securityContext");
        velocityServerRoot = Objects.requireNonNull(velocityServerRoot, "velocityServerRoot").toAbsolutePath().normalize();
        routeBridgeUrl = requireHttpUri(routeBridgeUrl);
        loginGateBridgeUrl = requireHttpUri(loginGateBridgeUrl);
        proxyRouteCommandTopic = requireNonBlank(proxyRouteCommandTopic, "proxyRouteCommandTopic");
        routeCommandTopic = requireNonBlank(routeCommandTopic, "routeCommandTopic");
        loginGateScope = requireNonBlank(loginGateScope, "loginGateScope");
    }

    public static VelocityPluginRuntimeConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String proxyRouteCommandTopic = required(environment, "FULCRUM_VELOCITY_ROUTE_COMMAND_TOPIC");
        String routeCommandTopic = required(environment, "FULCRUM_ROUTE_COMMAND_TOPIC");
        HostInstanceIdentity identity = new HostInstanceIdentity(
                new InstanceId(required(environment, "FULCRUM_INSTANCE_ID")),
                HostInstanceKinds.VELOCITY,
                new PoolId(required(environment, "FULCRUM_POOL_ID")),
                new MachineRef(required(environment, "FULCRUM_MACHINE_REF")),
                new PrincipalId(required(environment, "FULCRUM_PRINCIPAL_ID")));
        HostSecurityContext securityContext = new HostSecurityContext(
                identity,
                optional(environment, "FULCRUM_CREDENTIAL_REF", "service-account:velocity-agent"),
                HostCredentialScope.of(
                        new HostResourceGrant(
                                HostResourceFamily.TOPIC,
                                HostAccessMode.CONSUME,
                                proxyRouteCommandTopic),
                        new HostResourceGrant(
                                HostResourceFamily.TOPIC,
                                HostAccessMode.PRODUCE,
                                routeCommandTopic)));
        return new VelocityPluginRuntimeConfiguration(
                securityContext,
                Path.of(required(environment, "FULCRUM_VELOCITY_SERVER_ROOT")),
                uri(required(environment, "FULCRUM_VELOCITY_ROUTE_BRIDGE_URL"), "FULCRUM_VELOCITY_ROUTE_BRIDGE_URL"),
                uri(required(environment, "FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL"), "FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL"),
                proxyRouteCommandTopic,
                routeCommandTopic,
                required(environment, "FULCRUM_LOGIN_GATE_SCOPE"));
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required Velocity plugin environment variable " + key);
        }
        return value;
    }

    private static String optional(Map<String, String> environment, String key, String defaultValue) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            return requireNonBlank(defaultValue, key);
        }
        return value.trim();
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static URI uri(String value, String label) {
        try {
            return requireHttpUri(new URI(requireNonBlank(value, label)));
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(label + " must be a valid URI", exception);
        }
    }

    private static URI requireHttpUri(URI uri) {
        URI checked = Objects.requireNonNull(uri, "routeBridgeUrl");
        String scheme = checked.getScheme();
        if (!"http".equals(scheme)) {
            throw new IllegalArgumentException("routeBridgeUrl must use http");
        }
        if (checked.getHost() == null || checked.getHost().isBlank()) {
            throw new IllegalArgumentException("routeBridgeUrl must include a host");
        }
        if (checked.getPort() < 1 || checked.getPort() > 65_535) {
            throw new IllegalArgumentException("routeBridgeUrl must include an explicit port between 1 and 65535");
        }
        if (checked.getPath() == null || checked.getPath().isBlank() || "/".equals(checked.getPath())) {
            throw new IllegalArgumentException("routeBridgeUrl must include a route bridge path");
        }
        return checked;
    }
}
