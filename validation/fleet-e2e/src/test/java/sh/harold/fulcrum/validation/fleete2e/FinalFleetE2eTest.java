package sh.harold.fulcrum.validation.fleete2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.adapters.agones.allocator.AgonesAllocatorRestClient;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.EffectId;
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
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.control.allocation.RosterAllocationBridge;
import sh.harold.fulcrum.control.allocation.RosterAllocationDecision;
import sh.harold.fulcrum.control.allocation.RosterAllocationDecisionStatus;
import sh.harold.fulcrum.control.allocation.RosterAllocationRequest;
import sh.harold.fulcrum.control.queue.ControlQueueNames;
import sh.harold.fulcrum.control.queue.FormRosterIntent;
import sh.harold.fulcrum.control.queue.QueueIntentId;
import sh.harold.fulcrum.control.queue.QueuePartitionKey;
import sh.harold.fulcrum.control.queue.QueueRosterCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlRecord;
import sh.harold.fulcrum.control.queue.QueueRosterController;
import sh.harold.fulcrum.control.queue.QueueRosterDecision;
import sh.harold.fulcrum.control.queue.QueueRosterDecisionStatus;
import sh.harold.fulcrum.control.queue.QueueRosterEvent;
import sh.harold.fulcrum.control.queue.RosterIntentId;
import sh.harold.fulcrum.control.queue.RosterIntentSnapshot;
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
import sh.harold.fulcrum.control.route.RouteAttemptController;
import sh.harold.fulcrum.control.route.RouteAttemptDecision;
import sh.harold.fulcrum.control.route.RouteAttemptDecisionStatus;
import sh.harold.fulcrum.control.route.RouteAttemptEvent;
import sh.harold.fulcrum.control.route.RouteAttemptId;
import sh.harold.fulcrum.control.route.RouteAttemptLifecycleStatus;
import sh.harold.fulcrum.core.content.ContentArtifactCandidate;
import sh.harold.fulcrum.core.content.ContentArtifactKind;
import sh.harold.fulcrum.core.content.ContentArtifactReadiness;
import sh.harold.fulcrum.core.content.ContentResolution;
import sh.harold.fulcrum.core.content.ContentResolutionRequest;
import sh.harold.fulcrum.core.content.ContentResolutionStatus;
import sh.harold.fulcrum.core.content.ContentResolver;
import sh.harold.fulcrum.core.content.ContentRotationPolicy;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ContractPin;
import sh.harold.fulcrum.core.manifest.ResolvedManifest;
import sh.harold.fulcrum.core.session.EffectClass;
import sh.harold.fulcrum.core.session.EffectClassifier;
import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectOrigin;
import sh.harold.fulcrum.core.session.EffectPayload;
import sh.harold.fulcrum.core.session.EffectSettlementMode;
import sh.harold.fulcrum.core.session.EffectTargetScope;
import sh.harold.fulcrum.core.session.SessionDomainEvent;
import sh.harold.fulcrum.core.session.SessionReduction;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.route.contract.RouteCommand;
import sh.harold.fulcrum.data.route.contract.RouteContracts;
import sh.harold.fulcrum.data.session.ActivateSession;
import sh.harold.fulcrum.data.session.CloseSession;
import sh.harold.fulcrum.data.session.OpenSession;
import sh.harold.fulcrum.data.session.SessionAuthority;
import sh.harold.fulcrum.data.session.SessionCloseReason;
import sh.harold.fulcrum.data.session.SessionCommand;
import sh.harold.fulcrum.data.session.SessionLifecycleStatus;
import sh.harold.fulcrum.data.session.SessionOwnerToken;
import sh.harold.fulcrum.data.session.SessionReceipt;
import sh.harold.fulcrum.data.session.SessionState;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationFactory;
import sh.harold.fulcrum.host.api.HostObservationTypes;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.api.HostSessionAttachment;
import sh.harold.fulcrum.host.effect.EffectAdmissionGate;
import sh.harold.fulcrum.host.effect.EffectAdmissionPolicy;
import sh.harold.fulcrum.host.effect.EffectAdmissionReceipt;
import sh.harold.fulcrum.host.effect.EffectAdmissionRule;
import sh.harold.fulcrum.host.effect.EffectAdmissionStatus;
import sh.harold.fulcrum.host.paper.ArtifactSource;
import sh.harold.fulcrum.host.paper.CachedArtifact;
import sh.harold.fulcrum.host.paper.PaperArtifactCache;
import sh.harold.fulcrum.host.tick.HostLocalEffectDispatcher;
import sh.harold.fulcrum.host.tick.HostTickRuntimeContext;
import sh.harold.fulcrum.host.tick.HostTickSessionRuntime;
import sh.harold.fulcrum.host.velocity.VelocityRouteCommandFactory;
import sh.harold.fulcrum.host.velocity.VelocityRouteTransfer;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;
import sh.harold.fulcrum.standard.contracts.RankContracts;
import sh.harold.fulcrum.standard.contracts.PartyContracts;
import sh.harold.fulcrum.standard.contracts.FriendsContracts;
import sh.harold.fulcrum.standard.contracts.EconomyContracts;
import sh.harold.fulcrum.standard.contracts.StatsContracts;
import sh.harold.fulcrum.standard.economy.EconomyAccountId;
import sh.harold.fulcrum.standard.economy.EconomyAuthority;
import sh.harold.fulcrum.standard.economy.EconomyCapability;
import sh.harold.fulcrum.standard.economy.EconomyLedgerEntryRecorded;
import sh.harold.fulcrum.standard.economy.EconomyProjection;
import sh.harold.fulcrum.standard.economy.EconomyReceipt;
import sh.harold.fulcrum.standard.economy.EconomyState;
import sh.harold.fulcrum.standard.economy.PostLedgerEntry;
import sh.harold.fulcrum.standard.friends.FriendsCapability;
import sh.harold.fulcrum.standard.contracts.GuildContracts;
import sh.harold.fulcrum.standard.guild.GuildCapability;
import sh.harold.fulcrum.standard.party.PartyCapability;
import sh.harold.fulcrum.standard.profile.PlayerProfileCapability;
import sh.harold.fulcrum.standard.punishment.PunishmentCapability;
import sh.harold.fulcrum.standard.punishment.PunishmentLoginGate;
import sh.harold.fulcrum.standard.punishment.PunishmentLoginRequest;
import sh.harold.fulcrum.standard.rank.RankCapability;
import sh.harold.fulcrum.standard.stats.RecordStatDelta;
import sh.harold.fulcrum.standard.stats.StatsAuthority;
import sh.harold.fulcrum.standard.stats.StatsCapability;
import sh.harold.fulcrum.standard.stats.StatsCounterId;
import sh.harold.fulcrum.standard.stats.StatsDeltaRecorded;
import sh.harold.fulcrum.standard.stats.StatsProjection;
import sh.harold.fulcrum.standard.stats.StatsReceipt;
import sh.harold.fulcrum.standard.stats.StatsState;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FinalFleetE2eTest {
    private static final Instant BASE_TIME = Instant.parse("2026-06-18T00:00:00Z");
    private static final String TRACE_ID = "trace-final-fleet-e2e";
    private static final PrincipalId QUEUE_PRINCIPAL = new PrincipalId("principal-controller-queue-final");
    private static final PrincipalId ROUTE_PRINCIPAL = new PrincipalId("principal-controller-route-final");
    private static final PrincipalId SESSION_PRINCIPAL = new PrincipalId("principal-session-authority-final");
    private static final PrincipalId VELOCITY_PRINCIPAL = new PrincipalId("principal-velocity-final");
    private static final PrincipalId PAPER_PRINCIPAL = new PrincipalId("principal-paper-final");
    private static final ExperienceId EXPERIENCE_ID = new ExperienceId("experience.arena.final");
    private static final PoolId PAPER_POOL_ID = new PoolId("pool-paper-final");
    private static final PoolId VELOCITY_POOL_ID = new PoolId("pool-velocity-final");
    private static final Optional<String> MODE_ID = Optional.of("standard");
    private static final QueuePartitionKey QUEUE_PARTITION = new QueuePartitionKey(EXPERIENCE_ID, MODE_ID, PAPER_POOL_ID);
    private static final SubjectId SUBJECT_ONE = subject("00000000-0000-0000-0000-000000000e01");
    private static final SubjectId SUBJECT_TWO = subject("00000000-0000-0000-0000-000000000e02");
    private static final List<SubjectId> SUBJECTS = List.of(SUBJECT_ONE, SUBJECT_TWO);
    private static final SessionId SESSION_ID = new SessionId("session-final-e2e-1");
    private static final SessionOwnerToken SESSION_OWNER_TOKEN = new SessionOwnerToken("session-owner-final-e2e-1");
    private static final ResolvedManifestId RESOLVED_MANIFEST_ID = new ResolvedManifestId("resolved-manifest-final-e2e-1");
    private static final RouteId ROUTE_ID = new RouteId("route-final-e2e-1");
    private static final RouteAttemptId ROUTE_ATTEMPT_ID = new RouteAttemptId("route-attempt-final-e2e-1");
    private static final RosterIntentId ROSTER_ID = new RosterIntentId("roster-final-e2e-1");
    private static final ArtifactId MAP_ARTIFACT_ID = new ArtifactId("artifact.final.map");
    private static final ArtifactId CONFIG_ARTIFACT_ID = new ArtifactId("artifact.final.config");
    private static final String HOST_RUNTIME_ABI = "paper-26.1.2";
    private static final long QUEUE_FENCING_EPOCH = 101;
    private static final long ROUTE_FENCING_EPOCH = 102;
    private static final long SESSION_FENCING_EPOCH = 103;
    private static final long REWARD_FENCING_EPOCH = 104;
    private static final CapabilityId ECONOMY_CAPABILITY = EconomyCapability.CAPABILITY_ID;
    private static final CapabilityId STATS_CAPABILITY = StatsCapability.CAPABILITY_ID;
    private static final String ECONOMY_CURRENCY = "coins";
    private static final String SESSION_COMPLETIONS_STAT = "session-completions";
    private static final HostResourceGrant ECONOMY_COMMAND_GRANT =
            new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "cmd.standard.economy");
    private static final HostResourceGrant STATS_COMMAND_GRANT =
            new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "cmd.standard.stats");
    private static final HostResourceGrant ROUTE_COMMAND_GRANT =
            new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, RouteContracts.COMMAND_TOPIC);

    @Test
    void finalFleetPathPassesOnProductionShapedProfile(@TempDir Path cacheDirectory) throws Exception {
        DeploymentProfile singleMachine = DeploymentProfile.load("single-machine");
        DeploymentProfile smallProduction = DeploymentProfile.load("small-production");
        DeploymentProfile largeProduction = DeploymentProfile.load("large-production");
        assertProductionShapedProfile(singleMachine, smallProduction, largeProduction);
        assertStandardCapabilitiesRemainOutsideKernel();

        TraceTimeline timeline = new TraceTimeline();
        List<CommandEnvelope<?>> commandEnvelopes = new ArrayList<>();
        SUBJECTS.forEach(subject -> assertTrue(PunishmentLoginGate.evaluate(
                new PunishmentLoginRequest(subject, BASE_TIME.plusSeconds(1)),
                Optional.empty()).allowed()));
        timeline.record("login-gate", trace("proxy-login-gate", new InstanceId("instance-velocity-final")), ROUTE_ID.value());

        QueueFlow queueFlow = runQueueRoster(commandEnvelopes, timeline);
        RosterIntentSnapshot roster = queueFlow.roster();

        try (AllocatorFixture allocator = AllocatorFixture.responding(agonesAllocationResponse())) {
            HostAllocationClaim claim = allocateRosterThroughProductionAdapter(allocator.uri(), roster, timeline);
            assertTrue(allocator.requestBody().contains("\"gameServerState\":\"Ready\""));
            assertTrue(allocator.requestBody().contains("\"sh.harold.fulcrum/session-id\":\"" + SESSION_ID.value() + "\""));
            assertTrue(allocator.requestBody().contains("\"sh.harold.fulcrum/resolved-manifest-id\":\"" + RESOLVED_MANIFEST_ID.value() + "\""));

            ResolvedManifest manifest = resolveContentManifest();
            assertEquals(RESOLVED_MANIFEST_ID, manifest.resolvedManifestId());
            List<CachedArtifact> cachedArtifacts = preparePaperRuntimeWorld(cacheDirectory, manifest);
            assertEquals(2, cachedArtifacts.size());
            assertTrue(cachedArtifacts.stream().noneMatch(CachedArtifact::cacheHit));
            timeline.record("paper-world-prepared", trace("paper-artifact-cache", claim.instanceIdentity().instanceId()), SESSION_ID.value());

            Map<InstanceId, Set<SessionId>> paperAssignments = new HashMap<>();
            paperAssignments.computeIfAbsent(claim.instanceIdentity().instanceId(), ignored -> new HashSet<>()).add(SESSION_ID);
            assertEquals(Set.of(SESSION_ID), paperAssignments.get(claim.instanceIdentity().instanceId()));

            RouteFlow routeFlow = routeSubjects(commandEnvelopes, timeline, claim);
            HostSessionAttachment attachment = attachPaperSession(claim, routeFlow.snapshot().routeId(), timeline);

            SessionFlow sessionFlow = openAndActivateSession(commandEnvelopes, claim, manifest, timeline);
            assertEquals(SessionLifecycleStatus.ACTIVE, sessionFlow.record().state().current().orElseThrow().status());
            assertEquals(RESOLVED_MANIFEST_ID, sessionFlow.record().state().current().orElseThrow().resolvedManifestId());
            assertEquals(claim.instanceIdentity().instanceId(), sessionFlow.record().state().current().orElseThrow().ownerInstanceId());

            RuntimeFlow runtimeFlow = runFixtureSessionRuntime(attachment);
            assertEquals(1, runtimeFlow.hostLocalEffects().size());
            assertEquals(4, runtimeFlow.platformEffects().size());
            timeline.record("fixture-reducer", trace("fixture-reducer", claim.instanceIdentity().instanceId()), SESSION_ID.value());

            EconomyRewardAuthority economyAuthority = new EconomyRewardAuthority();
            StatsRewardAuthority statsAuthority = new StatsRewardAuthority();
            EffectAdmissionGate admissionGate = new EffectAdmissionGate(EffectAdmissionPolicy.of(
                    new EffectAdmissionRule(EffectClass.AUTHORITY, "economy:", Optional.of(ECONOMY_CAPABILITY), ECONOMY_COMMAND_GRANT),
                    new EffectAdmissionRule(EffectClass.AUTHORITY, "stats:", Optional.of(STATS_CAPABILITY), STATS_COMMAND_GRANT)));
            HostSecurityContext paperSecurity = paperSecurityContext(claim.instanceIdentity());

            for (EffectEnvelope<? extends EffectPayload> effect : runtimeFlow.platformEffects()) {
                EffectAdmissionReceipt admission = admissionGate.admit(paperSecurity, attachment, effect);
                assertEquals(EffectAdmissionStatus.ACCEPTED, admission.status());
                RewardEffectPayload payload = (RewardEffectPayload) effect.payload();
                if (payload.domain().equals("economy")) {
                    AuthorityCommand<PostLedgerEntry> command = economyAuthority.commandFrom(effect, admission);
                    commandEnvelopes.add(command.envelope());
                    AuthorityDecision<EconomyState, EconomyReceipt> first = economyAuthority.apply(command);
                    AuthorityDecision<EconomyState, EconomyReceipt> duplicate = economyAuthority.apply(command);
                    assertEquals(AuthorityDecisionStatus.ACCEPTED, first.status());
                    assertTrue(duplicate.replayed());
                } else {
                    AuthorityCommand<RecordStatDelta> command = statsAuthority.commandFrom(effect, admission);
                    commandEnvelopes.add(command.envelope());
                    AuthorityDecision<StatsState, StatsReceipt> first = statsAuthority.apply(command);
                    AuthorityDecision<StatsState, StatsReceipt> duplicate = statsAuthority.apply(command);
                    assertEquals(AuthorityDecisionStatus.ACCEPTED, first.status());
                    assertTrue(duplicate.replayed());
                }
            }
            assertEquals(Map.of(SUBJECT_ONE, 100L, SUBJECT_TWO, 100L), economyAuthority.projectionTotals());
            assertEquals(Map.of(SUBJECT_ONE, 1L, SUBJECT_TWO, 1L), statsAuthority.projectionTotals());
            assertEquals(Map.of(SUBJECT_ONE, 1L, SUBJECT_TWO, 1L), statsAuthority.experienceProjectionTotals(EXPERIENCE_ID));
            assertEquals(SUBJECTS.size(), economyAuthority.mutationRuns());
            assertEquals(SUBJECTS.size(), statsAuthority.mutationRuns());

            AuthorityRecord<SessionState> endedRecord = closeSession(commandEnvelopes, sessionFlow.record(), timeline);
            assertEquals(SessionLifecycleStatus.ENDED, endedRecord.state().current().orElseThrow().status());

            WarmFleetProbe warmFleet = new WarmFleetProbe(1);
            warmFleet.reclaim(claim);
            warmFleet.refill(claim.instanceIdentity());
            assertTrue(warmFleet.reclaimedInstances().contains(claim.instanceIdentity().instanceId()));
            assertEquals(1, warmFleet.readyCount());

            assertAllCommandsCarryTrace(commandEnvelopes);
            assertNoHostCanonicalStoreWriteGrants(paperSecurity);
            assertCorrelatedRouteSessionAndEffects(routeFlow, runtimeFlow, attachment, manifest, claim);
            assertTrue(timeline.query(TRACE_ID).stream().map(TraceEvent::label).collect(Collectors.toSet()).containsAll(Set.of(
                    "login-gate",
                    "queue-roster",
                    "allocation-claim",
                    "paper-world-prepared",
                    "route-attempt",
                    "host-attach",
                    "session-active",
                    "fixture-reducer",
                    "session-ended")));
        }
    }

    private static QueueFlow runQueueRoster(
            List<CommandEnvelope<?>> commandEnvelopes,
            TraceTimeline timeline) {
        QueueRosterController controller = new QueueRosterController();
        QueueRosterControlRecord record = QueueRosterController.emptyRecord(QUEUE_FENCING_EPOCH);
        List<QueueRosterEvent> events = new ArrayList<>();

        record = acceptedQueue(controller, record, submit("queue-final-1", SUBJECT_ONE, 2, 0),
                ControlQueueNames.SUBMIT_QUEUE_INTENT, "cmd-queue-submit-1", BASE_TIME, events, commandEnvelopes).record();
        record = acceptedQueue(controller, record, submit("queue-final-2", SUBJECT_TWO, 1, 1),
                ControlQueueNames.SUBMIT_QUEUE_INTENT, "cmd-queue-submit-2", BASE_TIME.plusSeconds(1), events, commandEnvelopes).record();
        record = acceptedQueue(controller, record, formRoster(), ControlQueueNames.FORM_ROSTER_INTENT,
                "cmd-queue-form", BASE_TIME.plusSeconds(2), events, commandEnvelopes).record();

        QueueRosterControlRecord replayed = QueueRosterController.replay(QUEUE_FENCING_EPOCH, events);
        assertEquals(record, replayed);
        RosterIntentSnapshot roster = record.state().rosterIntent(ROSTER_ID).orElseThrow();
        assertEquals(SUBJECTS, roster.subjectIds());
        timeline.record("queue-roster", trace("queue-roster", new InstanceId("instance-controller-queue-final")), roster.rosterIntentId().value());
        return new QueueFlow(record, roster);
    }

    private static HostAllocationClaim allocateRosterThroughProductionAdapter(
            URI allocatorUri,
            RosterIntentSnapshot roster,
            TraceTimeline timeline) {
        RosterAllocationBridge bridge = new RosterAllocationBridge(new AgonesAllocatorRestClient(allocatorUri, "fulcrum-prod"));
        RosterAllocationDecision decision = bridge.allocate(new RosterAllocationRequest(
                roster,
                SESSION_ID,
                RESOLVED_MANIFEST_ID,
                BASE_TIME.plusSeconds(3)));
        assertEquals(RosterAllocationDecisionStatus.ACCEPTED, decision.status());
        HostAllocationClaim claim = decision.claim().orElseThrow();
        assertEquals(SESSION_ID, claim.sessionId());
        assertEquals(RESOLVED_MANIFEST_ID, claim.resolvedManifestId());
        assertEquals(PAPER_POOL_ID, claim.instanceIdentity().poolId());
        assertEquals(HostInstanceKinds.PAPER, claim.instanceIdentity().instanceKind());
        timeline.record("allocation-claim", claim.traceEnvelope(), claim.slotId().value());
        return claim;
    }

    private static ResolvedManifest resolveContentManifest() {
        byte[] mapBytes = bytes("final-e2e-map-template");
        byte[] configBytes = bytes("final-e2e-config");
        List<ContractPin> contractPins = contractPins();
        ContentResolution resolution = new ContentResolver().resolve(
                new ContentResolutionRequest(
                        RESOLVED_MANIFEST_ID,
                        new ArtifactId("artifact.final.code"),
                        EXPERIENCE_ID,
                        MODE_ID,
                        PAPER_POOL_ID,
                        contractPins,
                        HOST_RUNTIME_ABI,
                        Optional.empty(),
                        "resolver-final-e2e"),
                new ContentRotationPolicy(
                        "policy.final.e2e",
                        "policy-final-e2e-1",
                        "catalog-final-e2e-1",
                        List.of(ContentArtifactKind.MAP_TEMPLATE, ContentArtifactKind.CONFIG_MODE)),
                List.of(
                        contentCandidate(MAP_ARTIFACT_ID, ContentArtifactKind.MAP_TEMPLATE, mapBytes, contractPins, 1),
                        contentCandidate(CONFIG_ARTIFACT_ID, ContentArtifactKind.CONFIG_MODE, configBytes, contractPins, 2)));
        assertEquals(ContentResolutionStatus.RESOLVED, resolution.status());
        return resolution.resolvedManifest().orElseThrow();
    }

    private static List<CachedArtifact> preparePaperRuntimeWorld(Path cacheDirectory, ResolvedManifest manifest) throws IOException {
        Map<ArtifactId, byte[]> artifacts = Map.of(
                MAP_ARTIFACT_ID, bytes("final-e2e-map-template"),
                CONFIG_ARTIFACT_ID, bytes("final-e2e-config"));
        ArtifactSource source = artifactId -> {
            byte[] artifact = artifacts.get(artifactId);
            if (artifact == null) {
                throw new IOException("artifact not found: " + artifactId.value());
            }
            return artifact;
        };
        PaperArtifactCache cache = new PaperArtifactCache(cacheDirectory, source);
        List<CachedArtifact> cached = new ArrayList<>();
        for (ArtifactPin pin : manifest.contentArtifacts()) {
            cached.add(cache.pullVerified(pin));
        }
        for (CachedArtifact artifact : cached) {
            assertTrue(Files.exists(artifact.cachedPath()));
        }
        return List.copyOf(cached);
    }

    private static RouteFlow routeSubjects(
            List<CommandEnvelope<?>> commandEnvelopes,
            TraceTimeline timeline,
            HostAllocationClaim claim) {
        RouteAttemptController controller = new RouteAttemptController();
        RouteAttemptControlRecord record = RouteAttemptController.emptyRecord(ROUTE_FENCING_EPOCH);
        List<RouteAttemptEvent> events = new ArrayList<>();

        record = acceptedRoute(controller, record, requestRoute(claim), ControlRouteNames.REQUEST_ROUTE_ATTEMPT,
                "cmd-route-request", BASE_TIME.plusSeconds(4), events, commandEnvelopes).record();
        record = acceptedRoute(controller, record, new IssueProxyRoute(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(5)),
                ControlRouteNames.ISSUE_PROXY_ROUTE, "cmd-route-proxy", BASE_TIME.plusSeconds(5), events, commandEnvelopes).record();
        record = acceptedRoute(controller, record, new PrepareHostRoute(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(6)),
                ControlRouteNames.PREPARE_HOST_ROUTE, "cmd-route-host", BASE_TIME.plusSeconds(6), events, commandEnvelopes).record();

        RouteAttemptControlRecord recovered = RouteAttemptController.replay(ROUTE_FENCING_EPOCH, events);
        assertEquals(record, recovered);
        RouteAttemptController recoveredController = new RouteAttemptController();

        record = acceptedRoute(recoveredController, recovered, new ObserveHostAttach(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(7)),
                ControlRouteNames.OBSERVE_HOST_ATTACH, "cmd-route-attach", BASE_TIME.plusSeconds(7), events, commandEnvelopes).record();
        record = acceptedRoute(recoveredController, record, new AcknowledgeRouteAttempt(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(8)),
                ControlRouteNames.ACKNOWLEDGE_ROUTE_ATTEMPT, "cmd-route-ack", BASE_TIME.plusSeconds(8), events, commandEnvelopes).record();

        HostSecurityContext velocitySecurity = velocitySecurityContext();
        VelocityRouteCommandFactory routeCommandFactory = new VelocityRouteCommandFactory(velocitySecurity);
        for (SubjectId subject : SUBJECTS) {
            CommandEnvelope<RouteCommand> envelope = routeCommandFactory.acknowledgeRoute(
                    new CommandId("cmd-velocity-route-" + subject.value()),
                    new IdempotencyKey("idem-velocity-route-" + subject.value()),
                    trace("velocity-transfer-" + subject.value(), velocitySecurity.identity().instanceId()),
                    new VelocityRouteTransfer(ROUTE_ID, subject, SESSION_ID, claim.instanceIdentity().instanceId(), BASE_TIME.plusSeconds(7)));
            commandEnvelopes.add(envelope);
        }

        assertEquals(RouteAttemptLifecycleStatus.ACKED, record.snapshot().orElseThrow().status());
        timeline.record("route-attempt", trace("route-attempt", new InstanceId("instance-controller-route-final")), record.snapshot().orElseThrow().routeId().value());
        return new RouteFlow(record.snapshot().orElseThrow());
    }

    private static HostSessionAttachment attachPaperSession(
            HostAllocationClaim claim,
            RouteId routeId,
            TraceTimeline timeline) {
        HostSessionAttachment attachment = new HostSessionAttachment(
                claim.instanceIdentity(),
                routeId,
                SUBJECT_ONE,
                SESSION_ID,
                trace("host-attach", claim.instanceIdentity().instanceId()),
                BASE_TIME.plusSeconds(7));
        HostObservation observation = HostObservationFactory.sessionAttached(attachment);
        assertEquals(HostObservationTypes.SESSION_ATTACHED, observation.observationType());
        assertEquals(SESSION_ID.value(), observation.attributes().get("sessionId"));
        timeline.record("host-attach", observation.traceEnvelope(), attachment.routeId().value());
        return attachment;
    }

    private static SessionFlow openAndActivateSession(
            List<CommandEnvelope<?>> commandEnvelopes,
            HostAllocationClaim claim,
            ResolvedManifest manifest,
            TraceTimeline timeline) {
        SessionAuthority authority = new SessionAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityRecord<SessionState> record = SessionAuthority.emptyRecord(SESSION_FENCING_EPOCH);
        AuthorityDecision<SessionState, SessionReceipt> opened = authority.handle(sessionCommand(
                "cmd-session-open",
                "idem-session-open",
                new OpenSession(
                        SESSION_ID,
                        EXPERIENCE_ID,
                        claim.slotId(),
                        claim.instanceIdentity().instanceId(),
                        SESSION_OWNER_TOKEN,
                        manifest.resolvedManifestId(),
                        BASE_TIME.plusSeconds(3),
                        BASE_TIME.plusSeconds(60)),
                record.revision(),
                BASE_TIME.plusSeconds(3),
                commandEnvelopes), record);
        assertEquals(AuthorityDecisionStatus.ACCEPTED, opened.status());
        record = new AuthorityRecord<>(opened.revision(), SESSION_FENCING_EPOCH, opened.state());

        AuthorityDecision<SessionState, SessionReceipt> activated = authority.handle(sessionCommand(
                "cmd-session-activate",
                "idem-session-activate",
                new ActivateSession(
                        SESSION_ID,
                        SESSION_OWNER_TOKEN,
                        1,
                        BASE_TIME.plusSeconds(8),
                        BASE_TIME.plusSeconds(120)),
                record.revision(),
                BASE_TIME.plusSeconds(8),
                commandEnvelopes), record);
        assertEquals(AuthorityDecisionStatus.ACCEPTED, activated.status());
        record = new AuthorityRecord<>(activated.revision(), SESSION_FENCING_EPOCH, activated.state());
        timeline.record("session-active", activated.traceEnvelope(), SESSION_ID.value());
        return new SessionFlow(authority, record);
    }

    private static RuntimeFlow runFixtureSessionRuntime(HostSessionAttachment attachment) {
        List<EffectEnvelope<? extends EffectPayload>> hostLocalEffects = new ArrayList<>();
        List<EffectEnvelope<? extends EffectPayload>> platformEffects = new ArrayList<>();
        HostTickSessionRuntime<FixtureSessionState, FixtureHostEvent, FixtureSessionEvent> runtime = new HostTickSessionRuntime<>(
                new HostTickRuntimeContext(attachment),
                new FixtureSessionState(0),
                hostEvent -> Optional.of(hostEvent.domainEvent()),
                FinalFleetE2eTest::reduceFixtureSession,
                new EffectClassifier(),
                new HostLocalEffectDispatcher(new InlineMainThread()),
                hostLocalEffects::add,
                platformEffects::add);

        runtime.acceptHostEvent(new FixtureHostEvent(new FixtureSessionEvent(
                "fixture.completed",
                SESSION_ID,
                SUBJECTS,
                trace("fixture-domain-event", attachment.instanceIdentity().instanceId()),
                BASE_TIME.plusSeconds(20))));
        assertEquals(new FixtureSessionState(1), runtime.state());
        return new RuntimeFlow(hostLocalEffects, platformEffects);
    }

    private static SessionReduction<FixtureSessionState> reduceFixtureSession(
            FixtureSessionState state,
            FixtureSessionEvent event) {
        List<EffectEnvelope<? extends EffectPayload>> effects = new ArrayList<>();
        effects.add(EffectEnvelope.issue(
                new EffectId("effect-host-local-final"),
                new IdempotencyKey("idem-host-local-final"),
                EffectOrigin.session(event.sessionId()),
                event.traceEnvelope(),
                Optional.empty(),
                new EffectTargetScope("session:" + event.sessionId().value()),
                EffectClass.HOST_LOCAL,
                new HostNoticePayload("session-complete"),
                event.occurredAt(),
                Optional.empty(),
                EffectSettlementMode.HOST_INLINE));
        for (SubjectId subject : event.subjectIds()) {
            effects.add(rewardEffect("economy", ECONOMY_CAPABILITY, subject, 100, event));
            effects.add(rewardEffect("stats", STATS_CAPABILITY, subject, 1, event));
        }
        return SessionReduction.withEffects(new FixtureSessionState(state.handledEvents() + 1), effects);
    }

    private static EffectEnvelope<RewardEffectPayload> rewardEffect(
            String domain,
            CapabilityId capabilityId,
            SubjectId subject,
            int amount,
            FixtureSessionEvent event) {
        return EffectEnvelope.issue(
                new EffectId("effect-" + domain + "-" + subject.value()),
                new IdempotencyKey("idem-" + domain + "-" + subject.value()),
                EffectOrigin.session(event.sessionId()),
                event.traceEnvelope(),
                Optional.of(capabilityId),
                new EffectTargetScope(domain + ":" + subject.value()),
                EffectClass.AUTHORITY,
                new RewardEffectPayload(domain, subject, amount),
                event.occurredAt(),
                Optional.of(event.occurredAt().plusSeconds(30)),
                EffectSettlementMode.ACCEPTED_ASYNC);
    }

    private static AuthorityRecord<SessionState> closeSession(
            List<CommandEnvelope<?>> commandEnvelopes,
            AuthorityRecord<SessionState> activeRecord,
            TraceTimeline timeline) {
        SessionAuthority authority = new SessionAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityDecision<SessionState, SessionReceipt> closed = authority.handle(sessionCommand(
                "cmd-session-close",
                "idem-session-close",
                new CloseSession(SESSION_ID, SESSION_OWNER_TOKEN, 1, BASE_TIME.plusSeconds(30), SessionCloseReason.COMPLETED),
                activeRecord.revision(),
                BASE_TIME.plusSeconds(30),
                commandEnvelopes), activeRecord);
        assertEquals(AuthorityDecisionStatus.ACCEPTED, closed.status());
        timeline.record("session-ended", closed.traceEnvelope(), SESSION_ID.value());
        return new AuthorityRecord<>(closed.revision(), SESSION_FENCING_EPOCH, closed.state());
    }

    private static QueueRosterDecision acceptedQueue(
            QueueRosterController controller,
            QueueRosterControlRecord record,
            QueueRosterCommand payload,
            CommandName commandName,
            String commandId,
            Instant receivedAt,
            List<QueueRosterEvent> events,
            List<CommandEnvelope<?>> commandEnvelopes) {
        QueueRosterControlCommand<? extends QueueRosterCommand> command = queueCommand(
                payload,
                commandName,
                commandId,
                "idem-" + commandId,
                receivedAt,
                Optional.of(record.revision()));
        commandEnvelopes.add(command.envelope());
        QueueRosterDecision decision = controller.handle(command, record);
        assertEquals(QueueRosterDecisionStatus.ACCEPTED, decision.status());
        events.addAll(decision.events());
        return decision;
    }

    private static RouteAttemptDecision acceptedRoute(
            RouteAttemptController controller,
            RouteAttemptControlRecord record,
            RouteAttemptCommand payload,
            CommandName commandName,
            String commandId,
            Instant receivedAt,
            List<RouteAttemptEvent> events,
            List<CommandEnvelope<?>> commandEnvelopes) {
        RouteAttemptControlCommand<? extends RouteAttemptCommand> command = routeCommand(
                payload,
                commandName,
                commandId,
                "idem-" + commandId,
                receivedAt,
                Optional.of(record.revision()));
        commandEnvelopes.add(command.envelope());
        RouteAttemptDecision decision = controller.handle(command, record);
        assertEquals(RouteAttemptDecisionStatus.ACCEPTED, decision.status());
        events.addAll(decision.events());
        return decision;
    }

    private static AuthorityCommand<SessionCommand> sessionCommand(
            String commandId,
            String idempotencyKey,
            SessionCommand payload,
            Revision expectedRevision,
            Instant receivedAt,
            List<CommandEnvelope<?>> commandEnvelopes) {
        CommandEnvelope<SessionCommand> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                SESSION_PRINCIPAL,
                SessionAuthority.aggregateId(payload.sessionId()),
                new ContractName("session"),
                new CommandName(sessionCommandName(payload)),
                trace(commandId, new InstanceId("instance-session-authority-final")),
                Optional.empty(),
                payload);
        commandEnvelopes.add(envelope);
        return new AuthorityCommand<>(
                envelope,
                SESSION_PRINCIPAL,
                SESSION_FENCING_EPOCH,
                Optional.of(expectedRevision),
                payload.getClass().getSimpleName() + ":" + commandId,
                receivedAt);
    }

    private static QueueRosterControlCommand<? extends QueueRosterCommand> queueCommand(
            QueueRosterCommand payload,
            CommandName commandName,
            String commandId,
            String idempotencyKey,
            Instant receivedAt,
            Optional<Revision> expectedRevision) {
        return new QueueRosterControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        QUEUE_PRINCIPAL,
                        ControlQueueNames.aggregateId(payload.partitionKey()),
                        ControlQueueNames.CONTRACT,
                        commandName,
                        trace(commandId, new InstanceId("instance-controller-queue-final")),
                        Optional.of(receivedAt.plusSeconds(30)),
                        payload),
                QUEUE_PRINCIPAL,
                QUEUE_FENCING_EPOCH,
                expectedRevision,
                payload.getClass().getSimpleName() + ":" + commandId,
                receivedAt);
    }

    private static RouteAttemptControlCommand<? extends RouteAttemptCommand> routeCommand(
            RouteAttemptCommand payload,
            CommandName commandName,
            String commandId,
            String idempotencyKey,
            Instant receivedAt,
            Optional<Revision> expectedRevision) {
        return new RouteAttemptControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        ROUTE_PRINCIPAL,
                        ControlRouteNames.aggregateId(payload.routeAttemptId()),
                        ControlRouteNames.CONTRACT,
                        commandName,
                        trace(commandId, new InstanceId("instance-controller-route-final")),
                        Optional.of(receivedAt.plusSeconds(30)),
                        payload),
                ROUTE_PRINCIPAL,
                ROUTE_FENCING_EPOCH,
                expectedRevision,
                payload.getClass().getSimpleName() + ":" + commandId,
                receivedAt);
    }

    private static SubmitQueueIntent submit(String queueIntentId, SubjectId subjectId, int priority, long createdOffsetSeconds) {
        Instant createdAt = BASE_TIME.plusSeconds(createdOffsetSeconds);
        return new SubmitQueueIntent(
                new QueueIntentId(queueIntentId),
                List.of(subjectId),
                EXPERIENCE_ID,
                MODE_ID,
                PAPER_POOL_ID,
                priority,
                createdAt,
                BASE_TIME.plusSeconds(60),
                trace("queue-" + queueIntentId, new InstanceId("instance-controller-queue-final")));
    }

    private static FormRosterIntent formRoster() {
        return new FormRosterIntent(
                ROSTER_ID,
                QUEUE_PARTITION,
                List.of(new QueueIntentId("queue-final-1"), new QueueIntentId("queue-final-2")),
                2,
                BASE_TIME.plusSeconds(2),
                trace("queue-form", new InstanceId("instance-controller-queue-final")));
    }

    private static RequestRouteAttempt requestRoute(HostAllocationClaim claim) {
        return new RequestRouteAttempt(
                ROUTE_ATTEMPT_ID,
                ROUTE_ID,
                SESSION_ID,
                claim.slotId(),
                SUBJECTS,
                List.of(new InstanceId("instance-velocity-final")),
                new PresenceId("presence-final-e2e-1"),
                claim.instanceIdentity().instanceId(),
                RESOLVED_MANIFEST_ID,
                BASE_TIME.plusSeconds(4),
                BASE_TIME.plusSeconds(30),
                trace("route-request", new InstanceId("instance-controller-route-final")));
    }

    private static ContentArtifactCandidate contentCandidate(
            ArtifactId artifactId,
            ContentArtifactKind kind,
            byte[] bytes,
            List<ContractPin> contractPins,
            int rotationOrder) {
        return new ContentArtifactCandidate(
                new ArtifactPin(artifactId, sha256(bytes), kind.name().toLowerCase()),
                kind,
                "catalog-final-e2e-1",
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID.orElseThrow()),
                Set.of(PAPER_POOL_ID),
                contractPins,
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                true,
                100,
                rotationOrder);
    }

    private static HostSecurityContext paperSecurityContext(HostInstanceIdentity identity) {
        return new HostSecurityContext(
                identity,
                "service-account:paper-agent-final",
                HostCredentialScope.of(
                        ECONOMY_COMMAND_GRANT,
                        STATS_COMMAND_GRANT,
                        new HostResourceGrant(HostResourceFamily.ARTIFACT, HostAccessMode.READ, "object://artifacts/final"),
                        new HostResourceGrant(HostResourceFamily.HOT_PROJECTION, HostAccessMode.READ, RankContracts.EFFECTIVE_PROJECTION)));
    }

    private static HostSecurityContext velocitySecurityContext() {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-velocity-final"),
                        HostInstanceKinds.VELOCITY,
                        VELOCITY_POOL_ID,
                        new MachineRef("machine-proxy-final"),
                        VELOCITY_PRINCIPAL),
                "service-account:velocity-agent-final",
                HostCredentialScope.of(ROUTE_COMMAND_GRANT));
    }

    private static void assertProductionShapedProfile(
            DeploymentProfile singleMachine,
            DeploymentProfile smallProduction,
            DeploymentProfile largeProduction) {
        assertEquals("single-machine", singleMachine.profileId());
        assertEquals("small-production", smallProduction.profileId());
        assertEquals("large-production", largeProduction.profileId());
        assertEquals(singleMachine.semanticModel(), smallProduction.semanticModel());
        assertEquals(smallProduction.semanticModel(), largeProduction.semanticModel());
        assertEquals(singleMachine.contractSet(), smallProduction.contractSet());
        assertEquals(smallProduction.contractSet(), largeProduction.contractSet());
        assertEquals("kubernetes-native", smallProduction.agonesMode());
        assertEquals("external-object-store", smallProduction.objectStorage());
        assertFalse(smallProduction.storageShape().equals(singleMachine.storageShape()));
    }

    private static void assertStandardCapabilitiesRemainOutsideKernel() {
        List<CapabilityDescriptor> descriptors = List.of(
                PlayerProfileCapability.descriptor(),
                RankCapability.descriptor(),
                PartyCapability.descriptor(),
                FriendsCapability.descriptor(),
                GuildCapability.descriptor(),
                EconomyCapability.descriptor(),
                StatsCapability.descriptor(),
                PunishmentCapability.descriptor());
        assertEquals(
                Set.of(PlayerProfileCapability.CAPABILITY_ID, RankCapability.CAPABILITY_ID, PartyCapability.CAPABILITY_ID, FriendsCapability.CAPABILITY_ID, GuildCapability.CAPABILITY_ID, EconomyCapability.CAPABILITY_ID, StatsCapability.CAPABILITY_ID, PunishmentCapability.CAPABILITY_ID),
                descriptors.stream().map(CapabilityDescriptor::capabilityId).collect(Collectors.toSet()));
        assertTrue(descriptors.stream().flatMap(descriptor -> descriptor.authorityDomains().stream())
                .allMatch(domain -> domain.resourceClass().equals("standard")));
        assertEquals(PlayerProfileContracts.CONTRACT, PlayerProfileCapability.descriptor().declaredContracts().getFirst().name());
        assertEquals(RankContracts.CONTRACT, RankCapability.descriptor().declaredContracts().getFirst().name());
        assertEquals(PartyContracts.CONTRACT, PartyCapability.descriptor().declaredContracts().getFirst().name());
        assertEquals(FriendsContracts.CONTRACT, FriendsCapability.descriptor().declaredContracts().getFirst().name());
        assertEquals(GuildContracts.CONTRACT, GuildCapability.descriptor().declaredContracts().getFirst().name());
        assertEquals(EconomyContracts.CONTRACT, EconomyCapability.descriptor().declaredContracts().getFirst().name());
        assertEquals(StatsContracts.CONTRACT, StatsCapability.descriptor().declaredContracts().getFirst().name());
        assertEquals(PunishmentContracts.CONTRACT, PunishmentCapability.descriptor().declaredContracts().getFirst().name());
    }

    private static void assertAllCommandsCarryTrace(List<CommandEnvelope<?>> commands) {
        assertFalse(commands.isEmpty());
        assertTrue(commands.stream().allMatch(command -> command.traceEnvelope().traceId().equals(TRACE_ID)));
        assertTrue(commands.stream().allMatch(command -> command.traceEnvelope().originInstanceId() != null));
    }

    private static void assertNoHostCanonicalStoreWriteGrants(HostSecurityContext securityContext) {
        assertTrue(securityContext.credentialScope().grants().stream()
                .noneMatch(grant -> grant.resourceName().contains("postgres")
                        || grant.resourceName().contains("cassandra")
                        || grant.resourceName().contains("canonical")));
    }

    private static void assertCorrelatedRouteSessionAndEffects(
            RouteFlow routeFlow,
            RuntimeFlow runtimeFlow,
            HostSessionAttachment attachment,
            ResolvedManifest manifest,
            HostAllocationClaim claim) {
        assertEquals(ROUTE_ID, routeFlow.snapshot().routeId());
        assertEquals(SESSION_ID, routeFlow.snapshot().sessionId());
        assertEquals(SESSION_ID, attachment.sessionId());
        assertEquals(ROUTE_ID, attachment.routeId());
        assertEquals(RESOLVED_MANIFEST_ID, routeFlow.snapshot().targetResolvedManifestId());
        assertEquals(RESOLVED_MANIFEST_ID, manifest.resolvedManifestId());
        assertEquals(claim.instanceIdentity().instanceId(), routeFlow.snapshot().targetInstanceId());
        assertTrue(runtimeFlow.platformEffects().stream()
                .allMatch(effect -> effect.origin().equals(EffectOrigin.session(SESSION_ID))
                        && effect.traceEnvelope().originInstanceId().equals(claim.instanceIdentity().instanceId())));
    }

    private static List<ContractPin> contractPins() {
        return List.of(
                new ContractPin(PlayerProfileContracts.CONTRACT, "1.0.0"),
                new ContractPin(RankContracts.CONTRACT, "1.0.0"),
                new ContractPin(EconomyContracts.CONTRACT, "1.0.0"),
                new ContractPin(StatsContracts.CONTRACT, "1.0.0"),
                new ContractPin(PunishmentContracts.CONTRACT, "1.0.0"));
    }

    private static String sessionCommandName(SessionCommand payload) {
        if (payload instanceof OpenSession) {
            return "open-session";
        }
        if (payload instanceof ActivateSession) {
            return "activate-session";
        }
        if (payload instanceof CloseSession) {
            return "close-session";
        }
        throw new IllegalArgumentException("unsupported Session command");
    }

    private static TraceEnvelope trace(String spanId, InstanceId originInstanceId) {
        return new TraceEnvelope(TRACE_ID, spanId, Optional.empty(), BASE_TIME, "final-fleet-e2e", originInstanceId);
    }

    private static SubjectId subject(String uuid) {
        return new SubjectId(UUID.fromString(uuid));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private static String agonesAllocationResponse() {
        return """
                {
                  "gameServerName": "agones-gameserver-final",
                  "nodeName": "machine-game-final",
                  "metadata": {
                    "annotations": {
                      "sh.harold.fulcrum/instance-id": "instance-paper-final",
                      "sh.harold.fulcrum/slot-id": "slot-paper-final",
                      "sh.harold.fulcrum/instance-kind": "paper",
                      "sh.harold.fulcrum/principal-id": "%s"
                    }
                  }
                }
                """.formatted(PAPER_PRINCIPAL.value());
    }

    private record QueueFlow(QueueRosterControlRecord record, RosterIntentSnapshot roster) {
    }

    private record RouteFlow(sh.harold.fulcrum.control.route.RouteAttemptSnapshot snapshot) {
    }

    private record SessionFlow(SessionAuthority authority, AuthorityRecord<SessionState> record) {
    }

    private record RuntimeFlow(
            List<EffectEnvelope<? extends EffectPayload>> hostLocalEffects,
            List<EffectEnvelope<? extends EffectPayload>> platformEffects) {
        private RuntimeFlow {
            hostLocalEffects = List.copyOf(hostLocalEffects);
            platformEffects = List.copyOf(platformEffects);
        }
    }

    private record FixtureHostEvent(FixtureSessionEvent domainEvent) {
    }

    private record FixtureSessionEvent(
            String eventType,
            SessionId sessionId,
            List<SubjectId> subjectIds,
            TraceEnvelope traceEnvelope,
            Instant occurredAt) implements SessionDomainEvent {
        private FixtureSessionEvent {
            subjectIds = List.copyOf(subjectIds);
        }
    }

    private record FixtureSessionState(int handledEvents) {
    }

    private record HostNoticePayload(String notice) implements EffectPayload {
        @Override
        public String payloadType() {
            return "fixture.host-notice";
        }
    }

    private record RewardEffectPayload(String domain, SubjectId subjectId, int amount) implements EffectPayload {
        private RewardEffectPayload {
            domain = requireNonBlank(domain, "domain");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            if (amount <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
        }

        @Override
        public String payloadType() {
            return "fixture.reward-effect";
        }
    }

    private static final class EconomyRewardAuthority {
        private final EconomyAuthority authority = new EconomyAuthority(new InMemoryIdempotencyLedger<>());
        private final Map<EconomyAccountId, AuthorityRecord<EconomyState>> records = new HashMap<>();
        private final List<EconomyLedgerEntryRecorded> events = new ArrayList<>();

        private AuthorityCommand<PostLedgerEntry> commandFrom(
                EffectEnvelope<? extends EffectPayload> effect,
                EffectAdmissionReceipt admission) {
            RewardEffectPayload payload = (RewardEffectPayload) effect.payload();
            assertEquals("economy", payload.domain());
            assertEquals(Optional.of(ECONOMY_CAPABILITY), admission.requiredCapability());
            EconomyAccountId accountId = EconomyAuthority.accountId(payload.subjectId(), ECONOMY_CURRENCY);
            AuthorityRecord<EconomyState> currentRecord = record(accountId);
            PostLedgerEntry commandPayload = new PostLedgerEntry(
                    payload.subjectId(),
                    ECONOMY_CURRENCY,
                    payload.amount(),
                    "session-reward",
                    effect.issuedAt(),
                    currentRecord.revision().value());
            CommandEnvelope<PostLedgerEntry> envelope = new CommandEnvelope<>(
                    new CommandId("cmd-economy-" + payload.subjectId().value()),
                    effect.idempotencyKey(),
                    admission.authenticatedPrincipal(),
                    EconomyAuthority.aggregateId(accountId),
                    EconomyContracts.CONTRACT,
                    new CommandName("post-ledger-entry"),
                    effect.traceEnvelope(),
                    effect.deadlineAt(),
                    commandPayload);
            return new AuthorityCommand<>(
                    envelope,
                    admission.authenticatedPrincipal(),
                    REWARD_FENCING_EPOCH,
                    Optional.of(currentRecord.revision()),
                    "economy:" + payload.subjectId().value() + ":" + ECONOMY_CURRENCY + ":" + payload.amount(),
                    effect.issuedAt());
        }

        private AuthorityDecision<EconomyState, EconomyReceipt> apply(AuthorityCommand<PostLedgerEntry> command) {
            EconomyAccountId accountId = command.envelope().payload().accountId();
            AuthorityDecision<EconomyState, EconomyReceipt> decision = authority.handle(command, record(accountId));
            if (decision.status() == AuthorityDecisionStatus.ACCEPTED) {
                records.put(accountId, new AuthorityRecord<>(decision.revision(), REWARD_FENCING_EPOCH, decision.state()));
                if (!decision.replayed()) {
                    events.add(new EconomyLedgerEntryRecorded(
                            decision.response().ledgerEntry().orElseThrow(),
                            decision.revision()));
                }
            }
            return decision;
        }

        private AuthorityRecord<EconomyState> record(EconomyAccountId accountId) {
            return records.computeIfAbsent(accountId, ignored -> EconomyAuthority.emptyRecord(REWARD_FENCING_EPOCH));
        }

        private Map<SubjectId, Long> projectionTotals() {
            return EconomyProjection.rebuild(events).balances().entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> entry.getKey().subjectId(),
                            entry -> entry.getValue().balanceMinorUnits()));
        }

        private int mutationRuns() {
            return events.size();
        }
    }

    private static final class StatsRewardAuthority {
        private final StatsAuthority authority = new StatsAuthority(new InMemoryIdempotencyLedger<>());
        private final Map<StatsCounterId, AuthorityRecord<StatsState>> records = new HashMap<>();
        private final List<StatsDeltaRecorded> events = new ArrayList<>();

        private AuthorityCommand<RecordStatDelta> commandFrom(
                EffectEnvelope<? extends EffectPayload> effect,
                EffectAdmissionReceipt admission) {
            RewardEffectPayload payload = (RewardEffectPayload) effect.payload();
            assertEquals("stats", payload.domain());
            assertEquals(Optional.of(STATS_CAPABILITY), admission.requiredCapability());
            StatsCounterId counterId = StatsAuthority.counterId(payload.subjectId(), SESSION_COMPLETIONS_STAT);
            AuthorityRecord<StatsState> currentRecord = record(counterId);
            RecordStatDelta commandPayload = new RecordStatDelta(
                    payload.subjectId(),
                    EXPERIENCE_ID,
                    SESSION_COMPLETIONS_STAT,
                    payload.amount(),
                    effect.issuedAt(),
                    currentRecord.revision().value());
            CommandEnvelope<RecordStatDelta> envelope = new CommandEnvelope<>(
                    new CommandId("cmd-stats-" + payload.subjectId().value()),
                    effect.idempotencyKey(),
                    admission.authenticatedPrincipal(),
                    StatsAuthority.aggregateId(counterId),
                    StatsContracts.CONTRACT,
                    new CommandName("record-stat-delta"),
                    effect.traceEnvelope(),
                    effect.deadlineAt(),
                    commandPayload);
            return new AuthorityCommand<>(
                    envelope,
                    admission.authenticatedPrincipal(),
                    REWARD_FENCING_EPOCH,
                    Optional.of(currentRecord.revision()),
                    "stats:" + payload.subjectId().value() + ":" + EXPERIENCE_ID.value() + ":" + SESSION_COMPLETIONS_STAT + ":" + payload.amount(),
                    effect.issuedAt());
        }

        private AuthorityDecision<StatsState, StatsReceipt> apply(AuthorityCommand<RecordStatDelta> command) {
            StatsCounterId counterId = command.envelope().payload().counterId();
            AuthorityDecision<StatsState, StatsReceipt> decision = authority.handle(command, record(counterId));
            if (decision.status() == AuthorityDecisionStatus.ACCEPTED) {
                records.put(counterId, new AuthorityRecord<>(decision.revision(), REWARD_FENCING_EPOCH, decision.state()));
                if (!decision.replayed()) {
                    events.add(new StatsDeltaRecorded(
                            decision.response().ledgerEntry().orElseThrow(),
                            decision.revision()));
                }
            }
            return decision;
        }

        private AuthorityRecord<StatsState> record(StatsCounterId counterId) {
            return records.computeIfAbsent(counterId, ignored -> StatsAuthority.emptyRecord(REWARD_FENCING_EPOCH));
        }

        private Map<SubjectId, Long> projectionTotals() {
            return StatsProjection.rebuild(events).counters().entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> entry.getKey().subjectId(),
                            entry -> entry.getValue().total()));
        }

        private Map<SubjectId, Long> experienceProjectionTotals(ExperienceId experienceId) {
            StatsProjection projection = StatsProjection.rebuild(events);
            return records.keySet().stream()
                    .collect(Collectors.toMap(
                            StatsCounterId::subjectId,
                            counterId -> projection.experienceCounter(counterId, experienceId).orElseThrow().total()));
        }

        private int mutationRuns() {
            return events.size();
        }
    }

    private static final class InlineMainThread implements sh.harold.fulcrum.host.tick.HostMainThread {
        @Override
        public boolean isMainThread() {
            return true;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }

    private static final class WarmFleetProbe {
        private final Set<InstanceId> reclaimedInstances = new HashSet<>();
        private int readyCount;

        private WarmFleetProbe(int readyCount) {
            this.readyCount = readyCount;
        }

        private void reclaim(HostAllocationClaim claim) {
            reclaimedInstances.add(claim.instanceIdentity().instanceId());
            readyCount = Math.max(0, readyCount - 1);
        }

        private void refill(HostInstanceIdentity identity) {
            if (!reclaimedInstances.contains(identity.instanceId())) {
                throw new IllegalStateException("cannot refill before reclaim");
            }
            readyCount++;
        }

        private Set<InstanceId> reclaimedInstances() {
            return Set.copyOf(reclaimedInstances);
        }

        private int readyCount() {
            return readyCount;
        }
    }

    private static final class TraceTimeline {
        private final List<TraceEvent> events = new ArrayList<>();

        private void record(String label, TraceEnvelope traceEnvelope, String correlationId) {
            events.add(new TraceEvent(label, traceEnvelope, correlationId));
        }

        private List<TraceEvent> query(String traceId) {
            return events.stream()
                    .filter(event -> event.traceEnvelope().traceId().equals(traceId))
                    .toList();
        }
    }

    private record TraceEvent(String label, TraceEnvelope traceEnvelope, String correlationId) {
    }

    private record DeploymentProfile(
            String profileId,
            String semanticModel,
            String contractSet,
            String servicePlacement,
            String storageShape,
            String agonesMode,
            String objectStorage) {
        private static final Pattern FIELD_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");

        private static DeploymentProfile load(String profileId) throws IOException {
            String path = "/fulcrum/profiles/" + profileId + ".json";
            try (InputStream input = FinalFleetE2eTest.class.getResourceAsStream(path)) {
                if (input == null) {
                    throw new IOException("missing profile resource: " + path);
                }
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> fields = FIELD_PATTERN.matcher(json)
                        .results()
                        .collect(Collectors.toMap(match -> match.group(1), match -> match.group(2)));
                return new DeploymentProfile(
                        field(fields, "profileId"),
                        field(fields, "semanticModel"),
                        field(fields, "contractSet"),
                        field(fields, "servicePlacement"),
                        field(fields, "storageShape"),
                        field(fields, "agonesMode"),
                        field(fields, "objectStorage"));
            }
        }

        private static String field(Map<String, String> fields, String key) {
            String value = fields.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("profile field missing: " + key);
            }
            return value;
        }
    }

    private static final class AllocatorFixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> requestBody = new AtomicReference<>();

        private AllocatorFixture(HttpServer server) {
            this.server = server;
        }

        private static AllocatorFixture responding(String responseBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            AllocatorFixture fixture = new AllocatorFixture(server);
            server.createContext("/gameserverallocation", exchange -> fixture.handle(exchange, responseBody));
            server.start();
            return fixture;
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private String requestBody() {
            return requestBody.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange, String responseBody) throws IOException {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
