package sh.harold.fulcrum.distribution.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationDecisionStatus;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest;
import sh.harold.fulcrum.control.capability.CapabilityEnablementControlCommand;
import sh.harold.fulcrum.control.capability.CapabilityEnablementControlRecord;
import sh.harold.fulcrum.control.capability.CapabilityEnablementDecisionStatus;
import sh.harold.fulcrum.control.capability.ControlCapabilityNames;
import sh.harold.fulcrum.control.capability.EnableCapability;
import sh.harold.fulcrum.control.fault.ControlFaultNames;
import sh.harold.fulcrum.control.fault.FaultControlCommand;
import sh.harold.fulcrum.control.fault.FaultControlRecord;
import sh.harold.fulcrum.control.fault.FaultDecisionStatus;
import sh.harold.fulcrum.control.fault.FaultId;
import sh.harold.fulcrum.control.fault.FaultTargetType;
import sh.harold.fulcrum.control.fault.RecordFault;
import sh.harold.fulcrum.control.instance.ControlInstanceNames;
import sh.harold.fulcrum.control.instance.ExperienceShape;
import sh.harold.fulcrum.control.instance.InstanceRegistryControlCommand;
import sh.harold.fulcrum.control.instance.InstanceRegistryDecisionStatus;
import sh.harold.fulcrum.control.instance.InstanceRegistryRecord;
import sh.harold.fulcrum.control.instance.InstanceRegistryStatus;
import sh.harold.fulcrum.control.instance.RegisterInstance;
import sh.harold.fulcrum.control.instance.SharedShardExperienceDescriptor;
import sh.harold.fulcrum.control.instance.SharedShardOccupancySnapshot;
import sh.harold.fulcrum.control.instance.SharedShardPlacementCandidate;
import sh.harold.fulcrum.control.instance.SharedShardPlacementDecisionStatus;
import sh.harold.fulcrum.control.instance.SharedShardPlacementRequest;
import sh.harold.fulcrum.control.instance.SharedShardPoolDescriptor;
import sh.harold.fulcrum.control.lifecycle.ControlLifecycleNames;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionControlCommand;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionControlRecord;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionDecisionStatus;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionStatus;
import sh.harold.fulcrum.control.lifecycle.LifecyclePhase;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlRecord;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceDecisionStatus;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceId;
import sh.harold.fulcrum.control.lifecycle.RecordLifecycleObservation;
import sh.harold.fulcrum.control.lifecycle.RequestExperienceSession;
import sh.harold.fulcrum.control.queue.ControlQueueNames;
import sh.harold.fulcrum.control.queue.QueueIntentId;
import sh.harold.fulcrum.control.queue.QueueRosterControlCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlRecord;
import sh.harold.fulcrum.control.queue.QueueRosterDecisionStatus;
import sh.harold.fulcrum.control.queue.SubmitQueueIntent;
import sh.harold.fulcrum.control.route.AcknowledgeRouteAttempt;
import sh.harold.fulcrum.control.route.ControlRouteNames;
import sh.harold.fulcrum.control.route.IssueProxyRoute;
import sh.harold.fulcrum.control.route.ObserveHostAttach;
import sh.harold.fulcrum.control.route.PrepareHostRoute;
import sh.harold.fulcrum.control.route.RequestRouteAttempt;
import sh.harold.fulcrum.control.route.RouteAttemptCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlRecord;
import sh.harold.fulcrum.control.route.RouteAttemptDecisionStatus;
import sh.harold.fulcrum.control.route.RouteAttemptId;
import sh.harold.fulcrum.control.route.RouteAttemptLifecycleStatus;
import sh.harold.fulcrum.data.artifact.ArtifactDigest;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataAuthority;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandDelivery;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffset;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;
import sh.harold.fulcrum.data.store.valkey.ValkeyClientHandle;
import sh.harold.fulcrum.data.presence.PresenceAuthority;
import sh.harold.fulcrum.data.route.RouteAuthority;
import sh.harold.fulcrum.data.route.contract.AcknowledgeRoute;
import sh.harold.fulcrum.data.route.contract.RouteCommand;
import sh.harold.fulcrum.data.session.SessionAuthority;
import sh.harold.fulcrum.data.subject.RegisterSubject;
import sh.harold.fulcrum.data.subject.SubjectAuthority;
import sh.harold.fulcrum.data.subject.SubjectCommand;
import sh.harold.fulcrum.data.subject.SubjectExternalIdentity;
import sh.harold.fulcrum.data.subject.SubjectIdentityProvider;
import sh.harold.fulcrum.data.subject.SubjectLifecycleStatus;
import sh.harold.fulcrum.data.subject.SubjectState;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostNetworkEndpoint;
import sh.harold.fulcrum.host.api.HostObservationFactory;
import sh.harold.fulcrum.host.api.HostObservationWireCodec;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.api.HostSessionAttachment;
import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRequest;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationStatus;
import sh.harold.fulcrum.sdk.authority.HttpAuthorityBackendRegistrationClient;
import sh.harold.fulcrum.host.paper.PaperAllocatedAssignmentFile;
import sh.harold.fulcrum.host.velocity.VelocityRouteBridgeCodec;
import sh.harold.fulcrum.host.velocity.VelocityRouteTransfer;
import sh.harold.fulcrum.host.worker.WorkerAgentRuntime;
import sh.harold.fulcrum.host.worker.WorkerJobDecisionStatus;
import sh.harold.fulcrum.host.worker.WorkerJobId;
import sh.harold.fulcrum.host.worker.WorkerJobKind;
import sh.harold.fulcrum.host.worker.WorkerJobReceipt;
import sh.harold.fulcrum.host.worker.WorkerJobRequest;
import sh.harold.fulcrum.host.worker.WorkerJobResult;
import sh.harold.fulcrum.host.worker.WorkerLagBudget;
import sh.harold.fulcrum.testkit.substrate.FulcrumSubstrateStack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FulcrumLauncherTest {
    @TempDir
    private Path tempDir;

    @Test
    void operatorHelpListsSubcommandsAndKeepsInternalEntrypointVisible() {
        LaunchResult result = run(RuntimeEnvironment.of(Map.of()), "--help");

        assertEquals(FulcrumLauncher.OK, result.code());
        assertEquals("", result.err());
        assertTrue(result.out().contains("Usage: fulcrum <up|status|down|bundle|dev|author|identity>"));
        assertTrue(result.out().contains("Internal image entrypoint default"));
    }

    @Test
    void operatorUpDryRunWritesSupervisedRunPlanForSlimTier() throws Exception {
        Path stateDir = tempDir.resolve("operator-state");

        LaunchResult result = run(RuntimeEnvironment.of(Map.of()),
                "up",
                "--tier=slim",
                "--state-dir=" + stateDir,
                "--dry-run",
                "--run-for=PT2S");

        assertEquals(FulcrumLauncher.OK, result.code(), result.err());
        assertEquals("", result.err());
        assertTrue(result.out().contains("profile=single-machine"));
        assertTrue(result.out().contains("tier=slim"));
        assertTrue(result.out().contains("deploymentUnit=supervised-process"));
        assertTrue(result.out().contains("fulcrum --profile=single-machine --tier=slim --role=all --mode=run --run-for=PT2S"));
        String plan = Files.readString(stateDir.resolve("run-plan.json"));
        assertTrue(plan.contains("\"schema\": \"fulcrum.operator-run-plan/v1\""));
        assertTrue(plan.contains("\"deploymentUnit\": \"supervised-process\""));
        assertTrue(plan.contains("\"tier\": \"slim\""));
        assertFalse(plan.contains(".java"));
        assertFalse(plan.contains("gradle"));
    }

    @Test
    void operatorUpDryRunRoutesFullEngineToComposePlan() throws Exception {
        Path stateDir = tempDir.resolve("compose-state");

        LaunchResult result = run(RuntimeEnvironment.of(Map.of()),
                "up",
                "--tier=full-engine",
                "--state-dir=" + stateDir,
                "--dry-run");

        assertEquals(FulcrumLauncher.OK, result.code(), result.err());
        assertTrue(result.out().contains("deploymentUnit=compose"));
        assertTrue(result.out().contains("docker compose -f fulcrum/compose/single-machine-full-engine.compose.yaml"));
        String plan = Files.readString(stateDir.resolve("run-plan.json"));
        assertTrue(plan.contains("\"deploymentUnit\": \"compose\""));
        assertTrue(plan.contains("\"entrypoint\": \"docker compose\""));
    }

    @Test
    void operatorStatusAndDownUseSavedRunPlan() {
        Path stateDir = tempDir.resolve("status-state");
        LaunchResult up = run(RuntimeEnvironment.of(Map.of()),
                "up",
                "--state-dir=" + stateDir,
                "--dry-run");
        assertEquals(FulcrumLauncher.OK, up.code(), up.err());

        LaunchResult status = run(RuntimeEnvironment.of(Map.of()), "status", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, status.code(), status.err());
        assertTrue(status.out().contains("status=planned"));
        assertTrue(status.out().contains("deploymentUnit=supervised-process"));

        LaunchResult down = run(RuntimeEnvironment.of(Map.of()), "down", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, down.code(), down.err());
        assertTrue(down.out().contains("status=stopped"));

        LaunchResult stopped = run(RuntimeEnvironment.of(Map.of()), "status", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, stopped.code(), stopped.err());
        assertTrue(stopped.out().contains("status=stopped"));
    }

    @Test
    void operatorUpRejectsProductionProfilesWithHelmInstruction() {
        LaunchResult result = run(RuntimeEnvironment.of(Map.of()),
                "up",
                "--profile=small-production",
                "--dry-run");

        assertEquals(FulcrumLauncher.USAGE_ERROR, result.code());
        assertTrue(result.err().contains("use the Fulcrum Helm chart"));
    }

    @Test
    void reservedOperatorGroupsHaveStableRefusalAndHelp() {
        LaunchResult bundleHelp = run(RuntimeEnvironment.of(Map.of()), "bundle", "--help");
        assertEquals(FulcrumLauncher.OK, bundleHelp.code());
        assertTrue(bundleHelp.out().contains("Usage: fulcrum bundle"));

        LaunchResult authorHelp = run(RuntimeEnvironment.of(Map.of()), "author", "--help");
        assertEquals(FulcrumLauncher.OK, authorHelp.code());
        assertTrue(authorHelp.out().contains("Usage: fulcrum author"));

        LaunchResult devHelp = run(RuntimeEnvironment.of(Map.of()), "dev", "--help");
        assertEquals(FulcrumLauncher.OK, devHelp.code());
        assertTrue(devHelp.out().contains("Usage: fulcrum dev"));
    }

    @Test
    void planModeListsAllServiceAndHostEntrypoints() {
        LaunchResult result = run(RuntimeEnvironment.of(Map.of()), "--profile=single-machine");

        assertEquals(FulcrumLauncher.OK, result.code());
        assertEquals("", result.err());
        assertTrue(result.out().contains("profile=single-machine"));
        assertTrue(result.out().contains("storageTier=in-memory"));
        assertTrue(result.out().contains("storageShape=embedded-in-memory-authority-stores"));
        assertTrue(result.out().contains("authority-service"));
        assertTrue(result.out().contains("controller-service"));
        assertTrue(result.out().contains("worker-agent"));
        assertTrue(result.out().contains("paper-agent"));
        assertTrue(result.out().contains("velocity-agent"));
        assertTrue(result.out().contains("fulcrum --role=paper-agent --profile=single-machine --mode=run"));
        assertTrue(result.out().contains("--tier=in-memory"));
    }

    @Test
    void planModeAcceptsExplicitSingleMachineTier() {
        LaunchResult result = run(RuntimeEnvironment.of(Map.of()),
                "--profile=single-machine",
                "--tier=full-engine");

        assertEquals(FulcrumLauncher.OK, result.code());
        assertEquals("", result.err());
        assertTrue(result.out().contains("profile=single-machine"));
        assertTrue(result.out().contains("storageTier=full-engine"));
        assertTrue(result.out().contains("storageShape=full-engine-compose-bindings"));
        assertTrue(result.out().contains("--tier=full-engine"));
    }

    @Test
    void productionProfilesRejectSingleMachineTierSwitch() {
        LaunchResult result = run(RuntimeEnvironment.of(Map.of()),
                "--profile=large-production",
                "--tier=in-memory");

        assertEquals(FulcrumLauncher.USAGE_ERROR, result.code());
        assertTrue(result.err().contains("--tier is only supported by the single-machine profile"));
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
    void runtimeConnectionSettingsResolveRoleBindingsAndRedactSecrets() {
        LaunchPlan plan = RuntimeEntrypointRegistry.plan(
                LaunchCommand.parse(new String[]{
                        "--profile=single-machine",
                        "--role=all",
                        "--mode=run"
                }),
                Thread.currentThread().getContextClassLoader());

        RuntimeConnectionSettings settings = RuntimeConnectionSettings.resolve(plan, allBindings());

        assertEquals(
                List.of(new RuntimeConnectionSettings.HostPort("localhost", 9092)),
                settings.authority().orElseThrow().kafkaBootstrapServers());
        assertEquals(
                "jdbc:postgresql://localhost:5432/fulcrum",
                settings.authority().orElseThrow().postgres().jdbcUrl());
        assertEquals(
                List.of(new RuntimeConnectionSettings.HostPort("localhost", 9042)),
                settings.authority().orElseThrow().cassandraContactPoints());
        assertEquals("dc-test", settings.authority().orElseThrow().cassandraLocalDatacenter());
        assertEquals(
                java.net.URI.create("http://localhost:8000/gameserverallocation"),
                settings.controller().orElseThrow().agonesAllocatorUrl());
        assertEquals("fulcrum-lobby", settings.controller().orElseThrow().agonesNamespace());
        assertTrue(settings.controller().orElseThrow().agonesAllocatorClientCertificatePath().isEmpty());
        assertFalse(settings.controller().orElseThrow().agonesAllocatorDisableHostnameVerification());
        assertEquals("host.paper.commands", settings.controller().orElseThrow().hostCommandTopic());
        assertEquals("host.observation", settings.controller().orElseThrow().hostObservationTopic());
        assertEquals("host.velocity.routes", settings.controller().orElseThrow().proxyRouteCommandTopic());
        assertEquals("worker-results", settings.worker().orElseThrow().objectBucket());
        assertEquals(tempDir.resolve("object-store"), settings.worker().orElseThrow().objectStoreRoot());
        assertEquals(
                RuntimeConnectionSettings.ObjectStoreMode.LOCAL,
                settings.worker().orElseThrow().objectStore().mode());
        assertEquals(tempDir.resolve("paper"), settings.paper().orElseThrow().paperServerRoot());
        assertEquals(
                tempDir.resolve("paper").resolve(PaperAllocatedAssignmentFile.FILE_NAME),
                settings.paper().orElseThrow().allocatedAssignmentFile());
        assertEquals(
                List.of(new RuntimeConnectionSettings.HostPort("localhost", 9092)),
                settings.paper().orElseThrow().paperKafkaBootstrapServers());
        assertEquals(
                java.net.URI.create("http://localhost:9358/"),
                settings.paper().orElseThrow().agonesSdkUrl());
        assertEquals(
                java.net.URI.create("http://127.0.0.1:18080/observations"),
                settings.paper().orElseThrow().observationBridgeUrl());
        assertEquals(
                java.net.URI.create("http://127.0.0.1:18083/capabilities"),
                settings.paper().orElseThrow().capabilityBridgeUrl());
        assertEquals(
                java.net.URI.create("http://127.0.0.1:18084/rewards"),
                settings.paper().orElseThrow().rewardBridgeUrl());
        assertEquals(
                new RuntimeConnectionSettings.HostPort("localhost", 6379),
                settings.paper().orElseThrow().valkeyEndpoint());
        assertEquals(1, settings.paper().orElseThrow().rewardDeliveryCopies());
        assertEquals(new SessionId("session-lobby-runtime"), settings.paper().orElseThrow().sessionId());
        assertEquals(new SlotId("slot-lobby-runtime"), settings.paper().orElseThrow().slotId());
        assertEquals(
                new ResolvedManifestId("manifest-lobby-runtime"),
                settings.paper().orElseThrow().resolvedManifest().resolvedManifestId());
        assertEquals(
                java.net.URI.create("http://127.0.0.1:18081/routes"),
                settings.velocity().orElseThrow().routeBridgeUrl());
        assertEquals(
                java.net.URI.create("http://127.0.0.1:18082/login-gate"),
                settings.velocity().orElseThrow().loginGateBridgeUrl());
        assertEquals(
                List.of(new RuntimeConnectionSettings.HostPort("localhost", 9092)),
                settings.velocity().orElseThrow().velocityKafkaBootstrapServers());
        assertEquals(
                new RuntimeConnectionSettings.HostPort("localhost", 6379),
                settings.velocity().orElseThrow().valkeyEndpoint());
        assertEquals(
                "ctrl.state.shared-shard-allocation",
                settings.velocity().orElseThrow().sharedShardAllocationStateTopic());
        assertEquals("cmd.presence", settings.velocity().orElseThrow().presenceCommandTopic());
        assertEquals("ctrl.cmd.queue-roster", settings.velocity().orElseThrow().queueRosterCommandTopic());
        assertEquals(
                "ctrl.cmd.shared-shard-placement",
                settings.velocity().orElseThrow().sharedShardPlacementCommandTopic());
        assertEquals("ctrl.cmd.route-attempt", settings.velocity().orElseThrow().routeAttemptCommandTopic());
        assertEquals("ctrl.cmd.lifecycle-trace", settings.velocity().orElseThrow().lifecycleTraceCommandTopic());
        assertEquals(
                new sh.harold.fulcrum.api.kernel.ExperienceId("experience-lobby"),
                settings.velocity().orElseThrow().lobbyExperienceId());
        assertEquals(
                new sh.harold.fulcrum.api.kernel.PoolId("pool-lobby"),
                settings.velocity().orElseThrow().lobbyPoolId());
        assertEquals("fulcrum-lobby-paper", settings.velocity().orElseThrow().lobbyAgonesFleetName());
        assertEquals(75, settings.velocity().orElseThrow().lobbyTargetCapacity());
        assertEquals(150, settings.velocity().orElseThrow().lobbyHardCapacity());
        assertEquals(
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                settings.velocity().orElseThrow().lobbyResolvedManifestId());
        assertEquals("capability-scope-lobby", settings.velocity().orElseThrow().lobbyCapabilityScopeFingerprint());
        assertEquals("lobby-login", settings.velocity().orElseThrow().loginGateScope());
        assertEquals(Duration.ofMinutes(5), settings.velocity().orElseThrow().presenceLease());

        String summary = String.join("\n", settings.redactedSummary());
        assertTrue(summary.contains("password=<redacted>"));
        assertTrue(summary.contains("sessionOwnerToken=<redacted>"));
        assertTrue(summary.contains("worker-agent: objectStoreMode=local"));
        assertTrue(summary.contains("paper-agent: objectStoreMode=local"));
        assertTrue(summary.contains("rewardDeliveryCopies=1"));
        assertFalse(summary.contains("postgres-secret"));
        assertFalse(summary.contains("owner-token-lobby-runtime"));
        assertFalse(summary.contains("FULCRUM_POSTGRES_PASSWORD"));
    }

    @Test
    void runtimeConnectionSettingsResolveS3ObjectStorageBindingsAndRedactSecrets() {
        Map<String, String> values = allBindingsMap();
        values.remove("FULCRUM_OBJECT_STORE_ROOT");
        values.put("FULCRUM_OBJECT_STORE_MODE", "s3");
        values.put("FULCRUM_OBJECT_STORE_ENDPOINT", "http://minio.fulcrum-lobby:9000");
        values.put("FULCRUM_OBJECT_STORE_REGION", "us-test-1");
        values.put("FULCRUM_OBJECT_STORE_ACCESS_KEY", "fulcrum-access");
        values.put("FULCRUM_OBJECT_STORE_SECRET_KEY", "fulcrum-secret");
        LaunchPlan plan = RuntimeEntrypointRegistry.plan(
                LaunchCommand.parse(new String[]{
                        "--profile=single-machine",
                        "--role=all",
                        "--mode=run"
                }),
                Thread.currentThread().getContextClassLoader());

        assertTrue(plan.canStart(RuntimeEnvironment.of(values)));
        RuntimeConnectionSettings settings = RuntimeConnectionSettings.resolve(plan, RuntimeEnvironment.of(values));

        RuntimeConnectionSettings.ObjectStoreConnection workerStore = settings.worker().orElseThrow().objectStore();
        assertEquals(RuntimeConnectionSettings.ObjectStoreMode.S3, workerStore.mode());
        RuntimeConnectionSettings.S3ObjectStoreConnection s3 = workerStore.s3().orElseThrow();
        assertEquals(java.net.URI.create("http://minio.fulcrum-lobby:9000"), s3.endpoint());
        assertEquals("us-test-1", s3.region());

        String summary = String.join("\n", settings.redactedSummary());
        assertTrue(summary.contains("worker-agent: objectStoreMode=s3"));
        assertTrue(summary.contains("paper-agent: objectStoreMode=s3"));
        assertTrue(summary.contains("objectStoreEndpoint=http://minio.fulcrum-lobby:9000"));
        assertTrue(summary.contains("objectStoreAccessKey=<redacted>"));
        assertTrue(summary.contains("objectStoreSecretKey=<redacted>"));
        assertFalse(summary.contains("fulcrum-access"));
        assertFalse(summary.contains("fulcrum-secret"));
    }

    @Test
    void runtimeConnectionSettingsResolvePaperAllocatedAssignmentFileOverride() {
        Map<String, String> values = allBindingsMap();
        values.put("FULCRUM_PAPER_ALLOCATION_FILE", tempDir.resolve("paper-state").resolve("allocated.properties").toString());
        LaunchPlan plan = RuntimeEntrypointRegistry.plan(
                LaunchCommand.parse(new String[]{
                        "--profile=single-machine",
                        "--role=paper-agent",
                        "--mode=run"
                }),
                Thread.currentThread().getContextClassLoader());

        RuntimeConnectionSettings settings = RuntimeConnectionSettings.resolve(plan, RuntimeEnvironment.of(values));

        assertEquals(
                tempDir.resolve("paper-state").resolve("allocated.properties"),
                settings.paper().orElseThrow().allocatedAssignmentFile());
    }

    @Test
    void runtimeConnectionSettingsResolvePaperRewardDuplicateDeliveryOverride() {
        Map<String, String> values = allBindingsMap();
        values.put("FULCRUM_PAPER_REWARD_DELIVERY_COPIES", "2");
        LaunchPlan plan = RuntimeEntrypointRegistry.plan(
                LaunchCommand.parse(new String[]{
                        "--profile=single-machine",
                        "--role=paper-agent",
                        "--mode=run"
                }),
                Thread.currentThread().getContextClassLoader());

        RuntimeConnectionSettings settings = RuntimeConnectionSettings.resolve(plan, RuntimeEnvironment.of(values));

        assertEquals(2, settings.paper().orElseThrow().rewardDeliveryCopies());
        assertTrue(String.join("\n", settings.redactedSummary()).contains("rewardDeliveryCopies=2"));
    }

    @Test
    void runtimeConnectionSettingsResolveAgonesAllocatorMtlsBindings() {
        Map<String, String> values = allBindingsMap();
        values.put("FULCRUM_AGONES_ALLOCATOR_CLIENT_CERT_PATH", tempDir.resolve("tls.crt").toString());
        values.put("FULCRUM_AGONES_ALLOCATOR_CLIENT_KEY_PATH", tempDir.resolve("tls.key").toString());
        values.put("FULCRUM_AGONES_ALLOCATOR_CA_CERT_PATH", tempDir.resolve("tls-ca.crt").toString());
        LaunchPlan plan = RuntimeEntrypointRegistry.plan(
                LaunchCommand.parse(new String[]{
                        "--profile=single-machine",
                        "--role=controller-service",
                        "--mode=run"
                }),
                Thread.currentThread().getContextClassLoader());

        RuntimeConnectionSettings settings = RuntimeConnectionSettings.resolve(plan, RuntimeEnvironment.of(values));

        RuntimeConnectionSettings.ControllerConnections controller = settings.controller().orElseThrow();
        assertEquals(Optional.of(tempDir.resolve("tls.crt")), controller.agonesAllocatorClientCertificatePath());
        assertEquals(Optional.of(tempDir.resolve("tls.key")), controller.agonesAllocatorClientKeyPath());
        assertEquals(Optional.of(tempDir.resolve("tls-ca.crt")), controller.agonesAllocatorCaCertificatePath());
        String summary = String.join("\n", settings.redactedSummary());
        assertTrue(summary.contains("controller-service: agonesAllocatorMtls=true"));
        assertFalse(summary.contains("tls.key"));
    }

    @Test
    void runtimeConnectionSettingsResolveAgonesAllocatorCaOnlyBinding() {
        Map<String, String> values = allBindingsMap();
        values.put("FULCRUM_AGONES_ALLOCATOR_CA_CERT_PATH", tempDir.resolve("tls-ca.crt").toString());
        values.put("FULCRUM_AGONES_ALLOCATOR_DISABLE_HOSTNAME_VERIFICATION", "true");
        LaunchPlan plan = RuntimeEntrypointRegistry.plan(
                LaunchCommand.parse(new String[]{
                        "--profile=single-machine",
                        "--role=controller-service",
                        "--mode=run"
                }),
                Thread.currentThread().getContextClassLoader());

        RuntimeConnectionSettings settings = RuntimeConnectionSettings.resolve(plan, RuntimeEnvironment.of(values));

        RuntimeConnectionSettings.ControllerConnections controller = settings.controller().orElseThrow();
        assertEquals(Optional.empty(), controller.agonesAllocatorClientCertificatePath());
        assertEquals(Optional.empty(), controller.agonesAllocatorClientKeyPath());
        assertEquals(Optional.of(tempDir.resolve("tls-ca.crt")), controller.agonesAllocatorCaCertificatePath());
        assertTrue(controller.agonesAllocatorDisableHostnameVerification());
        assertTrue(String.join("\n", settings.redactedSummary()).contains("controller-service: agonesAllocatorMtls=false"));
        assertTrue(String.join("\n", settings.redactedSummary()).contains("controller-service: agonesAllocatorHostnameVerification=false"));
    }

    @Test
    void runtimeConnectionSettingsRejectPartialAgonesAllocatorMtlsBindings() {
        Map<String, String> values = allBindingsMap();
        values.put("FULCRUM_AGONES_ALLOCATOR_CLIENT_CERT_PATH", tempDir.resolve("tls.crt").toString());
        LaunchPlan plan = RuntimeEntrypointRegistry.plan(
                LaunchCommand.parse(new String[]{
                        "--profile=single-machine",
                        "--role=controller-service",
                        "--mode=run"
                }),
                Thread.currentThread().getContextClassLoader());

        RuntimeConfigurationException exception = assertThrows(
                RuntimeConfigurationException.class,
                () -> RuntimeConnectionSettings.resolve(plan, RuntimeEnvironment.of(values)));

        assertTrue(exception.getMessage().contains("client certificate and client key"));
    }

    @Test
    void runtimeExternalClientsConstructRoleAdaptersAndRedactSecrets() {
        LaunchPlan plan = RuntimeEntrypointRegistry.plan(
                LaunchCommand.parse(new String[]{
                        "--profile=single-machine",
                        "--role=all",
                        "--mode=run"
                }),
                Thread.currentThread().getContextClassLoader());
        RuntimeConnectionSettings settings = RuntimeConnectionSettings.resolve(plan, allBindings());

        try (RuntimeExternalClients clients = RuntimeExternalClients.create(settings)) {
            assertTrue(clients.authority().orElseThrow().postgres().dataSource() != null);
            assertTrue(clients.controller().orElseThrow().allocationPort() != null);
            assertTrue(clients.controller().orElseThrow().hostObservationKafka() != null);
            assertTrue(clients.worker().orElseThrow().objectStorage() != null);
            assertTrue(clients.paper().orElseThrow().paperKafka() != null);
            assertTrue(clients.paper().orElseThrow().objectStorage() != null);
            assertTrue(clients.paper().orElseThrow().valkey() != null);
            assertTrue(clients.velocity().orElseThrow().velocityKafka() != null);
            assertTrue(clients.velocity().orElseThrow().routeBridgeClient() != null);
            assertTrue(clients.velocity().orElseThrow().valkey() != null);

            String summary = String.join("\n", clients.redactedSummary());

            assertTrue(summary.contains("authority-service: kafkaClient=bootstrapServers=localhost:9092"));
            assertTrue(summary.contains("authority-service: postgresClient=jdbcUrl=jdbc:postgresql://localhost:5432/fulcrum"));
            assertTrue(summary.contains("authority-service: cassandraClient=contactPoints="));
            assertTrue(summary.contains("localDatacenter=dc-test"));
            assertTrue(summary.contains("authority-service: valkeyClient=localhost:6379"));
            assertTrue(summary.contains("controller-service: controlKafkaClient=bootstrapServers=localhost:9092"));
            assertTrue(summary.contains("controller-service: agonesNamespace=fulcrum-lobby"));
            assertTrue(summary.contains("controller-service: agonesAllocatorMtls=false"));
            assertTrue(summary.contains("controller-service: agonesAllocatorHostnameVerification=true"));
            assertTrue(summary.contains("controller-service: hostCommandTopic=host.paper.commands"));
            assertTrue(summary.contains("controller-service: hostObservationTopic=host.observation"));
            assertTrue(summary.contains("controller-service: proxyRouteCommandTopic=host.velocity.routes"));
            assertTrue(summary.contains("worker-agent: objectBucket=worker-results"));
            assertTrue(summary.contains("worker-agent: objectStoreMode=local"));
            assertTrue(summary.contains("paper-agent: paperKafkaClient=bootstrapServers=localhost:9092"));
            assertTrue(summary.contains("paper-agent: objectBucket=artifact-store"));
            assertTrue(summary.contains("paper-agent: objectStoreMode=local"));
            assertTrue(summary.contains("paper-agent: observationBridgeUrl=http://127.0.0.1:18080/observations"));
            assertTrue(summary.contains("paper-agent: capabilityBridgeUrl=http://127.0.0.1:18083/capabilities"));
            assertTrue(summary.contains("paper-agent: rewardBridgeUrl=http://127.0.0.1:18084/rewards"));
            assertTrue(summary.contains("paper-agent: valkeyClient=localhost:6379"));
            assertTrue(summary.contains("velocity-agent: velocityKafkaClient=bootstrapServers=localhost:9092"));
            assertTrue(summary.contains("velocity-agent: loginGateBridgeUrl=http://127.0.0.1:18082/login-gate"));
            assertTrue(summary.contains("velocity-agent: presenceCommandTopic=cmd.presence"));
            assertTrue(summary.contains("velocity-agent: queueRosterCommandTopic=ctrl.cmd.queue-roster"));
            assertTrue(summary.contains("velocity-agent: sharedShardPlacementCommandTopic=ctrl.cmd.shared-shard-placement"));
            assertTrue(summary.contains("velocity-agent: routeAttemptCommandTopic=ctrl.cmd.route-attempt"));
            assertTrue(summary.contains("velocity-agent: lifecycleTraceCommandTopic=ctrl.cmd.lifecycle-trace"));
            assertTrue(summary.contains("velocity-agent: sharedShardAllocationStateTopic=ctrl.state.shared-shard-allocation"));
            assertTrue(summary.contains("velocity-agent: lobbyRouting=experienceId=experience-lobby|poolId=pool-lobby|fleet=fulcrum-lobby-paper|resolvedManifestId=manifest-lobby-bedrock-v1"));
            assertTrue(summary.contains("velocity-agent: valkeyClient=localhost:6379"));
            assertFalse(summary.contains("postgres-secret"));
            assertFalse(summary.contains("FULCRUM_POSTGRES_PASSWORD"));
        }
    }

    @Test
    void runModeRejectsMalformedExternalBindingsBeforeStartup() {
        Map<String, String> values = new HashMap<>(allBindingsMap());
        values.put("FULCRUM_AGONES_ALLOCATOR_URL", "ftp://localhost:8000/gameserverallocation");

        LaunchResult result = run(RuntimeEnvironment.of(values),
                "--profile=single-machine",
                "--role=controller-service",
                "--mode=run",
                "--run-for=PT0.1S");

        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, result.code());
        assertFalse(result.out().contains("Fulcrum runtime started"));
        assertTrue(result.err().contains("FULCRUM_AGONES_ALLOCATOR_URL must use http or https"));
    }

    @Test
    void runModeStartsSupervisorAndProbeServer() throws Exception {
        int probePort = freePort();
        LaunchResult result = run(allBindings(),
                "--profile=single-machine",
                "--role=controller-service",
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
    void controllerRuntimeExposesNeutralAuthorityRegistrationEndpointWhenConfigured() throws Exception {
        int registrationPort = freePort();
        CapabilityDescriptor descriptor = registrationDescriptor();
        HostSecurityContext securityContext = registrationSecurityContext();
        AuthorityBackendRegistrationRequest request = AuthorityBackendRegistrationRequest.credentialed(
                descriptor,
                securityContext,
                "sha256:neutral-registration-bundle",
                AuthorityArtifactVerificationEvidence.verified(
                        "OCI",
                        "oci://ghcr.io/harolddotsh/neutral-registration@sha256:neutral-registration-bundle",
                        "sha256:neutral-registration-bundle",
                        "cosign:test"),
                Instant.parse("2026-06-20T12:00:00Z"));

        try (ControllerRuntimeServiceEngine engine = new ControllerRuntimeServiceEngine(
                List.of(new ControllerWorkerBinding("registration-test", Optional::empty)),
                Duration.ofMillis(10),
                Optional.of(new RuntimeConnectionSettings.HostPort("127.0.0.1", registrationPort)))) {
            engine.start();

            AuthorityBackendRegistrationReceipt receipt = new HttpAuthorityBackendRegistrationClient(
                    URI.create("http://127.0.0.1:" + registrationPort + "/authority-backends/register"))
                    .register(request);

            assertEquals(AuthorityBackendRegistrationStatus.ADMITTED, receipt.status());
            assertEquals(descriptor.capabilityId(), receipt.capabilityId());
            assertEquals(Optional.of(securityContext.identity().principalId()), receipt.principalId());
            assertEquals(1, receipt.fencingEpoch());
            assertTrue(engine.live());
        }
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
    void authorityRuntimeEnginePollsWorkerBindingsUntilStopped() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        AtomicInteger committed = new AtomicInteger();
        AtomicReference<AuthorityRecord<String>> storedRecord = new AtomicReference<>();
        AuthorityRecordStore<String> recordStore = new AuthorityRecordStore<>() {
            @Override
            public AuthorityRecord<String> load(AggregateId aggregateId) {
                AuthorityRecord<String> current = storedRecord.get();
                return current == null ? new AuthorityRecord<>(new Revision(0), 1, "initial") : current;
            }

            @Override
            public void store(AggregateId aggregateId, AuthorityRecord<String> record) {
                storedRecord.set(record);
            }
        };
        AuthorityRuntimeWorker<String, RuntimeCommand, String> worker = new AuthorityRuntimeWorker<>(
                () -> {
                    int poll = polls.incrementAndGet();
                    if (poll == 1) {
                        return Optional.of(new AuthorityCommandDelivery<>(
                                runtimeCommand("command-runtime-engine", "value-runtime-engine"),
                                new AuthorityOffset("cmd.subject", 0, 41)));
                    }
                    return Optional.empty();
                },
                recordStore,
                (command, currentRecord) -> {
                    handled.incrementAndGet();
                    return AuthorityDecision.accepted(
                            new Revision(currentRecord.revision().value() + 1),
                            command.envelope().payload().value(),
                            "accepted-runtime-engine",
                            List.of(),
                            trace());
                },
                (command, decision) -> {
                },
                emission -> {
                },
                (delivery, decision) -> {
                },
                offset -> committed.incrementAndGet());
        AuthorityRuntimeServiceEngine engine = new AuthorityRuntimeServiceEngine(
                List.of(AuthorityWorkerBinding.fromWorker("subject", worker)),
                Duration.ofMillis(10));

        engine.start();
        awaitTrue(() -> polls.get() >= 2, Duration.ofSeconds(2), "authority worker was not polled");
        engine.close();

        assertTrue(engine.loopCount() >= 2);
        assertTrue(polls.get() >= 2);
        assertEquals(1, handled.get());
        assertEquals(1, committed.get());
        assertEquals(new AuthorityRecord<>(new Revision(1), 1, "value-runtime-engine"), storedRecord.get());
        assertFalse(engine.live());
        assertFalse(engine.ready());
    }

    @Test
    void controllerRuntimeEnginePollsWorkerBindingsUntilStopped() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        ControllerRuntimeServiceEngine engine = new ControllerRuntimeServiceEngine(
                List.of(new ControllerWorkerBinding(
                        "route-controller",
                        () -> {
                            int poll = polls.incrementAndGet();
                            if (poll == 1) {
                                return Optional.of(new ControllerRuntimeReceipt("route-controller", "route-attempt-1"));
                            }
                            return Optional.empty();
                        })),
                Duration.ofMillis(10));

        engine.start();
        awaitTrue(() -> polls.get() >= 2, Duration.ofSeconds(2), "controller worker was not polled");
        engine.close();

        assertTrue(engine.loopCount() >= 2);
        assertTrue(polls.get() >= 2);
        assertFalse(engine.live());
        assertFalse(engine.ready());
    }

    @Test
    void controllerWorkerCatalogBuildsCoreControllerDomains() {
        ControllerWorkerCatalog catalog = new ControllerWorkerCatalog(new LocalControllerRuntimeBindings(), 1);

        assertEquals(
                ControllerWorkerCatalog.controllerDomains(),
                catalog.workerBindings().stream().map(ControllerWorkerBinding::controllerDomain).toList());
    }

    @Test
    void catalogBuiltSharedShardAllocationWorkerProcessesRequestThroughRuntimeLoop() throws Exception {
        LocalControllerRuntimeBindings bindings = new LocalControllerRuntimeBindings();
        ControllerWorkerCatalog catalog = new ControllerWorkerCatalog(
                bindings,
                request -> new HostAllocationClaim(
                        new SlotId("slot-lobby-runtime"),
                        request.sessionId(),
                        new HostInstanceIdentity(
                                new InstanceId("instance-paper-lobby-runtime"),
                                HostInstanceKinds.PAPER,
                                request.poolId(),
                                new MachineRef("machine-allocation-runtime"),
                                new PrincipalId("principal-paper-lobby-runtime")),
                        request.resolvedManifestId(),
                        new HostNetworkEndpoint("paper-lobby-runtime.internal", 25_568),
                        request.traceEnvelope(),
                        request.requestedAt()),
                37);
        SessionId session = new SessionId("session-shared-shard-allocation");
        bindings.enqueueSharedShardAllocation(sharedShardAllocationRequest(session));
        ControllerRuntimeServiceEngine engine = new ControllerRuntimeServiceEngine(
                catalog.workerBindings(),
                Duration.ofMillis(10));

        try {
            engine.start();
            awaitTrue(
                    () -> !bindings.committedReceipts(ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION).isEmpty(),
                    Duration.ofSeconds(2),
                    "catalog-built shared-shard allocation worker did not commit its receipt");
        } finally {
            engine.close();
        }

        assertEquals(SharedShardAllocationDecisionStatus.ACCEPTED,
                bindings.sharedShardAllocationDecisions().getFirst().status());
        assertEquals(new SlotId("slot-lobby-runtime"),
                bindings.sharedShardAllocationDecisions().getFirst().claim().orElseThrow().slotId());
        assertFalse(bindings.sharedShardAllocationEmissions().isEmpty());
        assertEquals(List.of(new ControllerRuntimeReceipt(
                        ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION,
                        session.value())),
                bindings.committedReceipts(ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION));
    }

    @Test
    void catalogBuiltSharedShardPlacementWorkerProcessesRequestThroughRuntimeLoop() throws Exception {
        LocalControllerRuntimeBindings bindings = new LocalControllerRuntimeBindings();
        ControllerWorkerCatalog catalog = new ControllerWorkerCatalog(bindings, 39);
        SubjectId subject = new SubjectId(UUID.fromString("12121212-1212-1212-1212-121212121212"));
        PresenceId presence = new PresenceId("presence-placement-runtime");
        SharedShardPlacementRequest request = sharedShardPlacementRequest(
                "placement-attempt-runtime",
                subject,
                presence,
                new ResolvedManifestId("manifest-lobby-runtime"));
        bindings.enqueueSharedShardPlacement(
                request,
                List.of(sharedShardPlacementCandidate(
                        "instance-paper-placement-runtime",
                        "session-placement-runtime",
                        "slot-placement-runtime",
                        request.experience().poolId(),
                        request.experience().resolvedManifestId(),
                        InstanceRegistryStatus.READY,
                        12,
                        request.experience().hardCapacity(),
                        true)));
        ControllerRuntimeServiceEngine engine = new ControllerRuntimeServiceEngine(
                catalog.workerBindings(),
                Duration.ofMillis(10));

        try {
            engine.start();
            awaitTrue(
                    () -> !bindings.committedReceipts(ControllerWorkerCatalog.SHARED_SHARD_PLACEMENT).isEmpty(),
                    Duration.ofSeconds(2),
                    "catalog-built shared-shard placement worker did not commit its receipt");
        } finally {
            engine.close();
        }

        assertEquals(SharedShardPlacementDecisionStatus.SELECTED_EXISTING_SESSION,
                bindings.sharedShardPlacementDecisions().getFirst().status());
        assertEquals(Optional.of(new SessionId("session-placement-runtime")),
                bindings.sharedShardPlacementDecisions().getFirst().sessionId());
        assertEquals(Optional.of(new SlotId("slot-placement-runtime")),
                bindings.sharedShardPlacementDecisions().getFirst().slotId());
        assertEquals(List.of(new ControllerRuntimeReceipt(
                        ControllerWorkerCatalog.SHARED_SHARD_PLACEMENT,
                        request.placementAttemptId())),
                bindings.committedReceipts(ControllerWorkerCatalog.SHARED_SHARD_PLACEMENT));
    }

    @Test
    void catalogBuiltInstanceRegistryWorkerProcessesCommandThroughRuntimeLoop() throws Exception {
        LocalControllerRuntimeBindings bindings = new LocalControllerRuntimeBindings();
        ControllerWorkerCatalog catalog = new ControllerWorkerCatalog(bindings, 23);
        InstanceId instance = new InstanceId("instance-controller-runtime");
        bindings.enqueueInstanceRegistry(registerInstanceCommand(instance, 23));
        ControllerRuntimeServiceEngine engine = new ControllerRuntimeServiceEngine(
                catalog.workerBindings(),
                Duration.ofMillis(10));

        try {
            engine.start();
            awaitTrue(
                    () -> !bindings.committedReceipts(ControllerWorkerCatalog.INSTANCE_REGISTRY).isEmpty(),
                    Duration.ofSeconds(2),
                    "catalog-built instance-registry worker did not commit its receipt");
        } finally {
            engine.close();
        }

        InstanceRegistryRecord stored = bindings.storedInstanceRegistryRecord(instance).orElseThrow();
        assertEquals(new Revision(1), stored.revision());
        assertEquals(23, stored.fencingEpoch());
        assertEquals(InstanceRegistryStatus.REGISTERED, stored.snapshot().orElseThrow().status());
        assertEquals(instance, stored.snapshot().orElseThrow().instanceId());
        assertEquals(List.of(new ControllerRuntimeReceipt(
                        ControllerWorkerCatalog.INSTANCE_REGISTRY,
                        "command-register-" + instance.value())),
                bindings.committedReceipts(ControllerWorkerCatalog.INSTANCE_REGISTRY));
        assertEquals(InstanceRegistryDecisionStatus.ACCEPTED, bindings.instanceRegistryDecisions().getFirst().status());
        assertFalse(bindings.instanceRegistryEmissions().isEmpty());
    }

    @Test
    void catalogBuiltRouteAttemptWorkerProcessesCommandThroughRuntimeLoop() throws Exception {
        LocalControllerRuntimeBindings bindings = new LocalControllerRuntimeBindings();
        ControllerWorkerCatalog catalog = new ControllerWorkerCatalog(bindings, 29);
        RouteAttemptId routeAttempt = new RouteAttemptId("route-attempt-controller-runtime");
        bindings.enqueueRouteAttempt(requestRouteAttemptCommand(routeAttempt, 29));
        ControllerRuntimeServiceEngine engine = new ControllerRuntimeServiceEngine(
                catalog.workerBindings(),
                Duration.ofMillis(10));

        try {
            engine.start();
            awaitTrue(
                    () -> !bindings.committedReceipts(ControllerWorkerCatalog.ROUTE_ATTEMPT).isEmpty(),
                    Duration.ofSeconds(2),
                    "catalog-built route-attempt worker did not commit its receipt");
        } finally {
            engine.close();
        }

        RouteAttemptControlRecord stored = bindings.storedRouteAttemptRecord(routeAttempt).orElseThrow();
        assertEquals(new Revision(1), stored.revision());
        assertEquals(29, stored.fencingEpoch());
        assertEquals(RouteAttemptLifecycleStatus.CREATED, stored.snapshot().orElseThrow().status());
        assertEquals(routeAttempt, stored.snapshot().orElseThrow().routeAttemptId());
        assertEquals(List.of(new ControllerRuntimeReceipt(
                        ControllerWorkerCatalog.ROUTE_ATTEMPT,
                        "command-route-request-" + routeAttempt.value())),
                bindings.committedReceipts(ControllerWorkerCatalog.ROUTE_ATTEMPT));
        assertEquals(RouteAttemptDecisionStatus.ACCEPTED, bindings.routeAttemptDecisions().getFirst().status());
        assertFalse(bindings.routeAttemptEmissions().isEmpty());
    }

    @Test
    void catalogBuiltControllerWorkersProcessControlDomainsThroughRuntimeLoop() throws Exception {
        LocalControllerRuntimeBindings bindings = new LocalControllerRuntimeBindings();
        ControllerWorkerCatalog catalog = new ControllerWorkerCatalog(bindings, 31);
        SessionId session = new SessionId("session-controller-domains");
        LifecycleTraceId lifecycleTrace = new LifecycleTraceId(trace().traceId());
        CapabilityScope scope = CapabilityScope.experience(new ExperienceId("experience-lobby"));
        SubmitQueueIntent queuePayload = submitQueueIntent(new QueueIntentId("queue-intent-controller-domains"));
        FaultId fault = new FaultId("fault-controller-domains");

        bindings.enqueueExperienceSession(requestExperienceSessionCommand(session, 31));
        bindings.enqueueLifecycleTrace(recordLifecycleObservationCommand(lifecycleTrace, 31));
        bindings.enqueueCapabilityEnablement(enableCapabilityCommand(scope, 31));
        bindings.enqueueQueueRoster(submitQueueIntentCommand(queuePayload, 31));
        bindings.enqueueFault(recordFaultCommand(fault, 31));

        ControllerRuntimeServiceEngine engine = new ControllerRuntimeServiceEngine(
                catalog.workerBindings(),
                Duration.ofMillis(10));
        List<String> expectedDomains = List.of(
                ControllerWorkerCatalog.EXPERIENCE_SESSION,
                ControllerWorkerCatalog.LIFECYCLE_TRACE,
                ControllerWorkerCatalog.CAPABILITY_ENABLEMENT,
                ControllerWorkerCatalog.QUEUE_ROSTER,
                ControllerWorkerCatalog.FAULT);

        try {
            engine.start();
            awaitTrue(
                    () -> expectedDomains.stream().allMatch(domain -> !bindings.committedReceipts(domain).isEmpty()),
                    Duration.ofSeconds(2),
                    "catalog-built controller workers did not commit all domain receipts");
        } finally {
            engine.close();
        }

        ExperienceSessionControlRecord sessionRecord = bindings.storedExperienceSessionRecord(session).orElseThrow();
        assertEquals(new Revision(1), sessionRecord.revision());
        assertEquals(ExperienceSessionStatus.REQUESTED, sessionRecord.sessionRecord().orElseThrow().status());
        assertEquals(ExperienceSessionDecisionStatus.ACCEPTED, bindings.experienceSessionDecisions().getFirst().status());
        assertFalse(bindings.experienceSessionEmissions().isEmpty());

        LifecycleTraceControlRecord traceRecord = bindings.storedLifecycleTraceRecord(lifecycleTrace).orElseThrow();
        assertEquals(new Revision(1), traceRecord.revision());
        assertEquals(1, traceRecord.traceRecord().entries().size());
        assertEquals(LifecycleTraceDecisionStatus.ACCEPTED, bindings.lifecycleTraceDecisions().getFirst().status());
        assertFalse(bindings.lifecycleTraceEmissions().isEmpty());

        CapabilityEnablementControlRecord capabilityRecord = bindings.storedCapabilityEnablementRecord(scope).orElseThrow();
        assertEquals(new Revision(1), capabilityRecord.revision());
        assertTrue(capabilityRecord.state().binding(new CapabilityId("lobby-chat")).orElseThrow().enabled());
        assertEquals(CapabilityEnablementDecisionStatus.ACCEPTED, bindings.capabilityEnablementDecisions().getFirst().status());
        assertFalse(bindings.capabilityEnablementEmissions().isEmpty());

        QueueRosterControlRecord queueRecord = bindings.storedQueueRosterRecord(queuePayload.partitionKey()).orElseThrow();
        assertEquals(new Revision(1), queueRecord.revision());
        assertTrue(queueRecord.state().queueIntent(queuePayload.queueIntentId()).isPresent());
        assertEquals(QueueRosterDecisionStatus.ACCEPTED, bindings.queueRosterDecisions().getFirst().status());
        assertFalse(bindings.queueRosterEmissions().isEmpty());

        FaultControlRecord faultRecord = bindings.storedFaultRecord(fault).orElseThrow();
        assertEquals(new Revision(1), faultRecord.revision());
        assertTrue(faultRecord.faultRecord().isPresent());
        assertEquals(FaultDecisionStatus.ACCEPTED, bindings.faultDecisions().getFirst().status());
        assertFalse(bindings.faultEmissions().isEmpty());
    }

    @Test
    void workerRuntimeEnginePollsWorkerAgentRuntimeBindingsUntilStopped() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        ResolvedManifestId manifest = new ResolvedManifestId("manifest-worker-runtime");
        Clock clock = Clock.fixed(Instant.parse("2026-06-17T10:00:00Z"), ZoneOffset.UTC);
        WorkerAgentRuntime runtime = new WorkerAgentRuntime(
                RuntimeIdentityIssuer.issue(
                        DeploymentProfile.SINGLE_MACHINE.loadDescriptor(Thread.currentThread().getContextClassLoader()),
                        RuntimeEntrypointRegistry.entriesFor(LaunchRole.WORKER_AGENT).getFirst(),
                        RuntimeEnvironment.of(Map.of("FULCRUM_MACHINE_REF", "machine-worker-test"))),
                manifest,
                List.of(new WorkerLagBudget(WorkerJobKind.CONTENT_VALIDATION, Duration.ofSeconds(5))));
        WorkerJobRequest request = new WorkerJobRequest(
                new WorkerJobId("job-worker-runtime"),
                WorkerJobKind.CONTENT_VALIDATION,
                "artifact-lobby-bedrock",
                new IdempotencyKey("idem-worker-runtime"),
                "payload-worker-runtime",
                manifest,
                trace(),
                clock.instant().minusMillis(100),
                Optional.empty());
        WorkerRuntimeServiceEngine engine = new WorkerRuntimeServiceEngine(
                List.of(WorkerJobBinding.fromRuntime(
                        "content-validation",
                        runtime,
                        () -> {
                            int poll = polls.incrementAndGet();
                            return poll == 1 ? Optional.of(request) : Optional.empty();
                        },
                        job -> {
                            handled.incrementAndGet();
                            return new WorkerJobResult("accepted", "object://worker-output");
                        },
                        clock)),
                Duration.ofMillis(10));

        engine.start();
        awaitTrue(() -> polls.get() >= 2, Duration.ofSeconds(2), "worker job binding was not polled");
        engine.close();

        assertTrue(engine.loopCount() >= 2);
        assertTrue(polls.get() >= 2);
        assertEquals(1, handled.get());
        assertFalse(engine.live());
        assertFalse(engine.ready());
    }

    @Test
    void workerJobCatalogBuildsWorkerJobDomains() {
        ResolvedManifestId manifest = new ResolvedManifestId("manifest-worker-catalog");
        Clock clock = Clock.fixed(Instant.parse("2026-06-17T10:00:00Z"), ZoneOffset.UTC);
        WorkerAgentRuntime runtime = workerRuntime(manifest);
        WorkerJobCatalog catalog = new WorkerJobCatalog(
                runtime,
                new LocalWorkerRuntimeBindings(),
                workerObjectStore("domain"),
                clock);

        assertEquals(
                WorkerJobCatalog.workerDomains(),
                catalog.workerBindings().stream().map(WorkerJobBinding::workerDomain).toList());
    }

    @Test
    void catalogBuiltWorkerJobProcessesRequestThroughRuntimeLoop() throws Exception {
        ResolvedManifestId manifest = new ResolvedManifestId("manifest-worker-catalog");
        Clock clock = Clock.fixed(Instant.parse("2026-06-17T10:00:00Z"), ZoneOffset.UTC);
        LocalWorkerRuntimeBindings bindings = new LocalWorkerRuntimeBindings();
        LocalObjectStorageAdapter objectStorage = workerObjectStore("catalog");
        WorkerJobRequest request = workerJobRequest(
                new WorkerJobId("job-worker-catalog"),
                WorkerJobKind.CONTENT_VALIDATION,
                manifest,
                clock.instant().minusMillis(100));
        bindings.enqueue(request);
        WorkerRuntimeServiceEngine engine = new WorkerRuntimeServiceEngine(
                new WorkerJobCatalog(workerRuntime(manifest), bindings, objectStorage, clock).workerBindings(),
                Duration.ofMillis(10));

        try {
            engine.start();
            awaitTrue(
                    () -> !bindings.receipts(WorkerJobKind.CONTENT_VALIDATION).isEmpty(),
                    Duration.ofSeconds(2),
                    "catalog-built worker job did not record its receipt");
        } finally {
            engine.close();
        }

        assertEquals(1, bindings.receipts(WorkerJobKind.CONTENT_VALIDATION).size());
        assertEquals(WorkerJobDecisionStatus.ACCEPTED, bindings.receipts(WorkerJobKind.CONTENT_VALIDATION).getFirst().status());
        String outputRef = bindings.receipts(WorkerJobKind.CONTENT_VALIDATION)
                .getFirst()
                .result()
                .orElseThrow()
                .outputRef();
        assertTrue(outputRef.startsWith("object://worker-results/"));
        assertTrue(objectStorage.exists(new ArtifactObjectAddress(outputRef)));
        assertFalse(engine.live());
        assertFalse(engine.ready());
    }

    @Test
    void workerJobWireCodecRoundTripsRequestAndReceipt() {
        ResolvedManifestId manifest = new ResolvedManifestId("manifest-worker-wire");
        WorkerJobRequest request = workerJobRequest(
                new WorkerJobId("job-worker-wire"),
                WorkerJobKind.CONTENT_VALIDATION,
                manifest,
                Instant.parse("2026-06-17T10:00:00Z"));

        WorkerJobRequest decodedRequest = WorkerJobWireCodec.decodeRequest(new ConsumerRecord<>(
                "worker.jobs",
                0,
                7,
                request.jobId().value(),
                WorkerJobWireCodec.encodeRequest(request)));

        assertEquals(request, decodedRequest);

        WorkerJobReceipt receipt = WorkerJobReceipt.accepted(
                request,
                new InstanceId("instance-worker-wire"),
                Duration.ofMillis(125),
                new WorkerJobResult("accepted", "object://worker-results/job-worker-wire"));

        assertEquals(receipt, WorkerJobWireCodec.decodeReceipt(WorkerJobWireCodec.encodeReceipt(receipt)));
    }

    @Test
    void authorityWorkerCatalogBuildsCoreAuthorityDomains() {
        AuthorityWorkerCatalog catalog = new AuthorityWorkerCatalog(new LocalAuthorityRuntimeBindings(), 1);

        assertEquals(
                AuthorityWorkerCatalog.authorityDomains(),
                catalog.workerBindings().stream().map(AuthorityWorkerBinding::authorityDomain).toList());
    }

    @Test
    void catalogBuiltSubjectWorkerProcessesCommandThroughRuntimeLoop() throws Exception {
        LocalAuthorityRuntimeBindings bindings = new LocalAuthorityRuntimeBindings();
        AuthorityWorkerCatalog catalog = new AuthorityWorkerCatalog(bindings, 19);
        SubjectId subject = new SubjectId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        bindings.enqueue(
                "subject",
                new AuthorityCommandDelivery<>(
                        registerSubjectCommand(subject),
                        new AuthorityOffset("cmd.subject", 0, 7)));
        AuthorityRuntimeServiceEngine engine = new AuthorityRuntimeServiceEngine(
                catalog.workerBindings(),
                Duration.ofMillis(10));

        try {
            engine.start();
            awaitTrue(
                    () -> !bindings.committedOffsets("subject").isEmpty(),
                    Duration.ofSeconds(2),
                    "catalog-built subject worker did not commit its offset");
        } finally {
            engine.close();
        }

        AuthorityRecord<SubjectState> stored = bindings
                .<SubjectState>storedRecord("subject", SubjectAuthority.aggregateId(subject))
                .orElseThrow();
        assertEquals(new Revision(1), stored.revision());
        assertEquals(19, stored.fencingEpoch());
        assertEquals(SubjectLifecycleStatus.ACTIVE, stored.state().current().orElseThrow().status());
        assertEquals(subject, stored.state().current().orElseThrow().subjectId());
        assertEquals(List.of(new AuthorityOffset("cmd.subject", 0, 0)), bindings.committedOffsets("subject"));
    }

    @Test
    void launcherStartsAsSeparateJavaProcessWithReadinessAndRuntimeProgressProbe() throws Exception {
        int probePort = freePort();
        ProcessBuilder builder = new ProcessBuilder(
                javaBinary(),
                "-cp",
                System.getProperty("java.class.path"),
                FulcrumLauncher.class.getName(),
                "--profile=single-machine",
                "--role=velocity-agent",
                "--mode=run",
                "--probe-host=127.0.0.1",
                "--probe-port=" + probePort,
                "--run-for=PT1S");
        builder.environment().putAll(allBindingsMap());

        Process process = builder.start();
        String readyBody = awaitRuntimeProgress(
                "http://127.0.0.1:" + probePort + "/ready",
                "velocity-agent",
                Duration.ofSeconds(5));
        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(readyBody.contains("\"ready\":true"));
        assertTrue(loopCountFor(readyBody, "velocity-agent") > 0, readyBody);
        assertTrue(exited, "launcher process did not exit");
        assertEquals(0, process.exitValue(), err);
        assertTrue(out.contains("Fulcrum runtime started"));
        assertTrue(out.contains("Fulcrum runtime stopped"));
        assertOnlyExpectedProcessStderr(err);
    }

    @Test
    void authorityServiceProcessesPresenceCommandAgainstExternalSubstrate() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createAuthorityTopics(stack.kafkaBootstrapServers());
            createAuthoritySchemas(stack);

            LaunchCommand command = LaunchCommand.parse(new String[]{
                    "--profile=single-machine",
                    "--role=authority-service",
                    "--mode=run",
                    "--probe-host=127.0.0.1",
                    "--probe-port=0"
            });
            LaunchPlan plan = RuntimeEntrypointRegistry.plan(command, Thread.currentThread().getContextClassLoader());
            RuntimeEnvironment environment = RuntimeEnvironment.of(authorityBindingsMap(stack));

            try (FulcrumRuntimeSupervisor supervisor = new FulcrumRuntimeSupervisor(
                    plan,
                    environment,
                    command.probeHost(),
                    command.probePort())) {
                supervisor.start();
                awaitRuntimeProgress(
                        "http://127.0.0.1:" + supervisor.probePort() + "/ready",
                        "authority-service",
                        Duration.ofSeconds(20));

                String subjectId = "11111111-1111-1111-1111-111111111111";
                String aggregateId = "subject:" + subjectId;
                sendPresenceCommand(stack.kafkaBootstrapServers(), presenceClaimCommand(subjectId));

                awaitTrue(
                        () -> "1".equals(stack.queryPostgresScalar("""
                                SELECT revision
                                FROM authority_records
                                WHERE aggregate_id = '%s';
                                """.formatted(sql(aggregateId)))),
                        Duration.ofSeconds(30),
                        "presence authority record was not stored");
                awaitTrue(
                        () -> "1".equals(stack.queryPostgresScalar("""
                                SELECT count(*)
                                FROM authority_decisions
                                WHERE command_id = 'command-presence-claim-external'
                                  AND source_topic = 'cmd.presence'
                                  AND status = 'ACCEPTED'
                                  AND revision = 1;
                                """)),
                        Duration.ofSeconds(30),
                        "presence authority decision was not recorded");
                awaitTrue(
                        () -> stack.queryCassandra("""
                                SELECT presence_id, owner_instance_id, lifecycle_status, revision
                                FROM fulcrum.presence_hot
                                WHERE subject_id = '%s';
                                """.formatted(cql(subjectId))).contains("presence-external-1"),
                        Duration.ofSeconds(30),
                        "presence hot projection was not written");

                String cacheKey = PresenceAuthority.cacheKey(new SubjectId(UUID.fromString(subjectId)));
                awaitTrue(
                        () -> stack.getValkey(cacheKey).contains("presenceId=presence-external-1"),
                        Duration.ofSeconds(30),
                        "presence cache entry was not written");
                awaitTrue(
                        () -> committedOffset(
                                stack.kafkaBootstrapServers(),
                                "fulcrum-authority-service-presence",
                                "cmd.presence",
                                0).orElse(-1L) == 1L,
                        Duration.ofSeconds(30),
                        "presence command offset was not committed");

                List<String> events = drainTopic(stack.kafkaBootstrapServers(), "evt.presence", 1);
                List<String> states = drainTopic(stack.kafkaBootstrapServers(), "state.presence", 1);
                List<String> responses = drainTopic(stack.kafkaBootstrapServers(), "rsp.presence", 1);

                assertTrue(events.getFirst().contains("change=CLAIMED"));
                assertTrue(states.getFirst().contains("presenceId=presence-external-1"));
                assertTrue(responses.getFirst().contains("status=ACCEPTED"));
                assertTrue(responses.getFirst().contains("ownerEpoch=1"));

                String registeredSubjectId = "22222222-2222-2222-2222-222222222222";
                String registeredAggregateId = "subject:" + registeredSubjectId;
                sendSubjectCommand(stack.kafkaBootstrapServers(), subjectRegisterCommand(registeredSubjectId));
                awaitTrue(
                        () -> "1".equals(stack.queryPostgresScalar("""
                                SELECT revision
                                FROM authority_records
                                WHERE aggregate_id = '%s';
                                """.formatted(sql(registeredAggregateId)))),
                        Duration.ofSeconds(30),
                        "subject authority record was not stored");
                awaitTrue(
                        () -> "1".equals(stack.queryPostgresScalar("""
                                SELECT count(*)
                                FROM authority_decisions
                                WHERE command_id = 'command-subject-register-external'
                                  AND source_topic = 'cmd.subject'
                                  AND status = 'ACCEPTED'
                                  AND revision = 1;
                                """)),
                        Duration.ofSeconds(30),
                        "subject authority decision was not recorded");
                awaitTrue(
                        () -> stack.queryCassandra("""
                                SELECT subject_id, lifecycle_status, revision
                                FROM fulcrum.subject_identity_hot
                                WHERE identity_provider = 'MINECRAFT_ACCOUNT'
                                  AND external_identity = '%s';
                                """.formatted(cql("minecraft:" + registeredSubjectId))).contains(registeredSubjectId),
                        Duration.ofSeconds(30),
                        "subject identity projection was not written");
                String subjectCacheKey = SubjectAuthority.cacheKey(new SubjectId(UUID.fromString(registeredSubjectId)));
                awaitTrue(
                        () -> stack.getValkey(subjectCacheKey).contains("externalIdentity=minecraft:" + registeredSubjectId),
                        Duration.ofSeconds(30),
                        "subject cache entry was not written");
                awaitTrue(
                        () -> committedOffset(
                                stack.kafkaBootstrapServers(),
                                "fulcrum-authority-service-subject",
                                "cmd.subject",
                                0).orElse(-1L) == 1L,
                        Duration.ofSeconds(30),
                        "subject command offset was not committed");
                List<String> subjectEvents = drainTopic(stack.kafkaBootstrapServers(), "evt.subject", 1);
                List<String> subjectStates = drainTopic(stack.kafkaBootstrapServers(), "state.subject", 1);
                List<String> subjectResponses = drainTopic(stack.kafkaBootstrapServers(), "rsp.subject", 1);

                assertTrue(subjectEvents.getFirst().contains("change=REGISTERED"));
                assertTrue(subjectStates.getFirst().contains("externalIdentity=minecraft:" + registeredSubjectId));
                assertTrue(subjectResponses.getFirst().contains("status=ACCEPTED"));

                String routeId = "route-lobby-external";
                String routeAggregateId = "route:" + routeId;
                sendRouteCommand(stack.kafkaBootstrapServers(), routeId, routeOpenCommand(routeId, registeredSubjectId));
                awaitTrue(
                        () -> "1".equals(stack.queryPostgresScalar("""
                                SELECT revision
                                FROM authority_records
                                WHERE aggregate_id = '%s';
                                """.formatted(sql(routeAggregateId)))),
                        Duration.ofSeconds(30),
                        "route authority record was not stored");
                awaitTrue(
                        () -> "1".equals(stack.queryPostgresScalar("""
                                SELECT count(*)
                                FROM authority_decisions
                                WHERE command_id = 'command-route-open-external'
                                  AND source_topic = 'cmd.route'
                                  AND status = 'ACCEPTED'
                                  AND revision = 1;
                                """)),
                        Duration.ofSeconds(30),
                        "route authority decision was not recorded");
                awaitTrue(
                        () -> stack.queryCassandra("""
                                SELECT subject_id, target_session_id, lifecycle_status, revision
                                FROM fulcrum.route_hot
                                WHERE route_id = '%s';
                                """.formatted(cql(routeId))).contains("PENDING"),
                        Duration.ofSeconds(30),
                        "route hot projection was not written");
                String routeCacheKey = RouteAuthority.cacheKey(new RouteId(routeId));
                awaitTrue(
                        () -> stack.getValkey(routeCacheKey).contains("targetSessionId=session-lobby-external"),
                        Duration.ofSeconds(30),
                        "route cache entry was not written");
                awaitTrue(
                        () -> committedOffset(
                                stack.kafkaBootstrapServers(),
                                "fulcrum-authority-service-route",
                                "cmd.route",
                                0).orElse(-1L) == 1L,
                        Duration.ofSeconds(30),
                        "route command offset was not committed");
                List<String> routeEvents = drainTopic(stack.kafkaBootstrapServers(), "evt.route", 1);
                List<String> routeStates = drainTopic(stack.kafkaBootstrapServers(), "state.route", 1);
                List<String> routeResponses = drainTopic(stack.kafkaBootstrapServers(), "rsp.route", 1);

                assertTrue(routeEvents.getFirst().contains("change=OPENED"));
                assertTrue(routeStates.getFirst().contains("targetInstanceId=instance-paper-external"));
                assertTrue(routeResponses.getFirst().contains("lifecycleStatus=PENDING"));

                String sessionId = "session-lobby-external";
                String sessionAggregateId = "session:" + sessionId;
                sendSessionCommand(stack.kafkaBootstrapServers(), sessionId, sessionOpenCommand(sessionId));
                awaitTrue(
                        () -> "1".equals(stack.queryPostgresScalar("""
                                SELECT revision
                                FROM authority_records
                                WHERE aggregate_id = '%s';
                                """.formatted(sql(sessionAggregateId)))),
                        Duration.ofSeconds(30),
                        "session authority record was not stored");
                awaitTrue(
                        () -> "1".equals(stack.queryPostgresScalar("""
                                SELECT count(*)
                                FROM authority_decisions
                                WHERE command_id = 'command-session-open-external'
                                  AND source_topic = 'cmd.session'
                                  AND status = 'ACCEPTED'
                                  AND revision = 1;
                                """)),
                        Duration.ofSeconds(30),
                        "session authority decision was not recorded");
                awaitTrue(
                        () -> stack.queryCassandra("""
                                SELECT owner_instance_id, lifecycle_status, revision
                                FROM fulcrum.session_hot
                                WHERE session_id = '%s';
                                """.formatted(cql(sessionId))).contains("PREPARING"),
                        Duration.ofSeconds(30),
                        "session hot projection was not written");
                String sessionCacheKey = SessionAuthority.cacheKey(new SessionId(sessionId));
                awaitTrue(
                        () -> stack.getValkey(sessionCacheKey).contains("ownerInstanceId=instance-paper-external"),
                        Duration.ofSeconds(30),
                        "session cache entry was not written");
                awaitTrue(
                        () -> committedOffset(
                                stack.kafkaBootstrapServers(),
                                "fulcrum-authority-service-session",
                                "cmd.session",
                                0).orElse(-1L) == 1L,
                        Duration.ofSeconds(30),
                        "session command offset was not committed");
                List<String> sessionEvents = drainTopic(stack.kafkaBootstrapServers(), "evt.session", 1);
                List<String> sessionStates = drainTopic(stack.kafkaBootstrapServers(), "state.session", 1);
                List<String> sessionResponses = drainTopic(stack.kafkaBootstrapServers(), "rsp.session", 1);

                assertTrue(sessionEvents.getFirst().contains("change=OPENED"));
                assertTrue(sessionStates.getFirst().contains("ownerInstanceId=instance-paper-external"));
                assertTrue(sessionResponses.getFirst().contains("lifecycleStatus=PREPARING"));

                ArtifactDigest artifactDigest = new ArtifactDigest(
                        "sha256",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
                String artifactAggregateId = ArtifactMetadataAuthority.aggregateId(artifactDigest).value();
                sendArtifactMetadataCommand(
                        stack.kafkaBootstrapServers(),
                        artifactAggregateId,
                        artifactPublishCommand(artifactDigest));
                awaitTrue(
                        () -> "1".equals(stack.queryPostgresScalar("""
                                SELECT revision
                                FROM authority_records
                                WHERE aggregate_id = '%s';
                                """.formatted(sql(artifactAggregateId)))),
                        Duration.ofSeconds(30),
                        "artifact metadata authority record was not stored");
                awaitTrue(
                        () -> "1".equals(stack.queryPostgresScalar("""
                                SELECT count(*)
                                FROM authority_decisions
                                WHERE command_id = 'command-artifact-publish-external'
                                  AND source_topic = 'cmd.artifact-metadata'
                                  AND status = 'ACCEPTED'
                                  AND revision = 1;
                                """)),
                        Duration.ofSeconds(30),
                        "artifact metadata authority decision was not recorded");
                awaitTrue(
                        () -> stack.queryCassandra("""
                                SELECT kind, content_address, revision
                                FROM fulcrum.artifact_metadata_hot
                                WHERE digest_algorithm = 'sha256'
                                  AND digest_value = '%s';
                                """.formatted(cql(artifactDigest.value()))).contains("CONTENT_PACK_ARTIFACT"),
                        Duration.ofSeconds(30),
                        "artifact metadata hot projection was not written");
                String artifactCacheKey = "artifact-metadata:" + artifactAggregateId;
                awaitTrue(
                        () -> stack.getValkey(artifactCacheKey).contains("contentAddress=object://content/lobby-pack"),
                        Duration.ofSeconds(30),
                        "artifact metadata cache entry was not written");
                awaitTrue(
                        () -> committedOffset(
                                stack.kafkaBootstrapServers(),
                                "fulcrum-authority-service-artifact-metadata",
                                "cmd.artifact-metadata",
                                0).orElse(-1L) == 1L,
                        Duration.ofSeconds(30),
                        "artifact metadata command offset was not committed");
                List<String> artifactEvents = drainTopic(stack.kafkaBootstrapServers(), "evt.artifact-metadata", 1);
                List<String> artifactStates = drainTopic(stack.kafkaBootstrapServers(), "state.artifact-metadata", 1);
                List<String> artifactResponses = drainTopic(stack.kafkaBootstrapServers(), "rsp.artifact-metadata", 1);

                assertTrue(artifactEvents.getFirst().contains("CONTENT_PACK_ARTIFACT"));
                assertTrue(artifactStates.getFirst().contains("producerPrincipal=principal-artifact-service"));
                assertTrue(artifactResponses.getFirst().contains("status=ACCEPTED"));
            }
        }
    }

    @Test
    void authorityServiceCatalogStaysDomainBlind() {
        assertEquals(
                List.of("subject", "presence", "route", "session", "artifact-metadata"),
                AuthorityWorkerCatalog.authorityDomains());
    }


    @Test
    void controllerServiceProcessesInstanceRegistryCommandAgainstExternalControlLog() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createControllerTopics(stack.kafkaBootstrapServers());

            LaunchCommand command = LaunchCommand.parse(new String[]{
                    "--profile=single-machine",
                    "--role=controller-service",
                    "--mode=run",
                    "--probe-host=127.0.0.1",
                    "--probe-port=0"
            });
            LaunchPlan plan = RuntimeEntrypointRegistry.plan(command, Thread.currentThread().getContextClassLoader());
            RuntimeEnvironment environment = RuntimeEnvironment.of(controllerBindingsMap(stack));

            try (FulcrumRuntimeSupervisor supervisor = new FulcrumRuntimeSupervisor(
                    plan,
                    environment,
                    command.probeHost(),
                    command.probePort())) {
                supervisor.start();
                awaitRuntimeProgress(
                        "http://127.0.0.1:" + supervisor.probePort() + "/ready",
                        "controller-service",
                        Duration.ofSeconds(20));

                InstanceId instanceId = new InstanceId("instance-controller-external");
                InstanceRegistryControlCommand<RegisterInstance> registerCommand = registerInstanceCommand(instanceId, 1);
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        ExternalInstanceRegistryControllerWorker.COMMAND_TOPIC,
                        ControlInstanceNames.aggregateId(instanceId).value(),
                        InstanceRegistryControlWireCodec.encodeRegisterCommand(registerCommand));

                awaitTrue(
                        () -> committedOffset(
                                stack.kafkaBootstrapServers(),
                                controllerServiceGroupIdForCommandTopic(
                                        ExternalInstanceRegistryControllerWorker.COMMAND_TOPIC),
                                ExternalInstanceRegistryControllerWorker.COMMAND_TOPIC,
                                0).orElse(-1L) == 1L,
                        Duration.ofSeconds(30),
                        "instance-registry control offset was not committed");

                List<String> events = drainTopic(
                        stack.kafkaBootstrapServers(),
                        ExternalInstanceRegistryControllerWorker.EVENT_TOPIC,
                        1);
                List<String> states = drainTopic(
                        stack.kafkaBootstrapServers(),
                        "ctrl.state.instance-registry",
                        1);
                List<String> responses = drainTopic(
                        stack.kafkaBootstrapServers(),
                        ExternalInstanceRegistryControllerWorker.RESPONSE_TOPIC,
                        1);

                assertTrue(events.getFirst().contains("ctrl.instance.registered"));
                assertTrue(states.getFirst().contains("instanceId=" + instanceId.value()));
                assertTrue(states.getFirst().contains("status=REGISTERED"));
                assertTrue(responses.getFirst().contains("accepted=true"));
            }
        }
    }

    @Test
    void controllerServiceProcessesRemainingDomainsAgainstExternalControlLog() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start();
                AllocatorFixture allocator = AllocatorFixture.responding(200, allocationResponse("paper"))) {
            createControllerTopics(stack.kafkaBootstrapServers());

            LaunchCommand command = LaunchCommand.parse(new String[]{
                    "--profile=single-machine",
                    "--role=controller-service",
                    "--mode=run",
                    "--probe-host=127.0.0.1",
                    "--probe-port=0"
            });
            LaunchPlan plan = RuntimeEntrypointRegistry.plan(command, Thread.currentThread().getContextClassLoader());
            Map<String, String> values = controllerBindingsMap(stack);
            values.put("FULCRUM_AGONES_ALLOCATOR_URL", allocator.uri().toString());
            RuntimeEnvironment environment = RuntimeEnvironment.of(values);

            try (FulcrumRuntimeSupervisor supervisor = new FulcrumRuntimeSupervisor(
                    plan,
                    environment,
                    command.probeHost(),
                    command.probePort())) {
                supervisor.start();
                awaitRuntimeProgress(
                        "http://127.0.0.1:" + supervisor.probePort() + "/ready",
                        "controller-service",
                        Duration.ofSeconds(20));

                RouteAttemptId routeAttempt = new RouteAttemptId("route-attempt-controller-external");
                SessionId session = new SessionId("session-controller-external");
                LifecycleTraceId lifecycleTrace = new LifecycleTraceId(trace().traceId());
                CapabilityScope scope = CapabilityScope.experience(new ExperienceId("experience-lobby"));
                SubmitQueueIntent queuePayload = submitQueueIntent(new QueueIntentId("queue-intent-controller-external"));
                FaultId fault = new FaultId("fault-controller-external");
                SubjectId placementSubject = new SubjectId(UUID.fromString("88888888-8888-8888-8888-888888888888"));
                PresenceId placementPresence = new PresenceId("presence-placement-external");
                SharedShardPlacementRequest placementRequest = sharedShardPlacementRequest(
                        "placement-attempt-controller-external",
                        placementSubject,
                        placementPresence,
                        new ResolvedManifestId("manifest-lobby-shared-shard"));
                SharedShardPlacementCandidate placementCandidate = sharedShardPlacementCandidate(
                        "instance-paper-placement-external",
                        "session-placement-external",
                        "slot-placement-external",
                        placementRequest.experience().poolId(),
                        placementRequest.experience().resolvedManifestId(),
                        InstanceRegistryStatus.READY,
                        7,
                        placementRequest.experience().hardCapacity(),
                        true);
                SessionId allocationSession = new SessionId("session-allocation-external");
                SharedShardAllocationRequest allocationRequest = sharedShardAllocationRequest(allocationSession);

                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.route-attempt",
                        ControlRouteNames.aggregateId(routeAttempt).value(),
                        ControlCommandWireCodec.encodeRouteAttemptRequest(requestRouteAttemptCommand(routeAttempt, 1)));
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.experience-session",
                        ControlLifecycleNames.sessionAggregateId(session).value(),
                        ControlCommandWireCodec.encodeExperienceSessionRequest(requestExperienceSessionCommand(session, 1)));
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.lifecycle-trace",
                        ControlLifecycleNames.traceAggregateId(lifecycleTrace).value(),
                        ControlCommandWireCodec.encodeLifecycleTraceRecord(recordLifecycleObservationCommand(lifecycleTrace, 1)));
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.capability-enablement",
                        ControlCapabilityNames.aggregateId(scope).value(),
                        ControlCommandWireCodec.encodeCapabilityEnablement(enableCapabilityCommand(scope, 1)));
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.queue-roster",
                        ControlQueueNames.aggregateId(queuePayload.partitionKey()).value(),
                        ControlCommandWireCodec.encodeQueueRosterSubmit(submitQueueIntentCommand(queuePayload, 1)));
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.fault",
                        ControlFaultNames.aggregateId(fault).value(),
                        ControlCommandWireCodec.encodeFaultRecord(recordFaultCommand(fault, 1)));
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.shared-shard-placement",
                        placementRequest.placementAttemptId(),
                        ControlCommandWireCodec.encodeSharedShardPlacementRequest(
                                placementRequest,
                                List.of(placementCandidate)));
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.shared-shard-allocation",
                        allocationSession.value(),
                        ControlCommandWireCodec.encodeSharedShardAllocationRequest(allocationRequest));

                for (String domain : List.of(
                        ControllerWorkerCatalog.ROUTE_ATTEMPT,
                        ControllerWorkerCatalog.EXPERIENCE_SESSION,
                        ControllerWorkerCatalog.LIFECYCLE_TRACE,
                        ControllerWorkerCatalog.CAPABILITY_ENABLEMENT,
                        ControllerWorkerCatalog.QUEUE_ROSTER,
                        ControllerWorkerCatalog.FAULT,
                        ControllerWorkerCatalog.SHARED_SHARD_PLACEMENT,
                        ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION)) {
                    awaitTrue(
                            () -> committedOffset(
                                    stack.kafkaBootstrapServers(),
                                    controllerServiceGroupId(domain),
                                    "ctrl.cmd." + domain,
                                    0).orElse(-1L) == 1L,
                            Duration.ofSeconds(30),
                            domain + " control offset was not committed");
                    assertTrue(drainTopic(stack.kafkaBootstrapServers(), "ctrl.rsp." + domain, 1)
                            .getFirst()
                            .contains("accepted=true"));
                }

                assertTrue(drainTopic(stack.kafkaBootstrapServers(), "ctrl.state.shared-shard-allocation", 2)
                        .stream()
                        .anyMatch(value -> value.contains("instanceId=instance-paper-agones-1")
                                && value.contains("minecraftHost=10.244.0.17")
                                && value.contains("minecraftPort=31565")));
                assertTrue(drainTopic(stack.kafkaBootstrapServers(), "ctrl.state.shared-shard-placement", 1)
                        .getFirst()
                        .contains("sessionId=session-placement-external"));
                assertTrue(allocator.requestBody().contains("\"sh.harold.fulcrum/session-id\":\""
                        + allocationSession.value() + "\""));
            }
        }
    }

    @Test
    void controllerServiceRequestsSharedShardAllocationWhenLobbyPlacementIsFull() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start();
                AllocatorFixture allocator = AllocatorFixture.responding(200, allocationResponse("paper"))) {
            createControllerTopics(stack.kafkaBootstrapServers());
            Map<String, String> values = controllerBindingsMap(stack);
            values.put("FULCRUM_AGONES_ALLOCATOR_URL", allocator.uri().toString());

            try (FulcrumRuntimeSupervisor controller = startControllerSupervisor(values)) {
                SharedShardPlacementRequest placementRequest = sharedShardPlacementRequest(
                        "placement-attempt-lobby-full",
                        new SubjectId(UUID.fromString("abababab-abab-abab-abab-abababababab")),
                        new PresenceId("presence-lobby-full"),
                        new ResolvedManifestId("manifest-lobby-shared-shard"));
                SharedShardPlacementCandidate fullLobby = sharedShardPlacementCandidate(
                        "instance-paper-full-lobby",
                        "session-full-lobby",
                        "slot-full-lobby",
                        placementRequest.experience().poolId(),
                        placementRequest.experience().resolvedManifestId(),
                        InstanceRegistryStatus.READY,
                        placementRequest.experience().hardCapacity(),
                        placementRequest.experience().hardCapacity(),
                        true);

                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.shared-shard-placement",
                        placementRequest.placementAttemptId(),
                        ControlCommandWireCodec.encodeSharedShardPlacementRequest(
                                placementRequest,
                                List.of(fullLobby)));

                awaitCommittedOffset(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.shared-shard-placement",
                        1,
                        "full shared-shard placement command was not committed");
                awaitCommittedOffset(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.shared-shard-allocation",
                        1,
                        "derived shared-shard allocation command was not committed");

                String derivedSessionId = "session-" + placementRequest.placementAttemptId();
                List<String> placementResponses = drainTopic(
                        stack.kafkaBootstrapServers(),
                        "ctrl.rsp.shared-shard-placement",
                        1);
                assertTrue(placementResponses.getFirst().contains("status=REQUEST_ALLOCATION"));
                assertTrue(placementResponses.getFirst().contains("sessionId=none"));

                List<String> allocationCommands = drainTopic(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.shared-shard-allocation",
                        1);
                assertTrue(allocationCommands.getFirst().contains("sessionId=" + derivedSessionId));
                assertTrue(allocationCommands.getFirst().contains("resolvedManifestId=manifest-lobby-shared-shard"));

                List<String> allocationStates = drainTopic(
                        stack.kafkaBootstrapServers(),
                        "ctrl.state.shared-shard-allocation",
                        3);
                assertTrue(allocationStates.stream().anyMatch(value ->
                        value.contains("sessionId=" + derivedSessionId)
                                && value.contains("minecraftHost=10.244.0.17")
                                && value.contains("minecraftPort=31565")));
                assertTrue(allocator.requestBody().contains("\"sh.harold.fulcrum/session-id\":\""
                        + derivedSessionId + "\""));
            }
        }
    }

    @Test
    void barePipeClaimsPresencePlacesSharedShardAndAcknowledgesRouteAgainstRunningServices() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createAuthorityTopics(stack.kafkaBootstrapServers());
            createAuthoritySchemas(stack);
            createControllerTopics(stack.kafkaBootstrapServers());

            try (FulcrumRuntimeSupervisor authority = startAuthoritySupervisor(authorityBindingsMap(stack));
                    FulcrumRuntimeSupervisor controller = startControllerSupervisor(controllerBindingsMap(stack))) {
                ResolvedManifestId manifest = new ResolvedManifestId("manifest-lobby-bare-pipe");
                PoolId pool = new PoolId("pool-lobby");
                InstanceId targetInstance = new InstanceId("instance-paper-lobby-bare-pipe");
                InstanceId proxyInstance = new InstanceId("instance-velocity-bare-pipe");
                SessionId session = new SessionId("session-lobby-bare-pipe");
                SlotId slot = new SlotId("slot-lobby-bare-pipe");
                List<BarePipeLogin> logins = List.of(
                        new BarePipeLogin(
                                new SubjectId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1")),
                                new PresenceId("presence-bare-pipe-a"),
                                new RouteId("route-bare-pipe-a"),
                                new RouteAttemptId("route-attempt-bare-pipe-a"),
                                "login-a"),
                        new BarePipeLogin(
                                new SubjectId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2")),
                                new PresenceId("presence-bare-pipe-b"),
                                new RouteId("route-bare-pipe-b"),
                                new RouteAttemptId("route-attempt-bare-pipe-b"),
                                "login-b"));

                for (int index = 0; index < logins.size(); index++) {
                    BarePipeLogin login = logins.get(index);
                    String subjectId = login.subject().value().toString();
                    sendPresenceCommand(
                            stack.kafkaBootstrapServers(),
                            subjectId,
                            presenceClaimCommand(
                                    subjectId,
                                    login.presence().value(),
                                    login.route().value(),
                                    session.value(),
                                    login.suffix(),
                                    "2026-06-17T00:00:0" + (index + 1) + "Z",
                                    "2026-06-17T00:01:0" + (index + 1) + "Z"));
                }
                awaitTrue(
                        () -> committedOffset(
                                stack.kafkaBootstrapServers(),
                                "fulcrum-authority-service-presence",
                                "cmd.presence",
                                0).orElse(-1L) >= logins.size(),
                        Duration.ofSeconds(60),
                        () -> barePipePresenceCommitFailure(stack, authority.probePort(), logins.size()));
                for (BarePipeLogin login : logins) {
                    awaitTrue(
                            () -> stack.queryCassandra("""
                                    SELECT presence_id, lifecycle_status, revision
                                    FROM fulcrum.presence_hot
                                    WHERE subject_id = '%s';
                                    """.formatted(cql(login.subject().value().toString()))).contains(login.presence().value()),
                            Duration.ofSeconds(60),
                            "bare-pipe Presence projection was not written for " + login.subject().value());
                }

                for (int index = 0; index < logins.size(); index++) {
                    BarePipeLogin login = logins.get(index);
                    SharedShardPlacementRequest placementRequest = sharedShardPlacementRequest(
                            "placement-attempt-" + login.suffix(),
                            login.subject(),
                            login.presence(),
                            manifest);
                    SharedShardPlacementCandidate candidate = sharedShardPlacementCandidate(
                            targetInstance.value(),
                            session.value(),
                            slot.value(),
                            pool,
                            manifest,
                            InstanceRegistryStatus.READY,
                            42 + index,
                            placementRequest.experience().hardCapacity(),
                            true);
                    sendControlCommand(
                            stack.kafkaBootstrapServers(),
                            "ctrl.cmd.shared-shard-placement",
                            placementRequest.placementAttemptId(),
                            ControlCommandWireCodec.encodeSharedShardPlacementRequest(
                                    placementRequest,
                                    List.of(candidate)));
                    awaitCommittedOffset(
                            stack.kafkaBootstrapServers(),
                            "ctrl.cmd.shared-shard-placement",
                            index + 1L,
                            "bare-pipe shared-shard placement command was not committed for " + login.suffix());
                }
                List<String> placementResponses = drainTopic(
                        stack.kafkaBootstrapServers(),
                        "ctrl.rsp.shared-shard-placement",
                        logins.size());
                assertEquals(logins.size(), placementResponses.size());
                assertTrue(placementResponses.stream().allMatch(value ->
                        value.contains("status=SELECTED_EXISTING_SESSION")
                                && value.contains("sessionId=" + session.value())
                                && value.contains("slotId=" + slot.value())
                                && value.contains("instanceId=" + targetInstance.value())));

                Instant routeStart = Instant.parse("2026-06-17T00:00:10Z");
                long expectedRouteOffset = 0;
                for (int index = 0; index < logins.size(); index++) {
                    BarePipeLogin login = logins.get(index);
                    List<RouteAttemptControlCommand<? extends RouteAttemptCommand>> commands = routeAckSequence(
                            login.routeAttempt(),
                            login.route(),
                            session,
                            slot,
                            login.subject(),
                            login.presence(),
                            proxyInstance,
                            targetInstance,
                            manifest,
                            login.suffix(),
                            routeStart.plusSeconds(index * 10L));
                    for (RouteAttemptControlCommand<? extends RouteAttemptCommand> routeCommand : commands) {
                        sendControlCommand(
                                stack.kafkaBootstrapServers(),
                                "ctrl.cmd.route-attempt",
                                ControlRouteNames.aggregateId(routeCommand.envelope().payload().routeAttemptId()).value(),
                                ControlCommandWireCodec.encodeRouteAttemptCommand(routeCommand));
                        expectedRouteOffset++;
                        awaitCommittedOffset(
                                stack.kafkaBootstrapServers(),
                                "ctrl.cmd.route-attempt",
                                expectedRouteOffset,
                                "bare-pipe route-attempt command was not committed for "
                                        + routeCommand.envelope().commandName().value()
                                        + " / " + login.suffix());
                    }
                }
                List<String> proxyRouteCommands = drainTopic(
                        stack.kafkaBootstrapServers(),
                        "host.velocity.routes",
                        logins.size());
                List<String> hostRouteCommands = drainTopic(
                        stack.kafkaBootstrapServers(),
                        "host.paper.commands",
                        logins.size());
                for (BarePipeLogin login : logins) {
                    assertTrue(proxyRouteCommands.stream().anyMatch(value ->
                                    value.startsWith("proxy.route")
                                            && value.contains("routeAttemptId=" + login.routeAttempt().value())
                                            && value.contains("routeId=" + login.route().value())
                                            && value.contains("subjectId=" + login.subject().value())
                                            && value.contains("sessionId=" + session.value())
                                            && value.contains("targetInstanceId=" + targetInstance.value())),
                            "proxy route command was not emitted for " + login.subject().value());
                    assertTrue(hostRouteCommands.stream().anyMatch(value ->
                                    value.startsWith("host.route.prepare")
                                            && value.contains("routeAttemptId=" + login.routeAttempt().value())
                                            && value.contains("routeId=" + login.route().value())
                                            && value.contains("sessionId=" + session.value())
                                            && value.contains("resolvedManifestId=" + manifest.value())),
                            "host route command was not emitted for " + login.subject().value());
                }
                List<String> routeStates = drainTopic(
                        stack.kafkaBootstrapServers(),
                        "ctrl.state.route-attempt",
                        logins.size() * 5);
                for (BarePipeLogin login : logins) {
                    assertTrue(routeStates.stream().anyMatch(value ->
                                    value.contains("routeAttemptId=" + login.routeAttempt().value())
                                            && value.contains("status=ACKED")
                                            && value.contains("sessionId=" + session.value())
                                            && value.contains("allocationSlotId=" + slot.value())),
                            "route attempt was not ACKED for " + login.subject().value());
                }
            }
        }
    }

    @Test
    void controllerServiceBuffersPaperAttachObservationUntilRouteAttemptIsIssuedToHost() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createControllerTopics(stack.kafkaBootstrapServers());

            try (FulcrumRuntimeSupervisor controller = startControllerSupervisor(controllerBindingsMap(stack))) {
                RouteAttemptId routeAttempt = new RouteAttemptId("route-attempt-paper-attach-observation");
                SubjectId subject = new SubjectId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
                RouteId route = new RouteId("route-velocity-login-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
                PresenceId presence = new PresenceId("presence-paper-attach-observation");
                SessionId session = new SessionId("session-paper-attach-observation");
                SlotId slot = new SlotId("slot-paper-attach-observation");
                PoolId pool = new PoolId("pool-lobby");
                InstanceId proxyInstance = new InstanceId("instance-velocity-attach-observation");
                InstanceId targetInstance = new InstanceId("instance-paper-attach-observation");
                ResolvedManifestId manifest = new ResolvedManifestId("manifest-paper-attach-observation");
                Instant routeStart = Instant.parse("2026-06-17T00:03:00Z");
                List<RouteAttemptControlCommand<? extends RouteAttemptCommand>> commands = routeAckSequence(
                        routeAttempt,
                        route,
                        session,
                        slot,
                        subject,
                        presence,
                        proxyInstance,
                        targetInstance,
                        manifest,
                        "paper-attach-observation",
                        routeStart);

                HostInstanceIdentity paperIdentity = new HostInstanceIdentity(
                        targetInstance,
                        HostInstanceKinds.PAPER,
                        pool,
                        new MachineRef("machine-paper-attach-observation"),
                        new PrincipalId("principal-paper-attach-observation"));
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "host.observation",
                        targetInstance.value(),
                        HostObservationWireCodec.encode(HostObservationFactory.sessionAttached(new HostSessionAttachment(
                                paperIdentity,
                                route,
                                subject,
                                session,
                                trace(),
                                routeStart.plusSeconds(3)))));
                awaitTrue(
                        () -> committedOffset(
                                stack.kafkaBootstrapServers(),
                                "fulcrum-controller-service-host-observation-route",
                                "host.observation",
                                0).orElse(-1L) >= 1L,
                        Duration.ofSeconds(30),
                        "Paper attach observation was not committed");

                for (int index = 0; index < 3; index++) {
                    RouteAttemptControlCommand<? extends RouteAttemptCommand> routeCommand = commands.get(index);
                    sendControlCommand(
                            stack.kafkaBootstrapServers(),
                            "ctrl.cmd.route-attempt",
                            ControlRouteNames.aggregateId(routeCommand.envelope().payload().routeAttemptId()).value(),
                            ControlCommandWireCodec.encodeRouteAttemptCommand(routeCommand));
                    awaitCommittedOffset(
                            stack.kafkaBootstrapServers(),
                            "ctrl.cmd.route-attempt",
                            index + 1L,
                            "route command was not committed before Paper attach observation replay");
                }
                awaitCommittedOffset(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.route-attempt",
                        5L,
                        "generated route acknowledgement commands were not processed");

                List<String> routeCommands = drainTopic(stack.kafkaBootstrapServers(), "ctrl.cmd.route-attempt", 5);
                assertTrue(routeCommands.stream().anyMatch(value ->
                                value.contains("commandName=ctrl.route.observe-host-attach")
                                        && value.contains("routeAttemptId=" + routeAttempt.value())
                                        && value.contains("expectedRevision=3")),
                        "ObserveHostAttach command was not generated from Paper attach observation");
                assertTrue(routeCommands.stream().anyMatch(value ->
                                value.contains("commandName=ctrl.route.acknowledge")
                                        && value.contains("routeAttemptId=" + routeAttempt.value())
                                        && value.contains("expectedRevision=4")),
                        "AcknowledgeRouteAttempt command was not generated from Paper attach observation");
                awaitCommittedOffset(
                        stack.kafkaBootstrapServers(),
                        "ctrl.cmd.lifecycle-trace",
                        2L,
                        "generated lifecycle trace commands were not processed");
                List<String> lifecycleCommands = drainTopic(stack.kafkaBootstrapServers(), "ctrl.cmd.lifecycle-trace", 2);
                List<RecordLifecycleObservation> lifecycleObservations = lifecycleCommands.stream()
                        .map(value -> ControlCommandWireCodec.decodeLifecycleTraceRecord(
                                new ConsumerRecord<>("ctrl.cmd.lifecycle-trace", 0, 0L, null, value)))
                        .map(command -> command.envelope().payload())
                        .toList();
                assertTrue(lifecycleObservations.stream().anyMatch(observation ->
                                observation.phase() == LifecyclePhase.HOST_ATTACH_OBSERVED
                                        && observation.traceId().value().equals(trace().traceId())
                                        && observation.aggregateId().equals(targetInstance.value())),
                        "HOST_ATTACH_OBSERVED lifecycle trace was not generated from Paper attach observation");
                assertTrue(lifecycleObservations.stream().anyMatch(observation ->
                                observation.phase() == LifecyclePhase.SESSION_ACTIVE
                                        && observation.traceId().value().equals(trace().traceId())
                                        && observation.aggregateId().equals(session.value())),
                        "SESSION_ACTIVE lifecycle trace was not generated from Paper attach observation");
                assertTrue(drainTopic(stack.kafkaBootstrapServers(), "ctrl.state.lifecycle-trace", 2)
                                .stream()
                                .anyMatch(value -> value.contains("traceId=" + trace().traceId())
                                        && value.contains("phase=HOST_ATTACH_OBSERVED")
                                        && value.contains("phase=SESSION_ACTIVE")
                                        && value.contains("sessionId=" + session.value())
                                        && value.contains("resolvedManifestId=" + manifest.value())),
                        "lifecycle trace state did not include host attach and Session active milestones");
                assertTrue(drainTopic(stack.kafkaBootstrapServers(), "ctrl.state.route-attempt", 5)
                                .stream()
                                .anyMatch(value -> value.contains("routeAttemptId=" + routeAttempt.value())
                                        && value.contains("status=ACKED")
                                        && value.contains("sessionId=" + session.value())),
                        "route attempt was not ACKED after Paper attach observation");
            }
        }
    }

    @Test
    void controllerServiceReplaysInstanceRegistryStateAfterRestart() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createControllerTopics(stack.kafkaBootstrapServers());
            Map<String, String> values = controllerBindingsMap(stack);
            InstanceId instanceId = new InstanceId("instance-controller-replay");
            String commandValue = InstanceRegistryControlWireCodec.encodeRegisterCommand(registerInstanceCommand(instanceId, 1));

            try (FulcrumRuntimeSupervisor supervisor = startControllerSupervisor(values)) {
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        ExternalInstanceRegistryControllerWorker.COMMAND_TOPIC,
                        ControlInstanceNames.aggregateId(instanceId).value(),
                        commandValue);
                awaitCommittedOffset(
                        stack.kafkaBootstrapServers(),
                        ExternalInstanceRegistryControllerWorker.COMMAND_TOPIC,
                        1,
                        "initial instance-registry command offset was not committed");
            }

            try (FulcrumRuntimeSupervisor supervisor = startControllerSupervisor(values)) {
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        ExternalInstanceRegistryControllerWorker.COMMAND_TOPIC,
                        ControlInstanceNames.aggregateId(instanceId).value(),
                        commandValue);
                awaitCommittedOffset(
                        stack.kafkaBootstrapServers(),
                        ExternalInstanceRegistryControllerWorker.COMMAND_TOPIC,
                        2,
                        "replayed instance-registry command offset was not committed");
            }

            List<String> responses = drainTopic(
                    stack.kafkaBootstrapServers(),
                    ExternalInstanceRegistryControllerWorker.RESPONSE_TOPIC,
                    2);
            assertTrue(responses.getFirst().contains("accepted=true"));
            assertTrue(responses.get(1).contains("accepted=true"));
            assertFalse(responses.get(1).contains("REVISION_MISMATCH"));
        }
    }

    @Test
    void controllerServiceReplaysRemainingDomainStateAfterRestart() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start();
                AllocatorFixture allocator = AllocatorFixture.responding(200, allocationResponse("paper"))) {
            createControllerTopics(stack.kafkaBootstrapServers());
            Map<String, String> values = controllerBindingsMap(stack);
            values.put("FULCRUM_AGONES_ALLOCATOR_URL", allocator.uri().toString());

            RouteAttemptId routeAttempt = new RouteAttemptId("route-attempt-controller-replay");
            SessionId session = new SessionId("session-controller-replay");
            LifecycleTraceId lifecycleTrace = new LifecycleTraceId(trace().traceId());
            CapabilityScope scope = CapabilityScope.experience(new ExperienceId("experience-lobby-replay"));
            SubmitQueueIntent queuePayload = submitQueueIntent(new QueueIntentId("queue-intent-controller-replay"));
            FaultId fault = new FaultId("fault-controller-replay");
            SharedShardPlacementRequest placementRequest = sharedShardPlacementRequest(
                    "placement-attempt-controller-replay",
                    new SubjectId(UUID.fromString("99999999-9999-9999-9999-999999999999")),
                    new PresenceId("presence-placement-replay"),
                    new ResolvedManifestId("manifest-lobby-shared-shard"));
            SharedShardPlacementCandidate placementCandidate = sharedShardPlacementCandidate(
                    "instance-paper-placement-replay",
                    "session-placement-replay",
                    "slot-placement-replay",
                    placementRequest.experience().poolId(),
                    placementRequest.experience().resolvedManifestId(),
                    InstanceRegistryStatus.READY,
                    9,
                    placementRequest.experience().hardCapacity(),
                    true);
            SessionId allocationSession = new SessionId("session-allocation-replay");
            SharedShardAllocationRequest allocationRequest = sharedShardAllocationRequest(allocationSession);
            List<ControlLogCommand> commands = remainingControllerCommands(
                    routeAttempt,
                    session,
                    lifecycleTrace,
                    scope,
                    queuePayload,
                    fault,
                    placementRequest,
                    placementCandidate,
                    allocationRequest);

            try (FulcrumRuntimeSupervisor supervisor = startControllerSupervisor(values)) {
                sendRemainingControllerCommands(stack.kafkaBootstrapServers(), commands);
                awaitRemainingControllerOffsets(stack.kafkaBootstrapServers(), 1);
            }

            try (FulcrumRuntimeSupervisor supervisor = startControllerSupervisor(values)) {
                sendRemainingControllerCommands(stack.kafkaBootstrapServers(), commands);
                awaitRemainingControllerOffsets(stack.kafkaBootstrapServers(), 2);
            }

            for (String domain : List.of(
                    ControllerWorkerCatalog.ROUTE_ATTEMPT,
                    ControllerWorkerCatalog.EXPERIENCE_SESSION,
                    ControllerWorkerCatalog.LIFECYCLE_TRACE,
                    ControllerWorkerCatalog.CAPABILITY_ENABLEMENT,
                    ControllerWorkerCatalog.QUEUE_ROSTER,
                    ControllerWorkerCatalog.FAULT)) {
                List<String> responses = drainTopic(stack.kafkaBootstrapServers(), "ctrl.rsp." + domain, 2);
                assertTrue(responses.getFirst().contains("accepted=true"));
                assertTrue(responses.get(1).contains("accepted=true"), domain + " replay response: " + responses);
                assertFalse(responses.get(1).contains("IDEMPOTENCY_CONFLICT"), domain + " replay response: " + responses);
            }

            List<String> placementResponses = drainTopic(
                    stack.kafkaBootstrapServers(),
                    "ctrl.rsp." + ControllerWorkerCatalog.SHARED_SHARD_PLACEMENT,
                    2);
            assertTrue(placementResponses.getFirst().contains("accepted=true"));
            assertTrue(placementResponses.get(1).contains("accepted=true"));
            assertTrue(placementResponses.get(1).contains("sessionId=session-placement-replay"));

            List<String> allocationResponses = drainTopic(
                    stack.kafkaBootstrapServers(),
                    "ctrl.rsp." + ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION,
                    2);
            assertTrue(allocationResponses.getFirst().contains("accepted=true"));
            assertTrue(allocationResponses.getFirst().contains("minecraftHost=10.244.0.17"));
            assertTrue(allocationResponses.getFirst().contains("minecraftPort=31565"));
            assertTrue(allocationResponses.get(1).contains("accepted=true"));
            assertTrue(allocationResponses.get(1).contains("minecraftHost=10.244.0.17"));
            assertTrue(allocationResponses.get(1).contains("minecraftPort=31565"));
            assertEquals(1, allocator.requestCount());
        }
    }

    @Test
    void workerAgentProcessesJobCommandAgainstExternalWorkerLog() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createWorkerTopics(stack.kafkaBootstrapServers());

            LaunchCommand command = LaunchCommand.parse(new String[]{
                    "--profile=single-machine",
                    "--role=worker-agent",
                    "--mode=run",
                    "--probe-host=127.0.0.1",
                    "--probe-port=0"
            });
            LaunchPlan plan = RuntimeEntrypointRegistry.plan(command, Thread.currentThread().getContextClassLoader());
            RuntimeEnvironment environment = RuntimeEnvironment.of(workerBindingsMap(stack));
            WorkerJobRequest request = workerJobRequest(
                    new WorkerJobId("job-worker-external"),
                    WorkerJobKind.CONTENT_VALIDATION,
                    new ResolvedManifestId("manifest-host-worker"),
                    Instant.now().minusMillis(100));

            try (FulcrumRuntimeSupervisor supervisor = new FulcrumRuntimeSupervisor(
                    plan,
                    environment,
                    command.probeHost(),
                    command.probePort())) {
                supervisor.start();
                awaitRuntimeProgress(
                        "http://127.0.0.1:" + supervisor.probePort() + "/ready",
                        "worker-agent",
                        Duration.ofSeconds(20));
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "worker.jobs",
                        request.jobId().value(),
                        WorkerJobWireCodec.encodeRequest(request));

                awaitTrue(
                        () -> committedOffset(
                                stack.kafkaBootstrapServers(),
                                ExternalWorkerJobWorker.GROUP_ID,
                                "worker.jobs",
                                0).orElse(-1L) == 1L,
                        Duration.ofSeconds(30),
                        "worker job offset was not committed");
            }

            try (FulcrumRuntimeSupervisor supervisor = new FulcrumRuntimeSupervisor(
                    plan,
                    environment,
                    command.probeHost(),
                    command.probePort())) {
                supervisor.start();
                awaitRuntimeProgress(
                        "http://127.0.0.1:" + supervisor.probePort() + "/ready",
                        "worker-agent",
                        Duration.ofSeconds(20));
                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "worker.jobs",
                        request.jobId().value(),
                        WorkerJobWireCodec.encodeRequest(request));

                awaitTrue(
                        () -> committedOffset(
                                stack.kafkaBootstrapServers(),
                                ExternalWorkerJobWorker.GROUP_ID,
                                "worker.jobs",
                                0).orElse(-1L) == 2L,
                        Duration.ofSeconds(30),
                        "replayed worker job offset was not committed");
            }

            List<String> results = drainTopic(stack.kafkaBootstrapServers(), "worker.results", 3);
            String result = results.getFirst();
            String outputRef = wireField(result, "outputRef");

            assertTrue(result.contains("status=ACCEPTED"));
            assertTrue(result.contains("accepted=true"));
            assertTrue(results.stream().anyMatch(value -> value.contains("recordType=worker-idempotency")));
            assertTrue(results.stream().anyMatch(value -> value.contains("status=REPLAYED")));
            assertTrue(outputRef.startsWith("object://worker-results/"));
            assertTrue(new LocalObjectStorageAdapter(tempDir.resolve("object-store"), "worker-results")
                    .exists(new ArtifactObjectAddress(outputRef)));
        }
    }

    @Test
    void velocityAgentConsumesProxyRouteCommandThroughBridgeAndPublishesRouteAcknowledgement() throws Exception {
        RouteId routeId = new RouteId("route-velocity-worker");
        SubjectId subject = new SubjectId(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        SessionId session = new SessionId("session-velocity-worker");
        InstanceId targetInstance = new InstanceId("instance-paper-velocity-worker");
        VelocityRouteTransfer transfer = new VelocityRouteTransfer(
                routeId,
                subject,
                session,
                targetInstance,
                Instant.parse("2026-06-17T00:00:04Z"));
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start();
                RouteBridgeFixture bridge = RouteBridgeFixture.responding(transfer)) {
            createVelocityTopics(stack.kafkaBootstrapServers());
            sendControlCommand(
                    stack.kafkaBootstrapServers(),
                    "ctrl.state.shared-shard-allocation",
                    "ctrl.state.shared-shard-allocation:" + session.value(),
                    sharedShardAllocationState(session, targetInstance));

            LaunchCommand command = LaunchCommand.parse(new String[]{
                    "--profile=single-machine",
                    "--role=velocity-agent",
                    "--mode=run",
                    "--probe-host=127.0.0.1",
                    "--probe-port=0"
            });
            LaunchPlan plan = RuntimeEntrypointRegistry.plan(command, Thread.currentThread().getContextClassLoader());
            RuntimeEnvironment environment = RuntimeEnvironment.of(velocityBindingsMap(stack, bridge.uri()));

            try (FulcrumRuntimeSupervisor supervisor = new FulcrumRuntimeSupervisor(
                    plan,
                    environment,
                    command.probeHost(),
                    command.probePort())) {
                supervisor.start();
                awaitRuntimeProgress(
                        "http://127.0.0.1:" + supervisor.probePort() + "/ready",
                        "velocity-agent",
                        Duration.ofSeconds(20));

                sendControlCommand(
                        stack.kafkaBootstrapServers(),
                        "host.velocity.routes",
                        "route-attempt-velocity-worker",
                        "proxy.route"
                                + "|routeAttemptId=route-attempt-velocity-worker"
                                + "|routeId=" + routeId.value()
                                + "|subjectId=" + subject.value()
                                + "|sessionId=" + session.value()
                                + "|targetInstanceId=" + targetInstance.value()
                                + "|traceId=trace-velocity-worker");

                awaitTrue(
                        () -> committedOffset(
                                stack.kafkaBootstrapServers(),
                                "fulcrum-velocity-agent",
                                "host.velocity.routes",
                                0).orElse(-1L) == 1L,
                        Duration.ofSeconds(30),
                        "velocity route command offset was not committed");
            }

            assertTrue(bridge.requestBody().contains("backendInstanceId=" + targetInstance.value()));
            assertTrue(bridge.requestBody().contains("backendHost=10.244.0.17"));
            assertTrue(bridge.requestBody().contains("backendPort=31565"));

            String routeCommand = drainTopic(stack.kafkaBootstrapServers(), "cmd.route", 1).getFirst();
            AuthorityCommand<RouteCommand> decoded = RouteAuthorityWireCodec.decodeCommand(
                    new ConsumerRecord<>("cmd.route", 0, 0, "route:" + routeId.value(), routeCommand));
            AcknowledgeRoute payload = assertInstanceOf(AcknowledgeRoute.class, decoded.envelope().payload());
            assertEquals(routeId, payload.routeId());
            assertEquals(subject, payload.subjectId());
            assertEquals(session, payload.targetSessionId());
            assertEquals(targetInstance, payload.targetInstanceId());
            assertEquals(transfer.acknowledgedAt(), payload.acknowledgedAt());
            assertEquals("principal-single-machine-velocity-agent", decoded.authenticatedPrincipal().value());
        }
    }

    @Test
    void velocityLoginGateScopeUsesNeutralLobbyScope() {
        assertEquals("lobby-login", allBindingsMap().get("FULCRUM_LOGIN_GATE_SCOPE"));
    }


    @Test
    void launcherStartsControllerServiceAsSeparateJavaProcessWithRuntimeProgressProbe() throws Exception {
        int probePort = freePort();
        ProcessBuilder builder = new ProcessBuilder(
                javaBinary(),
                "-cp",
                System.getProperty("java.class.path"),
                FulcrumLauncher.class.getName(),
                "--profile=single-machine",
                "--role=controller-service",
                "--mode=run",
                "--probe-host=127.0.0.1",
                "--probe-port=" + probePort,
                "--run-for=PT1S");
        builder.environment().putAll(allBindingsMap());

        Process process = builder.start();
        String readyBody = awaitRuntimeProgress(
                "http://127.0.0.1:" + probePort + "/ready",
                "controller-service",
                Duration.ofSeconds(5));
        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(readyBody.contains("\"ready\":true"));
        assertTrue(loopCountFor(readyBody, "controller-service") > 0, readyBody);
        assertTrue(exited, "launcher process did not exit");
        assertEquals(0, process.exitValue(), err);
        assertTrue(out.contains("Fulcrum runtime started"));
        assertTrue(out.contains("Fulcrum runtime stopped"));
        assertOnlyExpectedProcessStderr(err);
    }

    @Test
    void launcherStartsWorkerAgentAsSeparateJavaProcessWithRuntimeProgressProbe() throws Exception {
        int probePort = freePort();
        ProcessBuilder builder = new ProcessBuilder(
                javaBinary(),
                "-cp",
                System.getProperty("java.class.path"),
                FulcrumLauncher.class.getName(),
                "--profile=single-machine",
                "--role=worker-agent",
                "--mode=run",
                "--probe-host=127.0.0.1",
                "--probe-port=" + probePort,
                "--run-for=PT1S");
        builder.environment().putAll(allBindingsMap());

        Process process = builder.start();
        String readyBody = awaitRuntimeProgress(
                "http://127.0.0.1:" + probePort + "/ready",
                "worker-agent",
                Duration.ofSeconds(5));
        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(readyBody.contains("\"ready\":true"));
        assertTrue(loopCountFor(readyBody, "worker-agent") > 0, readyBody);
        assertTrue(exited, "launcher process did not exit");
        assertEquals(0, process.exitValue(), err);
        assertTrue(out.contains("Fulcrum runtime started"));
        assertTrue(out.contains("Fulcrum runtime stopped"));
        assertOnlyExpectedProcessStderr(err);
    }

    @Test
    void profileDescriptorsLoadFromDistributionClasspath() {
        for (DeploymentProfile profile : DeploymentProfile.values()) {
            ProfileDescriptor descriptor = profile.loadDescriptor(Thread.currentThread().getContextClassLoader());

            assertEquals(profile.id(), descriptor.profileId());
            assertEquals("fulcrum-v2-substrate", descriptor.semanticModel());
            assertEquals("fulcrum-step0-contracts", descriptor.contractSet());
            assertTrue(descriptor.resourcePath().endsWith(profile.id() + ".json"));
            if (profile == DeploymentProfile.SINGLE_MACHINE) {
                assertEquals(Optional.of(SingleMachineTier.IN_MEMORY), descriptor.defaultTier());
                assertEquals(
                        Set.of(SingleMachineTier.IN_MEMORY, SingleMachineTier.SLIM, SingleMachineTier.FULL_ENGINE),
                        descriptor.availableTiers());
            } else {
                assertTrue(descriptor.defaultTier().isEmpty());
                assertTrue(descriptor.availableTiers().isEmpty());
            }
        }
    }

    @Test
    void invalidRoleReturnsUsageError() {
        LaunchResult result = run(RuntimeEnvironment.of(Map.of()), "--role=nope");

        assertEquals(FulcrumLauncher.USAGE_ERROR, result.code());
        assertTrue(result.err().contains("Unknown launch role: nope"));
        assertTrue(result.err().contains("Usage: fulcrum"));
    }

    private CapabilityDescriptor registrationDescriptor() {
        return new CapabilityDescriptor(
                new CapabilityId("neutral-registration-backend"),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(new CapabilityAuthorityDeclaration("neutral.authority", "external-authority", 1)),
                List.of(),
                List.of(CapabilityScope.NETWORK));
    }

    private HostSecurityContext registrationSecurityContext() {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-neutral-registration-backend"),
                        "authority-backend",
                        new PoolId("pool-neutral-registration"),
                        new MachineRef("machine-neutral-registration"),
                        new PrincipalId("principal-neutral-registration-backend")),
                "service-account:neutral-registration-backend",
                HostCredentialScope.of(
                        new HostResourceGrant(
                                HostResourceFamily.AUTHORITY_DOMAIN,
                                HostAccessMode.PRODUCE,
                                "neutral.authority"),
                        new HostResourceGrant(
                                HostResourceFamily.RESOURCE_CLASS,
                                HostAccessMode.READ,
                                "external-authority")));
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
        values.put("FULCRUM_CASSANDRA_LOCAL_DATACENTER", "dc-test");
        values.put("FULCRUM_VALKEY_ENDPOINT", "localhost:6379");
        values.put("FULCRUM_CONTROL_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        values.put("FULCRUM_AGONES_ALLOCATOR_URL", "http://localhost:8000/gameserverallocation");
        values.put("FULCRUM_AGONES_NAMESPACE", "fulcrum-lobby");
        values.put("FULCRUM_CONTROL_STATE_TOPIC", "ctrl.state");
        values.put("FULCRUM_WORKER_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        values.put("FULCRUM_WORKER_JOB_TOPIC", "worker.jobs");
        values.put("FULCRUM_WORKER_RESULT_TOPIC", "worker.results");
        values.put("FULCRUM_WORKER_OBJECT_BUCKET", "worker-results");
        values.put("FULCRUM_OBJECT_STORE_ROOT", tempDir.resolve("object-store").toString());
        values.put("FULCRUM_PAPER_SERVER_ROOT", tempDir.resolve("paper").toString());
        values.put("FULCRUM_PAPER_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        values.put("FULCRUM_PAPER_AGONES_SDK_URL", "http://localhost:9358/");
        values.put("FULCRUM_PAPER_OBSERVATION_BRIDGE_URL", "http://127.0.0.1:18080/observations");
        values.put("FULCRUM_PAPER_CAPABILITY_BRIDGE_URL", "http://127.0.0.1:18083/capabilities");
        values.put("FULCRUM_PAPER_REWARD_BRIDGE_URL", "http://127.0.0.1:18084/rewards");
        values.put("FULCRUM_PAPER_EXPERIENCE_ID", "experience-lobby-runtime");
        values.put("FULCRUM_PAPER_SESSION_ID", "session-lobby-runtime");
        values.put("FULCRUM_PAPER_SLOT_ID", "slot-lobby-runtime");
        values.put("FULCRUM_PAPER_RESOLVED_MANIFEST_ID", "manifest-lobby-runtime");
        values.put("FULCRUM_PAPER_CODE_ARTIFACT_ID", "artifact-paper-code-runtime");
        values.put("FULCRUM_PAPER_WORLD_ARTIFACT_ID", "artifact-lobby-world-runtime");
        values.put("FULCRUM_PAPER_WORLD_ARTIFACT_DIGEST", "0".repeat(64));
        values.put("FULCRUM_PAPER_WORLD_ARTIFACT_COMPATIBILITY", "lobby-world-v1");
        values.put("FULCRUM_PAPER_SESSION_OWNER_TOKEN", "owner-token-lobby-runtime");
        values.put("FULCRUM_PAPER_SESSION_LEASE", "PT5M");
        values.put("FULCRUM_PAPER_HOST_RUNTIME_ABI", "paper-host-runtime-v1");
        values.put("FULCRUM_HOST_COMMAND_TOPIC", "host.paper.commands");
        values.put("FULCRUM_HOST_OBSERVATION_TOPIC", "host.observation");
        values.put("FULCRUM_VELOCITY_SERVER_ROOT", tempDir.resolve("velocity").toString());
        values.put("FULCRUM_VELOCITY_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        values.put("FULCRUM_VELOCITY_ROUTE_BRIDGE_URL", "http://127.0.0.1:18081/routes");
        values.put("FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL", "http://127.0.0.1:18082/login-gate");
        values.put("FULCRUM_VELOCITY_ROUTE_COMMAND_TOPIC", "host.velocity.routes");
        values.put("FULCRUM_ROUTE_COMMAND_TOPIC", "cmd.route");
        values.put("FULCRUM_QUEUE_ROSTER_COMMAND_TOPIC", "ctrl.cmd.queue-roster");
        values.put("FULCRUM_PRESENCE_COMMAND_TOPIC", "cmd.presence");
        values.put("FULCRUM_SHARED_SHARD_PLACEMENT_COMMAND_TOPIC", "ctrl.cmd.shared-shard-placement");
        values.put("FULCRUM_ROUTE_ATTEMPT_COMMAND_TOPIC", "ctrl.cmd.route-attempt");
        values.put("FULCRUM_LIFECYCLE_TRACE_COMMAND_TOPIC", "ctrl.cmd.lifecycle-trace");
        values.put("FULCRUM_SHARED_SHARD_ALLOCATION_STATE_TOPIC", "ctrl.state.shared-shard-allocation");
        values.put("FULCRUM_LOBBY_EXPERIENCE_ID", "experience-lobby");
        values.put("FULCRUM_LOBBY_POOL_ID", "pool-lobby");
        values.put("FULCRUM_LOBBY_AGONES_FLEET_NAME", "fulcrum-lobby-paper");
        values.put("FULCRUM_LOBBY_TARGET_CAPACITY", "75");
        values.put("FULCRUM_LOBBY_HARD_CAPACITY", "150");
        values.put("FULCRUM_LOBBY_RESOLVED_MANIFEST_ID", "manifest-lobby-bedrock-v1");
        values.put("FULCRUM_LOBBY_CAPABILITY_SCOPE_FINGERPRINT", "capability-scope-lobby");
        values.put("FULCRUM_LOGIN_GATE_SCOPE", "lobby-login");
        values.put("FULCRUM_VELOCITY_PRESENCE_LEASE", "PT5M");
        values.put("FULCRUM_MACHINE_REF", "machine-test");
        return values;
    }

    private Map<String, String> authorityBindingsMap(FulcrumSubstrateStack stack) {
        Map<String, String> values = new HashMap<>(allBindingsMap());
        values.put("FULCRUM_KAFKA_BOOTSTRAP_SERVERS", plainBootstrapServers(stack.kafkaBootstrapServers()));
        values.put("FULCRUM_POSTGRES_JDBC_URL", stack.postgresJdbcUrl());
        values.put("FULCRUM_POSTGRES_USERNAME", stack.postgresUsername());
        values.put("FULCRUM_POSTGRES_PASSWORD", stack.postgresPassword());
        values.put("FULCRUM_CASSANDRA_CONTACT_POINTS", stack.cassandraHost() + ":" + stack.cassandraPort());
        values.put("FULCRUM_CASSANDRA_LOCAL_DATACENTER", "datacenter1");
        values.put("FULCRUM_VALKEY_ENDPOINT", stack.valkeyEndpoint());
        return values;
    }

    private Map<String, String> controllerBindingsMap(FulcrumSubstrateStack stack) {
        Map<String, String> values = new HashMap<>(allBindingsMap());
        values.put("FULCRUM_CONTROL_KAFKA_BOOTSTRAP_SERVERS", plainBootstrapServers(stack.kafkaBootstrapServers()));
        values.put("FULCRUM_CONTROL_STATE_TOPIC", "ctrl.state");
        return values;
    }

    private Map<String, String> workerBindingsMap(FulcrumSubstrateStack stack) {
        Map<String, String> values = new HashMap<>(allBindingsMap());
        values.put("FULCRUM_WORKER_KAFKA_BOOTSTRAP_SERVERS", plainBootstrapServers(stack.kafkaBootstrapServers()));
        return values;
    }

    private Map<String, String> paperBindingsMap(FulcrumSubstrateStack stack) throws IOException {
        Map<String, String> values = new HashMap<>(allBindingsMap());
        values.put("FULCRUM_PAPER_KAFKA_BOOTSTRAP_SERVERS", plainBootstrapServers(stack.kafkaBootstrapServers()));
        values.put("FULCRUM_PAPER_CAPABILITY_BRIDGE_URL", "http://127.0.0.1:" + freePort() + "/capabilities");
        values.put("FULCRUM_PAPER_REWARD_BRIDGE_URL", "http://127.0.0.1:" + freePort() + "/rewards");
        values.put("FULCRUM_VALKEY_ENDPOINT", stack.valkeyEndpoint());
        return values;
    }

    private Map<String, String> velocityBindingsMap(FulcrumSubstrateStack stack, URI routeBridgeUrl) throws IOException {
        return velocityBindingsMap(
                stack,
                routeBridgeUrl,
                URI.create("http://127.0.0.1:" + freePort() + "/login-gate"));
    }

    private Map<String, String> velocityBindingsMap(
            FulcrumSubstrateStack stack,
            URI routeBridgeUrl,
            URI loginGateBridgeUrl) {
        Map<String, String> values = new HashMap<>(allBindingsMap());
        values.put("FULCRUM_VELOCITY_KAFKA_BOOTSTRAP_SERVERS", plainBootstrapServers(stack.kafkaBootstrapServers()));
        values.put("FULCRUM_VELOCITY_ROUTE_BRIDGE_URL", routeBridgeUrl.toString());
        values.put("FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL", loginGateBridgeUrl.toString());
        values.put("FULCRUM_VALKEY_ENDPOINT", stack.valkeyEndpoint());
        return values;
    }

    private static void createAuthorityTopics(String bootstrapServers) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            List<NewTopic> topics = new ArrayList<>();
            for (String domain : AuthorityWorkerCatalog.authorityDomains()) {
                topics.add(new NewTopic("cmd." + domain, 1, (short) 1));
                topics.add(new NewTopic("evt." + domain, 1, (short) 1));
                topics.add(new NewTopic("state." + domain, 1, (short) 1));
                topics.add(new NewTopic("rsp." + domain, 1, (short) 1));
            }
            admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
        }
    }

    private static void createControllerTopics(String bootstrapServers) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            List<NewTopic> topics = new ArrayList<>();
            for (String domain : ControllerWorkerCatalog.controllerDomains()) {
                topics.add(new NewTopic("ctrl.cmd." + domain, 1, (short) 1));
                topics.add(new NewTopic("ctrl.evt." + domain, 1, (short) 1));
                topics.add(new NewTopic("ctrl.state." + domain, 1, (short) 1));
                topics.add(new NewTopic("ctrl.rsp." + domain, 1, (short) 1));
            }
            topics.add(new NewTopic("host.paper.commands", 1, (short) 1));
            topics.add(new NewTopic("host.observation", 1, (short) 1));
            topics.add(new NewTopic("host.velocity.routes", 1, (short) 1));
            admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
        }
    }

    private static void createWorkerTopics(String bootstrapServers) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            admin.createTopics(List.of(
                    new NewTopic("worker.jobs", 1, (short) 1),
                    new NewTopic("worker.results", 1, (short) 1)
            )).all().get(30, TimeUnit.SECONDS);
        }
    }

    private static void createVelocityTopics(String bootstrapServers) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            admin.createTopics(List.of(
                    new NewTopic("host.velocity.routes", 1, (short) 1),
                    new NewTopic("cmd.route", 1, (short) 1),
                    new NewTopic("cmd.presence", 1, (short) 1),
                    new NewTopic("ctrl.cmd.queue-roster", 1, (short) 1),
                    new NewTopic("ctrl.cmd.shared-shard-placement", 1, (short) 1),
                    new NewTopic("ctrl.cmd.route-attempt", 1, (short) 1),
                    new NewTopic("ctrl.cmd.lifecycle-trace", 1, (short) 1),
                    new NewTopic("ctrl.state.shared-shard-allocation", 1, (short) 1)
            )).all().get(30, TimeUnit.SECONDS);
        }
    }

    private static void createAuthoritySchemas(FulcrumSubstrateStack stack) {
        stack.executePostgres("""
                CREATE TABLE authority_records (
                    aggregate_id TEXT PRIMARY KEY,
                    revision BIGINT NOT NULL,
                    fencing_epoch BIGINT NOT NULL,
                    state_payload TEXT NOT NULL
                );
                CREATE TABLE authority_decisions (
                    command_id TEXT PRIMARY KEY,
                    aggregate_id TEXT NOT NULL,
                    source_topic TEXT NOT NULL,
                    source_partition INTEGER NOT NULL,
                    source_offset BIGINT NOT NULL,
                    status TEXT NOT NULL,
                    rejection_reason TEXT NOT NULL,
                    revision BIGINT NOT NULL,
                    replayed BOOLEAN NOT NULL,
                    trace_id TEXT NOT NULL,
                    decision_payload TEXT NOT NULL
                );
                """);
        stack.executeCassandra("""
                CREATE KEYSPACE IF NOT EXISTS fulcrum
                WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

                CREATE TABLE IF NOT EXISTS fulcrum.presence_hot (
                    subject_id text PRIMARY KEY,
                    presence_id text,
                    owner_instance_id text,
                    owner_token text,
                    owner_epoch bigint,
                    lifecycle_status text,
                    session_id text,
                    route_id text,
                    observed_at text,
                    expires_at text,
                    revision bigint
                );

                CREATE TABLE IF NOT EXISTS fulcrum.subject_identity_hot (
                    identity_provider text,
                    external_identity text,
                    subject_id text,
                    lifecycle_status text,
                    registered_by text,
                    registered_at text,
                    retired_by text,
                    retired_at text,
                    retire_reason text,
                    revision bigint,
                    PRIMARY KEY ((identity_provider), external_identity)
                );

                CREATE TABLE IF NOT EXISTS fulcrum.route_hot (
                    route_id text PRIMARY KEY,
                    subject_id text,
                    target_session_id text,
                    target_instance_id text,
                    lifecycle_status text,
                    requested_at text,
                    expires_at text,
                    completed_at text,
                    revision bigint
                );

                CREATE TABLE IF NOT EXISTS fulcrum.session_hot (
                    session_id text PRIMARY KEY,
                    experience_id text,
                    slot_id text,
                    owner_instance_id text,
                    owner_token text,
                    owner_epoch bigint,
                    resolved_manifest_id text,
                    lifecycle_status text,
                    opened_at text,
                    lease_expires_at text,
                    activated_at text,
                    closed_at text,
                    close_reason text,
                    revision bigint
                );

                CREATE TABLE IF NOT EXISTS fulcrum.artifact_metadata_hot (
                    digest_algorithm text,
                    digest_value text,
                    kind text,
                    byte_length bigint,
                    content_address text,
                    producer_principal text,
                    provenance text,
                    published_at text,
                    revision bigint,
                    PRIMARY KEY ((digest_algorithm), digest_value)
                );

                """);
    }

    private static void sendPresenceCommand(String bootstrapServers, String payload) throws Exception {
        sendPresenceCommand(
                bootstrapServers,
                "11111111-1111-1111-1111-111111111111",
                payload);
    }

    private static void sendPresenceCommand(String bootstrapServers, String subjectId, String payload) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>("cmd.presence", "subject:" + subjectId, payload))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    private static void sendSubjectCommand(String bootstrapServers, String payload) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>("cmd.subject", "subject:22222222-2222-2222-2222-222222222222", payload))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    private static void sendRouteCommand(String bootstrapServers, String routeId, String payload) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>("cmd.route", "route:" + routeId, payload))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    private static void sendSessionCommand(String bootstrapServers, String sessionId, String payload) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>("cmd.session", "session:" + sessionId, payload))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    private static void sendArtifactMetadataCommand(
            String bootstrapServers,
            String aggregateId,
            String payload) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>("cmd.artifact-metadata", aggregateId, payload))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    private static void sendAuthorityCommand(
            String bootstrapServers,
            String topic,
            String aggregateId,
            String payload) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>(topic, aggregateId, payload))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    private static void sendControlCommand(
            String bootstrapServers,
            String topic,
            String key,
            String payload) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>(topic, key, payload))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    private static String presenceClaimCommand(String subjectId) {
        return presenceClaimCommand(
                subjectId,
                "presence-external-1",
                "route-lobby-external",
                "session-lobby-external",
                "external",
                "2026-06-17T00:00:01Z",
                "2026-06-17T00:01:00Z");
    }

    private static String presenceClaimCommand(
            String subjectId,
            String presenceId,
            String routeId,
            String sessionId,
            String suffix,
            String observedAt,
            String expiresAt) {
        return "commandName=claim-presence"
                + "\ncommandId=command-presence-claim-" + suffix
                + "\nidempotencyKey=idempotency-presence-claim-" + suffix
                + "\ndeclaredPrincipalId=principal-velocity-edge"
                + "\nauthenticatedPrincipalId=principal-velocity-edge"
                + "\nsubjectId=" + subjectId
                + "\npresenceId=" + presenceId
                + "\nownerInstanceId=instance-velocity-external"
                + "\nownerToken=owner-token-" + suffix
                + "\nsessionId=" + sessionId
                + "\nrouteId=" + routeId
                + "\nobservedAt=" + observedAt
                + "\nexpiresAt=" + expiresAt
                + "\nreceivedAt=2026-06-17T00:00:02Z"
                + "\nrequestedAt=2026-06-17T00:00:00Z"
                + "\nfencingEpoch=1"
                + "\nexpectedRevision=0"
                + "\npayloadFingerprint=claim-presence-" + suffix + ":" + subjectId + ":" + presenceId
                + "\ntraceId=trace-presence-" + suffix
                + "\nspanId=span-presence-" + suffix
                + "\noriginService=velocity-agent"
                + "\noriginInstanceId=instance-velocity-external";
    }

    private static String subjectRegisterCommand(String subjectId) {
        return "commandName=register-subject"
                + "\ncommandId=command-subject-register-external"
                + "\nidempotencyKey=idempotency-subject-register-external"
                + "\nprincipalId=principal-identity-service"
                + "\nauthenticatedPrincipal=principal-identity-service"
                + "\naggregateId=subject:" + subjectId
                + "\ncontractName=subject"
                + "\nsubjectId=" + subjectId
                + "\nidentityProvider=MINECRAFT_ACCOUNT"
                + "\nexternalIdentity=minecraft:" + subjectId
                + "\nregisteredAt=2026-06-17T00:00:03Z"
                + "\nreceivedAt=2026-06-17T00:00:04Z"
                + "\ntraceCreatedAt=2026-06-17T00:00:02Z"
                + "\nfencingEpoch=1"
                + "\nexpectedRevision=0"
                + "\npayloadFingerprint=register-subject-external:" + subjectId
                + "\ntraceId=trace-subject-external"
                + "\nspanId=span-subject-external"
                + "\noriginService=identity-service"
                + "\noriginInstanceId=instance-identity-external";
    }

    private static String routeOpenCommand(String routeId, String subjectId) {
        return "commandName=open-route"
                + "\ncommandId=command-route-open-external"
                + "\nidempotencyKey=idempotency-route-open-external"
                + "\nprincipalId=principal-route-controller"
                + "\nauthenticatedPrincipal=principal-route-controller"
                + "\naggregateId=route:" + routeId
                + "\ncontractName=route"
                + "\nrouteId=" + routeId
                + "\nsubjectId=" + subjectId
                + "\ntargetSessionId=session-lobby-external"
                + "\ntargetInstanceId=instance-paper-external"
                + "\nrequestedAt=2026-06-17T00:00:05Z"
                + "\nexpiresAt=2026-06-17T00:01:05Z"
                + "\nreceivedAt=2026-06-17T00:00:06Z"
                + "\nfencingEpoch=1"
                + "\nexpectedRevision=0"
                + "\npayloadFingerprint=open-route-external:" + routeId
                + "\ntraceId=trace-route-external"
                + "\nspanId=span-route-external"
                + "\noriginService=route-controller"
                + "\noriginInstanceId=instance-route-controller-external";
    }

    private static String sessionOpenCommand(String sessionId) {
        return "commandName=open-session"
                + "\ncommandId=command-session-open-external"
                + "\nidempotencyKey=idempotency-session-open-external"
                + "\nprincipalId=principal-session-controller"
                + "\nauthenticatedPrincipal=principal-session-controller"
                + "\naggregateId=session:" + sessionId
                + "\ncontractName=session"
                + "\nsessionId=" + sessionId
                + "\nexperienceId=experience-lobby-external"
                + "\nslotId=slot-lobby-external"
                + "\nownerInstanceId=instance-paper-external"
                + "\nownerToken=session-owner-token-external"
                + "\nresolvedManifestId=manifest-lobby-external"
                + "\nopenedAt=2026-06-17T00:00:07Z"
                + "\nleaseExpiresAt=2026-06-17T00:05:00Z"
                + "\nreceivedAt=2026-06-17T00:00:08Z"
                + "\nfencingEpoch=1"
                + "\nexpectedRevision=0"
                + "\npayloadFingerprint=open-session-external:" + sessionId
                + "\ntraceId=trace-session-external"
                + "\nspanId=span-session-external"
                + "\noriginService=session-controller"
                + "\noriginInstanceId=instance-session-controller-external";
    }

    private static String artifactPublishCommand(ArtifactDigest digest) {
        return "commandName=publish-artifact-metadata"
                + "\ncommandId=command-artifact-publish-external"
                + "\nidempotencyKey=idempotency-artifact-publish-external"
                + "\nprincipalId=principal-artifact-service"
                + "\nauthenticatedPrincipal=principal-artifact-service"
                + "\naggregateId=" + ArtifactMetadataAuthority.aggregateId(digest).value()
                + "\ncontractName=artifact-metadata"
                + "\ndigestAlgorithm=" + digest.algorithm()
                + "\ndigestValue=" + digest.value()
                + "\nkind=CONTENT_PACK_ARTIFACT"
                + "\nbyteLength=4096"
                + "\ncontentAddress=object://content/lobby-pack"
                + "\nprovenance=build:lobby-pack"
                + "\nreceivedAt=2026-06-17T00:00:10Z"
                + "\ntraceCreatedAt=2026-06-17T00:00:09Z"
                + "\nfencingEpoch=1"
                + "\nexpectedRevision=0"
                + "\npayloadFingerprint=publish-artifact-external:" + digest.value()
                + "\ntraceId=trace-artifact-external"
                + "\nspanId=span-artifact-external"
                + "\noriginService=artifact-service"
                + "\noriginInstanceId=instance-artifact-service-external";
    }

    private static List<String> drainTopic(String bootstrapServers, String topic, int expectedMinimum) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, topic + "-drain-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "2000",
                ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        try {
            consumer.subscribe(List.of(topic));
            List<String> values = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && values.size() < expectedMinimum) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    values.add(record.value());
                }
            }
            return values;
        } finally {
            consumer.close(Duration.ofSeconds(2));
        }
    }

    private static Optional<Long> committedOffset(
            String bootstrapServers,
            String groupId,
            String topic,
            int partition) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin
                    .listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(30, TimeUnit.SECONDS);
            OffsetAndMetadata offset = offsets.get(new TopicPartition(topic, partition));
            return offset == null ? Optional.empty() : Optional.of(offset.offset());
        }
    }

    private static void awaitCommittedOffset(
            String bootstrapServers,
            String topic,
            long expectedOffset,
            String failureMessage) throws Exception {
        awaitTrue(
                () -> committedOffset(
                        bootstrapServers,
                        controllerServiceGroupIdForCommandTopic(topic),
                        topic,
                        0).orElse(-1L) >= expectedOffset,
                Duration.ofSeconds(30),
                failureMessage);
    }

    private static String controllerServiceGroupIdForCommandTopic(String topic) {
        String prefix = "ctrl.cmd.";
        if (!topic.startsWith(prefix)) {
            throw new IllegalArgumentException("controller command topic must start with " + prefix + ": " + topic);
        }
        return controllerServiceGroupId(topic.substring(prefix.length()));
    }

    private static String controllerServiceGroupId(String domain) {
        return "fulcrum-controller-service-" + domain;
    }

    private static void awaitRemainingControllerOffsets(String bootstrapServers, long expectedOffset) throws Exception {
        for (String domain : List.of(
                ControllerWorkerCatalog.ROUTE_ATTEMPT,
                ControllerWorkerCatalog.EXPERIENCE_SESSION,
                ControllerWorkerCatalog.LIFECYCLE_TRACE,
                ControllerWorkerCatalog.CAPABILITY_ENABLEMENT,
                ControllerWorkerCatalog.QUEUE_ROSTER,
                ControllerWorkerCatalog.FAULT,
                ControllerWorkerCatalog.SHARED_SHARD_PLACEMENT,
                ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION)) {
            awaitCommittedOffset(
                    bootstrapServers,
                    "ctrl.cmd." + domain,
                    expectedOffset,
                    domain + " control offset was not committed to " + expectedOffset);
        }
    }

    private static FulcrumRuntimeSupervisor startControllerSupervisor(Map<String, String> values) throws Exception {
        LaunchCommand command = LaunchCommand.parse(new String[]{
                "--profile=single-machine",
                "--role=controller-service",
                "--mode=run",
                "--probe-host=127.0.0.1",
                "--probe-port=0"
        });
        LaunchPlan plan = RuntimeEntrypointRegistry.plan(command, Thread.currentThread().getContextClassLoader());
        FulcrumRuntimeSupervisor supervisor = new FulcrumRuntimeSupervisor(
                plan,
                RuntimeEnvironment.of(new HashMap<>(values)),
                command.probeHost(),
                command.probePort());
        supervisor.start();
        awaitRuntimeProgress(
                "http://127.0.0.1:" + supervisor.probePort() + "/ready",
                "controller-service",
                Duration.ofSeconds(20));
        return supervisor;
    }

    private static FulcrumRuntimeSupervisor startAuthoritySupervisor(Map<String, String> values) throws Exception {
        LaunchCommand command = LaunchCommand.parse(new String[]{
                "--profile=single-machine",
                "--role=authority-service",
                "--mode=run",
                "--probe-host=127.0.0.1",
                "--probe-port=0"
        });
        LaunchPlan plan = RuntimeEntrypointRegistry.plan(command, Thread.currentThread().getContextClassLoader());
        FulcrumRuntimeSupervisor supervisor = new FulcrumRuntimeSupervisor(
                plan,
                RuntimeEnvironment.of(new HashMap<>(values)),
                command.probeHost(),
                command.probePort());
        supervisor.start();
        awaitRuntimeProgress(
                "http://127.0.0.1:" + supervisor.probePort() + "/ready",
                "authority-service",
                Duration.ofSeconds(20));
        return supervisor;
    }

    private static List<ControlLogCommand> remainingControllerCommands(
            RouteAttemptId routeAttempt,
            SessionId session,
            LifecycleTraceId lifecycleTrace,
            CapabilityScope scope,
            SubmitQueueIntent queuePayload,
            FaultId fault,
            SharedShardPlacementRequest placementRequest,
            SharedShardPlacementCandidate placementCandidate,
            SharedShardAllocationRequest allocationRequest) {
        return List.of(
                new ControlLogCommand(
                        ControllerWorkerCatalog.ROUTE_ATTEMPT,
                        ControlRouteNames.aggregateId(routeAttempt).value(),
                        ControlCommandWireCodec.encodeRouteAttemptRequest(requestRouteAttemptCommand(routeAttempt, 1))),
                new ControlLogCommand(
                        ControllerWorkerCatalog.EXPERIENCE_SESSION,
                        ControlLifecycleNames.sessionAggregateId(session).value(),
                        ControlCommandWireCodec.encodeExperienceSessionRequest(requestExperienceSessionCommand(session, 1))),
                new ControlLogCommand(
                        ControllerWorkerCatalog.LIFECYCLE_TRACE,
                        ControlLifecycleNames.traceAggregateId(lifecycleTrace).value(),
                        ControlCommandWireCodec.encodeLifecycleTraceRecord(recordLifecycleObservationCommand(lifecycleTrace, 1))),
                new ControlLogCommand(
                        ControllerWorkerCatalog.CAPABILITY_ENABLEMENT,
                        ControlCapabilityNames.aggregateId(scope).value(),
                        ControlCommandWireCodec.encodeCapabilityEnablement(enableCapabilityCommand(scope, 1))),
                new ControlLogCommand(
                        ControllerWorkerCatalog.QUEUE_ROSTER,
                        ControlQueueNames.aggregateId(queuePayload.partitionKey()).value(),
                        ControlCommandWireCodec.encodeQueueRosterSubmit(submitQueueIntentCommand(queuePayload, 1))),
                new ControlLogCommand(
                        ControllerWorkerCatalog.FAULT,
                        ControlFaultNames.aggregateId(fault).value(),
                        ControlCommandWireCodec.encodeFaultRecord(recordFaultCommand(fault, 1))),
                new ControlLogCommand(
                        ControllerWorkerCatalog.SHARED_SHARD_PLACEMENT,
                        placementRequest.placementAttemptId(),
                        ControlCommandWireCodec.encodeSharedShardPlacementRequest(
                                placementRequest,
                                List.of(placementCandidate))),
                new ControlLogCommand(
                        ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION,
                        allocationRequest.sessionId().value(),
                        ControlCommandWireCodec.encodeSharedShardAllocationRequest(allocationRequest)));
    }

    private static void sendRemainingControllerCommands(
            String bootstrapServers,
            List<ControlLogCommand> commands) throws Exception {
        for (ControlLogCommand command : commands) {
            sendControlCommand(
                    bootstrapServers,
                    "ctrl.cmd." + command.domain(),
                    command.key(),
                    command.value());
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

    private static String sql(String value) {
        return value.replace("'", "''");
    }

    private static String cql(String value) {
        return value.replace("'", "''");
    }

    private static String wireField(String payload, String key) {
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0 && line.substring(0, separator).equals(key)) {
                return line.substring(separator + 1);
            }
        }
        throw new AssertionError("Missing wire field " + key + " in " + payload);
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

    private static String awaitRuntimeProgress(String uri, String role, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String lastBody = "";
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                lastBody = get(uri);
                if (loopCountFor(lastBody, role) > 0) {
                    return lastBody;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(50);
        }
        if (lastFailure != null) {
            throw new AssertionError("Timed out waiting for runtime progress from " + uri, lastFailure);
        }
        throw new AssertionError("Timed out waiting for runtime progress from " + uri + ": " + lastBody);
    }

    private static long loopCountFor(String body, String role) {
        String roleMarker = "\"role\":\"" + role + "\"";
        int roleIndex = body.indexOf(roleMarker);
        if (roleIndex < 0) {
            return -1;
        }
        String loopMarker = "\"loopCount\":";
        int loopIndex = body.indexOf(loopMarker, roleIndex);
        if (loopIndex < 0) {
            return -1;
        }
        int valueStart = loopIndex + loopMarker.length();
        int valueEnd = valueStart;
        while (valueEnd < body.length() && Character.isDigit(body.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd == valueStart) {
            return -1;
        }
        return Long.parseLong(body.substring(valueStart, valueEnd));
    }

    private static void assertOnlyExpectedProcessStderr(String err) {
        if (err.isBlank()) {
            return;
        }
        String expected = String.join("\n",
                "SLF4J: Failed to load class \"org.slf4j.impl.StaticLoggerBinder\".",
                "SLF4J: Defaulting to no-operation (NOP) logger implementation",
                "SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.");
        assertEquals(expected, err.replace("\r\n", "\n").trim());
    }

    private static AuthorityCommand<RuntimeCommand> runtimeCommand(String commandId, String value) {
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey("idem-" + commandId),
                        new PrincipalId("principal-runtime-engine"),
                        new AggregateId("subject:test"),
                        new ContractName("runtime-engine-test"),
                        new CommandName("runtime-engine-command"),
                        trace(),
                        Optional.empty(),
                        new RuntimeCommand(value)),
                new PrincipalId("principal-runtime-engine"),
                1,
                Optional.of(new Revision(0)),
                "payload-" + value,
                Instant.parse("2026-06-17T00:00:00Z"));
    }

    private static AuthorityCommand<SubjectCommand> registerSubjectCommand(SubjectId subject) {
        RegisterSubject payload = new RegisterSubject(
                subject,
                SubjectIdentityProvider.MINECRAFT_ACCOUNT,
                new SubjectExternalIdentity("minecraft:" + subject.value()),
                Instant.parse("2026-06-17T00:00:00Z"));
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-register-" + subject.value()),
                        new IdempotencyKey("idem-register-" + subject.value()),
                        new PrincipalId("principal-subject-runtime"),
                        SubjectAuthority.aggregateId(subject),
                        new ContractName("subject"),
                        new CommandName("register-subject"),
                        trace(),
                        Optional.empty(),
                        payload),
                new PrincipalId("principal-subject-runtime"),
                19,
                Optional.of(new Revision(0)),
                "payload-register-" + subject.value(),
                Instant.parse("2026-06-17T00:00:00Z"));
    }


    private static WorkerAgentRuntime workerRuntime(ResolvedManifestId manifest) {
        return new WorkerAgentRuntime(
                RuntimeIdentityIssuer.issue(
                        DeploymentProfile.SINGLE_MACHINE.loadDescriptor(Thread.currentThread().getContextClassLoader()),
                        RuntimeEntrypointRegistry.entriesFor(LaunchRole.WORKER_AGENT).getFirst(),
                        RuntimeEnvironment.of(Map.of("FULCRUM_MACHINE_REF", "machine-worker-catalog"))),
                manifest,
                List.of(new WorkerLagBudget(WorkerJobKind.CONTENT_VALIDATION, Duration.ofSeconds(5))));
    }

    private LocalObjectStorageAdapter workerObjectStore(String suffix) {
        return new LocalObjectStorageAdapter(tempDir.resolve("worker-object-store-" + suffix), "worker-results");
    }

    private static WorkerJobRequest workerJobRequest(
            WorkerJobId jobId,
            WorkerJobKind jobKind,
            ResolvedManifestId manifest,
            Instant enqueuedAt) {
        return new WorkerJobRequest(
                jobId,
                jobKind,
                "artifact-lobby-bedrock",
                new IdempotencyKey("idem-" + jobId.value()),
                "payload-" + jobId.value(),
                manifest,
                trace(),
                enqueuedAt,
                Optional.empty());
    }

    private static SharedShardAllocationRequest sharedShardAllocationRequest(SessionId session) {
        return new SharedShardAllocationRequest(
                new ExperienceId("experience-lobby"),
                new PoolId("pool-lobby"),
                session,
                new ResolvedManifestId("manifest-lobby-shared-shard"),
                trace(),
                Instant.parse("2026-06-17T00:00:00Z"));
    }

    private static String sharedShardAllocationState(SessionId session, InstanceId targetInstance) {
        SharedShardAllocationRequest request = sharedShardAllocationRequest(session);
        HostAllocationClaim claim = new HostAllocationClaim(
                new SlotId("slot-velocity-worker"),
                session,
                new HostInstanceIdentity(
                        targetInstance,
                        HostInstanceKinds.PAPER,
                        request.poolId(),
                        new MachineRef("machine-paper-velocity-worker"),
                        new PrincipalId("principal-paper-velocity-worker")),
                request.resolvedManifestId(),
                new HostNetworkEndpoint("10.244.0.17", 31_565),
                trace(),
                Instant.parse("2026-06-17T00:00:01Z"));
        return ControllerStateWireCodec.encodeSharedShardAllocation(
                new ExternalControllerWorkerCatalog.StoredSharedShardAllocation(
                        "fingerprint-velocity-worker",
                        request,
                        claim));
    }

    private static SharedShardPlacementRequest sharedShardPlacementRequest(
            String placementAttemptId,
            SubjectId subject,
            PresenceId presence,
            ResolvedManifestId manifest) {
        return new SharedShardPlacementRequest(
                new SharedShardExperienceDescriptor(
                        new ExperienceId("experience-lobby"),
                        ExperienceShape.SHARED_SHARD,
                        new SharedShardPoolDescriptor(new PoolId("pool-lobby"), "lobby-fleet", 75, 150),
                        manifest),
                subject,
                presence,
                placementAttemptId,
                Optional.of("capability-scope-lobby"),
                Instant.parse("2026-06-17T00:00:00Z"),
                trace());
    }

    private static SharedShardPlacementCandidate sharedShardPlacementCandidate(
            String instanceId,
            String sessionId,
            String slotId,
            PoolId poolId,
            ResolvedManifestId manifest,
            InstanceRegistryStatus status,
            int currentPresences,
            int hardCapacity,
            boolean acceptingPresences) {
        Instant observedAt = Instant.parse("2026-06-17T00:00:00Z");
        return new SharedShardPlacementCandidate(
                new sh.harold.fulcrum.control.instance.InstanceSnapshot(
                        new InstanceId(instanceId),
                        HostInstanceKinds.PAPER,
                        poolId,
                        new MachineRef("machine-placement-runtime"),
                        new PrincipalId("principal-" + instanceId),
                        Optional.of(manifest),
                        status,
                        Optional.empty(),
                        trace(),
                        observedAt),
                new SharedShardOccupancySnapshot(
                        new SessionId(sessionId),
                        new SlotId(slotId),
                        currentPresences,
                        hardCapacity,
                        acceptingPresences,
                        observedAt,
                        trace()));
    }

    private static InstanceRegistryControlCommand<RegisterInstance> registerInstanceCommand(
            InstanceId instance,
            long fencingEpoch) {
        PrincipalId principal = new PrincipalId("principal-instance-runtime");
        RegisterInstance payload = new RegisterInstance(
                instance,
                "paper",
                new PoolId("pool-lobby"),
                new MachineRef("machine-controller-runtime"),
                principal,
                Instant.parse("2026-06-17T00:00:00Z"),
                trace());
        return new InstanceRegistryControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-register-" + instance.value()),
                        new IdempotencyKey("idem-register-" + instance.value()),
                        principal,
                        ControlInstanceNames.aggregateId(instance),
                        ControlInstanceNames.CONTRACT,
                        ControlInstanceNames.REGISTER,
                        trace(),
                        Optional.empty(),
                        payload),
                principal,
                fencingEpoch,
                Optional.of(new Revision(0)),
                "payload-register-" + instance.value(),
                Instant.parse("2026-06-17T00:00:00Z"));
    }

    private static RouteAttemptControlCommand<RequestRouteAttempt> requestRouteAttemptCommand(
            RouteAttemptId routeAttempt,
            long fencingEpoch) {
        Instant requestedAt = Instant.parse("2026-06-17T00:00:00Z");
        RequestRouteAttempt payload = new RequestRouteAttempt(
                routeAttempt,
                new RouteId("route-controller-runtime"),
                new SessionId("session-controller-runtime"),
                new SlotId("slot-controller-runtime"),
                List.of(new SubjectId(UUID.fromString("55555555-5555-5555-5555-555555555555"))),
                List.of(new InstanceId("instance-velocity-runtime")),
                new PresenceId("presence-controller-runtime"),
                new InstanceId("instance-paper-runtime"),
                new ResolvedManifestId("manifest-controller-runtime"),
                requestedAt,
                requestedAt.plusSeconds(30),
                trace());
        return routeAttemptControlCommand(
                payload,
                ControlRouteNames.REQUEST_ROUTE_ATTEMPT,
                "request-" + routeAttempt.value(),
                fencingEpoch,
                0,
                requestedAt);
    }

    private static List<RouteAttemptControlCommand<? extends RouteAttemptCommand>> routeAckSequence(
            RouteAttemptId routeAttempt,
            RouteId routeId,
            SessionId session,
            SlotId slot,
            SubjectId subject,
            PresenceId presence,
            InstanceId proxy,
            InstanceId target,
            ResolvedManifestId manifest,
            String suffix,
            Instant requestedAt) {
        RequestRouteAttempt request = new RequestRouteAttempt(
                routeAttempt,
                routeId,
                session,
                slot,
                List.of(subject),
                List.of(proxy),
                presence,
                target,
                manifest,
                requestedAt,
                requestedAt.plusSeconds(30),
                trace());
        return List.of(
                routeAttemptControlCommand(
                        request,
                        ControlRouteNames.REQUEST_ROUTE_ATTEMPT,
                        suffix + "-request",
                        1,
                        0,
                        requestedAt),
                routeAttemptControlCommand(
                        new IssueProxyRoute(routeAttempt, requestedAt.plusSeconds(1)),
                        ControlRouteNames.ISSUE_PROXY_ROUTE,
                        suffix + "-issue-proxy",
                        1,
                        1,
                        requestedAt.plusSeconds(1)),
                routeAttemptControlCommand(
                        new PrepareHostRoute(routeAttempt, requestedAt.plusSeconds(2)),
                        ControlRouteNames.PREPARE_HOST_ROUTE,
                        suffix + "-prepare-host",
                        1,
                        2,
                        requestedAt.plusSeconds(2)),
                routeAttemptControlCommand(
                        new ObserveHostAttach(routeAttempt, requestedAt.plusSeconds(3)),
                        ControlRouteNames.OBSERVE_HOST_ATTACH,
                        suffix + "-observe-host-attach",
                        1,
                        3,
                        requestedAt.plusSeconds(3)),
                routeAttemptControlCommand(
                        new AcknowledgeRouteAttempt(routeAttempt, requestedAt.plusSeconds(4)),
                        ControlRouteNames.ACKNOWLEDGE_ROUTE_ATTEMPT,
                        suffix + "-acknowledge",
                        1,
                        4,
                        requestedAt.plusSeconds(4)));
    }

    private static <T extends RouteAttemptCommand> RouteAttemptControlCommand<T> routeAttemptControlCommand(
            T payload,
            CommandName commandName,
            String commandSuffix,
            long fencingEpoch,
            long expectedRevision,
            Instant receivedAt) {
        PrincipalId principal = new PrincipalId("principal-route-runtime");
        return new RouteAttemptControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-route-" + commandSuffix),
                        new IdempotencyKey("idem-route-" + commandSuffix),
                        principal,
                        ControlRouteNames.aggregateId(payload.routeAttemptId()),
                        ControlRouteNames.CONTRACT,
                        commandName,
                        trace(),
                        Optional.of(receivedAt.plusSeconds(30)),
                        payload),
                principal,
                fencingEpoch,
                Optional.of(new Revision(expectedRevision)),
                "payload-route-" + commandSuffix + ":" + expectedRevision,
                receivedAt);
    }

    private static ExperienceSessionControlCommand<RequestExperienceSession> requestExperienceSessionCommand(
            SessionId session,
            long fencingEpoch) {
        PrincipalId principal = new PrincipalId("principal-experience-session-runtime");
        Instant requestedAt = Instant.parse("2026-06-17T00:00:00Z");
        RequestExperienceSession payload = new RequestExperienceSession(
                session,
                new ExperienceId("experience-lobby"),
                Optional.empty(),
                "shared-shard",
                List.of(new SubjectId(UUID.fromString("66666666-6666-6666-6666-666666666666"))),
                requestedAt,
                trace());
        return new ExperienceSessionControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-session-request-" + session.value()),
                        new IdempotencyKey("idem-session-request-" + session.value()),
                        principal,
                        ControlLifecycleNames.sessionAggregateId(session),
                        ControlLifecycleNames.SESSION_CONTRACT,
                        ControlLifecycleNames.REQUEST_EXPERIENCE_SESSION,
                        trace(),
                        Optional.of(requestedAt.plusSeconds(30)),
                        payload),
                principal,
                fencingEpoch,
                Optional.of(new Revision(0)),
                "payload-session-request-" + session.value(),
                requestedAt);
    }

    private static LifecycleTraceControlCommand<RecordLifecycleObservation> recordLifecycleObservationCommand(
            LifecycleTraceId lifecycleTrace,
            long fencingEpoch) {
        PrincipalId principal = new PrincipalId("principal-lifecycle-trace-runtime");
        Instant observedAt = Instant.parse("2026-06-17T00:00:00Z");
        RecordLifecycleObservation payload = new RecordLifecycleObservation(
                lifecycleTrace,
                LifecyclePhase.ROUTE_ATTEMPT_CREATED,
                "route-attempt",
                "route-attempt-controller-runtime",
                Optional.empty(),
                Optional.empty(),
                observedAt,
                trace());
        return new LifecycleTraceControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-lifecycle-observe-" + lifecycleTrace.value()),
                        new IdempotencyKey("idem-lifecycle-observe-" + lifecycleTrace.value()),
                        principal,
                        ControlLifecycleNames.traceAggregateId(lifecycleTrace),
                        ControlLifecycleNames.TRACE_CONTRACT,
                        ControlLifecycleNames.RECORD_LIFECYCLE_OBSERVATION,
                        trace(),
                        Optional.of(observedAt.plusSeconds(30)),
                        payload),
                principal,
                fencingEpoch,
                Optional.of(new Revision(0)),
                "payload-lifecycle-observe-" + lifecycleTrace.value(),
                observedAt);
    }

    private static CapabilityEnablementControlCommand<EnableCapability> enableCapabilityCommand(
            CapabilityScope scope,
            long fencingEpoch) {
        PrincipalId principal = new PrincipalId("principal-capability-runtime");
        Instant enabledAt = Instant.parse("2026-06-17T00:00:00Z");
        EnableCapability payload = new EnableCapability(
                scope,
                new CapabilityId("lobby-chat"),
                "lobby-contracts-v1",
                "lobby-bringup",
                enabledAt,
                trace());
        return new CapabilityEnablementControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-capability-enable-" + scope.value()),
                        new IdempotencyKey("idem-capability-enable-" + scope.value()),
                        principal,
                        ControlCapabilityNames.aggregateId(scope),
                        ControlCapabilityNames.CONTRACT,
                        ControlCapabilityNames.ENABLE,
                        trace(),
                        Optional.of(enabledAt.plusSeconds(30)),
                        payload),
                principal,
                fencingEpoch,
                Optional.of(new Revision(0)),
                "payload-capability-enable-" + scope.value(),
                enabledAt);
    }

    private static SubmitQueueIntent submitQueueIntent(QueueIntentId queueIntent) {
        Instant createdAt = Instant.parse("2026-06-17T00:00:00Z");
        return new SubmitQueueIntent(
                queueIntent,
                List.of(new SubjectId(UUID.fromString("77777777-7777-7777-7777-777777777777"))),
                new ExperienceId("experience-lobby"),
                Optional.empty(),
                new PoolId("pool-lobby"),
                0,
                createdAt,
                createdAt.plusSeconds(30),
                trace());
    }

    private static QueueRosterControlCommand<SubmitQueueIntent> submitQueueIntentCommand(
            SubmitQueueIntent payload,
            long fencingEpoch) {
        PrincipalId principal = new PrincipalId("principal-queue-runtime");
        return new QueueRosterControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-queue-submit-" + payload.queueIntentId().value()),
                        new IdempotencyKey("idem-queue-submit-" + payload.queueIntentId().value()),
                        principal,
                        ControlQueueNames.aggregateId(payload.partitionKey()),
                        ControlQueueNames.CONTRACT,
                        ControlQueueNames.SUBMIT_QUEUE_INTENT,
                        trace(),
                        Optional.of(payload.createdAt().plusSeconds(30)),
                        payload),
                principal,
                fencingEpoch,
                Optional.of(new Revision(0)),
                "payload-queue-submit-" + payload.queueIntentId().value(),
                payload.createdAt());
    }

    private static FaultControlCommand<RecordFault> recordFaultCommand(
            FaultId fault,
            long fencingEpoch) {
        PrincipalId principal = new PrincipalId("principal-fault-runtime");
        Instant observedAt = Instant.parse("2026-06-17T00:00:00Z");
        RecordFault payload = new RecordFault(
                fault,
                FaultTargetType.INSTANCE,
                "instance-paper-runtime",
                "lobby",
                "health-timeout",
                1,
                observedAt,
                trace());
        return new FaultControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-fault-record-" + fault.value()),
                        new IdempotencyKey("idem-fault-record-" + fault.value()),
                        principal,
                        ControlFaultNames.aggregateId(fault),
                        ControlFaultNames.CONTRACT,
                        ControlFaultNames.RECORD_FAULT,
                        trace(),
                        Optional.of(observedAt.plusSeconds(30)),
                        payload),
                principal,
                fencingEpoch,
                Optional.of(new Revision(0)),
                "payload-fault-record-" + fault.value(),
                observedAt);
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-runtime-engine",
                "span-runtime-engine",
                Optional.empty(),
                Instant.parse("2026-06-17T00:00:00Z"),
                "service-launcher-test",
                new InstanceId("instance-service-launcher-test"));
    }

    private static String allocationResponse(String instanceKind) {
        return """
                {
                  "gameServerName": "agones-gameserver-1",
                  "nodeName": "machine-agones-a",
                  "address": "10.244.0.17",
                  "ports": [
                    {"name": "metrics", "port": 9090},
                    {"name": "minecraft", "port": 31565}
                  ],
                  "metadata": {
                    "annotations": {
                      "sh.harold.fulcrum/instance-id": "instance-paper-agones-1",
                      "sh.harold.fulcrum/slot-id": "slot-agones-1",
                      "sh.harold.fulcrum/instance-kind": "%s",
                      "sh.harold.fulcrum/principal-id": "principal-paper-agones-1"
                    }
                  }
                }
                """.formatted(instanceKind);
    }

    private static String barePipePresenceCommitFailure(
            FulcrumSubstrateStack stack,
            int authorityProbePort,
            int expectedOffset) throws Exception {
        long offset = committedOffset(
                stack.kafkaBootstrapServers(),
                "fulcrum-authority-service-presence",
                "cmd.presence",
                0).orElse(-1L);
        String decisions = stack.queryPostgresScalar("SELECT count(*) FROM authority_decisions;");
        String records = stack.queryPostgresScalar("SELECT count(*) FROM authority_records;");
        String probe = safeGet("http://127.0.0.1:" + authorityProbePort + "/ready");
        return "bare-pipe Presence claims were not committed"
                + "; expectedOffsetAtLeast=" + expectedOffset
                + "; observedOffset=" + offset
                + "; authorityRecords=" + records
                + "; authorityDecisions=" + decisions
                + "; authorityReady=" + probe;
    }

    private static String safeGet(String uri) {
        try {
            return get(uri);
        } catch (Exception exception) {
            return exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout, String failureMessage) throws Exception {
        awaitTrue(condition, timeout, () -> failureMessage);
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout, FailureMessageSupplier failureMessage) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(failureMessage.get());
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

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    @FunctionalInterface
    private interface FailureMessageSupplier {
        String get() throws Exception;
    }

    private record RuntimeCommand(String value) implements CommandPayload {
    }

    private record LaunchResult(int code, String out, String err) {
    }

    private record BarePipeLogin(
            SubjectId subject,
            PresenceId presence,
            RouteId route,
            RouteAttemptId routeAttempt,
            String suffix) {
    }

    private record ControlLogCommand(String domain, String key, String value) {
    }

    private static final class AllocatorFixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> requestBody = new AtomicReference<>();
        private final AtomicInteger requestCount = new AtomicInteger();

        private AllocatorFixture(HttpServer server) {
            this.server = server;
        }

        static AllocatorFixture responding(int statusCode, String responseBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            AllocatorFixture fixture = new AllocatorFixture(server);
            server.createContext("/gameserverallocation", exchange -> fixture.handle(exchange, statusCode, responseBody));
            server.start();
            return fixture;
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        String requestBody() {
            return requestBody.get();
        }

        int requestCount() {
            return requestCount.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
            requestCount.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private static final class RouteBridgeFixture implements AutoCloseable {
        private final HttpServer server;
        private final VelocityRouteTransfer transfer;
        private final AtomicReference<String> requestBody = new AtomicReference<>();

        private RouteBridgeFixture(HttpServer server, VelocityRouteTransfer transfer) {
            this.server = server;
            this.transfer = transfer;
        }

        static RouteBridgeFixture responding(VelocityRouteTransfer transfer) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            RouteBridgeFixture fixture = new RouteBridgeFixture(server, transfer);
            server.createContext("/routes", fixture::handle);
            server.start();
            return fixture;
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/routes");
        }

        String requestBody() {
            return requestBody.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = VelocityRouteBridgeCodec.encodeResponse(Optional.of(transfer))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
