package sh.harold.fulcrum.host.velocity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VelocityPluginManifestTest {
    @Test
    void generatedPluginManifestDeclaresVelocityEntrypoint() throws IOException {
        try (var stream = VelocityPluginManifestTest.class.getClassLoader().getResourceAsStream("velocity-plugin.json")) {
            if (stream == null) {
                throw new IOException("Missing velocity-plugin.json");
            }
            String pluginJson = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(pluginJson.contains("\"id\":\"fulcrum-velocity-agent\""));
            assertTrue(pluginJson.contains("\"name\":\"FulcrumVelocityAgent\""));
            assertTrue(pluginJson.contains("\"main\":\"sh.harold.fulcrum.host.velocity.FulcrumVelocityPlugin\""));
            assertTrue(pluginJson.contains("\"version\":\"0.1.0-SNAPSHOT\""));
        }
    }

    @Test
    void runtimeConfigurationRequiresLauncherBindings() {
        VelocityPluginRuntimeConfiguration configuration = VelocityPluginRuntimeConfiguration.fromEnvironment(Map.of(
                "FULCRUM_VELOCITY_SERVER_ROOT", "build/velocity",
                "FULCRUM_VELOCITY_ROUTE_BRIDGE_URL", "http://127.0.0.1:18081/routes",
                "FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL", "http://127.0.0.1:18082/login-gate",
                "FULCRUM_VELOCITY_ROUTE_COMMAND_TOPIC", "host.velocity.routes",
                "FULCRUM_ROUTE_COMMAND_TOPIC", "cmd.route",
                "FULCRUM_LOGIN_GATE_SCOPE", "experience-lobby",
                "FULCRUM_INSTANCE_ID", "instance-velocity-plugin",
                "FULCRUM_POOL_ID", "pool-velocity",
                "FULCRUM_MACHINE_REF", "machine-velocity",
                "FULCRUM_PRINCIPAL_ID", "principal-velocity-plugin"));

        assertEquals("velocity", configuration.securityContext().identity().instanceKind());
        assertTrue(configuration.velocityServerRoot().isAbsolute());
        assertEquals("http://127.0.0.1:18081/routes", configuration.routeBridgeUrl().toString());
        assertEquals("http://127.0.0.1:18082/login-gate", configuration.loginGateBridgeUrl().toString());
        assertEquals("host.velocity.routes", configuration.proxyRouteCommandTopic());
        assertEquals("cmd.route", configuration.routeCommandTopic());
        assertEquals("experience-lobby", configuration.loginGateScope());
    }

    @Test
    void runtimeConfigurationRejectsMissingLoginGateScope() {
        assertThrows(IllegalArgumentException.class, () -> VelocityPluginRuntimeConfiguration.fromEnvironment(Map.of(
                "FULCRUM_VELOCITY_SERVER_ROOT", "build/velocity",
                "FULCRUM_VELOCITY_ROUTE_BRIDGE_URL", "http://127.0.0.1:18081/routes",
                "FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL", "http://127.0.0.1:18082/login-gate",
                "FULCRUM_VELOCITY_ROUTE_COMMAND_TOPIC", "host.velocity.routes",
                "FULCRUM_ROUTE_COMMAND_TOPIC", "cmd.route",
                "FULCRUM_INSTANCE_ID", "instance-velocity-plugin",
                "FULCRUM_POOL_ID", "pool-velocity",
                "FULCRUM_MACHINE_REF", "machine-velocity",
                "FULCRUM_PRINCIPAL_ID", "principal-velocity-plugin")));
    }
}
