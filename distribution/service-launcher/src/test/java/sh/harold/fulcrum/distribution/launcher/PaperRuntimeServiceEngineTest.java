package sh.harold.fulcrum.distribution.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ResolvedManifest;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationTypes;
import sh.harold.fulcrum.host.api.HostObservationWireCodec;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.paper.AgonesGameServerHttpClient;
import sh.harold.fulcrum.host.paper.PaperAllocatedAssignmentFile;
import sh.harold.fulcrum.host.paper.PaperArtifactCache;
import sh.harold.fulcrum.host.paper.PaperGameServerAssignment;
import sh.harold.fulcrum.host.paper.PaperGameServerLifecycle;
import sh.harold.fulcrum.host.paper.PaperObservationSink;
import sh.harold.fulcrum.host.paper.PaperSessionActivationRequest;
import sh.harold.fulcrum.host.paper.PaperSessionLifecyclePort;
import sh.harold.fulcrum.host.paper.PaperSessionOpenRequest;
import sh.harold.fulcrum.host.paper.PaperWorldArchiveInstaller;
import sh.harold.fulcrum.testkit.substrate.FulcrumSubstrateStack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class PaperRuntimeServiceEngineTest {
    private static final String ARTIFACT_BUCKET = "artifact-store";
    private static final ArtifactId WORLD_ARTIFACT = new ArtifactId("artifact-lobby-world-runtime");
    private static final Instant NOW = Instant.parse("2026-06-17T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void bootsWorldReportsAgonesReadyAndActivatesAssignedSession() throws Exception {
        byte[] archive = worldArchive(Map.of(
                "level.dat", "bedrock-lobby",
                "region/r.0.0.mca", "one-bedrock-block"));
        ArtifactPin artifactPin = new ArtifactPin(WORLD_ARTIFACT, sha256(archive), "lobby-world-v1");
        LocalObjectStorageAdapter objectStorage = new LocalObjectStorageAdapter(
                tempDir.resolve("object-store"),
                ARTIFACT_BUCKET);
        objectStorage.put(artifactPin, archive);
        RecordingSessionLifecyclePort sessionPort = new RecordingSessionLifecyclePort();
        RecordingObservationSink observations = new RecordingObservationSink();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        try (SdkFixture sdk = SdkFixture.startReadyThenAllocated()) {
            PaperGameServerLifecycle lifecycle = new PaperGameServerLifecycle(
                    securityContext(),
                    new AgonesGameServerHttpClient(sdk.uri()),
                    new PaperArtifactCache(tempDir.resolve("cache"), artifactId -> objectStorage.read(
                                    ArtifactBlobLayout.objectAddress(ARTIFACT_BUCKET, artifactPin))
                            .orElseThrow(() -> new IOException("missing test artifact"))),
                    new PaperWorldArchiveInstaller(tempDir.resolve("paper").resolve("world")),
                    sessionPort,
                    observations,
                    clock);
            PaperRuntimeServiceEngine engine = new PaperRuntimeServiceEngine(
                    securityContext(),
                    lifecycle,
                    assignment(artifactPin),
                    tempDir.resolve("paper").resolve(PaperAllocatedAssignmentFile.FILE_NAME),
                    Duration.ofMillis(25),
                    clock);

            try {
                engine.start();
                awaitReady(engine);

                assertEquals(1, sessionPort.opens().size());
                assertEquals(1, sessionPort.activations().size());
                PaperSessionOpenRequest open = sessionPort.opens().getFirst();
                PaperSessionActivationRequest activation = sessionPort.activations().getFirst();
                assertEquals(new SessionId("session-lobby-runtime-allocated"), open.sessionId());
                assertEquals(new SlotId("slot-lobby-runtime-allocated"), open.slotId());
                assertEquals(new InstanceId("instance-paper-runtime"), open.ownerInstanceId());
                assertEquals("owner-token-lobby-runtime", open.ownerToken());
                assertEquals(open.sessionId(), activation.sessionId());
                assertEquals(open.leaseExpiresAt(), activation.leaseExpiresAt());
                assertEquals(1, activation.ownerEpoch());
                assertEquals(1, observations.observations().size());
                assertTrue(Files.exists(tempDir.resolve("paper").resolve("world").resolve("level.dat")));
                assertTrue(Files.exists(tempDir.resolve("paper").resolve("world").resolve("region").resolve("r.0.0.mca")));
                assertEquals(
                        new SessionId("session-lobby-runtime-allocated"),
                        PaperAllocatedAssignmentFile.read(tempDir.resolve("paper").resolve(PaperAllocatedAssignmentFile.FILE_NAME))
                                .orElseThrow()
                                .sessionId());
                assertEquals(List.of(
                                "POST /ready {}",
                                "POST /health {}",
                                "GET /gameserver ",
                                "POST /health {}",
                                "GET /gameserver "),
                        sdk.requests().subList(0, 5));
            } finally {
                engine.close();
            }

            assertTrue(sdk.requests().contains("POST /shutdown {}"));
        }
    }

    @Test
    void launcherPaperRoleBootsAssignedWorldThroughRuntimeEngine() throws Exception {
        byte[] archive = worldArchive(Map.of("level.dat", "bedrock-lobby"));
        ArtifactPin artifactPin = new ArtifactPin(WORLD_ARTIFACT, sha256(archive), "lobby-world-v1");
        LocalObjectStorageAdapter objectStorage = new LocalObjectStorageAdapter(
                tempDir.resolve("launcher-object-store"),
                ARTIFACT_BUCKET);
        objectStorage.put(artifactPin, archive);

        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start();
             SdkFixture sdk = SdkFixture.start()) {
            String bootstrapServers = plainBootstrapServers(stack.kafkaBootstrapServers());
            createPaperTopics(bootstrapServers);
            Map<String, String> values = paperRuntimeBindings(
                    sdk.uri(),
                    artifactPin,
                    bootstrapServers,
                    stack.valkeyEndpoint());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            int code = new FulcrumLauncher(RuntimeEnvironment.of(values)).run(
                    new String[]{
                            "--profile=single-machine",
                            "--role=paper-agent",
                            "--mode=run",
                            "--probe-host=127.0.0.1",
                            "--probe-port=0",
                            "--run-for=PT0.4S"
                    },
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));

            assertEquals(FulcrumLauncher.OK, code, err.toString(StandardCharsets.UTF_8));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("Fulcrum runtime started"));
            assertTrue(sdk.requests().contains("POST /ready {}"));
            assertTrue(sdk.requests().contains("POST /health {}"));
            assertTrue(sdk.requests().contains("GET /gameserver "));
            assertTrue(sdk.requests().contains("POST /shutdown {}"));
            assertTrue(Files.exists(tempDir.resolve("launcher-paper").resolve("world").resolve("level.dat")));

            List<String> sessionCommands = drainTopic(bootstrapServers, "cmd.session", 2);
            List<String> observations = drainTopic(bootstrapServers, "host.observation", 1);
            assertEquals(2, sessionCommands.size());
            assertTrue(sessionCommands.getFirst().contains("commandName=open-session"));
            assertTrue(sessionCommands.getFirst().contains("session-lobby-runtime-allocated"));
            assertTrue(sessionCommands.get(1).contains("commandName=activate-session"));
            assertTrue(sessionCommands.get(1).contains("session-lobby-runtime-allocated"));
            assertEquals(HostObservationTypes.READINESS, HostObservationWireCodec.decode(observations.getFirst()).observationType());
        }
    }

    private static void awaitReady(PaperRuntimeServiceEngine engine) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            assertNull(engine.failure());
            if (engine.ready()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Paper runtime did not report ready");
    }

    private static PaperGameServerAssignment assignment(ArtifactPin artifactPin) {
        return new PaperGameServerAssignment(
                new ExperienceId("experience-lobby-runtime"),
                new SessionId("session-lobby-runtime"),
                new SlotId("slot-lobby-runtime"),
                new ResolvedManifest(
                        new ResolvedManifestId("manifest-lobby-runtime"),
                        new ArtifactId("artifact-paper-code-runtime"),
                        List.of(artifactPin),
                        List.of(),
                        "paper-host-runtime-v1"),
                artifactPin,
                "owner-token-lobby-runtime",
                Duration.ofMinutes(5));
    }

    private Map<String, String> paperRuntimeBindings(
            URI sdkUri,
            ArtifactPin artifactPin,
            String kafkaBootstrapServers,
            String valkeyEndpoint) throws IOException {
        Map<String, String> values = new HashMap<>();
        values.put("FULCRUM_OBJECT_STORE_ROOT", tempDir.resolve("launcher-object-store").toString());
        values.put("FULCRUM_PAPER_SERVER_ROOT", tempDir.resolve("launcher-paper").toString());
        values.put("FULCRUM_PAPER_KAFKA_BOOTSTRAP_SERVERS", kafkaBootstrapServers);
        values.put("FULCRUM_PAPER_AGONES_SDK_URL", sdkUri.toString());
        values.put("FULCRUM_PAPER_OBSERVATION_BRIDGE_URL", "http://127.0.0.1:" + freePort() + "/observations");
        values.put("FULCRUM_PAPER_CAPABILITY_BRIDGE_URL", "http://127.0.0.1:" + freePort() + "/capabilities");
        values.put("FULCRUM_VALKEY_ENDPOINT", valkeyEndpoint);
        values.put("FULCRUM_PAPER_EXPERIENCE_ID", "experience-lobby-runtime");
        values.put("FULCRUM_PAPER_SESSION_ID", "session-lobby-runtime");
        values.put("FULCRUM_PAPER_SLOT_ID", "slot-lobby-runtime");
        values.put("FULCRUM_PAPER_RESOLVED_MANIFEST_ID", "manifest-lobby-runtime");
        values.put("FULCRUM_PAPER_CODE_ARTIFACT_ID", "artifact-paper-code-runtime");
        values.put("FULCRUM_PAPER_WORLD_ARTIFACT_ID", artifactPin.artifactId().value());
        values.put("FULCRUM_PAPER_WORLD_ARTIFACT_DIGEST", artifactPin.digest());
        values.put("FULCRUM_PAPER_WORLD_ARTIFACT_COMPATIBILITY", artifactPin.compatibility());
        values.put("FULCRUM_PAPER_SESSION_OWNER_TOKEN", "owner-token-lobby-runtime");
        values.put("FULCRUM_PAPER_SESSION_LEASE", "PT5M");
        values.put("FULCRUM_PAPER_HOST_RUNTIME_ABI", "paper-host-runtime-v1");
        values.put("FULCRUM_HOST_COMMAND_TOPIC", "host.paper.commands");
        values.put("FULCRUM_HOST_OBSERVATION_TOPIC", "host.observation");
        values.put("FULCRUM_MACHINE_REF", "machine-test");
        return values;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void createPaperTopics(String bootstrapServers) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            admin.createTopics(List.of(
                    new NewTopic("cmd.session", 1, (short) 1),
                    new NewTopic("host.observation", 1, (short) 1)
            )).all().get(30, TimeUnit.SECONDS);
        }
    }

    private static List<String> drainTopic(String bootstrapServers, String topic, int expectedMinimum) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, topic + "-paper-runtime-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()))) {
            consumer.subscribe(List.of(topic));
            List<String> values = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && values.size() < expectedMinimum) {
                ConsumerRecords<String, String> records = consumer.poll(java.time.Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    values.add(record.value());
                }
            }
            return values;
        }
    }

    private static String plainBootstrapServers(String bootstrapServers) {
        String[] endpoints = bootstrapServers.split(",");
        for (int index = 0; index < endpoints.length; index++) {
            int scheme = endpoints[index].indexOf("://");
            if (scheme >= 0) {
                endpoints[index] = endpoints[index].substring(scheme + 3);
            }
        }
        return String.join(",", endpoints);
    }

    private static HostSecurityContext securityContext() {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-paper-runtime"),
                        HostInstanceKinds.PAPER,
                        new PoolId("pool-paper-runtime"),
                        new MachineRef("machine-test"),
                        new PrincipalId("principal-paper-runtime")),
                "service-account:paper-agent",
                HostCredentialScope.of(
                        new HostResourceGrant(HostResourceFamily.ARTIFACT, HostAccessMode.READ, WORLD_ARTIFACT.value())));
    }

    private static byte[] worldArchive(Map<String, String> entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create test world archive", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private static final class RecordingSessionLifecyclePort implements PaperSessionLifecyclePort {
        private final List<PaperSessionOpenRequest> opens = new ArrayList<>();
        private final List<PaperSessionActivationRequest> activations = new ArrayList<>();

        @Override
        public void openSession(PaperSessionOpenRequest request) {
            opens.add(request);
        }

        @Override
        public void activateSession(PaperSessionActivationRequest request) {
            activations.add(request);
        }

        private List<PaperSessionOpenRequest> opens() {
            return List.copyOf(opens);
        }

        private List<PaperSessionActivationRequest> activations() {
            return List.copyOf(activations);
        }
    }

    private static final class RecordingObservationSink implements PaperObservationSink {
        private final List<HostObservation> observations = new ArrayList<>();

        @Override
        public void publish(HostObservation observation) {
            observations.add(observation);
        }

        private List<HostObservation> observations() {
            return List.copyOf(observations);
        }
    }

    private static final class SdkFixture implements AutoCloseable {
        private final HttpServer server;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final int readyResponsesBeforeAllocation;
        private final AtomicInteger gameServerRequests = new AtomicInteger();

        private SdkFixture(HttpServer server, int readyResponsesBeforeAllocation) {
            this.server = server;
            this.readyResponsesBeforeAllocation = readyResponsesBeforeAllocation;
        }

        static SdkFixture start() throws IOException {
            return start(0);
        }

        static SdkFixture startReadyThenAllocated() throws IOException {
            return start(1);
        }

        private static SdkFixture start(int readyResponsesBeforeAllocation) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SdkFixture fixture = new SdkFixture(server, readyResponsesBeforeAllocation);
            server.createContext("/", fixture::handle);
            server.start();
            return fixture;
        }

        java.net.URI uri() {
            return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        List<String> requests() {
            return List.copyOf(requests);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + path + " " + body);
            if (path.equals("/gameserver")) {
                boolean allocated = gameServerRequests.incrementAndGet() > readyResponsesBeforeAllocation;
                respond(exchange, 200, """
                        {
                          "objectMeta": {
                            "name": "paper-runtime-gameserver",
                            "namespace": "fulcrum",
                            "annotations": {
                              "sh.harold.fulcrum/session-id": "session-lobby-runtime-allocated",
                              "sh.harold.fulcrum/slot-id": "slot-lobby-runtime-allocated",
                              "sh.harold.fulcrum/resolved-manifest-id": "manifest-lobby-runtime"
                            }
                          },
                          "status": {
                            "state": "%s"
                          }
                        }
                        """.formatted(allocated ? "Allocated" : "Ready"));
                return;
            }
            respond(exchange, 200, "{}");
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        }
    }
}
