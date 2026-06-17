package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FulcrumLauncherTest {
    @TempDir
    private Path tempDir;

    @Test
    void planModeListsAllServiceAndHostEntrypoints() {
        LaunchResult result = run(RuntimeEnvironment.of(Map.of()), "--profile=single-machine");

        assertEquals(FulcrumLauncher.OK, result.code());
        assertEquals("", result.err());
        assertTrue(result.out().contains("profile=single-machine"));
        assertTrue(result.out().contains("authority-service"));
        assertTrue(result.out().contains("controller-service"));
        assertTrue(result.out().contains("worker-agent"));
        assertTrue(result.out().contains("paper-agent"));
        assertTrue(result.out().contains("velocity-agent"));
        assertTrue(result.out().contains("fulcrum --role=paper-agent --profile=single-machine --mode=run"));
    }

    @Test
    void runModeFailsFastUntilExternalBindingsExist() {
        LaunchResult result = run(RuntimeEnvironment.of(Map.of()),
                "--profile=large-production",
                "--role=authority-service",
                "--mode=run");

        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, result.code());
        assertTrue(result.out().contains("storageShape=full-log-store-topology"));
        assertTrue(result.err().contains("Cannot start Fulcrum runtime"));
        assertTrue(result.err().contains("Kafka command, event, state, and response log clients"));
        assertTrue(result.err().contains("FULCRUM_KAFKA_BOOTSTRAP_SERVERS"));
        assertTrue(result.err().contains("PostgreSQL authority record adapter"));
    }

    @Test
    void runModeStartsSupervisorAndProbeServer() throws Exception {
        int probePort = freePort();
        LaunchResult result = run(allBindings(),
                "--profile=single-machine",
                "--role=all",
                "--mode=run",
                "--probe-host=127.0.0.1",
                "--probe-port=" + probePort,
                "--run-for=PT0.25S");

        assertEquals(FulcrumLauncher.OK, result.code(), result.err());
        assertTrue(result.out().contains("Fulcrum runtime started"));
        assertTrue(result.out().contains("probe=http://127.0.0.1:" + probePort));
        assertTrue(result.out().contains("Fulcrum runtime stopped"));
        assertEquals("", result.err());
    }

    @Test
    void supervisorIdentityProbeExposesScopedCredentialsWithoutStoreSecrets() throws Exception {
        LaunchCommand command = LaunchCommand.parse(new String[]{
                "--profile=single-machine",
                "--role=velocity-agent",
                "--mode=run",
                "--probe-host=127.0.0.1",
                "--probe-port=0"
        });
        LaunchPlan plan = RuntimeEntrypointRegistry.plan(command, Thread.currentThread().getContextClassLoader());

        try (FulcrumRuntimeSupervisor supervisor = new FulcrumRuntimeSupervisor(
                plan,
                allBindings(),
                command.probeHost(),
                command.probePort())) {
            supervisor.start();

            String body = get("http://127.0.0.1:" + supervisor.probePort() + "/identity");

            assertTrue(body.contains("\"role\":\"velocity-agent\""));
            assertTrue(body.contains("\"instanceKind\":\"velocity\""));
            assertTrue(body.contains("\"principalId\":\"principal-single-machine-velocity-agent\""));
            assertTrue(body.contains("\"credentialRef\":\"service-account:velocity-agent\""));
            assertFalse(body.contains("FULCRUM_POSTGRES_PASSWORD"));
            assertFalse(body.contains("postgres-secret"));
        }
    }

    @Test
    void launcherStartsAsSeparateJavaProcessWithReadinessProbe() throws Exception {
        int probePort = freePort();
        ProcessBuilder builder = new ProcessBuilder(
                javaBinary(),
                "-cp",
                System.getProperty("java.class.path"),
                FulcrumLauncher.class.getName(),
                "--profile=single-machine",
                "--role=authority-service",
                "--mode=run",
                "--probe-host=127.0.0.1",
                "--probe-port=" + probePort,
                "--run-for=PT1S");
        builder.environment().putAll(allBindingsMap());

        Process process = builder.start();
        String readyBody = awaitHttpOk("http://127.0.0.1:" + probePort + "/ready", Duration.ofSeconds(5));
        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(readyBody.contains("\"ready\":true"));
        assertTrue(exited, "launcher process did not exit");
        assertEquals(0, process.exitValue(), err);
        assertTrue(out.contains("Fulcrum runtime started"));
        assertTrue(out.contains("Fulcrum runtime stopped"));
        assertEquals("", err);
    }

    @Test
    void profileDescriptorsLoadFromDistributionClasspath() {
        for (DeploymentProfile profile : DeploymentProfile.values()) {
            ProfileDescriptor descriptor = profile.loadDescriptor(Thread.currentThread().getContextClassLoader());

            assertEquals(profile.id(), descriptor.profileId());
            assertEquals("fulcrum-v2-substrate", descriptor.semanticModel());
            assertEquals("fulcrum-step0-contracts", descriptor.contractSet());
            assertTrue(descriptor.resourcePath().endsWith(profile.id() + ".json"));
        }
    }

    @Test
    void invalidRoleReturnsUsageError() {
        LaunchResult result = run(RuntimeEnvironment.of(Map.of()), "--role=nope");

        assertEquals(FulcrumLauncher.USAGE_ERROR, result.code());
        assertTrue(result.err().contains("Unknown launch role: nope"));
        assertTrue(result.err().contains("Usage: fulcrum"));
    }

    private LaunchResult run(RuntimeEnvironment environment, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new FulcrumLauncher(environment).run(
                args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        return new LaunchResult(
                code,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)
        );
    }

    private RuntimeEnvironment allBindings() {
        return RuntimeEnvironment.of(allBindingsMap());
    }

    private Map<String, String> allBindingsMap() {
        Map<String, String> values = new HashMap<>();
        values.put("FULCRUM_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        values.put("FULCRUM_POSTGRES_JDBC_URL", "jdbc:postgresql://localhost:5432/fulcrum");
        values.put("FULCRUM_POSTGRES_USERNAME", "fulcrum");
        values.put("FULCRUM_POSTGRES_PASSWORD", "postgres-secret");
        values.put("FULCRUM_CASSANDRA_CONTACT_POINTS", "localhost:9042");
        values.put("FULCRUM_VALKEY_ENDPOINT", "localhost:6379");
        values.put("FULCRUM_CONTROL_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        values.put("FULCRUM_AGONES_ALLOCATOR_URL", "http://localhost:8000/gameserverallocation");
        values.put("FULCRUM_CONTROL_STATE_TOPIC", "ctrl.state");
        values.put("FULCRUM_WORKER_JOB_TOPIC", "worker.jobs");
        values.put("FULCRUM_WORKER_RESULT_TOPIC", "worker.results");
        values.put("FULCRUM_OBJECT_STORE_ROOT", tempDir.resolve("object-store").toString());
        values.put("FULCRUM_PAPER_SERVER_ROOT", tempDir.resolve("paper").toString());
        values.put("FULCRUM_HOST_COMMAND_TOPIC", "host.paper.commands");
        values.put("FULCRUM_VELOCITY_SERVER_ROOT", tempDir.resolve("velocity").toString());
        values.put("FULCRUM_ROUTE_COMMAND_TOPIC", "cmd.route");
        values.put("FULCRUM_LOGIN_GATE_SCOPE", "standard.punishment");
        values.put("FULCRUM_MACHINE_REF", "machine-test");
        return values;
    }

    private static String get(String uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(2)).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private static String awaitHttpOk(String uri, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return get(uri);
            } catch (Exception exception) {
                lastFailure = exception;
                Thread.sleep(50);
            }
        }
        throw new AssertionError("Timed out waiting for " + uri, lastFailure);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private record LaunchResult(int code, String out, String err) {
    }
}
