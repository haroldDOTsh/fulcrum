package sh.harold.fulcrum.host.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ResolvedManifest;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperGameServerLifecycleTest {
    private static final ArtifactId WORLD_ARTIFACT = new ArtifactId("artifact-lobby-world");
    private static final Instant NOW = Instant.parse("2026-06-17T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void preparesVerifiedWorldBeforeReportingAgonesReady() throws IOException {
        byte[] archive = worldArchive(Map.of(
                "level.dat", "bedrock-lobby",
                "region/r.0.0.mca", "one-bedrock-block"));
        AtomicInteger reads = new AtomicInteger();
        PaperArtifactCache cache = new PaperArtifactCache(
                tempDir.resolve("cache"),
                artifactId -> {
                    reads.incrementAndGet();
                    assertEquals(WORLD_ARTIFACT, artifactId);
                    return archive;
                });
        RecordingAgonesSdk agones = new RecordingAgonesSdk("Ready");
        RecordingObservationSink observations = new RecordingObservationSink();
        PaperGameServerAssignment assignment = assignment(archive);

        PaperPreparedWorld preparedWorld = lifecycle(cache, agones, observations, new RecordingSessionLifecyclePort())
                .prepareWorldAndReportReady(assignment, trace());

        assertEquals(List.of("ready", "health"), agones.calls());
        assertEquals(1, reads.get());
        assertFalse(preparedWorld.artifact().cacheHit());
        assertEquals(2, preparedWorld.fileCount());
        assertTrue(Files.exists(preparedWorld.worldDirectory().resolve("level.dat")));
        assertTrue(Files.exists(preparedWorld.worldDirectory().resolve("region/r.0.0.mca")));
        assertEquals(1, observations.observations().size());
        HostObservation readiness = observations.observations().getFirst();
        assertEquals("host.readiness", readiness.observationType());
        assertEquals("manifest-lobby", readiness.attributes().get("resolvedManifestId"));
    }

    @Test
    void opensAndActivatesSessionOnlyAfterAgonesAllocation() {
        byte[] archive = worldArchive(Map.of("level.dat", "bedrock-lobby"));
        RecordingAgonesSdk agones = new RecordingAgonesSdk(
                "Allocated",
                allocationMetadata("session-lobby-allocated", "slot-lobby-allocated", "manifest-lobby"));
        RecordingSessionLifecyclePort sessionPort = new RecordingSessionLifecyclePort();
        PaperGameServerAssignment assignment = assignment(archive);

        PaperGameServerAssignment allocatedAssignment = lifecycle(cache(archive), agones, new RecordingObservationSink(), sessionPort)
                .activateAllocatedSession(assignment, trace());

        assertEquals(new SessionId("session-lobby-allocated"), allocatedAssignment.sessionId());
        assertEquals(new SlotId("slot-lobby-allocated"), allocatedAssignment.slotId());
        assertEquals(1, sessionPort.opens().size());
        assertEquals(1, sessionPort.activations().size());
        PaperSessionOpenRequest open = sessionPort.opens().getFirst();
        PaperSessionActivationRequest activation = sessionPort.activations().getFirst();
        assertEquals(new SessionId("session-lobby-allocated"), open.sessionId());
        assertEquals(new SlotId("slot-lobby-allocated"), open.slotId());
        assertEquals(new InstanceId("instance-paper-lobby"), open.ownerInstanceId());
        assertEquals("owner-token-lobby", open.ownerToken());
        assertEquals(new ResolvedManifestId("manifest-lobby"), open.resolvedManifestId());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), open.leaseExpiresAt());
        assertEquals(open.sessionId(), activation.sessionId());
        assertEquals(1, activation.ownerEpoch());
        assertEquals(open.leaseExpiresAt(), activation.leaseExpiresAt());
    }

    @Test
    void returnsEmptyActivationWhenAgonesIsStillReady() {
        byte[] archive = worldArchive(Map.of("level.dat", "bedrock-lobby"));
        PaperGameServerLifecycle lifecycle = lifecycle(
                cache(archive),
                new RecordingAgonesSdk("Ready"),
                new RecordingObservationSink(),
                new RecordingSessionLifecyclePort());

        assertTrue(lifecycle.activateSessionIfAllocated(assignment(archive), trace()).isEmpty());
    }

    @Test
    void rejectsAllocatedSessionWithDifferentResolvedManifest() {
        byte[] archive = worldArchive(Map.of("level.dat", "bedrock-lobby"));
        RecordingSessionLifecyclePort sessionPort = new RecordingSessionLifecyclePort();
        PaperGameServerLifecycle lifecycle = lifecycle(
                cache(archive),
                new RecordingAgonesSdk(
                        "Allocated",
                        allocationMetadata("session-lobby-allocated", "slot-lobby-allocated", "manifest-other")),
                new RecordingObservationSink(),
                sessionPort);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> lifecycle.activateAllocatedSession(assignment(archive), trace()));

        assertTrue(exception.getMessage().contains("resolvedManifestId"));
        assertTrue(sessionPort.opens().isEmpty());
        assertTrue(sessionPort.activations().isEmpty());
    }

    @Test
    void rejectsSessionActivationBeforeAgonesAllocation() {
        byte[] archive = worldArchive(Map.of("level.dat", "bedrock-lobby"));
        PaperGameServerLifecycle lifecycle = lifecycle(
                cache(archive),
                new RecordingAgonesSdk("Ready"),
                new RecordingObservationSink(),
                new RecordingSessionLifecyclePort());

        assertThrows(IllegalStateException.class, () -> lifecycle.activateAllocatedSession(assignment(archive), trace()));
    }

    @Test
    void rejectsNonPaperIdentity() {
        HostSecurityContext velocityContext = new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-velocity"),
                        HostInstanceKinds.VELOCITY,
                        new PoolId("pool-lobby"),
                        new MachineRef("machine-a"),
                        new PrincipalId("principal-velocity")),
                "service-account:velocity",
                HostCredentialScope.of());

        assertThrows(IllegalArgumentException.class, () -> new PaperGameServerLifecycle(
                velocityContext,
                new RecordingAgonesSdk("Allocated"),
                cache(worldArchive(Map.of("level.dat", "bedrock-lobby"))),
                new PaperWorldArchiveInstaller(tempDir.resolve("world")),
                new RecordingSessionLifecyclePort(),
                new RecordingObservationSink(),
                clock()));
    }

    @Test
    void worldInstallerRejectsUnsafeArchiveEntriesAndNonEmptyTargets() throws IOException {
        byte[] archive = worldArchive(Map.of("../escape.dat", "bad"));
        CachedArtifact artifact = cache(archive).pullVerified(artifactPin(archive));
        PaperWorldArchiveInstaller installer = new PaperWorldArchiveInstaller(tempDir.resolve("unsafe-world"));

        assertThrows(IllegalArgumentException.class, () -> installer.install(artifact, assignment(archive)));

        Path nonEmptyWorld = tempDir.resolve("non-empty-world");
        Files.createDirectories(nonEmptyWorld);
        Files.writeString(nonEmptyWorld.resolve("old.dat"), "old");
        PaperWorldArchiveInstaller nonEmptyInstaller = new PaperWorldArchiveInstaller(nonEmptyWorld);
        byte[] safeArchive = worldArchive(Map.of("level.dat", "bedrock-lobby"));
        CachedArtifact safeArtifact = cache(safeArchive).pullVerified(artifactPin(safeArchive));

        assertThrows(IllegalStateException.class, () -> nonEmptyInstaller.install(
                safeArtifact,
                assignment(safeArtifact.artifactPin())));
    }

    private PaperGameServerLifecycle lifecycle(
            PaperArtifactCache cache,
            RecordingAgonesSdk agones,
            RecordingObservationSink observations,
            RecordingSessionLifecyclePort sessionPort) {
        return new PaperGameServerLifecycle(
                securityContext(),
                agones,
                cache,
                new PaperWorldArchiveInstaller(tempDir.resolve("world")),
                sessionPort,
                observations,
                clock());
    }

    private PaperArtifactCache cache(byte[] archive) {
        return new PaperArtifactCache(tempDir.resolve("cache"), artifactId -> archive);
    }

    private static PaperGameServerAssignment assignment(byte[] archive) {
        return assignment(artifactPin(archive));
    }

    private static PaperGameServerAssignment assignment(ArtifactPin artifactPin) {
        return new PaperGameServerAssignment(
                new ExperienceId("experience-lobby"),
                new SessionId("session-lobby"),
                new SlotId("slot-lobby"),
                new ResolvedManifest(
                        new ResolvedManifestId("manifest-lobby"),
                        new ArtifactId("artifact-paper-code"),
                        List.of(artifactPin),
                        List.of(),
                        "paper-host-runtime-v1"),
                artifactPin,
                "owner-token-lobby",
                Duration.ofMinutes(5));
    }

    private static ArtifactPin artifactPin(byte[] archive) {
        return new ArtifactPin(WORLD_ARTIFACT, sha256(archive), "lobby-world-v1");
    }

    private static HostSecurityContext securityContext() {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-paper-lobby"),
                        HostInstanceKinds.PAPER,
                        new PoolId("pool-lobby"),
                        new MachineRef("machine-a"),
                        new PrincipalId("principal-paper")),
                "service-account:paper",
                HostCredentialScope.of(
                        new HostResourceGrant(HostResourceFamily.ARTIFACT, HostAccessMode.READ, "artifact-lobby-world")));
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-paper-lifecycle",
                "span-paper-lifecycle",
                Optional.empty(),
                NOW,
                "paper-agent-test",
                new InstanceId("instance-paper-lobby"));
    }

    private static String allocationMetadata(String sessionId, String slotId, String resolvedManifestId) {
        return """
                {
                  "objectMeta": {
                    "annotations": {
                      "sh.harold.fulcrum/session-id": "%s",
                      "sh.harold.fulcrum/slot-id": "%s",
                      "sh.harold.fulcrum/resolved-manifest-id": "%s"
                    }
                  }
                }
                """.formatted(sessionId, slotId, resolvedManifestId);
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
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

    private static final class RecordingAgonesSdk implements AgonesGameServerSdkClient {
        private final String state;
        private final String rawJson;
        private final List<String> calls = new ArrayList<>();

        private RecordingAgonesSdk(String state) {
            this(state, "{}");
        }

        private RecordingAgonesSdk(String state, String rawJson) {
            this.state = state;
            this.rawJson = rawJson;
        }

        @Override
        public void ready() {
            calls.add("ready");
        }

        @Override
        public void health() {
            calls.add("health");
        }

        @Override
        public void allocate() {
            calls.add("allocate");
        }

        @Override
        public void reserve(Duration duration) {
            calls.add("reserve:" + duration.toSeconds());
        }

        @Override
        public void shutdown() {
            calls.add("shutdown");
        }

        @Override
        public AgonesGameServerSnapshot gameServer() {
            calls.add("gameserver");
            return new AgonesGameServerSnapshot("gameserver-lobby", "fulcrum", state, rawJson);
        }

        private List<String> calls() {
            return List.copyOf(calls);
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
}
