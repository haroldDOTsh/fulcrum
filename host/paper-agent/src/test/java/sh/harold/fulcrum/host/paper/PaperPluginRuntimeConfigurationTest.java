package sh.harold.fulcrum.host.paper;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.host.api.HostInstanceKinds;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PaperPluginRuntimeConfigurationTest {
    @Test
    void parsesPaperIdentitySessionAndSpawnBindings() {
        Map<String, String> values = bindings();
        values.put("FULCRUM_PAPER_ROUTE_ID_PREFIX", "route-lobby-");
        values.put("FULCRUM_PAPER_SPAWN_WORLD", "lobby");
        values.put("FULCRUM_PAPER_SPAWN_X", "10.5");
        values.put("FULCRUM_PAPER_SPAWN_Y", "71.0");
        values.put("FULCRUM_PAPER_SPAWN_Z", "-4.5");
        values.put("FULCRUM_PAPER_SPAWN_YAW", "180.0");
        values.put("FULCRUM_PAPER_SPAWN_PITCH", "12.5");
        values.put("FULCRUM_HOST_OBSERVATION_TOPIC", "host.observation.custom");
        values.put("FULCRUM_PAPER_OBSERVATION_BRIDGE_URL", "http://127.0.0.1:18080/observations");
        values.put("FULCRUM_PAPER_CAPABILITY_BRIDGE_URL", "http://127.0.0.1:18083/capabilities");

        PaperPluginRuntimeConfiguration configuration =
                PaperPluginRuntimeConfiguration.fromEnvironment(values);

        assertEquals(HostInstanceKinds.PAPER, configuration.securityContext().identity().instanceKind());
        assertEquals("instance-paper-plugin", configuration.securityContext().identity().instanceId().value());
        assertEquals(new SessionId("session-lobby-plugin"), configuration.sessionId());
        assertEquals(
                java.nio.file.Path.of("/var/fulcrum/paper").resolve(PaperAllocatedAssignmentFile.FILE_NAME),
                configuration.allocatedAssignmentFile());
        assertEquals("route-lobby-", configuration.routeIdPrefix());
        assertEquals("lobby", configuration.spawnPoint().worldName());
        assertEquals(10, configuration.spawnPoint().bedrockBlockX());
        assertEquals(70, configuration.spawnPoint().bedrockBlockY());
        assertEquals(-5, configuration.spawnPoint().bedrockBlockZ());
        assertEquals(
                URI.create("http://127.0.0.1:18080/observations"),
                configuration.observationBridgeUrl().orElseThrow());
        assertEquals(
                URI.create("http://127.0.0.1:18083/capabilities"),
                configuration.capabilityBridgeUrl().orElseThrow());
    }

    @Test
    void failsFastWhenRequiredIdentityBindingIsMissing() {
        Map<String, String> values = bindings();
        values.remove("FULCRUM_INSTANCE_ID");

        assertThrows(IllegalArgumentException.class, () ->
                PaperPluginRuntimeConfiguration.fromEnvironment(values));
    }

    @Test
    void rejectsInvalidSpawnCoordinates() {
        Map<String, String> values = bindings();
        values.put("FULCRUM_PAPER_SPAWN_X", "not-a-number");

        assertThrows(IllegalArgumentException.class, () ->
                PaperPluginRuntimeConfiguration.fromEnvironment(values));
    }

    @Test
    void rejectsCapabilityBridgeWithoutExplicitPort() {
        Map<String, String> values = bindings();
        values.put("FULCRUM_PAPER_CAPABILITY_BRIDGE_URL", "http://localhost/capabilities");

        assertThrows(IllegalArgumentException.class, () ->
                PaperPluginRuntimeConfiguration.fromEnvironment(values));
    }

    @Test
    void acceptsExplicitAllocatedAssignmentFileBinding() {
        Map<String, String> values = bindings();
        values.put("FULCRUM_PAPER_ALLOCATION_FILE", "C:/tmp/fulcrum/allocated.properties");

        PaperPluginRuntimeConfiguration configuration =
                PaperPluginRuntimeConfiguration.fromEnvironment(values);

        assertEquals(
                java.nio.file.Path.of("C:/tmp/fulcrum/allocated.properties"),
                configuration.allocatedAssignmentFile());
    }

    private static Map<String, String> bindings() {
        Map<String, String> values = new HashMap<>();
        values.put("FULCRUM_INSTANCE_ID", "instance-paper-plugin");
        values.put("FULCRUM_POOL_ID", "pool-lobby");
        values.put("FULCRUM_MACHINE_REF", "machine-a");
        values.put("FULCRUM_PRINCIPAL_ID", "principal-paper-plugin");
        values.put("FULCRUM_PAPER_SESSION_ID", "session-lobby-plugin");
        return values;
    }
}
