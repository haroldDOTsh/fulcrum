package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest;
import sh.harold.fulcrum.control.route.IssueProxyRoute;
import sh.harold.fulcrum.control.route.PrepareHostRoute;
import sh.harold.fulcrum.control.route.RequestRouteAttempt;
import sh.harold.fulcrum.control.route.RouteAttemptCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlCommand;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.presence.ClaimPresence;
import sh.harold.fulcrum.data.presence.PresenceCommand;
import sh.harold.fulcrum.data.route.contract.OpenRoute;
import sh.harold.fulcrum.data.route.contract.RouteCommand;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostNetworkEndpoint;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.velocity.VelocityBackendEndpoint;
import sh.harold.fulcrum.host.velocity.VelocityLoginGateDecision;
import sh.harold.fulcrum.host.velocity.VelocityLoginGateRequest;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VelocityLoginRoutingEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-06-17T00:00:00Z");
    private static final SubjectId SUBJECT =
            new SubjectId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    private static final SubjectId SECOND_SUBJECT =
            new SubjectId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

    @Test
    void allowedLoginPublishesPresencePlacementAndRouteAttemptCommandsWhenAllocationCandidateExists() {
        MockProducer<String, String> producer = producer();
        VelocitySharedShardAllocationRegistry allocations = new VelocitySharedShardAllocationRegistry();
        allocations.record(allocation());
        VelocityLoginRoutingEvaluator evaluator = new VelocityLoginRoutingEvaluator(
                request -> VelocityLoginGateDecision.allowed(request.subjectId()),
                producer,
                securityContext(),
                settings(),
                allocations);

        VelocityLoginGateDecision decision = evaluator.evaluate(new VelocityLoginGateRequest(
                SUBJECT,
                "FulcrumBotOne",
                "standard.punishment",
                NOW));

        assertTrue(decision.allowed());
        List<ProducerRecord<String, String>> records = producer.history();
        assertEquals(6, records.size());
        assertEquals("cmd.presence", records.get(0).topic());
        assertEquals("ctrl.cmd.shared-shard-placement", records.get(1).topic());
        assertEquals("cmd.route", records.get(2).topic());
        assertEquals("ctrl.cmd.route-attempt", records.get(3).topic());
        assertEquals("ctrl.cmd.route-attempt", records.get(4).topic());
        assertEquals("ctrl.cmd.route-attempt", records.get(5).topic());

        AuthorityCommand<PresenceCommand> presenceCommand = PresenceAuthorityWireCodec.decodeCommand(record(records.get(0)));
        ClaimPresence claim = assertInstanceOf(ClaimPresence.class, presenceCommand.envelope().payload());
        assertEquals(SUBJECT, claim.subjectId());
        assertEquals(Optional.of(new SessionId("session-lobby-shared")), claim.sessionId());
        assertEquals(Optional.of("route-velocity-login-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                claim.routeId().map(value -> value.value()));
        assertEquals(new InstanceId("instance-velocity-login"), claim.ownerInstanceId());

        SharedShardPlacementWireRequest placement =
                ControlCommandWireCodec.decodeSharedShardPlacementRequest(record(records.get(1)));
        assertEquals(SUBJECT, placement.request().subjectId());
        assertEquals("placement-velocity-login-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                placement.request().placementAttemptId());
        assertEquals(1, placement.candidates().size());
        assertEquals(new SessionId("session-lobby-shared"), placement.candidates().getFirst().occupancySnapshot().sessionId());
        assertEquals(new SlotId("slot-lobby-shared"), placement.candidates().getFirst().occupancySnapshot().slotId());

        AuthorityCommand<RouteCommand> routeCommand = RouteAuthorityWireCodec.decodeCommand(record(records.get(2)));
        OpenRoute openRoute = assertInstanceOf(OpenRoute.class, routeCommand.envelope().payload());
        assertEquals(SUBJECT, openRoute.subjectId());
        assertEquals(new SessionId("session-lobby-shared"), openRoute.targetSessionId());
        assertEquals(new InstanceId("instance-paper-lobby"), openRoute.targetInstanceId());

        RouteAttemptControlCommand<? extends RouteAttemptCommand> request =
                ControlCommandWireCodec.decodeRouteAttemptCommand(record(records.get(3)));
        RequestRouteAttempt requestPayload = assertInstanceOf(RequestRouteAttempt.class, request.envelope().payload());
        assertEquals(new SessionId("session-lobby-shared"), requestPayload.sessionId());
        assertEquals(new SlotId("slot-lobby-shared"), requestPayload.allocationSlotId());
        assertEquals(List.of(SUBJECT), requestPayload.subjectIds());
        assertEquals(List.of(new InstanceId("instance-velocity-login")), requestPayload.proxyInstanceIds());
        assertEquals(new InstanceId("instance-paper-lobby"), requestPayload.targetInstanceId());

        assertInstanceOf(IssueProxyRoute.class,
                ControlCommandWireCodec.decodeRouteAttemptCommand(record(records.get(4))).envelope().payload());
        assertInstanceOf(PrepareHostRoute.class,
                ControlCommandWireCodec.decodeRouteAttemptCommand(record(records.get(5))).envelope().payload());
    }

    @Test
    void deniedLoginDoesNotPublishRoutingCommands() {
        MockProducer<String, String> producer = producer();
        VelocityLoginRoutingEvaluator evaluator = new VelocityLoginRoutingEvaluator(
                request -> VelocityLoginGateDecision.denied(request.subjectId(), "Banned from the lobby"),
                producer,
                securityContext(),
                settings(),
                new VelocitySharedShardAllocationRegistry());

        VelocityLoginGateDecision decision = evaluator.evaluate(new VelocityLoginGateRequest(
                SUBJECT,
                "FulcrumBannedOne",
                "standard.punishment",
                NOW));

        assertTrue(decision.denialReason().orElseThrow().contains("Banned"));
        assertTrue(producer.history().isEmpty());
    }

    @Test
    void allowedDelegateDeniesLoginWhenNoAllocatedLobbyRouteExists() {
        MockProducer<String, String> producer = producer();
        VelocityLoginRoutingEvaluator evaluator = new VelocityLoginRoutingEvaluator(
                request -> VelocityLoginGateDecision.allowed(request.subjectId()),
                producer,
                securityContext(),
                settings(),
                new VelocitySharedShardAllocationRegistry());

        VelocityLoginGateDecision decision = evaluator.evaluate(new VelocityLoginGateRequest(
                SUBJECT,
                "FulcrumBotOne",
                "standard.punishment",
                NOW));

        assertFalse(decision.allowed());
        assertEquals(VelocityLoginRoutingEvaluator.NO_LOBBY_ROUTE_REASON,
                decision.denialReason().orElseThrow());
        List<ProducerRecord<String, String>> records = producer.history();
        assertEquals(1, records.size());
        assertEquals("ctrl.cmd.shared-shard-placement", records.getFirst().topic());
        SharedShardPlacementWireRequest placement =
                ControlCommandWireCodec.decodeSharedShardPlacementRequest(record(records.getFirst()));
        assertEquals(SUBJECT, placement.request().subjectId());
        assertTrue(placement.candidates().isEmpty());
    }

    @Test
    void secondLoginRequestsAllocationWhenExistingLobbyReachesHardCapacity() {
        MockProducer<String, String> producer = producer();
        VelocitySharedShardAllocationRegistry allocations = new VelocitySharedShardAllocationRegistry();
        allocations.record(allocation());
        VelocityLoginRoutingEvaluator evaluator = new VelocityLoginRoutingEvaluator(
                request -> VelocityLoginGateDecision.allowed(request.subjectId()),
                producer,
                securityContext(),
                settings(1),
                allocations);

        VelocityLoginGateDecision firstDecision = evaluator.evaluate(new VelocityLoginGateRequest(
                SUBJECT,
                "FulcrumBotOne",
                "standard.punishment",
                NOW));
        VelocityLoginGateDecision secondDecision = evaluator.evaluate(new VelocityLoginGateRequest(
                SECOND_SUBJECT,
                "FulcrumBotTwo",
                "standard.punishment",
                NOW.plusSeconds(1)));

        assertTrue(firstDecision.allowed());
        assertFalse(secondDecision.allowed());
        assertEquals(VelocityLoginRoutingEvaluator.NO_LOBBY_ROUTE_REASON,
                secondDecision.denialReason().orElseThrow());
        List<ProducerRecord<String, String>> records = producer.history();
        assertEquals(7, records.size());
        assertEquals("ctrl.cmd.shared-shard-placement", records.getLast().topic());
        SharedShardPlacementWireRequest placement =
                ControlCommandWireCodec.decodeSharedShardPlacementRequest(record(records.getLast()));
        assertEquals(SECOND_SUBJECT, placement.request().subjectId());
        assertEquals(1, placement.candidates().size());
        assertEquals(1, placement.candidates().getFirst().occupancySnapshot().currentPresences());
        assertEquals(1, placement.candidates().getFirst().occupancySnapshot().hardCapacity());
        assertEquals(new SessionId("session-lobby-shared"), placement.candidates().getFirst().occupancySnapshot().sessionId());
    }

    @Test
    void allocationRegistryUsesEncodedControllerStateForPlacementAndBackendLookup() {
        VelocitySharedShardAllocationRegistry allocations = new VelocitySharedShardAllocationRegistry();
        String encodedState = ControllerStateWireCodec.encodeSharedShardAllocation(allocation());
        assertTrue(ControllerStateWireCodec.isRecordType(
                encodedState,
                ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION));

        allocations.record(ControllerStateWireCodec.decodeSharedShardAllocation(encodedState));

        VelocityBackendEndpoint backend = allocations.backend(new InstanceId("instance-paper-lobby"));
        assertEquals(new InstanceId("instance-paper-lobby"), backend.instanceId());
        assertEquals("10.244.0.17", backend.host());
        assertEquals(31565, backend.port());
        var candidates = allocations.placementCandidates(settings(), allocation().request().traceEnvelope(), NOW);
        assertEquals(1, candidates.size());
        assertEquals(new SessionId("session-lobby-shared"), candidates.getFirst().occupancySnapshot().sessionId());
        assertEquals(new SlotId("slot-lobby-shared"), candidates.getFirst().occupancySnapshot().slotId());
    }

    private static MockProducer<String, String> producer() {
        return new MockProducer<String, String>(true, null, new StringSerializer(), new StringSerializer());
    }

    private static ConsumerRecord<String, String> record(ProducerRecord<String, String> record) {
        return new ConsumerRecord<>(record.topic(), 0, 0, record.key(), record.value());
    }

    private static HostSecurityContext securityContext() {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-velocity-login"),
                        HostInstanceKinds.VELOCITY,
                        new PoolId("pool-velocity"),
                        new MachineRef("machine-velocity-login"),
                        new PrincipalId("principal-velocity-login")),
                "service-account:velocity-agent",
                new HostCredentialScope(Set.of(
                        grant(HostAccessMode.PRODUCE, "cmd.presence"),
                        grant(HostAccessMode.PRODUCE, "ctrl.cmd.shared-shard-placement"),
                        grant(HostAccessMode.PRODUCE, "cmd.route"),
                        grant(HostAccessMode.PRODUCE, "ctrl.cmd.route-attempt"))));
    }

    private static HostResourceGrant grant(HostAccessMode mode, String name) {
        return new HostResourceGrant(HostResourceFamily.TOPIC, mode, name);
    }

    private static RuntimeConnectionSettings.VelocityConnections settings() {
        return settings(150);
    }

    private static RuntimeConnectionSettings.VelocityConnections settings(int lobbyHardCapacity) {
        return new RuntimeConnectionSettings.VelocityConnections(
                Path.of("velocity"),
                List.of(new RuntimeConnectionSettings.HostPort("localhost", 9092)),
                URI.create("http://127.0.0.1:18081/routes"),
                URI.create("http://127.0.0.1:18082/login-gate"),
                "host.velocity.routes",
                "cmd.route",
                "cmd.presence",
                "ctrl.cmd.shared-shard-placement",
                "ctrl.cmd.route-attempt",
                "ctrl.state.shared-shard-allocation",
                new ExperienceId("experience-lobby"),
                new PoolId("pool-lobby"),
                "fulcrum-lobby-paper",
                Math.min(75, lobbyHardCapacity),
                lobbyHardCapacity,
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "capability-scope-lobby",
                "standard.punishment",
                Duration.ofMinutes(5),
                new RuntimeConnectionSettings.HostPort("localhost", 6379));
    }

    private static ExternalControllerWorkerCatalog.StoredSharedShardAllocation allocation() {
        TraceEnvelope trace = new TraceEnvelope(
                "trace-allocation",
                "span-allocation",
                Optional.empty(),
                NOW,
                "test",
                new InstanceId("instance-test"));
        SharedShardAllocationRequest request = new SharedShardAllocationRequest(
                new ExperienceId("experience-lobby"),
                new PoolId("pool-lobby"),
                new SessionId("session-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                trace,
                NOW);
        HostAllocationClaim claim = new HostAllocationClaim(
                new SlotId("slot-lobby-shared"),
                new SessionId("session-lobby-shared"),
                new HostInstanceIdentity(
                        new InstanceId("instance-paper-lobby"),
                        HostInstanceKinds.PAPER,
                        new PoolId("pool-lobby"),
                        new MachineRef("machine-paper-lobby"),
                        new PrincipalId("principal-paper-lobby")),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                new HostNetworkEndpoint("10.244.0.17", 31565),
                trace,
                NOW);
        return new ExternalControllerWorkerCatalog.StoredSharedShardAllocation(
                "experience-lobby|pool-lobby|session-lobby-shared|manifest-lobby-bedrock-v1",
                request,
                claim);
    }
}
