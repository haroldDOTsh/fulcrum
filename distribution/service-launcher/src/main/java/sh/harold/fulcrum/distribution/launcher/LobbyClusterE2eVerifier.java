package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import sh.harold.fulcrum.adapters.objectstorage.S3ObjectStorageAdapter;
import sh.harold.fulcrum.distribution.launcher.MinecraftStatusClient.MinecraftStatusSnapshot;
import sh.harold.fulcrum.distribution.launcher.MinecraftStatusClient.LoginAttemptResult;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest;
import sh.harold.fulcrum.control.lifecycle.ControlLifecycleNames;
import sh.harold.fulcrum.control.lifecycle.LifecyclePhase;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlRecord;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceEntry;
import sh.harold.fulcrum.control.lifecycle.RecordLifecycleObservation;
import sh.harold.fulcrum.control.route.ControlRouteNames;
import sh.harold.fulcrum.control.route.IssueProxyRoute;
import sh.harold.fulcrum.control.route.PrepareHostRoute;
import sh.harold.fulcrum.control.route.RequestRouteAttempt;
import sh.harold.fulcrum.control.route.RouteAttemptCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlRecord;
import sh.harold.fulcrum.control.route.RouteAttemptId;
import sh.harold.fulcrum.control.route.RouteAttemptLifecycleStatus;
import sh.harold.fulcrum.control.route.RouteAttemptSnapshot;
import sh.harold.fulcrum.control.queue.ControlQueueNames;
import sh.harold.fulcrum.control.queue.FormRosterIntent;
import sh.harold.fulcrum.control.queue.QueueIntentId;
import sh.harold.fulcrum.control.queue.QueueIntentSnapshot;
import sh.harold.fulcrum.control.queue.QueueIntentStatus;
import sh.harold.fulcrum.control.queue.QueueRosterCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlRecord;
import sh.harold.fulcrum.control.queue.RosterIntentId;
import sh.harold.fulcrum.control.queue.RosterIntentSnapshot;
import sh.harold.fulcrum.control.queue.RosterIntentStatus;
import sh.harold.fulcrum.control.queue.SubmitQueueIntent;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.presence.ClaimPresence;
import sh.harold.fulcrum.data.presence.PresenceAuthority;
import sh.harold.fulcrum.data.presence.PresenceCommand;
import sh.harold.fulcrum.data.presence.PresenceLifecycleStatus;
import sh.harold.fulcrum.data.presence.PresenceSnapshot;
import sh.harold.fulcrum.data.presence.PresenceState;
import sh.harold.fulcrum.data.route.RouteAuthority;
import sh.harold.fulcrum.data.route.RouteLifecycleStatus;
import sh.harold.fulcrum.data.route.RouteSnapshot;
import sh.harold.fulcrum.data.route.RouteState;
import sh.harold.fulcrum.data.route.contract.AcknowledgeRoute;
import sh.harold.fulcrum.data.route.contract.OpenRoute;
import sh.harold.fulcrum.data.route.contract.RouteCommand;
import sh.harold.fulcrum.data.session.ActivateSession;
import sh.harold.fulcrum.data.session.OpenSession;
import sh.harold.fulcrum.data.session.SessionAuthority;
import sh.harold.fulcrum.data.session.SessionCommand;
import sh.harold.fulcrum.data.session.SessionLifecycleStatus;
import sh.harold.fulcrum.data.session.SessionSnapshot;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationTypes;
import sh.harold.fulcrum.host.api.HostObservationWireCodec;
import sh.harold.fulcrum.host.paper.PaperLobbyProofMessage;
import sh.harold.fulcrum.host.velocity.VelocityProxyRouteCommand;
import sh.harold.fulcrum.standard.contracts.EconomyContracts;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;
import sh.harold.fulcrum.standard.contracts.RankContracts;
import sh.harold.fulcrum.standard.contracts.StatsContracts;
import sh.harold.fulcrum.standard.economy.EconomyAuthority;
import sh.harold.fulcrum.standard.economy.EconomyBalanceSnapshot;
import sh.harold.fulcrum.standard.economy.EconomyState;
import sh.harold.fulcrum.standard.economy.PostLedgerEntry;
import sh.harold.fulcrum.standard.profile.PlayerProfileAuthority;
import sh.harold.fulcrum.standard.profile.PlayerProfileSnapshot;
import sh.harold.fulcrum.standard.profile.PlayerProfileState;
import sh.harold.fulcrum.standard.profile.UpsertPlayerProfile;
import sh.harold.fulcrum.standard.punishment.ActivePunishmentSnapshot;
import sh.harold.fulcrum.standard.punishment.IssuePunishment;
import sh.harold.fulcrum.standard.punishment.PunishmentAuthority;
import sh.harold.fulcrum.standard.punishment.PunishmentState;
import sh.harold.fulcrum.standard.rank.EffectiveRankSnapshot;
import sh.harold.fulcrum.standard.rank.GrantRank;
import sh.harold.fulcrum.standard.rank.RankAuthority;
import sh.harold.fulcrum.standard.rank.RankState;
import sh.harold.fulcrum.standard.stats.StatsCounterSnapshot;
import sh.harold.fulcrum.standard.stats.StatsState;
import sh.harold.fulcrum.standard.stats.RecordStatDelta;
import sh.harold.fulcrum.standard.stats.StatsAuthority;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class LobbyClusterE2eVerifier {
    private static final double POSITION_TOLERANCE = 0.001D;
    private static final String VELOCITY_LOGIN_PRINCIPAL_ID = "principal-fulcrum-velocity-agent";
    private static final String VELOCITY_LOGIN_ORIGIN_SERVICE = "velocity-login-routing";
    private static final String PAPER_AGENT_PRINCIPAL_ID = "principal-fulcrum-paper-agent";
    private static final String PAPER_AGENT_ORIGIN_SERVICE = "paper-agent";
    private static final long PAPER_REWARD_STATS_DELTA = 1L;
    private static final Pattern PORT_FORWARD_READY =
            Pattern.compile("Forwarding from 127\\.0\\.0\\.1:(\\d+) -> \\d+");
    private static final List<String> CASSANDRA_PRESENCE_COLUMNS = List.of(
            "subject_id", "presence_id", "lifecycle_status", "session_id", "route_id", "observed_at", "expires_at");
    private static final List<String> CASSANDRA_ROUTE_COLUMNS = List.of(
            "route_id", "subject_id", "target_session_id", "target_instance_id", "lifecycle_status",
            "requested_at", "completed_at");
    private static final List<String> CASSANDRA_SESSION_COLUMNS = List.of(
            "session_id", "experience_id", "slot_id", "owner_instance_id", "resolved_manifest_id",
            "lifecycle_status", "lease_expires_at", "activated_at");
    private static final List<String> CASSANDRA_PROFILE_COLUMNS = List.of(
            "subject_id", "display_name", "updated_by", "observed_at");
    private static final List<String> CASSANDRA_RANK_COLUMNS = List.of(
            "subject_id", "primary_rank_key", "permissions", "updated_by", "updated_at");
    private static final List<String> CASSANDRA_PUNISHMENT_COLUMNS = List.of(
            "subject_id", "reason", "issued_by", "issued_at", "expires_at");
    private static final List<String> CASSANDRA_ECONOMY_COLUMNS = List.of(
            "subject_id", "currency_key", "balance_minor_units", "last_entry_id", "updated_by", "updated_at");
    private static final List<String> CASSANDRA_STATS_COLUMNS = List.of(
            "subject_id", "stat_key", "total", "last_entry_id", "updated_by", "updated_at");

    private LobbyClusterE2eVerifier() {
    }

    public static void main(String[] args) throws Exception {
        VerificationConfig config = VerificationConfig.parse(args);
        MinecraftStatusClient client = new MinecraftStatusClient();
        EndpointStatus endpointStatus = waitForEndpointStatus(config, client);
        ResolvedMinecraftEndpoint endpoint = endpointStatus.endpoint();
        MinecraftStatusSnapshot status = endpointStatus.status();
        int loginProtocolVersion = config.protocolVersion() == 0
                ? status.protocolVersion()
                : config.protocolVersion();
        Instant routeAttemptStateFreshnessFloor = Instant.now().minus(config.routeAttemptStateFreshnessSkew());
        Instant sessionAuthorityStateFreshnessFloor =
                Instant.now().minus(config.sessionAuthorityStateFreshnessSkew());
        Instant presenceAuthorityStateFreshnessFloor =
                Instant.now().minus(config.presenceAuthorityStateFreshnessSkew());
        PaperLobbyProofMessage lobbyProof = client.lobbyProof(
                new InetSocketAddress(endpoint.host(), endpoint.port()),
                loginProtocolVersion,
                config.loginUsername(),
                config.timeout());
        verifyLobbyProof(
                "primary accepted login",
                config,
                lobbyProof,
                config.loginUsername(),
                config.expectedDisplayName(),
                config.expectedRankLabel(),
                config.expectedDecoratedChatContains());
        PaperLobbyProofMessage secondLobbyProof = client.lobbyProof(
                new InetSocketAddress(endpoint.host(), endpoint.port()),
                loginProtocolVersion,
                config.secondLoginUsername(),
                config.timeout());
        verifyLobbyProof(
                "second accepted login",
                config,
                secondLobbyProof,
                config.secondLoginUsername(),
                config.expectedSecondDisplayName(),
                config.expectedSecondRankLabel(),
                config.expectedSecondDecoratedChatContains());
        verifySameSharedShard(lobbyProof, secondLobbyProof);
        List<RouteAttemptExpectation> routeAttemptExpectations = new ArrayList<>();
        routeAttemptExpectations.add(RouteAttemptExpectation.from(
                "primary accepted login",
                config.loginUsername(),
                lobbyProof,
                routeAttemptStateFreshnessFloor));
        routeAttemptExpectations.add(RouteAttemptExpectation.from(
                "second accepted login",
                config.secondLoginUsername(),
                secondLobbyProof,
                routeAttemptStateFreshnessFloor));
        List<StandardCapabilityStateExpectation> standardCapabilityStateExpectations = new ArrayList<>();
        standardCapabilityStateExpectations.add(StandardCapabilityStateExpectation.from(
                "primary accepted login",
                config.loginUsername(),
                lobbyProof,
                config.expectedDisplayName(),
                config.expectedRankLabel()));
        standardCapabilityStateExpectations.add(StandardCapabilityStateExpectation.from(
                "second accepted login",
                config.secondLoginUsername(),
                secondLobbyProof,
                config.expectedSecondDisplayName(),
                config.expectedSecondRankLabel()));
        Optional<ScaleOutProof> scaleOutProof = Optional.empty();
        if (config.verifyScaleOut()) {
            scaleOutProof = Optional.of(verifyScaleOut(config, endpoint, client, loginProtocolVersion, lobbyProof));
            routeAttemptExpectations.add(RouteAttemptExpectation.from(
                    "scale-out accepted login",
                    config.scaleOutLoginUsername(),
                    scaleOutProof.orElseThrow().acceptedLoginProof(),
                    routeAttemptStateFreshnessFloor));
            standardCapabilityStateExpectations.add(StandardCapabilityStateExpectation.from(
                    "scale-out accepted login",
                    config.scaleOutLoginUsername(),
                    scaleOutProof.orElseThrow().acceptedLoginProof(),
                    config.expectedScaleOutDisplayName(),
                    config.expectedScaleOutRankLabel()));
        }
        Optional<LoginAttemptResult> deniedLogin = Optional.empty();
        Optional<PunishmentCapabilityStateExpectation> deniedPunishmentStateExpectation = Optional.empty();
        List<DeniedRouteAttemptExpectation> deniedRouteAttemptExpectations = new ArrayList<>();
        List<DeniedPresenceAuthorityStateExpectation> deniedPresenceStateExpectations = new ArrayList<>();
        if (config.deniedLoginUsername().isPresent()) {
            String username = config.deniedLoginUsername().orElseThrow();
            LoginAttemptResult result = client.login(
                    new InetSocketAddress(endpoint.host(), endpoint.port()),
                    loginProtocolVersion,
                    username,
                    config.timeout());
            if (result.accepted()) {
                throw new IOException("Expected denied login for " + username
                        + ", but Velocity accepted the login");
            }
            config.deniedLoginReasonContains().ifPresent(expected -> {
                String actual = result.denialReason().orElse("");
                if (!actual.contains(expected)) {
                    throw new IllegalStateException("Expected denied login reason to contain '" + expected
                            + "', got " + actual);
                }
            });
            deniedRouteAttemptExpectations.add(DeniedRouteAttemptExpectation.from(
                    "denied login",
                    username,
                    routeAttemptStateFreshnessFloor));
            deniedPresenceStateExpectations.add(DeniedPresenceAuthorityStateExpectation.from(
                    "denied login",
                    username,
                    presenceAuthorityStateFreshnessFloor));
            deniedPunishmentStateExpectation = Optional.of(PunishmentCapabilityStateExpectation.from(
                    "denied login",
                    username,
                    config.deniedLoginReasonContains(),
                    Instant.now()));
            deniedLogin = Optional.of(result);
        }
        List<RouteAuthorityCommandExpectation> routeAuthorityCommandExpectations =
                routeAuthorityCommandExpectations(routeAttemptExpectations);
        List<RouteAuthorityStateExpectation> routeAuthorityStateExpectations =
                routeAuthorityStateExpectations(routeAttemptExpectations);
        List<DeniedRouteAuthorityStateExpectation> deniedRouteAuthorityStateExpectations =
                deniedRouteAuthorityStateExpectations(deniedRouteAttemptExpectations);
        List<PresenceAuthorityStateExpectation> presenceAuthorityStateExpectations =
                presenceAuthorityStateExpectations(
                        routeAttemptExpectations,
                        presenceAuthorityStateFreshnessFloor);
        List<SessionAuthorityCommandExpectation> sessionAuthorityCommandExpectations =
                sessionAuthorityCommandExpectations(
                        routeAttemptExpectations,
                        sessionAuthorityStateFreshnessFloor);
        List<SessionAuthorityStateExpectation> sessionAuthorityStateExpectations =
                sessionAuthorityStateExpectations(
                        routeAttemptExpectations,
                        sessionAuthorityStateFreshnessFloor);
        Optional<RouteAttemptStateResult> routeAttemptState = verifyRouteAttemptState(
                config,
                routeAttemptExpectations,
                deniedRouteAttemptExpectations);
        Optional<RouteAuthorityCommandLogResult> routeAuthorityCommandLog = verifyRouteAuthorityCommandLog(
                config,
                routeAuthorityCommandExpectations,
                deniedRouteAuthorityCommandExpectations(deniedRouteAttemptExpectations));
        Optional<RouteAuthorityStateResult> routeAuthorityState = verifyRouteAuthorityState(
                config,
                routeAuthorityStateExpectations,
                deniedRouteAuthorityStateExpectations);
        List<LoginRoutingCommandExpectation> loginRoutingExpectations =
                loginRoutingCommandExpectations(routeAttemptExpectations);
        List<DeniedLoginRoutingCommandExpectation> deniedLoginRoutingExpectations =
                deniedLoginRoutingCommandExpectations(deniedRouteAttemptExpectations);
        Optional<LoginRoutingCommandLogResult> loginRoutingCommandLog = verifyLoginRoutingCommandLog(
                config,
                loginRoutingExpectations,
                deniedLoginRoutingExpectations);
        Optional<QueueRosterStateResult> queueRosterState = verifyQueueRosterState(
                config,
                loginRoutingExpectations,
                deniedLoginRoutingExpectations);
        Optional<LifecycleTraceStateResult> lifecycleTraceState = verifyLifecycleTraceState(
                config,
                loginRoutingExpectations,
                deniedLoginRoutingExpectations);
        Optional<HostRouteCommandLogResult> hostRouteCommandLog = verifyHostRouteCommandLogs(
                config,
                hostRouteCommandExpectations(routeAttemptExpectations),
                deniedHostRouteCommandExpectations(deniedRouteAttemptExpectations));
        Optional<HostObservationLogResult> hostObservationLog = verifyHostObservationLog(
                config,
                hostObservationExpectations(
                        routeAttemptExpectations,
                        config.expectedPoolId(),
                        routeAttemptStateFreshnessFloor),
                deniedHostObservationExpectations(
                        deniedRouteAttemptExpectations,
                        routeAttemptStateFreshnessFloor));
        Optional<PresenceAuthorityStateResult> presenceAuthorityState = verifyPresenceAuthorityState(
                config,
                presenceAuthorityStateExpectations,
                deniedPresenceStateExpectations);
        Optional<StandardCapabilityCommandLogResult> standardCapabilityCommandLog =
                verifyStandardCapabilityCommandLog(
                        config,
                        standardCapabilityStateExpectations,
                        deniedPunishmentStateExpectation);
        List<RewardCommandExpectation> rewardCommandExpectations =
                rewardCommandExpectations(routeAttemptExpectations, config);
        List<DeniedRewardCommandExpectation> deniedRewardCommandExpectations =
                deniedRewardCommandExpectations(deniedRouteAttemptExpectations);
        Optional<RewardCommandLogResult> rewardCommandLog = verifyRewardCommandLog(
                config,
                rewardCommandExpectations,
                deniedRewardCommandExpectations);
        Optional<RewardStateResult> rewardState = verifyRewardState(
                config,
                rewardCommandExpectations,
                deniedRewardCommandExpectations);
        Optional<StandardCapabilityStateResult> standardCapabilityState = verifyStandardCapabilityState(
                config,
                standardCapabilityStateExpectations,
                deniedPunishmentStateExpectation);
        Optional<SessionAuthorityCommandLogResult> sessionAuthorityCommandLog = verifySessionAuthorityCommandLog(
                config,
                sessionAuthorityCommandExpectations);
        Optional<SessionAuthorityStateResult> sessionAuthorityState = verifySessionAuthorityState(
                config,
                sessionAuthorityStateExpectations);
        List<SharedShardAllocationStateExpectation> sharedShardAllocationExpectations =
                sharedShardAllocationStateExpectations(routeAttemptExpectations);
        Optional<SharedShardAllocationCommandLogResult> sharedShardAllocationCommandLog =
                verifySharedShardAllocationCommandLog(
                        config,
                        sharedShardAllocationExpectations);
        Optional<SharedShardAllocationStateResult> sharedShardAllocationState = verifySharedShardAllocationState(
                config,
                sharedShardAllocationExpectations);
        Optional<AgonesFleetStateResult> agonesFleetState = verifyAgonesFleetState(
                config,
                routeAttemptExpectations);
        Optional<CassandraHotProjectionResult> cassandraHotProjections = verifyCassandraHotProjections(
                config,
                presenceAuthorityStateExpectations,
                deniedPresenceStateExpectations,
                routeAuthorityStateExpectations,
                deniedRouteAuthorityStateExpectations,
                sessionAuthorityStateExpectations,
                standardCapabilityStateExpectations,
                deniedPunishmentStateExpectation,
                rewardCommandExpectations,
                deniedRewardCommandExpectations);
        Optional<PostgresAuthorityRecordResult> postgresAuthorityRecords = verifyPostgresAuthorityRecords(
                config,
                presenceAuthorityStateExpectations,
                deniedPresenceStateExpectations,
                routeAuthorityStateExpectations,
                deniedRouteAuthorityStateExpectations,
                sessionAuthorityStateExpectations,
                standardCapabilityStateExpectations,
                deniedPunishmentStateExpectation,
                rewardCommandExpectations,
                deniedRewardCommandExpectations);
        Optional<ValkeyCacheResult> valkeyCache = verifyValkeyCache(
                config,
                presenceAuthorityStateExpectations,
                deniedPresenceStateExpectations,
                standardCapabilityStateExpectations,
                deniedPunishmentStateExpectation,
                rewardCommandExpectations,
                deniedRewardCommandExpectations);
        Optional<ObjectStoreArtifactResult> objectStoreArtifact = verifyObjectStoreArtifact(config);
        Optional<ProjectionConsistencyResult> projectionConsistency = verifyProjectionConsistency(
                config,
                routeAttemptState,
                routeAuthorityState,
                queueRosterState,
                lifecycleTraceState,
                presenceAuthorityState,
                standardCapabilityState,
                rewardState,
                sessionAuthorityState,
                sharedShardAllocationState,
                cassandraHotProjections,
                postgresAuthorityRecords,
                valkeyCache);
        Optional<TraceCorrelationResult> traceCorrelation = verifyTraceCorrelation(
                config,
                routeAttemptState,
                routeAuthorityCommandLog,
                routeAuthorityState,
                loginRoutingCommandLog,
                lifecycleTraceState,
                hostRouteCommandLog,
                hostObservationLog,
                standardCapabilityCommandLog,
                rewardCommandLog,
                sessionAuthorityCommandLog,
                sharedShardAllocationCommandLog,
                sharedShardAllocationState,
                agonesFleetState);
        System.out.printf(
                "Verified Minecraft status at %s:%d: version=%s protocol=%d online=%d max=%d%n",
                endpoint.host(),
                endpoint.port(),
                status.versionName(),
                status.protocolVersion(),
                status.onlinePlayers(),
                status.maxPlayers());
        System.out.printf(
                "Verified accepted Minecraft login and lobby proof for %s through %s:%d: displayName=%s rank=%s%n",
                config.loginUsername(),
                endpoint.host(),
                endpoint.port(),
                lobbyProof.displayName(),
                lobbyProof.rankLabel().orElse("<none>"));
        System.out.printf(
                "Verified second accepted Minecraft login joined same lobby Session for %s: instance=%s session=%s slot=%s%n",
                config.secondLoginUsername(),
                secondLobbyProof.instanceId().value(),
                secondLobbyProof.sessionId().value(),
                secondLobbyProof.slotId().value());
        scaleOutProof.ifPresent(proof -> System.out.printf(
                "Verified full lobby scale-out: %s was denied to trigger allocation, then %s joined instance=%s session=%s slot=%s%n",
                proof.triggerDeniedLogin().username(),
                config.scaleOutLoginUsername(),
                proof.acceptedLoginProof().instanceId().value(),
                proof.acceptedLoginProof().sessionId().value(),
                proof.acceptedLoginProof().slotId().value()));
        routeAttemptState.ifPresent(result -> System.out.printf(
                "Verified controller route-attempt ACK state for %d Minecraft login(s) from %d record(s) on %s%n",
                result.matchedCount(),
                result.recordsScanned(),
                config.routeAttemptStateTopic()));
        routeAuthorityCommandLog.ifPresent(result -> System.out.printf(
                "Verified Route authority open/ack command log for %d route(s) from %d command(s) on %s%n",
                result.matchedCount(),
                result.recordsScanned(),
                config.routeAuthorityCommandTopic()));
        routeAuthorityState.ifPresent(result -> System.out.printf(
                "Verified Route authority ACKNOWLEDGED state for %d route(s) from %d record(s) on %s%n",
                result.matchedCount(),
                result.recordsScanned(),
                config.routeAuthorityStateTopic()));
        loginRoutingCommandLog.ifPresent(result -> System.out.printf(
                "Verified Velocity login-routing command logs for %d accepted login(s): queueRosterCommands=%d presenceCommands=%d placementRequests=%d routeAttemptCommands=%d lifecycleTraceCommands=%d%n",
                result.matchedCount(),
                result.queueRosterCommandsScanned(),
                result.presenceCommandsScanned(),
                result.placementRequestsScanned(),
                result.routeAttemptCommandsScanned(),
                result.lifecycleTraceCommandsScanned()));
        queueRosterState.ifPresent(result -> System.out.printf(
                "Verified queue-roster controller state for %d accepted login(s) from %d record(s) on %s%n",
                result.matchedCount(),
                result.recordsScanned(),
                config.queueRosterStateTopic()));
        lifecycleTraceState.ifPresent(result -> System.out.printf(
                "Verified lifecycle trace state for %d accepted login(s) from %d record(s) on %s%n",
                result.matchedCount(),
                result.recordsScanned(),
                config.lifecycleTraceStateTopic()));
        hostRouteCommandLog.ifPresent(result -> System.out.printf(
                "Verified addressed host route commands for %d route(s): proxyCommands=%d paperCommands=%d%n",
                result.matchedCount(),
                result.proxyRecordsScanned(),
                result.paperRecordsScanned()));
        hostObservationLog.ifPresent(result -> System.out.printf(
                "Verified Paper host session-attached observations for %d Minecraft login(s) from %d observation(s) on %s%n",
                result.matchedCount(),
                result.recordsScanned(),
                config.hostObservationTopic()));
        presenceAuthorityState.ifPresent(result -> System.out.printf(
                "Verified Presence authority LIVE state for %d accepted login(s) from %d record(s) on %s%n",
                result.matchedCount(),
                result.recordsScanned(),
                config.presenceAuthorityStateTopic()));
        standardCapabilityState.ifPresent(result -> System.out.printf(
                "Verified standard capability state for %d accepted login(s): profileRecords=%d rankRecords=%d punishmentRecords=%d%n",
                result.matchedCount(),
                result.profileRecordsScanned(),
                result.rankRecordsScanned(),
                result.punishmentRecordsScanned()));
        standardCapabilityCommandLog.ifPresent(result -> System.out.printf(
                "Verified standard capability command logs for %d accepted login(s): profileCommands=%d rankCommands=%d punishmentCommands=%d%n",
                result.matchedCount(),
                result.profileCommandsScanned(),
                result.rankCommandsScanned(),
                result.punishmentCommandsScanned()));
        rewardCommandLog.ifPresent(result -> System.out.printf(
                "Verified Paper reward command logs for %d accepted login(s): economyCommands=%d statsCommands=%d%n",
                result.matchedCount(),
                result.economyCommandsScanned(),
                result.statsCommandsScanned()));
        rewardState.ifPresent(result -> System.out.printf(
                "Verified Paper reward authority state for %d accepted login(s): economyRecords=%d statsRecords=%d%n",
                result.matchedCount(),
                result.economyRecordsScanned(),
                result.statsRecordsScanned()));
        cassandraHotProjections.ifPresent(result -> System.out.printf(
                "Verified Cassandra hot projections for %d accepted login(s): presenceRows=%d routeRows=%d sessionRows=%d profileRows=%d rankRows=%d punishmentRows=%d economyRows=%d statsRows=%d%n",
                result.matchedCount(),
                result.presenceRowsScanned(),
                result.routeRowsScanned(),
                result.sessionRowsScanned(),
                result.profileRowsScanned(),
                result.rankRowsScanned(),
                result.punishmentRowsScanned(),
                result.economyRowsScanned(),
                result.statsRowsScanned()));
        postgresAuthorityRecords.ifPresent(result -> System.out.printf(
                "Verified PostgreSQL authority records for %d accepted login(s): presenceRows=%d routeRows=%d sessionRows=%d profileRows=%d rankRows=%d punishmentRows=%d economyRows=%d statsRows=%d%n",
                result.matchedCount(),
                result.presenceRowsScanned(),
                result.routeRowsScanned(),
                result.sessionRowsScanned(),
                result.profileRowsScanned(),
                result.rankRowsScanned(),
                result.punishmentRowsScanned(),
                result.economyRowsScanned(),
                result.statsRowsScanned()));
        valkeyCache.ifPresent(result -> System.out.printf(
                "Verified Valkey cache for %d accepted login(s): checkedKeys=%d presenceKeys=%d profileKeys=%d rankKeys=%d punishmentKeys=%d economyKeys=%d statsKeys=%d%n",
                result.matchedCount(),
                result.cacheKeysChecked(),
                result.presenceKeysChecked(),
                result.profileKeysChecked(),
                result.rankKeysChecked(),
                result.punishmentKeysChecked(),
                result.economyKeysChecked(),
                result.statsKeysChecked()));
        objectStoreArtifact.ifPresent(result -> System.out.printf(
                "Verified object-store lobby world artifact: address=%s byteLength=%d digest=sha-256:%s%n",
                result.address().value(),
                result.byteLength(),
                result.digest()));
        projectionConsistency.ifPresent(result -> System.out.printf(
                "Verified projection consistency evidence across Kafka state, Cassandra, PostgreSQL, and Valkey: routeAttempts=%d routes=%d presences=%d capabilities=%d rewards=%d sessions=%d allocations=%d%n",
                result.routeAttemptStateMatches(),
                result.routeAuthorityStateMatches(),
                result.presenceAuthorityStateMatches(),
                result.standardCapabilityStateMatches(),
                result.rewardStateMatches(),
                result.sessionAuthorityStateMatches(),
                result.sharedShardAllocationStateMatches()));
        traceCorrelation.ifPresent(result -> System.out.printf(
                "Verified trace correlation evidence across command logs, host observations, controller state, authority state, and Agones metadata: routeAttempts=%d routeCommands=%d loginRouting=%d hostRoutes=%d hostObservations=%d rewards=%d sessions=%d allocations=%d%n",
                result.routeAttemptStateMatches(),
                result.routeAuthorityCommandMatches(),
                result.loginRoutingCommandMatches(),
                result.hostRouteCommandMatches(),
                result.hostObservationMatches(),
                result.rewardCommandMatches(),
                result.sessionAuthorityCommandMatches(),
                result.sharedShardAllocationCommandMatches()));
        sessionAuthorityState.ifPresent(result -> System.out.printf(
                "Verified Session authority ACTIVE state for %d Session(s) from %d record(s) on %s%n",
                result.matchedCount(),
                result.recordsScanned(),
                config.sessionAuthorityStateTopic()));
        sessionAuthorityCommandLog.ifPresent(result -> System.out.printf(
                "Verified Session authority open/activate command log for %d Session(s) from %d command(s) on %s%n",
                result.matchedCount(),
                result.recordsScanned(),
                config.sessionAuthorityCommandTopic()));
        sharedShardAllocationCommandLog.ifPresent(result -> System.out.printf(
                "Verified controller shared-shard allocation command log for %d Session(s) from %d command(s) on %s%n",
                result.matchedCount(),
                result.recordsScanned(),
                config.sharedShardAllocationCommandTopic()));
        sharedShardAllocationState.ifPresent(result -> System.out.printf(
                "Verified controller shared-shard allocation state for %d Session(s) from %d record(s) on %s%n",
                result.matchedCount(),
                result.recordsScanned(),
                config.sharedShardAllocationStateTopic()));
        agonesFleetState.ifPresent(result -> System.out.printf(
                "Verified Agones Fleet %s allocatedReplicas=%d allocatedGameServers=%d proofInstances=%s%n",
                config.agonesFleetName(),
                result.allocatedReplicas(),
                result.allocatedGameServers(),
                result.proofInstanceIds().stream()
                        .map(InstanceId::value)
                        .sorted()
                        .collect(Collectors.joining(","))));
        deniedLogin.ifPresent(result -> System.out.printf(
                "Verified denied Minecraft login for %s through %s:%d: %s%n",
                result.username(),
                endpoint.host(),
                endpoint.port(),
                result.denialReason().orElse("<missing reason>")));
    }

    private static EndpointStatus waitForEndpointStatus(
            VerificationConfig config,
            MinecraftStatusClient client) throws IOException {
        long deadline = System.nanoTime() + config.endpointReadyTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                ResolvedMinecraftEndpoint endpoint = resolveEndpoint(config);
                MinecraftStatusSnapshot status = client.status(
                        new InetSocketAddress(endpoint.host(), endpoint.port()),
                        config.protocolVersion(),
                        attemptTimeout(config));
                return new EndpointStatus(endpoint, status);
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
                sleepBeforeRetry(deadline);
            }
        }
        throw new IOException("Timed out waiting for Velocity L4 endpoint to answer Minecraft status within "
                + config.endpointReadyTimeout() + ". Last failures: " + String.join(" | ", failures));
    }

    private static ScaleOutProof verifyScaleOut(
            VerificationConfig config,
            ResolvedMinecraftEndpoint endpoint,
            MinecraftStatusClient client,
            int protocolVersion,
            PaperLobbyProofMessage filledLobbyProof) throws IOException {
        InetSocketAddress socketAddress = new InetSocketAddress(endpoint.host(), endpoint.port());
        LoginAttemptResult trigger = client.login(
                socketAddress,
                protocolVersion,
                config.scaleOutTriggerLoginUsername(),
                config.timeout());
        if (trigger.accepted()) {
            throw new IOException("Expected full-lobby trigger login for " + config.scaleOutTriggerLoginUsername()
                    + " to be denied while allocation is requested, but Velocity accepted the login");
        }
        config.scaleOutTriggerDeniedReasonContains().ifPresent(expected -> {
            String actual = trigger.denialReason().orElse("");
            if (!actual.contains(expected)) {
                throw new IllegalStateException("Expected full-lobby trigger denial reason to contain '"
                        + expected + "', got " + actual);
            }
        });

        long deadline = System.nanoTime() + config.scaleOutTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                PaperLobbyProofMessage scaleOutProof = client.lobbyProof(
                        socketAddress,
                        protocolVersion,
                        config.scaleOutLoginUsername(),
                        attemptTimeout(config));
                verifyLobbyProof(
                        "scale-out accepted login",
                        config,
                        scaleOutProof,
                        config.scaleOutLoginUsername(),
                        config.expectedScaleOutDisplayName(),
                        config.expectedScaleOutRankLabel(),
                        config.expectedScaleOutDecoratedChatContains());
                verifyDifferentSharedShard(filledLobbyProof, scaleOutProof);
                return new ScaleOutProof(trigger, scaleOutProof);
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
                sleepBeforeRetry(deadline);
            }
        }
        throw new IOException("Timed out waiting for scale-out login " + config.scaleOutLoginUsername()
                + " to reach a different lobby shard after trigger denial. Last failures: "
                + String.join(" | ", failures));
    }

    private static Duration attemptTimeout(VerificationConfig config) {
        return Duration.ofMillis(Math.max(500L, Math.min(config.timeout().toMillis(), 5_000L)));
    }

    private static void sleepBeforeRetry(long deadline) throws IOException {
        long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
        if (remainingMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(Math.min(500L, remainingMillis));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for scale-out lobby allocation", exception);
        }
    }

    private static void verifyLobbyProof(
            String label,
            VerificationConfig config,
            PaperLobbyProofMessage proof,
            String username,
            String expectedDisplayName,
            String expectedRankLabel,
            String expectedDecoratedChatContains) {
        if (!PaperLobbyProofMessage.SPAWN_BLOCK.equals(config.expectedSpawnBlock())) {
            throw new IllegalStateException("Unsupported expected lobby spawn block "
                    + config.expectedSpawnBlock());
        }
        if (!config.expectedSpawnWorld().equals(proof.spawnWorld())) {
            throw new IllegalStateException("Expected " + label + " lobby spawnWorld "
                    + config.expectedSpawnWorld() + ", got " + proof.spawnWorld());
        }
        if (!config.expectedResolvedManifestId().equals(proof.resolvedManifestId())) {
            throw new IllegalStateException("Expected " + label + " lobby resolvedManifestId "
                    + config.expectedResolvedManifestId().value() + ", got " + proof.resolvedManifestId().value());
        }
        if (!config.expectedTraceId().equals(proof.traceId())) {
            throw new IllegalStateException("Expected " + label + " lobby traceId "
                    + config.expectedTraceId() + ", got " + proof.traceId());
        }
        if (config.expectedBedrockBlockX() != proof.bedrockBlockX()) {
            throw new IllegalStateException("Expected " + label + " lobby bedrockBlockX "
                    + config.expectedBedrockBlockX() + ", got " + proof.bedrockBlockX());
        }
        if (config.expectedBedrockBlockY() != proof.bedrockBlockY()) {
            throw new IllegalStateException("Expected " + label + " lobby bedrockBlockY "
                    + config.expectedBedrockBlockY() + ", got " + proof.bedrockBlockY());
        }
        if (config.expectedBedrockBlockZ() != proof.bedrockBlockZ()) {
            throw new IllegalStateException("Expected " + label + " lobby bedrockBlockZ "
                    + config.expectedBedrockBlockZ() + ", got " + proof.bedrockBlockZ());
        }
        assertClose(label, "playerX", config.expectedPlayerX(), proof.playerX());
        assertClose(label, "playerY", config.expectedPlayerY(), proof.playerY());
        assertClose(label, "playerZ", config.expectedPlayerZ(), proof.playerZ());
        assertClose(label, "playerYaw", config.expectedPlayerYaw(), proof.playerYaw());
        assertClose(label, "playerPitch", config.expectedPlayerPitch(), proof.playerPitch());
        if (!LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username).equals(proof.subjectId())) {
            throw new IllegalStateException("Expected " + label + " SubjectId for " + username
                    + " to be " + LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username).value()
                    + ", got " + proof.subjectId().value());
        }
        RouteId expectedRouteId = expectedRouteId(username);
        if (!expectedRouteId.equals(proof.routeId())) {
            throw new IllegalStateException("Expected " + label + " lobby routeId "
                    + expectedRouteId.value() + ", got " + proof.routeId().value());
        }
        if (!expectedDisplayName.equals(proof.displayName())) {
            throw new IllegalStateException("Expected " + label + " lobby displayName " + expectedDisplayName
                    + ", got " + proof.displayName());
        }
        String rankLabel = proof.rankLabel().orElse("");
        if (!expectedRankLabel.equals(rankLabel)) {
            throw new IllegalStateException("Expected " + label + " lobby rankLabel " + expectedRankLabel
                    + ", got " + rankLabel);
        }
        if (!proof.decoratedChat().contains(expectedDecoratedChatContains)) {
            throw new IllegalStateException("Expected " + label + " lobby decoratedChat to contain '"
                    + expectedDecoratedChatContains + "', got " + proof.decoratedChat());
        }
    }

    private static void assertClose(String label, String field, double expected, double actual) {
        if (Math.abs(expected - actual) > POSITION_TOLERANCE) {
            throw new IllegalStateException("Expected " + label + " lobby " + field
                    + " " + expected + ", got " + actual);
        }
    }

    private static RouteId expectedRouteId(String username) {
        String subject = LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username)
                .value()
                .toString()
                .replace("-", "");
        return new RouteId("route-velocity-login-" + subject);
    }

    private static void verifySameSharedShard(
            PaperLobbyProofMessage first,
            PaperLobbyProofMessage second) {
        if (!first.sessionId().equals(second.sessionId())) {
            throw new IllegalStateException("Expected second accepted login to join Session "
                    + first.sessionId().value() + ", got " + second.sessionId().value());
        }
        if (!first.slotId().equals(second.slotId())) {
            throw new IllegalStateException("Expected second accepted login to join Slot "
                    + first.slotId().value() + ", got " + second.slotId().value());
        }
        if (!first.instanceId().equals(second.instanceId())) {
            throw new IllegalStateException("Expected second accepted login to join Paper Instance "
                    + first.instanceId().value() + ", got " + second.instanceId().value());
        }
    }

    private static void verifyDifferentSharedShard(
            PaperLobbyProofMessage filledLobbyProof,
            PaperLobbyProofMessage scaleOutProof) {
        if (filledLobbyProof.sessionId().equals(scaleOutProof.sessionId())) {
            throw new IllegalStateException("Expected scale-out accepted login to join a new Session, got "
                    + scaleOutProof.sessionId().value());
        }
        if (filledLobbyProof.slotId().equals(scaleOutProof.slotId())) {
            throw new IllegalStateException("Expected scale-out accepted login to join a new Slot, got "
                    + scaleOutProof.slotId().value());
        }
        if (filledLobbyProof.instanceId().equals(scaleOutProof.instanceId())) {
            throw new IllegalStateException("Expected scale-out accepted login to join a new Paper Instance, got "
                    + scaleOutProof.instanceId().value());
        }
    }

    private static Optional<RouteAttemptStateResult> verifyRouteAttemptState(
            VerificationConfig config,
            List<RouteAttemptExpectation> expectations,
            List<DeniedRouteAttemptExpectation> deniedExpectations) throws IOException {
        if (!config.verifyRouteAttemptState()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.routeAttemptStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> output = consumeRouteAttemptState(config);
                if (output.isPresent()) {
                    return Optional.of(verifyRouteAttemptStateOutput(
                            output.orElseThrow(),
                            expectations,
                            deniedExpectations));
                }
                failures.add("route-attempt state topic returned no records");
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for controller route-attempt ACK state within "
                + config.routeAttemptStateTimeout()
                + " on " + config.routeAttemptStateTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    private static Optional<String> consumeRouteAttemptState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "controller route-attempt state topic",
                config.routeAttemptStateTopic(),
                "recordType=route-attempt");
    }

    private static Optional<String> consumeRouteAuthorityCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "Route authority command topic",
                config.routeAuthorityCommandTopic(),
                "commandName=");
    }

    private static Optional<String> consumeRouteAuthorityState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "Route authority state topic",
                config.routeAuthorityStateTopic(),
                "current=");
    }

    private static Optional<String> consumePresenceAuthorityCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "Presence authority command topic",
                config.presenceAuthorityCommandTopic(),
                "commandName=");
    }

    private static Optional<String> consumeQueueRosterCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "queue-roster command topic",
                config.queueRosterCommandTopic(),
                "commandName=");
    }

    private static Optional<String> consumeQueueRosterState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "queue-roster state topic",
                config.queueRosterStateTopic(),
                "recordType=queue-roster");
    }

    private static Optional<String> consumeSharedShardPlacementCommandLog(VerificationConfig config)
            throws IOException {
        return consumeKafkaTopic(
                config,
                "shared-shard placement command topic",
                config.sharedShardPlacementCommandTopic(),
                "placementAttemptId=");
    }

    private static Optional<String> consumeRouteAttemptCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "controller route-attempt command topic",
                config.routeAttemptCommandTopic(),
                "commandName=");
    }

    private static Optional<String> consumeLifecycleTraceCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "lifecycle trace command topic",
                config.lifecycleTraceCommandTopic(),
                "commandName=");
    }

    private static Optional<String> consumeLifecycleTraceState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "lifecycle trace state topic",
                config.lifecycleTraceStateTopic(),
                "recordType=lifecycle-trace");
    }

    private static Optional<String> consumeProxyRouteCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "Velocity proxy route command topic",
                config.proxyRouteCommandTopic(),
                "proxy.route");
    }

    private static Optional<String> consumePaperHostCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "Paper host command topic",
                config.paperHostCommandTopic(),
                "host.route.prepare");
    }

    private static Optional<String> consumePresenceAuthorityState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "Presence authority state topic",
                config.presenceAuthorityStateTopic(),
                "current=");
    }

    private static Optional<String> consumePlayerProfileState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "standard player-profile state topic",
                config.playerProfileStateTopic(),
                "subjectId=");
    }

    private static Optional<String> consumeRankState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "standard rank state topic",
                config.rankStateTopic(),
                "subjectId=");
    }

    private static Optional<String> consumePunishmentState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "standard punishment state topic",
                config.punishmentStateTopic(),
                "subjectId=");
    }

    private static Optional<String> consumeEconomyState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "standard economy state topic",
                config.economyStateTopic(),
                "subjectId=");
    }

    private static Optional<String> consumeStatsState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "standard stats state topic",
                config.statsStateTopic(),
                "subjectId=");
    }

    private static Optional<String> consumePlayerProfileCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "standard player-profile command topic",
                config.playerProfileCommandTopic(),
                "commandName=");
    }

    private static Optional<String> consumeRankCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "standard rank command topic",
                config.rankCommandTopic(),
                "commandName=");
    }

    private static Optional<String> consumePunishmentCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "standard punishment command topic",
                config.punishmentCommandTopic(),
                "commandName=");
    }

    private static Optional<String> consumeEconomyCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "standard economy command topic",
                config.economyCommandTopic(),
                "commandName=");
    }

    private static Optional<String> consumeStatsCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "standard stats command topic",
                config.statsCommandTopic(),
                "commandName=");
    }

    private static Optional<String> consumeSessionAuthorityState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "Session authority state topic",
                config.sessionAuthorityStateTopic(),
                "sessionId=");
    }

    private static Optional<String> consumeSessionAuthorityCommandLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "Session authority command topic",
                config.sessionAuthorityCommandTopic(),
                "commandName=");
    }

    private static Optional<String> consumeHostObservationLog(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "host observation topic",
                config.hostObservationTopic(),
                "observationType=" + HostObservationTypes.SESSION_ATTACHED);
    }

    private static Optional<String> consumeSharedShardAllocationState(VerificationConfig config) throws IOException {
        return consumeKafkaTopic(
                config,
                "shared-shard allocation state topic",
                config.sharedShardAllocationStateTopic(),
                "recordType=shared-shard-allocation");
    }

    private static Optional<String> consumeSharedShardAllocationCommandLog(VerificationConfig config)
            throws IOException {
        return consumeKafkaTopic(
                config,
                "shared-shard allocation command topic",
                config.sharedShardAllocationCommandTopic(),
                "experienceId=");
    }

    private static Optional<String> consumeKafkaTopic(
            VerificationConfig config,
            String label,
            String topic,
            String requiredMarker) throws IOException {
        long consumerTimeoutMillis = Math.max(500L, Math.min(5_000L, config.timeout().toMillis() / 2L));
        KubectlResult result = kubectlResult(config, label,
                "exec", config.kafkaPodName(), "-c", config.kafkaContainerName(), "--",
                config.kafkaConsoleConsumerPath(),
                "--bootstrap-server", config.kafkaBootstrapServer(),
                "--topic", topic,
                "--from-beginning",
                "--timeout-ms", Long.toString(consumerTimeoutMillis));
        if (result.exitCode() != 0 && !result.output().contains(requiredMarker)) {
            throw new IOException("Failed to read " + label + " with `"
                    + String.join(" ", result.command()) + "`: " + result.output());
        }
        if (result.output().isBlank() || !result.output().contains(requiredMarker)) {
            return Optional.empty();
        }
        return Optional.of(result.output());
    }

    private static Optional<RouteAuthorityCommandLogResult> verifyRouteAuthorityCommandLog(
            VerificationConfig config,
            List<RouteAuthorityCommandExpectation> expectations,
            List<DeniedRouteAuthorityCommandExpectation> deniedExpectations) throws IOException {
        if (!config.verifyRouteAuthorityCommandLog()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.routeAttemptStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> output = consumeRouteAuthorityCommandLog(config);
                if (output.isPresent()) {
                    return Optional.of(verifyRouteAuthorityCommandLogOutput(
                            output.orElseThrow(),
                            expectations,
                            deniedExpectations));
                }
                failures.add("Route authority command topic returned no records");
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for Route authority open/ack commands within "
                + config.routeAttemptStateTimeout()
                + " on " + config.routeAuthorityCommandTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static RouteAuthorityCommandLogResult verifyRouteAuthorityCommandLogOutput(
            String output,
            List<RouteAuthorityCommandExpectation> expectations,
            List<DeniedRouteAuthorityCommandExpectation> deniedExpectations) {
        List<AuthorityCommand<RouteCommand>> commands = routeAuthorityCommands(output);
        for (RouteAuthorityCommandExpectation expectation : expectations) {
            verifyRouteAuthorityCommand(expectation, commands, RouteAuthorityWireCodec.OPEN_COMMAND);
            verifyRouteAuthorityCommand(expectation, commands, RouteAuthorityWireCodec.ACKNOWLEDGE_COMMAND);
        }
        for (DeniedRouteAuthorityCommandExpectation expectation : deniedExpectations) {
            verifyDeniedRouteAuthorityCommandsAbsent(expectation, commands);
        }
        return new RouteAuthorityCommandLogResult(expectations.size(), commands.size());
    }

    private static Optional<RouteAuthorityStateResult> verifyRouteAuthorityState(
            VerificationConfig config,
            List<RouteAuthorityStateExpectation> expectations,
            List<DeniedRouteAuthorityStateExpectation> deniedExpectations) throws IOException {
        if (!config.verifyRouteAuthorityState()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.routeAttemptStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> output = consumeRouteAuthorityState(config);
                if (output.isPresent()) {
                    return Optional.of(verifyRouteAuthorityStateOutput(
                            output.orElseThrow(),
                            expectations,
                            deniedExpectations));
                }
                failures.add("Route authority state topic returned no route state records");
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for Route authority ACKNOWLEDGED state within "
                + config.routeAttemptStateTimeout()
                + " on " + config.routeAuthorityStateTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static RouteAuthorityStateResult verifyRouteAuthorityStateOutput(
            String output,
            List<RouteAuthorityStateExpectation> expectations,
            List<DeniedRouteAuthorityStateExpectation> deniedExpectations) {
        List<RouteSnapshot> snapshots = routeAuthorityStateSnapshots(output);
        for (RouteAuthorityStateExpectation expectation : expectations) {
            verifyRouteAuthorityStateRecord(expectation, snapshots);
        }
        for (DeniedRouteAuthorityStateExpectation expectation : deniedExpectations) {
            verifyDeniedRouteAuthorityStateRecordAbsent(expectation, snapshots);
        }
        return new RouteAuthorityStateResult(expectations.size(), snapshots.size());
    }

    private static List<RouteAuthorityStateExpectation> routeAuthorityStateExpectations(
            List<RouteAttemptExpectation> routeAttemptExpectations) {
        Map<RouteId, RouteAuthorityStateExpectation> expectations = new LinkedHashMap<>();
        for (RouteAttemptExpectation expectation : routeAttemptExpectations) {
            RouteAuthorityStateExpectation next = RouteAuthorityStateExpectation.from(expectation);
            RouteAuthorityStateExpectation previous = expectations.putIfAbsent(next.routeId(), next);
            if (previous != null) {
                List<String> mismatches = previous.compatibilityMismatches(next);
                if (!mismatches.isEmpty()) {
                    throw new IllegalStateException("Accepted lobby proofs disagreed for Route state "
                            + next.routeId().value()
                            + ": " + String.join(", ", mismatches));
                }
            }
        }
        return List.copyOf(expectations.values());
    }

    private static List<DeniedRouteAuthorityStateExpectation> deniedRouteAuthorityStateExpectations(
            List<DeniedRouteAttemptExpectation> deniedRouteAttemptExpectations) {
        return deniedRouteAttemptExpectations.stream()
                .map(DeniedRouteAuthorityStateExpectation::from)
                .toList();
    }

    private static List<RouteSnapshot> routeAuthorityStateSnapshots(String output) {
        return routeAuthorityStatePayloads(output).stream()
                .map(RouteAuthorityWireCodec::decodeState)
                .flatMap(state -> state.current().stream())
                .toList();
    }

    private static List<String> routeAuthorityStatePayloads(String output) {
        List<String> payloads = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            if (line.startsWith("current=")) {
                if (!current.isEmpty()) {
                    payloads.add(current.toString());
                }
                current = new StringBuilder(line).append('\n');
            } else if (!current.isEmpty() && line.contains("=")) {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            payloads.add(current.toString());
        }
        return payloads;
    }

    private static void verifyRouteAuthorityStateRecord(
            RouteAuthorityStateExpectation expectation,
            List<RouteSnapshot> snapshots) {
        List<String> diagnostics = new ArrayList<>();
        for (RouteSnapshot snapshot : snapshots) {
            if (!expectation.routeId().equals(snapshot.routeId())) {
                continue;
            }
            if (snapshot.status() != RouteLifecycleStatus.ACKNOWLEDGED) {
                diagnostics.add("Route " + expectation.routeId().value()
                        + " was present with status " + snapshot.status());
                continue;
            }
            List<String> mismatches = routeAuthorityStateMismatches(expectation, snapshot);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("ACKNOWLEDGED Route " + expectation.routeId().value()
                    + " mismatched " + String.join(", ", mismatches));
        }
        String scanned = snapshots.stream()
                .map(snapshot -> snapshot.routeId().value() + " status=" + snapshot.status())
                .sorted()
                .collect(Collectors.joining(", "));
        throw new IllegalStateException("Expected Route authority ACKNOWLEDGED state for "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " routeId=" + expectation.routeId().value()
                + " subjectId=" + expectation.subjectId().value()
                + " sessionId=" + expectation.sessionId().value()
                + " targetInstanceId=" + expectation.targetInstanceId().value()
                + ". Scanned route state=" + scanned
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> routeAuthorityStateMismatches(
            RouteAuthorityStateExpectation expectation,
            RouteSnapshot snapshot) {
        List<String> mismatches = new ArrayList<>();
        if (!expectation.subjectId().equals(snapshot.subjectId())) {
            mismatches.add("subjectId expected " + expectation.subjectId().value()
                    + " got " + snapshot.subjectId().value());
        }
        if (!expectation.sessionId().equals(snapshot.targetSessionId())) {
            mismatches.add("targetSessionId expected " + expectation.sessionId().value()
                    + " got " + snapshot.targetSessionId().value());
        }
        if (!expectation.targetInstanceId().equals(snapshot.targetInstanceId())) {
            mismatches.add("targetInstanceId expected " + expectation.targetInstanceId().value()
                    + " got " + snapshot.targetInstanceId().value());
        }
        if (snapshot.expiresAt().isBefore(expectation.minimumRouteExpiresAt())) {
            mismatches.add("expiresAt expected at or after " + expectation.minimumRouteExpiresAt()
                    + " got " + snapshot.expiresAt());
        }
        Instant completedAt = snapshot.completedAt().orElse(Instant.EPOCH);
        if (completedAt.isBefore(expectation.minimumCompletedAt())) {
            mismatches.add("completedAt expected at or after " + expectation.minimumCompletedAt()
                    + " got " + snapshot.completedAt().map(Instant::toString).orElse("<empty>"));
        }
        return mismatches;
    }

    private static void verifyDeniedRouteAuthorityStateRecordAbsent(
            DeniedRouteAuthorityStateExpectation expectation,
            List<RouteSnapshot> snapshots) {
        List<RouteSnapshot> matches = snapshots.stream()
                .filter(snapshot -> expectation.subjectId().equals(snapshot.subjectId()))
                .filter(snapshot -> !routeStateTouchedAt(snapshot).isBefore(expectation.minimumUpdatedAt()))
                .toList();
        if (matches.isEmpty()) {
            return;
        }
        String found = matches.stream()
                .map(snapshot -> snapshot.routeId().value() + " status=" + snapshot.status())
                .sorted()
                .collect(Collectors.joining(", "));
        throw new IllegalStateException("Expected Route authority state to omit denied "
                + expectation.label()
                + " (" + expectation.username() + ") subjectId=" + expectation.subjectId().value()
                + " updated at or after " + expectation.minimumUpdatedAt()
                + ", found " + found);
    }

    private static Instant routeStateTouchedAt(RouteSnapshot snapshot) {
        return snapshot.completedAt().orElse(snapshot.requestedAt());
    }

    private static List<RouteAuthorityCommandExpectation> routeAuthorityCommandExpectations(
            List<RouteAttemptExpectation> routeAttemptExpectations) {
        Map<RouteId, RouteAuthorityCommandExpectation> expectations = new LinkedHashMap<>();
        for (RouteAttemptExpectation expectation : routeAttemptExpectations) {
            RouteAuthorityCommandExpectation next = RouteAuthorityCommandExpectation.from(expectation);
            RouteAuthorityCommandExpectation previous = expectations.putIfAbsent(next.routeId(), next);
            if (previous != null) {
                List<String> mismatches = previous.compatibilityMismatches(next);
                if (!mismatches.isEmpty()) {
                    throw new IllegalStateException("Accepted lobby proofs disagreed for Route "
                            + next.routeId().value()
                            + ": " + String.join(", ", mismatches));
                }
            }
        }
        return List.copyOf(expectations.values());
    }

    private static List<DeniedRouteAuthorityCommandExpectation> deniedRouteAuthorityCommandExpectations(
            List<DeniedRouteAttemptExpectation> deniedRouteAttemptExpectations) {
        return deniedRouteAttemptExpectations.stream()
                .map(DeniedRouteAuthorityCommandExpectation::from)
                .toList();
    }

    private static List<AuthorityCommand<RouteCommand>> routeAuthorityCommands(String output) {
        return authorityCommandPayloads(output).stream()
                .map(payload -> RouteAuthorityWireCodec.decodeCommand(
                        new ConsumerRecord<>("", 0, 0L, null, payload)))
                .toList();
    }

    private static void verifyRouteAuthorityCommand(
            RouteAuthorityCommandExpectation expectation,
            List<AuthorityCommand<RouteCommand>> commands,
            String commandName) {
        List<String> diagnostics = new ArrayList<>();
        for (AuthorityCommand<RouteCommand> command : commands) {
            if (!commandName.equals(command.envelope().commandName().value())) {
                continue;
            }
            if (!expectation.routeId().equals(command.envelope().payload().routeId())) {
                continue;
            }
            List<String> mismatches = routeAuthorityCommandMismatches(expectation, command, commandName);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(commandName + " command for Route " + expectation.routeId().value()
                    + " mismatched " + String.join(", ", mismatches));
        }
        String scanned = commands.stream()
                .map(command -> command.envelope().commandName().value()
                        + "/" + command.envelope().payload().routeId().value())
                .sorted()
                .collect(Collectors.joining(", "));
        throw new IllegalStateException("Expected Route authority " + commandName
                + " command for " + expectation.label()
                + " (" + expectation.username() + ")"
                + " routeId=" + expectation.routeId().value()
                + " subjectId=" + expectation.subjectId().value()
                + " sessionId=" + expectation.sessionId().value()
                + " targetInstanceId=" + expectation.targetInstanceId().value()
                + " traceId=" + expectation.traceId()
                + ". Scanned commands=" + scanned
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyDeniedRouteAuthorityCommandsAbsent(
            DeniedRouteAuthorityCommandExpectation expectation,
            List<AuthorityCommand<RouteCommand>> commands) {
        List<String> found = new ArrayList<>();
        for (AuthorityCommand<RouteCommand> command : commands) {
            RouteCommand payload = command.envelope().payload();
            Optional<SubjectId> subjectId = routeAuthorityCommandSubject(payload);
            if (subjectId.isEmpty() || !expectation.subjectId().equals(subjectId.orElseThrow())) {
                continue;
            }
            if (command.receivedAt().isBefore(expectation.minimumReceivedAt())) {
                continue;
            }
            found.add(command.envelope().commandName().value()
                    + "/routeId=" + payload.routeId().value()
                    + "/receivedAt=" + command.receivedAt());
        }
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Expected Route authority command log to omit denied "
                + expectation.label()
                + " (" + expectation.username() + ") subjectId=" + expectation.subjectId().value()
                + " received at or after " + expectation.minimumReceivedAt()
                + ", found " + found.stream().sorted().collect(Collectors.joining(", ")));
    }

    private static Optional<SubjectId> routeAuthorityCommandSubject(RouteCommand payload) {
        if (payload instanceof OpenRoute open) {
            return Optional.of(open.subjectId());
        }
        if (payload instanceof AcknowledgeRoute acknowledge) {
            return Optional.of(acknowledge.subjectId());
        }
        return Optional.empty();
    }

    private static List<String> routeAuthorityCommandMismatches(
            RouteAuthorityCommandExpectation expectation,
            AuthorityCommand<RouteCommand> command,
            String commandName) {
        List<String> mismatches = new ArrayList<>();
        if (!RouteAuthorityWireCodec.CONTRACT.equals(command.envelope().contractName().value())) {
            mismatches.add("contractName expected " + RouteAuthorityWireCodec.CONTRACT
                    + " got " + command.envelope().contractName().value());
        }
        if (!expectation.traceId().equals(command.envelope().traceEnvelope().traceId())) {
            mismatches.add("traceId expected " + expectation.traceId()
                    + " got " + command.envelope().traceEnvelope().traceId());
        }
        RouteCommand payload = command.envelope().payload();
        if (RouteAuthorityWireCodec.OPEN_COMMAND.equals(commandName)) {
            if (payload instanceof OpenRoute open) {
                appendOpenRouteMismatches(expectation, open, mismatches);
            } else {
                mismatches.add("payload expected OpenRoute got " + payload.getClass().getSimpleName());
            }
        } else if (RouteAuthorityWireCodec.ACKNOWLEDGE_COMMAND.equals(commandName)) {
            if (payload instanceof AcknowledgeRoute acknowledge) {
                appendAcknowledgeRouteMismatches(expectation, acknowledge, mismatches);
            } else {
                mismatches.add("payload expected AcknowledgeRoute got " + payload.getClass().getSimpleName());
            }
        } else {
            mismatches.add("unsupported commandName " + commandName);
        }
        return mismatches;
    }

    private static void appendOpenRouteMismatches(
            RouteAuthorityCommandExpectation expectation,
            OpenRoute open,
            List<String> mismatches) {
        appendRouteTargetMismatches(expectation, open.subjectId(), open.targetSessionId(), open.targetInstanceId(),
                mismatches);
        if (open.expiresAt().isBefore(expectation.minimumRouteExpiresAt())) {
            mismatches.add("expiresAt expected at or after " + expectation.minimumRouteExpiresAt()
                    + " got " + open.expiresAt());
        }
    }

    private static void appendAcknowledgeRouteMismatches(
            RouteAuthorityCommandExpectation expectation,
            AcknowledgeRoute acknowledge,
            List<String> mismatches) {
        appendRouteTargetMismatches(
                expectation,
                acknowledge.subjectId(),
                acknowledge.targetSessionId(),
                acknowledge.targetInstanceId(),
                mismatches);
        if (acknowledge.acknowledgedAt().isBefore(expectation.minimumAcknowledgedAt())) {
            mismatches.add("acknowledgedAt expected at or after " + expectation.minimumAcknowledgedAt()
                    + " got " + acknowledge.acknowledgedAt());
        }
    }

    private static void appendRouteTargetMismatches(
            RouteAuthorityCommandExpectation expectation,
            SubjectId subjectId,
            SessionId targetSessionId,
            InstanceId targetInstanceId,
            List<String> mismatches) {
        if (!expectation.subjectId().equals(subjectId)) {
            mismatches.add("subjectId expected " + expectation.subjectId().value()
                    + " got " + subjectId.value());
        }
        if (!expectation.sessionId().equals(targetSessionId)) {
            mismatches.add("targetSessionId expected " + expectation.sessionId().value()
                    + " got " + targetSessionId.value());
        }
        if (!expectation.targetInstanceId().equals(targetInstanceId)) {
            mismatches.add("targetInstanceId expected " + expectation.targetInstanceId().value()
                    + " got " + targetInstanceId.value());
        }
    }

    private static Optional<LoginRoutingCommandLogResult> verifyLoginRoutingCommandLog(
            VerificationConfig config,
            List<LoginRoutingCommandExpectation> expectations,
            List<DeniedLoginRoutingCommandExpectation> deniedExpectations) throws IOException {
        if (!config.verifyLoginRoutingCommandLog()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.routeAttemptStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> queueRosterOutput = consumeQueueRosterCommandLog(config);
                Optional<String> presenceOutput = consumePresenceAuthorityCommandLog(config);
                Optional<String> placementOutput = consumeSharedShardPlacementCommandLog(config);
                Optional<String> routeAttemptOutput = consumeRouteAttemptCommandLog(config);
                Optional<String> lifecycleTraceOutput = consumeLifecycleTraceCommandLog(config);
                if (queueRosterOutput.isPresent()
                        && presenceOutput.isPresent()
                        && placementOutput.isPresent()
                        && routeAttemptOutput.isPresent()
                        && lifecycleTraceOutput.isPresent()) {
                    return Optional.of(verifyLoginRoutingCommandLogOutput(
                            queueRosterOutput.orElseThrow(),
                            presenceOutput.orElseThrow(),
                            placementOutput.orElseThrow(),
                            routeAttemptOutput.orElseThrow(),
                            lifecycleTraceOutput.orElseThrow(),
                            config,
                            expectations,
                            deniedExpectations));
                }
                if (queueRosterOutput.isEmpty()) {
                    failures.add("queue-roster command topic returned no queue records");
                }
                if (presenceOutput.isEmpty()) {
                    failures.add("Presence authority command topic returned no command records");
                }
                if (placementOutput.isEmpty()) {
                    failures.add("shared-shard placement command topic returned no placement records");
                }
                if (routeAttemptOutput.isEmpty()) {
                    failures.add("controller route-attempt command topic returned no command records");
                }
                if (lifecycleTraceOutput.isEmpty()) {
                    failures.add("lifecycle trace command topic returned no command records");
                }
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for Velocity login-routing command logs within "
                + config.routeAttemptStateTimeout()
                + " on " + config.queueRosterCommandTopic()
                + ", " + config.presenceAuthorityCommandTopic()
                + ", " + config.sharedShardPlacementCommandTopic()
                + ", " + config.routeAttemptCommandTopic()
                + ", and " + config.lifecycleTraceCommandTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static LoginRoutingCommandLogResult verifyLoginRoutingCommandLogOutput(
            String queueRosterOutput,
            String presenceOutput,
            String placementOutput,
            String routeAttemptOutput,
            String lifecycleTraceOutput,
            VerificationConfig config,
            List<LoginRoutingCommandExpectation> expectations,
            List<DeniedLoginRoutingCommandExpectation> deniedExpectations) {
        List<QueueRosterControlCommand<? extends QueueRosterCommand>> queueRosterCommands =
                queueRosterCommands(queueRosterOutput);
        List<AuthorityCommand<PresenceCommand>> presenceCommands = presenceAuthorityCommands(presenceOutput);
        List<SharedShardPlacementWireRequest> placementRequests = sharedShardPlacementRequests(placementOutput);
        List<RouteAttemptControlCommand<? extends RouteAttemptCommand>> routeAttemptCommands =
                routeAttemptCommands(routeAttemptOutput);
        List<LifecycleTraceControlCommand<RecordLifecycleObservation>> lifecycleTraceCommands =
                lifecycleTraceCommands(lifecycleTraceOutput);
        for (LoginRoutingCommandExpectation expectation : expectations) {
            verifyQueueRosterCommandSequence(config, expectation, queueRosterCommands);
            InstanceId velocityInstanceId = verifyPresenceClaimCommand(expectation, presenceCommands);
            verifySharedShardPlacementRequest(config, expectation, placementRequests, velocityInstanceId);
            verifyRouteAttemptCommandSequence(expectation, routeAttemptCommands, velocityInstanceId);
            verifyLifecycleTraceCommandSequence(expectation, lifecycleTraceCommands);
        }
        for (DeniedLoginRoutingCommandExpectation expectation : deniedExpectations) {
            verifyDeniedLoginRoutingCommandsAbsent(
                    expectation,
                    queueRosterCommands,
                    presenceCommands,
                    placementRequests,
                    routeAttemptCommands,
                    lifecycleTraceCommands);
        }
        return new LoginRoutingCommandLogResult(
                expectations.size(),
                queueRosterCommands.size(),
                presenceCommands.size(),
                placementRequests.size(),
                routeAttemptCommands.size(),
                lifecycleTraceCommands.size());
    }

    private static List<LoginRoutingCommandExpectation> loginRoutingCommandExpectations(
            List<RouteAttemptExpectation> routeAttemptExpectations) {
        Map<String, LoginRoutingCommandExpectation> expectations = new LinkedHashMap<>();
        for (RouteAttemptExpectation expectation : routeAttemptExpectations) {
            LoginRoutingCommandExpectation next = LoginRoutingCommandExpectation.from(expectation);
            LoginRoutingCommandExpectation previous = expectations.putIfAbsent(next.routeAttemptId(), next);
            if (previous != null) {
                List<String> mismatches = previous.compatibilityMismatches(next);
                if (!mismatches.isEmpty()) {
                    throw new IllegalStateException("Accepted lobby proofs disagreed for Velocity login-routing "
                            + next.routeAttemptId()
                            + ": " + String.join(", ", mismatches));
                }
            }
        }
        return List.copyOf(expectations.values());
    }

    private static List<DeniedLoginRoutingCommandExpectation> deniedLoginRoutingCommandExpectations(
            List<DeniedRouteAttemptExpectation> deniedRouteAttemptExpectations) {
        return deniedRouteAttemptExpectations.stream()
                .map(DeniedLoginRoutingCommandExpectation::from)
                .toList();
    }

    private static List<AuthorityCommand<PresenceCommand>> presenceAuthorityCommands(String output) {
        return authorityCommandPayloads(output).stream()
                .map(payload -> PresenceAuthorityWireCodec.decodeCommand(
                        new ConsumerRecord<>("", 0, 0L, null, payload)))
                .toList();
    }

    private static List<SharedShardPlacementWireRequest> sharedShardPlacementRequests(String output) {
        return sharedShardPlacementRequestPayloads(output).stream()
                .map(payload -> ControlCommandWireCodec.decodeSharedShardPlacementRequest(
                        new ConsumerRecord<>("", 0, 0L, null, payload)))
                .toList();
    }

    private static List<String> sharedShardPlacementRequestPayloads(String output) {
        List<String> payloads = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            if (line.startsWith("placementAttemptId=")) {
                if (!current.isEmpty()) {
                    payloads.add(current.toString());
                }
                current = new StringBuilder(line).append('\n');
            } else if (!current.isEmpty() && line.contains("=")) {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            payloads.add(current.toString());
        }
        return payloads;
    }

    private static List<RouteAttemptControlCommand<? extends RouteAttemptCommand>> routeAttemptCommands(
            String output) {
        List<RouteAttemptControlCommand<? extends RouteAttemptCommand>> commands = new ArrayList<>();
        for (String payload : authorityCommandPayloads(output)) {
            commands.add(ControlCommandWireCodec.decodeRouteAttemptCommand(
                    new ConsumerRecord<>("", 0, 0L, null, payload)));
        }
        return List.copyOf(commands);
    }

    private static List<QueueRosterControlCommand<? extends QueueRosterCommand>> queueRosterCommands(
            String output) {
        List<QueueRosterControlCommand<? extends QueueRosterCommand>> commands = new ArrayList<>();
        for (String payload : authorityCommandPayloads(output)) {
            commands.add(ControlCommandWireCodec.decodeQueueRosterCommand(
                    new ConsumerRecord<>("", 0, 0L, null, payload)));
        }
        return List.copyOf(commands);
    }

    private static void verifyQueueRosterCommandSequence(
            VerificationConfig config,
            LoginRoutingCommandExpectation expectation,
            List<QueueRosterControlCommand<? extends QueueRosterCommand>> commands) {
        verifyQueueRosterCommand(
                config,
                expectation,
                commands,
                ControlQueueNames.SUBMIT_QUEUE_INTENT.value(),
                "submit",
                0L);
        verifyQueueRosterCommand(
                config,
                expectation,
                commands,
                ControlQueueNames.FORM_ROSTER_INTENT.value(),
                "form",
                1L);
    }

    private static void verifyQueueRosterCommand(
            VerificationConfig config,
            LoginRoutingCommandExpectation expectation,
            List<QueueRosterControlCommand<? extends QueueRosterCommand>> commands,
            String commandName,
            String commandSuffix,
            long expectedRevision) {
        List<String> diagnostics = new ArrayList<>();
        for (QueueRosterControlCommand<? extends QueueRosterCommand> command : commands) {
            if (!commandName.equals(command.envelope().commandName().value())
                    || !queueRosterIntentMatches(expectation, command.envelope().payload())) {
                continue;
            }
            List<String> mismatches = queueRosterCommandMismatches(
                    config,
                    expectation,
                    command,
                    commandName,
                    commandSuffix,
                    expectedRevision);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(commandName + " command mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected queue-roster " + commandName
                + " command for " + expectation.label()
                + " (" + expectation.username() + ")"
                + " subjectId=" + expectation.subjectId().value()
                + " queueIntentId=" + expectedQueueIntentId(expectation).value()
                + " rosterIntentId=" + expectedRosterIntentId(expectation).value()
                + ". Scanned commands=" + queueRosterCommandIds(commands)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static boolean queueRosterIntentMatches(
            LoginRoutingCommandExpectation expectation,
            QueueRosterCommand payload) {
        if (payload instanceof SubmitQueueIntent submit) {
            return expectedQueueIntentId(expectation).equals(submit.queueIntentId());
        }
        if (payload instanceof FormRosterIntent form) {
            return expectedRosterIntentId(expectation).equals(form.rosterIntentId());
        }
        return false;
    }

    private static List<String> queueRosterCommandMismatches(
            VerificationConfig config,
            LoginRoutingCommandExpectation expectation,
            QueueRosterControlCommand<? extends QueueRosterCommand> command,
            String expectedCommandName,
            String commandSuffix,
            long expectedRevision) {
        List<String> mismatches = new ArrayList<>();
        QueueRosterCommand payload = command.envelope().payload();
        QueueIntentId queueIntentId = expectedQueueIntentId(expectation);
        RosterIntentId rosterIntentId = expectedRosterIntentId(expectation);
        String compactIntentId = compactQueueRosterPayloadId(payload);
        if (!expectedCommandName.equals(command.envelope().commandName().value())) {
            mismatches.add("commandName expected " + expectedCommandName
                    + " got " + command.envelope().commandName().value());
        }
        if (!ControlQueueNames.CONTRACT.value().equals(command.envelope().contractName().value())) {
            mismatches.add("contractName expected " + ControlQueueNames.CONTRACT.value()
                    + " got " + command.envelope().contractName().value());
        }
        if (!ControlQueueNames.aggregateId(payload.partitionKey()).equals(command.envelope().aggregateId())) {
            mismatches.add("aggregateId expected " + ControlQueueNames.aggregateId(payload.partitionKey()).value()
                    + " got " + command.envelope().aggregateId().value());
        }
        String expectedCommandId = "command-queue-velocity-login-" + commandSuffix + "-" + compactIntentId;
        if (!expectedCommandId.equals(command.envelope().commandId().value())) {
            mismatches.add("commandId expected " + expectedCommandId
                    + " got " + command.envelope().commandId().value());
        }
        String expectedIdempotencyKey = "idem-queue-velocity-login-" + commandSuffix + "-" + compactIntentId;
        if (!expectedIdempotencyKey.equals(command.envelope().idempotencyKey().value())) {
            mismatches.add("idempotencyKey expected " + expectedIdempotencyKey
                    + " got " + command.envelope().idempotencyKey().value());
        }
        if (!VELOCITY_LOGIN_PRINCIPAL_ID.equals(command.envelope().principalId().value())) {
            mismatches.add("principalId expected " + VELOCITY_LOGIN_PRINCIPAL_ID
                    + " got " + command.envelope().principalId().value());
        }
        if (!VELOCITY_LOGIN_PRINCIPAL_ID.equals(command.authenticatedPrincipal().value())) {
            mismatches.add("authenticatedPrincipal expected " + VELOCITY_LOGIN_PRINCIPAL_ID
                    + " got " + command.authenticatedPrincipal().value());
        }
        if (command.fencingEpoch() != 1L) {
            mismatches.add("fencingEpoch expected 1 got " + command.fencingEpoch());
        }
        if (!command.expectedRevision().map(revision -> revision.value() == expectedRevision).orElse(false)) {
            mismatches.add("expectedRevision expected " + expectedRevision
                    + " got " + command.expectedRevision()
                            .map(revision -> Long.toString(revision.value()))
                            .orElse("<empty>"));
        }
        String expectedTraceId = "trace-queue-velocity-login-" + compactIntentId;
        if (!expectedTraceId.equals(command.envelope().traceEnvelope().traceId())) {
            mismatches.add("traceId expected " + expectedTraceId
                    + " got " + command.envelope().traceEnvelope().traceId());
        }
        if (!("span-queue-velocity-login-" + commandSuffix + "-" + compactIntentId)
                .equals(command.envelope().traceEnvelope().spanId())) {
            mismatches.add("spanId mismatch");
        }
        if (!VELOCITY_LOGIN_ORIGIN_SERVICE.equals(command.envelope().traceEnvelope().originService())) {
            mismatches.add("originService expected " + VELOCITY_LOGIN_ORIGIN_SERVICE
                    + " got " + command.envelope().traceEnvelope().originService());
        }
        String expectedFingerprint = "queue-roster|command=" + expectedCommandName
                + "|id=" + compactIntentId
                + "|revision=" + expectedRevision;
        if (!expectedFingerprint.equals(command.payloadFingerprint())) {
            mismatches.add("payloadFingerprint expected " + expectedFingerprint
                    + " got " + command.payloadFingerprint());
        }
        if (payload instanceof SubmitQueueIntent submit) {
            appendSubmitQueueIntentMismatches(config, expectation, submit, queueIntentId, mismatches);
        } else if (payload instanceof FormRosterIntent form) {
            appendFormRosterIntentMismatches(config, form, queueIntentId, rosterIntentId, mismatches);
        } else {
            mismatches.add("payload expected SubmitQueueIntent or FormRosterIntent got "
                    + payload.getClass().getSimpleName());
        }
        return mismatches;
    }

    private static void appendSubmitQueueIntentMismatches(
            VerificationConfig config,
            LoginRoutingCommandExpectation expectation,
            SubmitQueueIntent submit,
            QueueIntentId queueIntentId,
            List<String> mismatches) {
        if (!queueIntentId.equals(submit.queueIntentId())) {
            mismatches.add("queueIntentId expected " + queueIntentId.value()
                    + " got " + submit.queueIntentId().value());
        }
        if (!List.of(expectation.subjectId()).equals(submit.subjectIds())) {
            mismatches.add("subjectIds expected " + expectation.subjectId().value()
                    + " got " + submit.subjectIds().stream()
                            .map(subjectId -> subjectId.value().toString())
                            .collect(Collectors.joining(",")));
        }
        if (!config.expectedExperienceId().equals(submit.experienceId())) {
            mismatches.add("experienceId expected " + config.expectedExperienceId().value()
                    + " got " + submit.experienceId().value());
        }
        if (submit.modeId().isPresent()) {
            mismatches.add("modeId expected empty got " + submit.modeId().orElseThrow());
        }
        if (!config.expectedPoolId().equals(submit.poolId())) {
            mismatches.add("poolId expected " + config.expectedPoolId().value()
                    + " got " + submit.poolId().value());
        }
        if (submit.priority() != 0) {
            mismatches.add("priority expected 0 got " + submit.priority());
        }
        if (submit.deadlineAt().isBefore(expectation.minimumLeaseExpiresAt())) {
            mismatches.add("deadlineAt expected at or after " + expectation.minimumLeaseExpiresAt()
                    + " got " + submit.deadlineAt());
        }
    }

    private static void appendFormRosterIntentMismatches(
            VerificationConfig config,
            FormRosterIntent form,
            QueueIntentId queueIntentId,
            RosterIntentId rosterIntentId,
            List<String> mismatches) {
        if (!rosterIntentId.equals(form.rosterIntentId())) {
            mismatches.add("rosterIntentId expected " + rosterIntentId.value()
                    + " got " + form.rosterIntentId().value());
        }
        if (!config.expectedExperienceId().equals(form.partitionKey().experienceId())) {
            mismatches.add("experienceId expected " + config.expectedExperienceId().value()
                    + " got " + form.partitionKey().experienceId().value());
        }
        if (form.partitionKey().modeId().isPresent()) {
            mismatches.add("modeId expected empty got " + form.partitionKey().modeId().orElseThrow());
        }
        if (!config.expectedPoolId().equals(form.partitionKey().poolId())) {
            mismatches.add("poolId expected " + config.expectedPoolId().value()
                    + " got " + form.partitionKey().poolId().value());
        }
        if (!List.of(queueIntentId).equals(form.queueIntentIds())) {
            mismatches.add("queueIntentIds expected " + queueIntentId.value()
                    + " got " + form.queueIntentIds().stream()
                            .map(QueueIntentId::value)
                            .collect(Collectors.joining(",")));
        }
        if (form.maxSubjects() != 1) {
            mismatches.add("maxSubjects expected 1 got " + form.maxSubjects());
        }
    }

    private static String queueRosterCommandIds(
            List<QueueRosterControlCommand<? extends QueueRosterCommand>> commands) {
        return commands.stream()
                .map(command -> command.envelope().commandName().value()
                        + "/" + compactQueueRosterPayloadId(command.envelope().payload()))
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static QueueIntentId expectedQueueIntentId(LoginRoutingCommandExpectation expectation) {
        return new QueueIntentId("queue-intent-velocity-login-" + expectation.subjectSuffix());
    }

    private static RosterIntentId expectedRosterIntentId(LoginRoutingCommandExpectation expectation) {
        return new RosterIntentId("roster-intent-velocity-login-" + expectation.subjectSuffix());
    }

    private static String compactQueueRosterPayloadId(QueueRosterCommand payload) {
        if (payload instanceof SubmitQueueIntent submit) {
            return submit.queueIntentId().value().replace("-", "");
        }
        if (payload instanceof FormRosterIntent form) {
            return form.rosterIntentId().value().replace("-", "");
        }
        return payload.getClass().getSimpleName();
    }

    private static InstanceId verifyPresenceClaimCommand(
            LoginRoutingCommandExpectation expectation,
            List<AuthorityCommand<PresenceCommand>> commands) {
        List<String> diagnostics = new ArrayList<>();
        for (AuthorityCommand<PresenceCommand> command : commands) {
            if (!(command.envelope().payload() instanceof ClaimPresence claim)
                    || !expectation.subjectId().equals(claim.subjectId())) {
                continue;
            }
            List<String> mismatches = presenceClaimCommandMismatches(expectation, command, claim);
            if (mismatches.isEmpty()) {
                return claim.ownerInstanceId();
            }
            diagnostics.add("claim-presence command mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Presence authority claim command for "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " subjectId=" + expectation.subjectId().value()
                + " presenceId=" + expectation.presenceId().value()
                + " sessionId=" + expectation.sessionId().value()
                + " routeId=" + expectation.routeId().value()
                + ". Scanned subjects=" + presenceClaimSubjects(commands)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> presenceClaimCommandMismatches(
            LoginRoutingCommandExpectation expectation,
            AuthorityCommand<PresenceCommand> command,
            ClaimPresence claim) {
        List<String> mismatches = velocityAuthorityCommandMismatches(
                command,
                PresenceAuthorityWireCodec.CLAIM_COMMAND,
                PresenceAuthorityWireCodec.CONTRACT,
                PresenceAuthority.aggregateId(expectation.subjectId()).value(),
                "command-presence-velocity-login-" + expectation.subjectSuffix(),
                "idem-presence-velocity-login-" + expectation.subjectSuffix(),
                "trace-velocity-login-" + expectation.subjectSuffix(),
                "claim-presence|subjectId=" + expectation.subjectId().value()
                        + "|presenceId=" + expectation.presenceId().value()
                        + "|sessionId=" + expectation.sessionId().value()
                        + "|routeId=" + expectation.routeId().value(),
                0L);
        if (!expectation.presenceId().equals(claim.presenceId())) {
            mismatches.add("presenceId expected " + expectation.presenceId().value()
                    + " got " + claim.presenceId().value());
        }
        if (!expectation.sessionId().equals(claim.sessionId().orElse(null))) {
            mismatches.add("sessionId expected " + expectation.sessionId().value()
                    + " got " + claim.sessionId().map(SessionId::value).orElse("<empty>"));
        }
        if (!expectation.routeId().equals(claim.routeId().orElse(null))) {
            mismatches.add("routeId expected " + expectation.routeId().value()
                    + " got " + claim.routeId().map(RouteId::value).orElse("<empty>"));
        }
        if (!("owner-token-velocity-login-" + expectation.subjectSuffix()).equals(claim.ownerToken().value())) {
            mismatches.add("ownerToken mismatch");
        }
        if (!claim.ownerInstanceId().equals(command.envelope().traceEnvelope().originInstanceId())) {
            mismatches.add("ownerInstanceId expected trace originInstanceId "
                    + command.envelope().traceEnvelope().originInstanceId().value()
                    + " got " + claim.ownerInstanceId().value());
        }
        if (claim.expiresAt().isBefore(expectation.minimumLeaseExpiresAt())) {
            mismatches.add("expiresAt expected at or after " + expectation.minimumLeaseExpiresAt()
                    + " got " + claim.expiresAt());
        }
        return mismatches;
    }

    private static List<String> velocityAuthorityCommandMismatches(
            AuthorityCommand<?> command,
            String expectedCommandName,
            String expectedContractName,
            String expectedAggregateId,
            String expectedCommandId,
            String expectedIdempotencyKey,
            String expectedTraceId,
            String expectedPayloadFingerprint,
            long expectedRevision) {
        List<String> mismatches = new ArrayList<>();
        if (!expectedCommandName.equals(command.envelope().commandName().value())) {
            mismatches.add("commandName expected " + expectedCommandName
                    + " got " + command.envelope().commandName().value());
        }
        if (!expectedContractName.equals(command.envelope().contractName().value())) {
            mismatches.add("contractName expected " + expectedContractName
                    + " got " + command.envelope().contractName().value());
        }
        if (!expectedAggregateId.equals(command.envelope().aggregateId().value())) {
            mismatches.add("aggregateId expected " + expectedAggregateId
                    + " got " + command.envelope().aggregateId().value());
        }
        if (!expectedCommandId.equals(command.envelope().commandId().value())) {
            mismatches.add("commandId expected " + expectedCommandId
                    + " got " + command.envelope().commandId().value());
        }
        if (!expectedIdempotencyKey.equals(command.envelope().idempotencyKey().value())) {
            mismatches.add("idempotencyKey expected " + expectedIdempotencyKey
                    + " got " + command.envelope().idempotencyKey().value());
        }
        if (!VELOCITY_LOGIN_PRINCIPAL_ID.equals(command.envelope().principalId().value())) {
            mismatches.add("principalId expected " + VELOCITY_LOGIN_PRINCIPAL_ID
                    + " got " + command.envelope().principalId().value());
        }
        if (!VELOCITY_LOGIN_PRINCIPAL_ID.equals(command.authenticatedPrincipal().value())) {
            mismatches.add("authenticatedPrincipal expected " + VELOCITY_LOGIN_PRINCIPAL_ID
                    + " got " + command.authenticatedPrincipal().value());
        }
        if (command.fencingEpoch() != 1L) {
            mismatches.add("fencingEpoch expected 1 got " + command.fencingEpoch());
        }
        if (!command.expectedRevision().map(revision -> revision.value() == expectedRevision).orElse(false)) {
            mismatches.add("expectedRevision expected " + expectedRevision
                    + " got " + command.expectedRevision()
                            .map(revision -> Long.toString(revision.value()))
                            .orElse("<empty>"));
        }
        if (!expectedTraceId.equals(command.envelope().traceEnvelope().traceId())) {
            mismatches.add("traceId expected " + expectedTraceId
                    + " got " + command.envelope().traceEnvelope().traceId());
        }
        if (!VELOCITY_LOGIN_ORIGIN_SERVICE.equals(command.envelope().traceEnvelope().originService())) {
            mismatches.add("originService expected " + VELOCITY_LOGIN_ORIGIN_SERVICE
                    + " got " + command.envelope().traceEnvelope().originService());
        }
        if (!expectedPayloadFingerprint.equals(command.payloadFingerprint())) {
            mismatches.add("payloadFingerprint expected " + expectedPayloadFingerprint
                    + " got " + command.payloadFingerprint());
        }
        return mismatches;
    }

    private static String presenceClaimSubjects(List<AuthorityCommand<PresenceCommand>> commands) {
        return commands.stream()
                .map(command -> command.envelope().payload())
                .filter(ClaimPresence.class::isInstance)
                .map(ClaimPresence.class::cast)
                .map(claim -> claim.subjectId().value().toString())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static void verifySharedShardPlacementRequest(
            VerificationConfig config,
            LoginRoutingCommandExpectation expectation,
            List<SharedShardPlacementWireRequest> requests,
            InstanceId velocityInstanceId) {
        List<String> diagnostics = new ArrayList<>();
        for (SharedShardPlacementWireRequest request : requests) {
            if (!expectation.subjectId().equals(request.request().subjectId())) {
                continue;
            }
            List<String> mismatches =
                    sharedShardPlacementRequestMismatches(config, expectation, request, velocityInstanceId);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("placement request mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected shared-shard placement request for "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " subjectId=" + expectation.subjectId().value()
                + " placementAttemptId=" + expectation.placementAttemptId()
                + " sessionId=" + expectation.sessionId().value()
                + " slotId=" + expectation.slotId().value()
                + ". Scanned subjects=" + placementRequestSubjects(requests)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> sharedShardPlacementRequestMismatches(
            VerificationConfig config,
            LoginRoutingCommandExpectation expectation,
            SharedShardPlacementWireRequest placement,
            InstanceId velocityInstanceId) {
        List<String> mismatches = new ArrayList<>();
        var request = placement.request();
        if (!expectation.placementAttemptId().equals(request.placementAttemptId())) {
            mismatches.add("placementAttemptId expected " + expectation.placementAttemptId()
                    + " got " + request.placementAttemptId());
        }
        if (!expectation.presenceId().equals(request.presenceId())) {
            mismatches.add("presenceId expected " + expectation.presenceId().value()
                    + " got " + request.presenceId().value());
        }
        if (!config.expectedExperienceId().equals(request.experience().experienceId())) {
            mismatches.add("experienceId expected " + config.expectedExperienceId().value()
                    + " got " + request.experience().experienceId().value());
        }
        if (!config.expectedPoolId().equals(request.experience().poolId())) {
            mismatches.add("poolId expected " + config.expectedPoolId().value()
                    + " got " + request.experience().poolId().value());
        }
        if (!config.agonesFleetName().equals(request.experience().poolDescriptor().agonesFleetName())) {
            mismatches.add("agonesFleetName expected " + config.agonesFleetName()
                    + " got " + request.experience().poolDescriptor().agonesFleetName());
        }
        if (!expectation.targetResolvedManifestId().equals(request.experience().resolvedManifestId())) {
            mismatches.add("resolvedManifestId expected " + expectation.targetResolvedManifestId().value()
                    + " got " + request.experience().resolvedManifestId().value());
        }
        if (!("trace-velocity-login-" + expectation.subjectSuffix()).equals(request.traceEnvelope().traceId())) {
            mismatches.add("traceId expected trace-velocity-login-" + expectation.subjectSuffix()
                    + " got " + request.traceEnvelope().traceId());
        }
        if (!VELOCITY_LOGIN_ORIGIN_SERVICE.equals(request.traceEnvelope().originService())) {
            mismatches.add("originService expected " + VELOCITY_LOGIN_ORIGIN_SERVICE
                    + " got " + request.traceEnvelope().originService());
        }
        if (!velocityInstanceId.equals(request.traceEnvelope().originInstanceId())) {
            mismatches.add("originInstanceId expected " + velocityInstanceId.value()
                    + " got " + request.traceEnvelope().originInstanceId().value());
        }
        boolean candidateMatched = false;
        List<String> candidateDiagnostics = new ArrayList<>();
        for (var candidate : placement.candidates()) {
            List<String> candidateMismatches = new ArrayList<>();
            if (!expectation.targetInstanceId().equals(candidate.instanceSnapshot().instanceId())) {
                candidateMismatches.add("candidate instanceId " + candidate.instanceSnapshot().instanceId().value());
            }
            if (!config.expectedPoolId().equals(candidate.instanceSnapshot().poolId())) {
                candidateMismatches.add("candidate poolId " + candidate.instanceSnapshot().poolId().value());
            }
            if (!expectation.targetResolvedManifestId().equals(
                    candidate.instanceSnapshot().resolvedManifestId().orElse(null))) {
                candidateMismatches.add("candidate resolvedManifestId "
                        + candidate.instanceSnapshot().resolvedManifestId()
                                .map(ResolvedManifestId::value)
                                .orElse("<empty>"));
            }
            if (!expectation.sessionId().equals(candidate.occupancySnapshot().sessionId())) {
                candidateMismatches.add("candidate sessionId "
                        + candidate.occupancySnapshot().sessionId().value());
            }
            if (!expectation.slotId().equals(candidate.occupancySnapshot().slotId())) {
                candidateMismatches.add("candidate slotId "
                        + candidate.occupancySnapshot().slotId().value());
            }
            if (!candidate.occupancySnapshot().acceptingPresences()) {
                candidateMismatches.add("candidate acceptingPresences false");
            }
            if (candidateMismatches.isEmpty()) {
                candidateMatched = true;
                break;
            }
            candidateDiagnostics.add(String.join(", ", candidateMismatches));
        }
        if (!candidateMatched) {
            mismatches.add("no matching Paper Session candidate for instance="
                    + expectation.targetInstanceId().value()
                    + " session=" + expectation.sessionId().value()
                    + " slot=" + expectation.slotId().value()
                    + " candidates=" + String.join(" | ", candidateDiagnostics));
        }
        return mismatches;
    }

    private static String placementRequestSubjects(List<SharedShardPlacementWireRequest> requests) {
        return requests.stream()
                .map(request -> request.request().subjectId().value().toString())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static void verifyRouteAttemptCommandSequence(
            LoginRoutingCommandExpectation expectation,
            List<RouteAttemptControlCommand<? extends RouteAttemptCommand>> commands,
            InstanceId velocityInstanceId) {
        verifyRouteAttemptCommand(
                expectation,
                commands,
                velocityInstanceId,
                ControlRouteNames.REQUEST_ROUTE_ATTEMPT,
                "request",
                0L);
        verifyRouteAttemptCommand(
                expectation,
                commands,
                velocityInstanceId,
                ControlRouteNames.ISSUE_PROXY_ROUTE,
                "issue-proxy",
                1L);
        verifyRouteAttemptCommand(
                expectation,
                commands,
                velocityInstanceId,
                ControlRouteNames.PREPARE_HOST_ROUTE,
                "prepare-host",
                2L);
    }

    private static void verifyRouteAttemptCommand(
            LoginRoutingCommandExpectation expectation,
            List<RouteAttemptControlCommand<? extends RouteAttemptCommand>> commands,
            InstanceId velocityInstanceId,
            sh.harold.fulcrum.api.contract.CommandName commandName,
            String commandSuffix,
            long expectedRevision) {
        List<String> diagnostics = new ArrayList<>();
        RouteAttemptId expectedRouteAttemptId = new RouteAttemptId(expectation.routeAttemptId());
        for (RouteAttemptControlCommand<? extends RouteAttemptCommand> command : commands) {
            if (!commandName.value().equals(command.envelope().commandName().value())
                    || !expectedRouteAttemptId.equals(command.envelope().payload().routeAttemptId())) {
                continue;
            }
            List<String> mismatches = routeAttemptCommandMismatches(
                    expectation,
                    command,
                    velocityInstanceId,
                    commandName.value(),
                    commandSuffix,
                    expectedRevision);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(commandName.value() + " command mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected route-attempt " + commandName.value()
                + " command for " + expectation.label()
                + " (" + expectation.username() + ")"
                + " routeAttemptId=" + expectation.routeAttemptId()
                + " routeId=" + expectation.routeId().value()
                + " subjectId=" + expectation.subjectId().value()
                + " sessionId=" + expectation.sessionId().value()
                + ". Scanned commands=" + routeAttemptCommandIds(commands)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> routeAttemptCommandMismatches(
            LoginRoutingCommandExpectation expectation,
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command,
            InstanceId velocityInstanceId,
            String expectedCommandName,
            String commandSuffix,
            long expectedRevision) {
        List<String> mismatches = new ArrayList<>();
        RouteAttemptId routeAttemptId = new RouteAttemptId(expectation.routeAttemptId());
        String routeAttemptSuffix = compactRouteAttemptId(routeAttemptId);
        if (!expectedCommandName.equals(command.envelope().commandName().value())) {
            mismatches.add("commandName expected " + expectedCommandName
                    + " got " + command.envelope().commandName().value());
        }
        if (!ControlRouteNames.CONTRACT.value().equals(command.envelope().contractName().value())) {
            mismatches.add("contractName expected " + ControlRouteNames.CONTRACT.value()
                    + " got " + command.envelope().contractName().value());
        }
        if (!ControlRouteNames.aggregateId(routeAttemptId).equals(command.envelope().aggregateId())) {
            mismatches.add("aggregateId expected " + ControlRouteNames.aggregateId(routeAttemptId).value()
                    + " got " + command.envelope().aggregateId().value());
        }
        String expectedCommandId = "command-route-velocity-login-" + commandSuffix + "-" + routeAttemptSuffix;
        if (!expectedCommandId.equals(command.envelope().commandId().value())) {
            mismatches.add("commandId expected " + expectedCommandId
                    + " got " + command.envelope().commandId().value());
        }
        String expectedIdempotencyKey = "idem-route-velocity-login-" + commandSuffix + "-" + routeAttemptSuffix;
        if (!expectedIdempotencyKey.equals(command.envelope().idempotencyKey().value())) {
            mismatches.add("idempotencyKey expected " + expectedIdempotencyKey
                    + " got " + command.envelope().idempotencyKey().value());
        }
        if (!VELOCITY_LOGIN_PRINCIPAL_ID.equals(command.envelope().principalId().value())) {
            mismatches.add("principalId expected " + VELOCITY_LOGIN_PRINCIPAL_ID
                    + " got " + command.envelope().principalId().value());
        }
        if (!VELOCITY_LOGIN_PRINCIPAL_ID.equals(command.authenticatedPrincipal().value())) {
            mismatches.add("authenticatedPrincipal expected " + VELOCITY_LOGIN_PRINCIPAL_ID
                    + " got " + command.authenticatedPrincipal().value());
        }
        if (command.fencingEpoch() != 1L) {
            mismatches.add("fencingEpoch expected 1 got " + command.fencingEpoch());
        }
        if (!command.expectedRevision().map(revision -> revision.value() == expectedRevision).orElse(false)) {
            mismatches.add("expectedRevision expected " + expectedRevision
                    + " got " + command.expectedRevision()
                            .map(revision -> Long.toString(revision.value()))
                            .orElse("<empty>"));
        }
        String expectedTraceId = "trace-route-velocity-login-" + routeAttemptSuffix;
        if (!expectedTraceId.equals(command.envelope().traceEnvelope().traceId())) {
            mismatches.add("traceId expected " + expectedTraceId
                    + " got " + command.envelope().traceEnvelope().traceId());
        }
        if (!("span-route-velocity-login-" + commandSuffix + "-" + routeAttemptSuffix)
                .equals(command.envelope().traceEnvelope().spanId())) {
            mismatches.add("spanId mismatch");
        }
        if (!VELOCITY_LOGIN_ORIGIN_SERVICE.equals(command.envelope().traceEnvelope().originService())) {
            mismatches.add("originService expected " + VELOCITY_LOGIN_ORIGIN_SERVICE
                    + " got " + command.envelope().traceEnvelope().originService());
        }
        if (!velocityInstanceId.equals(command.envelope().traceEnvelope().originInstanceId())) {
            mismatches.add("originInstanceId expected " + velocityInstanceId.value()
                    + " got " + command.envelope().traceEnvelope().originInstanceId().value());
        }
        String expectedFingerprint = "route-attempt|command=" + expectedCommandName
                + "|routeAttemptId=" + expectation.routeAttemptId()
                + "|revision=" + expectedRevision;
        if (!expectedFingerprint.equals(command.payloadFingerprint())) {
            mismatches.add("payloadFingerprint expected " + expectedFingerprint
                    + " got " + command.payloadFingerprint());
        }
        appendRouteAttemptPayloadMismatches(expectation, command.envelope().payload(), velocityInstanceId, mismatches);
        return mismatches;
    }

    private static void appendRouteAttemptPayloadMismatches(
            LoginRoutingCommandExpectation expectation,
            RouteAttemptCommand payload,
            InstanceId velocityInstanceId,
            List<String> mismatches) {
        if (payload instanceof RequestRouteAttempt request) {
            if (!expectation.routeId().equals(request.routeId())) {
                mismatches.add("routeId expected " + expectation.routeId().value()
                        + " got " + request.routeId().value());
            }
            if (!expectation.sessionId().equals(request.sessionId())) {
                mismatches.add("sessionId expected " + expectation.sessionId().value()
                        + " got " + request.sessionId().value());
            }
            if (!expectation.slotId().equals(request.allocationSlotId())) {
                mismatches.add("allocationSlotId expected " + expectation.slotId().value()
                        + " got " + request.allocationSlotId().value());
            }
            if (!List.of(expectation.subjectId()).equals(request.subjectIds())) {
                mismatches.add("subjectIds expected " + expectation.subjectId().value()
                        + " got " + request.subjectIds().stream()
                                .map(subjectId -> subjectId.value().toString())
                                .collect(Collectors.joining(",")));
            }
            if (!List.of(velocityInstanceId).equals(request.proxyInstanceIds())) {
                mismatches.add("proxyInstanceIds expected " + velocityInstanceId.value()
                        + " got " + request.proxyInstanceIds().stream()
                                .map(InstanceId::value)
                                .collect(Collectors.joining(",")));
            }
            if (!expectation.presenceId().equals(request.sourcePresenceId())) {
                mismatches.add("sourcePresenceId expected " + expectation.presenceId().value()
                        + " got " + request.sourcePresenceId().value());
            }
            if (!expectation.targetInstanceId().equals(request.targetInstanceId())) {
                mismatches.add("targetInstanceId expected " + expectation.targetInstanceId().value()
                        + " got " + request.targetInstanceId().value());
            }
            if (!expectation.targetResolvedManifestId().equals(request.targetResolvedManifestId())) {
                mismatches.add("targetResolvedManifestId expected " + expectation.targetResolvedManifestId().value()
                        + " got " + request.targetResolvedManifestId().value());
            }
            if (request.deadlineAt().isBefore(expectation.minimumLeaseExpiresAt())) {
                mismatches.add("deadlineAt expected at or after " + expectation.minimumLeaseExpiresAt()
                        + " got " + request.deadlineAt());
            }
            return;
        }
        if (payload instanceof IssueProxyRoute || payload instanceof PrepareHostRoute) {
            return;
        }
        mismatches.add("payload expected RequestRouteAttempt, IssueProxyRoute, or PrepareHostRoute got "
                + payload.getClass().getSimpleName());
    }

    private static String routeAttemptCommandIds(
            List<RouteAttemptControlCommand<? extends RouteAttemptCommand>> commands) {
        return commands.stream()
                .map(command -> command.envelope().commandName().value()
                        + "/" + command.envelope().payload().routeAttemptId().value())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static List<LifecycleTraceControlCommand<RecordLifecycleObservation>> lifecycleTraceCommands(
            String output) {
        List<LifecycleTraceControlCommand<RecordLifecycleObservation>> commands = new ArrayList<>();
        for (String payload : authorityCommandPayloads(output)) {
            commands.add(ControlCommandWireCodec.decodeLifecycleTraceRecord(
                    new ConsumerRecord<>("", 0, 0L, null, payload)));
        }
        return List.copyOf(commands);
    }

    private static void verifyLifecycleTraceCommandSequence(
            LoginRoutingCommandExpectation expectation,
            List<LifecycleTraceControlCommand<RecordLifecycleObservation>> commands) {
        verifyLifecycleTraceCommand(
                expectation,
                commands,
                LifecyclePhase.QUEUE_INTENT_SUBMITTED,
                "queue-intent",
                expectedQueueIntentId(expectation).value(),
                Optional.empty(),
                Optional.empty(),
                "queue");
        verifyLifecycleTraceCommand(
                expectation,
                commands,
                LifecyclePhase.ROSTER_INTENT_FORMED,
                "roster-intent",
                expectedRosterIntentId(expectation).value(),
                Optional.empty(),
                Optional.empty(),
                "roster");
        verifyLifecycleTraceCommand(
                expectation,
                commands,
                LifecyclePhase.ALLOCATION_CLAIMED,
                "slot",
                expectation.slotId().value(),
                Optional.of(expectation.sessionId()),
                Optional.of(expectation.targetResolvedManifestId()),
                "allocation");
        verifyLifecycleTraceCommand(
                expectation,
                commands,
                LifecyclePhase.ROUTE_ATTEMPT_CREATED,
                "route-attempt",
                expectation.routeAttemptId(),
                Optional.of(expectation.sessionId()),
                Optional.of(expectation.targetResolvedManifestId()),
                "route-attempt");
    }

    private static void verifyLifecycleTraceCommand(
            LoginRoutingCommandExpectation expectation,
            List<LifecycleTraceControlCommand<RecordLifecycleObservation>> commands,
            LifecyclePhase phase,
            String aggregateType,
            String aggregateId,
            Optional<SessionId> sessionId,
            Optional<ResolvedManifestId> resolvedManifestId,
            String commandSuffix) {
        List<String> diagnostics = new ArrayList<>();
        String expectedTraceId = expectedVelocityLifecycleTraceId(expectation);
        for (LifecycleTraceControlCommand<RecordLifecycleObservation> command : commands) {
            RecordLifecycleObservation payload = command.envelope().payload();
            if (payload.phase() != phase || !expectedTraceId.equals(payload.traceId().value())) {
                continue;
            }
            List<String> mismatches = lifecycleTraceCommandMismatches(
                    expectation,
                    command,
                    phase,
                    aggregateType,
                    aggregateId,
                    sessionId,
                    resolvedManifestId,
                    commandSuffix);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(phase.name() + " command mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected lifecycle trace " + phase.name()
                + " command for " + expectation.label()
                + " (" + expectation.username() + ")"
                + " traceId=" + expectedTraceId
                + " aggregateId=" + aggregateId
                + ". Scanned commands=" + lifecycleTraceCommandIds(commands)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> lifecycleTraceCommandMismatches(
            LoginRoutingCommandExpectation expectation,
            LifecycleTraceControlCommand<RecordLifecycleObservation> command,
            LifecyclePhase phase,
            String aggregateType,
            String aggregateId,
            Optional<SessionId> sessionId,
            Optional<ResolvedManifestId> resolvedManifestId,
            String commandSuffix) {
        List<String> mismatches = new ArrayList<>();
        RecordLifecycleObservation payload = command.envelope().payload();
        String expectedTraceId = expectedVelocityLifecycleTraceId(expectation);
        String compactTraceId = expectedTraceId.replace("-", "");
        if (!ControlLifecycleNames.RECORD_LIFECYCLE_OBSERVATION.value().equals(command.envelope().commandName().value())) {
            mismatches.add("commandName expected " + ControlLifecycleNames.RECORD_LIFECYCLE_OBSERVATION.value()
                    + " got " + command.envelope().commandName().value());
        }
        if (!ControlLifecycleNames.TRACE_CONTRACT.value().equals(command.envelope().contractName().value())) {
            mismatches.add("contractName expected " + ControlLifecycleNames.TRACE_CONTRACT.value()
                    + " got " + command.envelope().contractName().value());
        }
        if (!ControlLifecycleNames.traceAggregateId(payload.traceId()).equals(command.envelope().aggregateId())) {
            mismatches.add("aggregateId expected " + ControlLifecycleNames.traceAggregateId(payload.traceId()).value()
                    + " got " + command.envelope().aggregateId().value());
        }
        String expectedCommandId = "command-lifecycle-velocity-login-" + commandSuffix + "-" + compactTraceId;
        if (!expectedCommandId.equals(command.envelope().commandId().value())) {
            mismatches.add("commandId expected " + expectedCommandId
                    + " got " + command.envelope().commandId().value());
        }
        String expectedIdempotencyKey = "idem-lifecycle-velocity-login-" + commandSuffix + "-" + compactTraceId;
        if (!expectedIdempotencyKey.equals(command.envelope().idempotencyKey().value())) {
            mismatches.add("idempotencyKey expected " + expectedIdempotencyKey
                    + " got " + command.envelope().idempotencyKey().value());
        }
        if (!VELOCITY_LOGIN_PRINCIPAL_ID.equals(command.envelope().principalId().value())) {
            mismatches.add("principalId expected " + VELOCITY_LOGIN_PRINCIPAL_ID
                    + " got " + command.envelope().principalId().value());
        }
        if (!VELOCITY_LOGIN_PRINCIPAL_ID.equals(command.authenticatedPrincipal().value())) {
            mismatches.add("authenticatedPrincipal expected " + VELOCITY_LOGIN_PRINCIPAL_ID
                    + " got " + command.authenticatedPrincipal().value());
        }
        if (command.fencingEpoch() != 1L) {
            mismatches.add("fencingEpoch expected 1 got " + command.fencingEpoch());
        }
        if (command.expectedRevision().isPresent()) {
            mismatches.add("expectedRevision expected empty got "
                    + command.expectedRevision().orElseThrow().value());
        }
        if (!expectedTraceId.equals(command.envelope().traceEnvelope().traceId())) {
            mismatches.add("traceId expected " + expectedTraceId
                    + " got " + command.envelope().traceEnvelope().traceId());
        }
        if (!VELOCITY_LOGIN_ORIGIN_SERVICE.equals(command.envelope().traceEnvelope().originService())) {
            mismatches.add("originService expected " + VELOCITY_LOGIN_ORIGIN_SERVICE
                    + " got " + command.envelope().traceEnvelope().originService());
        }
        String expectedFingerprint = "lifecycle-trace|phase=" + phase.name()
                + "|traceId=" + expectedTraceId
                + "|aggregateType=" + aggregateType
                + "|aggregateId=" + aggregateId;
        if (!expectedFingerprint.equals(command.payloadFingerprint())) {
            mismatches.add("payloadFingerprint expected " + expectedFingerprint
                    + " got " + command.payloadFingerprint());
        }
        if (payload.phase() != phase) {
            mismatches.add("phase expected " + phase + " got " + payload.phase());
        }
        if (!aggregateType.equals(payload.aggregateType())) {
            mismatches.add("aggregateType expected " + aggregateType + " got " + payload.aggregateType());
        }
        if (!aggregateId.equals(payload.aggregateId())) {
            mismatches.add("aggregateId expected " + aggregateId + " got " + payload.aggregateId());
        }
        if (!sessionId.equals(payload.sessionId())) {
            mismatches.add("sessionId expected " + sessionId.map(SessionId::value).orElse("<empty>")
                    + " got " + payload.sessionId().map(SessionId::value).orElse("<empty>"));
        }
        if (!resolvedManifestId.equals(payload.resolvedManifestId())) {
            mismatches.add("resolvedManifestId expected "
                    + resolvedManifestId.map(ResolvedManifestId::value).orElse("<empty>")
                    + " got " + payload.resolvedManifestId().map(ResolvedManifestId::value).orElse("<empty>"));
        }
        return mismatches;
    }

    private static String lifecycleTraceCommandIds(
            List<LifecycleTraceControlCommand<RecordLifecycleObservation>> commands) {
        return commands.stream()
                .map(command -> command.envelope().payload().phase().name()
                        + "/" + command.envelope().payload().traceId().value()
                        + "/" + command.envelope().payload().aggregateId())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static void verifyDeniedLoginRoutingCommandsAbsent(
            DeniedLoginRoutingCommandExpectation expectation,
            List<QueueRosterControlCommand<? extends QueueRosterCommand>> queueRosterCommands,
            List<AuthorityCommand<PresenceCommand>> presenceCommands,
            List<SharedShardPlacementWireRequest> placementRequests,
            List<RouteAttemptControlCommand<? extends RouteAttemptCommand>> routeAttemptCommands,
            List<LifecycleTraceControlCommand<RecordLifecycleObservation>> lifecycleTraceCommands) {
        List<String> found = new ArrayList<>();
        queueRosterCommands.stream()
                .filter(command -> deniedQueueRosterCommandMatches(expectation, command.envelope().payload()))
                .forEach(command -> found.add("queue-roster " + command.envelope().commandName().value()
                        + "/" + compactQueueRosterPayloadId(command.envelope().payload())));
        presenceCommands.stream()
                .filter(command -> command.envelope().payload() instanceof ClaimPresence claim
                        && expectation.subjectId().equals(claim.subjectId()))
                .forEach(command -> found.add("presence " + command.envelope().commandId().value()));
        placementRequests.stream()
                .filter(request -> expectation.subjectId().equals(request.request().subjectId()))
                .forEach(request -> found.add("placement " + request.request().placementAttemptId()));
        routeAttemptCommands.stream()
                .filter(command -> deniedRouteAttemptCommandMatches(expectation, command))
                .forEach(command -> found.add("route-attempt " + command.envelope().commandName().value()
                        + "/" + command.envelope().payload().routeAttemptId().value()));
        lifecycleTraceCommands.stream()
                .filter(command -> deniedLifecycleTraceCommandMatches(expectation, command))
                .forEach(command -> found.add("lifecycle-trace " + command.envelope().payload().phase().name()
                        + "/" + command.envelope().payload().traceId().value()
                        + "/" + command.envelope().payload().aggregateId()));
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Expected Velocity login-routing command logs to omit denied "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " subjectId=" + expectation.subjectId().value()
                + ", found " + String.join(", ", found));
    }

    private static boolean deniedQueueRosterCommandMatches(
            DeniedLoginRoutingCommandExpectation expectation,
            QueueRosterCommand payload) {
        String subjectSuffix = compactSubject(expectation.subjectId());
        QueueIntentId queueIntentId = new QueueIntentId("queue-intent-velocity-login-" + subjectSuffix);
        RosterIntentId rosterIntentId = new RosterIntentId("roster-intent-velocity-login-" + subjectSuffix);
        if (payload instanceof SubmitQueueIntent submit) {
            return submit.subjectIds().contains(expectation.subjectId())
                    || queueIntentId.equals(submit.queueIntentId());
        }
        return payload instanceof FormRosterIntent form
                && (rosterIntentId.equals(form.rosterIntentId())
                        || form.queueIntentIds().contains(queueIntentId));
    }

    private static boolean deniedRouteAttemptCommandMatches(
            DeniedLoginRoutingCommandExpectation expectation,
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command) {
        if (expectation.routeAttemptId().equals(command.envelope().payload().routeAttemptId().value())) {
            return true;
        }
        return command.envelope().payload() instanceof RequestRouteAttempt request
                && request.subjectIds().contains(expectation.subjectId());
    }

    private static boolean deniedLifecycleTraceCommandMatches(
            DeniedLoginRoutingCommandExpectation expectation,
            LifecycleTraceControlCommand<RecordLifecycleObservation> command) {
        String subjectSuffix = compactSubject(expectation.subjectId());
        RecordLifecycleObservation payload = command.envelope().payload();
        return payload.traceId().value().equals("trace-velocity-login-" + subjectSuffix)
                || payload.aggregateId().contains(subjectSuffix)
                || payload.aggregateId().equals(expectation.routeAttemptId());
    }

    private static Optional<QueueRosterStateResult> verifyQueueRosterState(
            VerificationConfig config,
            List<LoginRoutingCommandExpectation> expectations,
            List<DeniedLoginRoutingCommandExpectation> deniedExpectations) throws IOException {
        if (!config.verifyQueueRosterState()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.routeAttemptStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> output = consumeQueueRosterState(config);
                if (output.isPresent()) {
                    return Optional.of(verifyQueueRosterStateOutput(
                            output.orElseThrow(),
                            config,
                            expectations,
                            deniedExpectations));
                }
                failures.add("queue-roster state topic returned no records");
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for queue-roster controller state within "
                + config.routeAttemptStateTimeout()
                + " on " + config.queueRosterStateTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static QueueRosterStateResult verifyQueueRosterStateOutput(
            String output,
            VerificationConfig config,
            List<LoginRoutingCommandExpectation> expectations,
            List<DeniedLoginRoutingCommandExpectation> deniedExpectations) {
        List<QueueRosterControlRecord> records = queueRosterStateRecords(output);
        for (LoginRoutingCommandExpectation expectation : expectations) {
            verifyQueueRosterStateRecord(config, expectation, records);
        }
        for (DeniedLoginRoutingCommandExpectation expectation : deniedExpectations) {
            verifyDeniedQueueRosterStateAbsent(expectation, records);
        }
        return new QueueRosterStateResult(expectations.size(), records.size());
    }

    private static List<QueueRosterControlRecord> queueRosterStateRecords(String output) {
        return routeAttemptPayloads(output).stream()
                .filter(payload -> ControllerStateWireCodec.isRecordType(
                        payload,
                        ControllerWorkerCatalog.QUEUE_ROSTER))
                .map(ControllerStateWireCodec::decodeQueueRoster)
                .toList();
    }

    private static void verifyQueueRosterStateRecord(
            VerificationConfig config,
            LoginRoutingCommandExpectation expectation,
            List<QueueRosterControlRecord> records) {
        List<String> diagnostics = new ArrayList<>();
        QueueIntentId queueIntentId = expectedQueueIntentId(expectation);
        RosterIntentId rosterIntentId = expectedRosterIntentId(expectation);
        for (QueueRosterControlRecord record : records) {
            Optional<QueueIntentSnapshot> queueIntent = record.state().queueIntent(queueIntentId);
            Optional<RosterIntentSnapshot> rosterIntent = record.state().rosterIntent(rosterIntentId);
            if (queueIntent.isEmpty() && rosterIntent.isEmpty()) {
                continue;
            }
            List<String> mismatches = queueRosterStateMismatches(
                    config,
                    expectation,
                    record,
                    queueIntent,
                    rosterIntent);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("record revision " + record.revision().value()
                    + " mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected queue-roster state for "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " subjectId=" + expectation.subjectId().value()
                + " queueIntentId=" + queueIntentId.value()
                + " rosterIntentId=" + rosterIntentId.value()
                + ". Scanned queueIntentIds=" + queueRosterQueueIntentIds(records)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> queueRosterStateMismatches(
            VerificationConfig config,
            LoginRoutingCommandExpectation expectation,
            QueueRosterControlRecord record,
            Optional<QueueIntentSnapshot> optionalQueueIntent,
            Optional<RosterIntentSnapshot> optionalRosterIntent) {
        List<String> mismatches = new ArrayList<>();
        if (record.fencingEpoch() != 1L) {
            mismatches.add("fencingEpoch expected 1 got " + record.fencingEpoch());
        }
        QueueIntentSnapshot queueIntent = optionalQueueIntent.orElse(null);
        if (queueIntent == null) {
            mismatches.add("queue intent missing");
        } else {
            if (!List.of(expectation.subjectId()).equals(queueIntent.subjectIds())) {
                mismatches.add("queue subjectIds expected " + expectation.subjectId().value()
                        + " got " + queueIntent.subjectIds().stream()
                                .map(subjectId -> subjectId.value().toString())
                                .collect(Collectors.joining(",")));
            }
            if (!config.expectedExperienceId().equals(queueIntent.experienceId())) {
                mismatches.add("queue experienceId expected " + config.expectedExperienceId().value()
                        + " got " + queueIntent.experienceId().value());
            }
            if (!config.expectedPoolId().equals(queueIntent.poolId())) {
                mismatches.add("queue poolId expected " + config.expectedPoolId().value()
                        + " got " + queueIntent.poolId().value());
            }
            if (queueIntent.modeId().isPresent()) {
                mismatches.add("queue modeId expected empty got " + queueIntent.modeId().orElseThrow());
            }
            if (queueIntent.status() != QueueIntentStatus.ROSTERED) {
                mismatches.add("queue status expected ROSTERED got " + queueIntent.status());
            }
            if (!Optional.of(expectedRosterIntentId(expectation)).equals(queueIntent.rosterIntentId())) {
                mismatches.add("queue rosterIntentId expected " + expectedRosterIntentId(expectation).value()
                        + " got " + queueIntent.rosterIntentId()
                                .map(RosterIntentId::value)
                                .orElse("<empty>"));
            }
        }
        RosterIntentSnapshot rosterIntent = optionalRosterIntent.orElse(null);
        if (rosterIntent == null) {
            mismatches.add("roster intent missing");
        } else {
            if (!List.of(expectedQueueIntentId(expectation)).equals(rosterIntent.queueIntentIds())) {
                mismatches.add("roster queueIntentIds expected " + expectedQueueIntentId(expectation).value()
                        + " got " + rosterIntent.queueIntentIds().stream()
                                .map(QueueIntentId::value)
                                .collect(Collectors.joining(",")));
            }
            if (!List.of(expectation.subjectId()).equals(rosterIntent.subjectIds())) {
                mismatches.add("roster subjectIds expected " + expectation.subjectId().value()
                        + " got " + rosterIntent.subjectIds().stream()
                                .map(subjectId -> subjectId.value().toString())
                                .collect(Collectors.joining(",")));
            }
            if (!config.expectedExperienceId().equals(rosterIntent.experienceId())) {
                mismatches.add("roster experienceId expected " + config.expectedExperienceId().value()
                        + " got " + rosterIntent.experienceId().value());
            }
            if (!config.expectedPoolId().equals(rosterIntent.poolId())) {
                mismatches.add("roster poolId expected " + config.expectedPoolId().value()
                        + " got " + rosterIntent.poolId().value());
            }
            if (rosterIntent.modeId().isPresent()) {
                mismatches.add("roster modeId expected empty got " + rosterIntent.modeId().orElseThrow());
            }
            if (rosterIntent.maxSubjects() != 1) {
                mismatches.add("roster maxSubjects expected 1 got " + rosterIntent.maxSubjects());
            }
            if (rosterIntent.status() != RosterIntentStatus.FORMED) {
                mismatches.add("roster status expected FORMED got " + rosterIntent.status());
            }
        }
        return mismatches;
    }

    private static void verifyDeniedQueueRosterStateAbsent(
            DeniedLoginRoutingCommandExpectation expectation,
            List<QueueRosterControlRecord> records) {
        String subjectSuffix = compactSubject(expectation.subjectId());
        QueueIntentId queueIntentId = new QueueIntentId("queue-intent-velocity-login-" + subjectSuffix);
        RosterIntentId rosterIntentId = new RosterIntentId("roster-intent-velocity-login-" + subjectSuffix);
        List<String> found = new ArrayList<>();
        for (QueueRosterControlRecord record : records) {
            record.state().queueIntent(queueIntentId)
                    .ifPresent(intent -> found.add("queueIntentId=" + intent.queueIntentId().value()));
            record.state().rosterIntent(rosterIntentId)
                    .ifPresent(intent -> found.add("rosterIntentId=" + intent.rosterIntentId().value()));
            record.state().queueIntents().values().stream()
                    .filter(intent -> intent.subjectIds().contains(expectation.subjectId()))
                    .forEach(intent -> found.add("queueSubject=" + intent.queueIntentId().value()));
            record.state().rosterIntents().values().stream()
                    .filter(intent -> intent.subjectIds().contains(expectation.subjectId()))
                    .forEach(intent -> found.add("rosterSubject=" + intent.rosterIntentId().value()));
        }
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Expected queue-roster state to omit denied "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " subjectId=" + expectation.subjectId().value()
                + ", found " + String.join(", ", found));
    }

    private static String queueRosterQueueIntentIds(List<QueueRosterControlRecord> records) {
        return records.stream()
                .flatMap(record -> record.state().queueIntents().keySet().stream())
                .map(QueueIntentId::value)
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static Optional<LifecycleTraceStateResult> verifyLifecycleTraceState(
            VerificationConfig config,
            List<LoginRoutingCommandExpectation> expectations,
            List<DeniedLoginRoutingCommandExpectation> deniedExpectations) throws IOException {
        if (!config.verifyLifecycleTraceState()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.routeAttemptStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> output = consumeLifecycleTraceState(config);
                if (output.isPresent()) {
                    return Optional.of(verifyLifecycleTraceStateOutput(
                            output.orElseThrow(),
                            expectations,
                            deniedExpectations));
                }
                failures.add("lifecycle trace state topic returned no records");
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for lifecycle trace state within "
                + config.routeAttemptStateTimeout()
                + " on " + config.lifecycleTraceStateTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static LifecycleTraceStateResult verifyLifecycleTraceStateOutput(
            String output,
            List<LoginRoutingCommandExpectation> expectations,
            List<DeniedLoginRoutingCommandExpectation> deniedExpectations) {
        List<LifecycleTraceControlRecord> records = lifecycleTraceStateRecords(output);
        for (LoginRoutingCommandExpectation expectation : expectations) {
            verifyLifecycleTraceStateRecord(expectation, records);
        }
        for (DeniedLoginRoutingCommandExpectation expectation : deniedExpectations) {
            verifyDeniedLifecycleTraceStateAbsent(expectation, records);
        }
        return new LifecycleTraceStateResult(expectations.size(), records.size());
    }

    private static List<LifecycleTraceControlRecord> lifecycleTraceStateRecords(String output) {
        return routeAttemptPayloads(output).stream()
                .filter(payload -> ControllerStateWireCodec.isRecordType(
                        payload,
                        ControllerWorkerCatalog.LIFECYCLE_TRACE))
                .map(ControllerStateWireCodec::decodeLifecycleTrace)
                .toList();
    }

    private static void verifyLifecycleTraceStateRecord(
            LoginRoutingCommandExpectation expectation,
            List<LifecycleTraceControlRecord> records) {
        String expectedTraceId = expectedVelocityLifecycleTraceId(expectation);
        List<String> diagnostics = new ArrayList<>();
        for (LifecycleTraceControlRecord record : records) {
            if (!expectedTraceId.equals(record.traceRecord().traceId().value())) {
                continue;
            }
            List<String> mismatches = lifecycleTraceStateMismatches(expectation, record);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("record revision " + record.revision().value()
                    + " mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected lifecycle trace state for "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " traceId=" + expectedTraceId
                + " sessionId=" + expectation.sessionId().value()
                + " routeAttemptId=" + expectation.routeAttemptId()
                + ". Scanned traceIds=" + lifecycleTraceIds(records)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> lifecycleTraceStateMismatches(
            LoginRoutingCommandExpectation expectation,
            LifecycleTraceControlRecord record) {
        List<String> mismatches = new ArrayList<>();
        if (record.fencingEpoch() != 1L) {
            mismatches.add("fencingEpoch expected 1 got " + record.fencingEpoch());
        }
        appendLifecycleEntryMismatch(
                mismatches,
                record,
                LifecyclePhase.QUEUE_INTENT_SUBMITTED,
                "queue-intent",
                expectedQueueIntentId(expectation).value(),
                Optional.empty(),
                Optional.empty());
        appendLifecycleEntryMismatch(
                mismatches,
                record,
                LifecyclePhase.ROSTER_INTENT_FORMED,
                "roster-intent",
                expectedRosterIntentId(expectation).value(),
                Optional.empty(),
                Optional.empty());
        appendLifecycleEntryMismatch(
                mismatches,
                record,
                LifecyclePhase.ALLOCATION_CLAIMED,
                "slot",
                expectation.slotId().value(),
                Optional.of(expectation.sessionId()),
                Optional.of(expectation.targetResolvedManifestId()));
        appendLifecycleEntryMismatch(
                mismatches,
                record,
                LifecyclePhase.ROUTE_ATTEMPT_CREATED,
                "route-attempt",
                expectation.routeAttemptId(),
                Optional.of(expectation.sessionId()),
                Optional.of(expectation.targetResolvedManifestId()));
        appendLifecycleEntryMismatch(
                mismatches,
                record,
                LifecyclePhase.HOST_ATTACH_OBSERVED,
                "instance",
                expectation.targetInstanceId().value(),
                Optional.of(expectation.sessionId()),
                Optional.of(expectation.targetResolvedManifestId()));
        appendLifecycleEntryMismatch(
                mismatches,
                record,
                LifecyclePhase.SESSION_ACTIVE,
                "session",
                expectation.sessionId().value(),
                Optional.of(expectation.sessionId()),
                Optional.of(expectation.targetResolvedManifestId()));
        return mismatches;
    }

    private static void appendLifecycleEntryMismatch(
            List<String> mismatches,
            LifecycleTraceControlRecord record,
            LifecyclePhase phase,
            String aggregateType,
            String aggregateId,
            Optional<SessionId> sessionId,
            Optional<ResolvedManifestId> resolvedManifestId) {
        List<String> diagnostics = new ArrayList<>();
        for (LifecycleTraceEntry entry : record.traceRecord().entries()) {
            if (entry.phase() != phase) {
                continue;
            }
            List<String> entryMismatches = new ArrayList<>();
            if (!aggregateType.equals(entry.aggregateType())) {
                entryMismatches.add("aggregateType expected " + aggregateType + " got " + entry.aggregateType());
            }
            if (!aggregateId.equals(entry.aggregateId())) {
                entryMismatches.add("aggregateId expected " + aggregateId + " got " + entry.aggregateId());
            }
            if (!sessionId.equals(entry.sessionId())) {
                entryMismatches.add("sessionId expected " + sessionId.map(SessionId::value).orElse("<empty>")
                        + " got " + entry.sessionId().map(SessionId::value).orElse("<empty>"));
            }
            if (!resolvedManifestId.equals(entry.resolvedManifestId())) {
                entryMismatches.add("resolvedManifestId expected "
                        + resolvedManifestId.map(ResolvedManifestId::value).orElse("<empty>")
                        + " got " + entry.resolvedManifestId().map(ResolvedManifestId::value).orElse("<empty>"));
            }
            if (!record.traceRecord().traceId().value().equals(entry.traceEnvelope().traceId())) {
                entryMismatches.add("entry traceId expected " + record.traceRecord().traceId().value()
                        + " got " + entry.traceEnvelope().traceId());
            }
            if (entryMismatches.isEmpty()) {
                return;
            }
            diagnostics.add(String.join(", ", entryMismatches));
        }
        mismatches.add(phase.name() + " missing or mismatched"
                + (diagnostics.isEmpty() ? "" : ": " + String.join(" | ", diagnostics)));
    }

    private static void verifyDeniedLifecycleTraceStateAbsent(
            DeniedLoginRoutingCommandExpectation expectation,
            List<LifecycleTraceControlRecord> records) {
        String subjectSuffix = compactSubject(expectation.subjectId());
        String deniedTraceId = "trace-velocity-login-" + subjectSuffix;
        List<String> found = new ArrayList<>();
        for (LifecycleTraceControlRecord record : records) {
            if (deniedTraceId.equals(record.traceRecord().traceId().value())) {
                found.add("traceId=" + deniedTraceId);
            }
            record.traceRecord().entries().stream()
                    .filter(entry -> entry.aggregateId().contains(subjectSuffix)
                            || entry.aggregateId().equals(expectation.routeAttemptId()))
                    .forEach(entry -> found.add(entry.phase().name() + "/" + entry.aggregateId()));
        }
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Expected lifecycle trace state to omit denied "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " subjectId=" + expectation.subjectId().value()
                + ", found " + String.join(", ", found));
    }

    private static String lifecycleTraceIds(List<LifecycleTraceControlRecord> records) {
        return records.stream()
                .map(record -> record.traceRecord().traceId().value())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static Optional<HostRouteCommandLogResult> verifyHostRouteCommandLogs(
            VerificationConfig config,
            List<HostRouteCommandExpectation> expectations,
            List<DeniedHostRouteCommandExpectation> deniedExpectations) throws IOException {
        if (!config.verifyHostRouteCommandLogs()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.routeAttemptStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> proxyOutput = consumeProxyRouteCommandLog(config);
                Optional<String> paperOutput = consumePaperHostCommandLog(config);
                if (proxyOutput.isPresent() && paperOutput.isPresent()) {
                    return Optional.of(verifyHostRouteCommandLogsOutput(
                            proxyOutput.orElseThrow(),
                            paperOutput.orElseThrow(),
                            expectations,
                            deniedExpectations));
                }
                if (proxyOutput.isEmpty()) {
                    failures.add("Velocity proxy route command topic returned no proxy.route records");
                }
                if (paperOutput.isEmpty()) {
                    failures.add("Paper host command topic returned no host.route.prepare records");
                }
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for addressed host route commands within "
                + config.routeAttemptStateTimeout()
                + " on " + config.proxyRouteCommandTopic()
                + " and " + config.paperHostCommandTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static HostRouteCommandLogResult verifyHostRouteCommandLogsOutput(
            String proxyRouteOutput,
            String paperHostOutput,
            List<HostRouteCommandExpectation> expectations,
            List<DeniedHostRouteCommandExpectation> deniedExpectations) {
        List<VelocityProxyRouteCommand> proxyCommands = proxyRouteCommands(proxyRouteOutput);
        List<PaperHostRoutePrepareCommand> paperCommands = paperHostRoutePrepareCommands(paperHostOutput);
        for (HostRouteCommandExpectation expectation : expectations) {
            verifyProxyRouteCommand(expectation, proxyCommands);
            verifyPaperHostRoutePrepareCommand(expectation, paperCommands);
        }
        for (DeniedHostRouteCommandExpectation expectation : deniedExpectations) {
            verifyDeniedProxyRouteCommandsAbsent(expectation, proxyCommands);
            verifyDeniedPaperHostRoutePrepareCommandsAbsent(expectation, paperCommands);
        }
        return new HostRouteCommandLogResult(expectations.size(), proxyCommands.size(), paperCommands.size());
    }

    private static List<HostRouteCommandExpectation> hostRouteCommandExpectations(
            List<RouteAttemptExpectation> routeAttemptExpectations) {
        Map<String, HostRouteCommandExpectation> expectations = new LinkedHashMap<>();
        for (RouteAttemptExpectation expectation : routeAttemptExpectations) {
            HostRouteCommandExpectation next = HostRouteCommandExpectation.from(expectation);
            HostRouteCommandExpectation previous = expectations.putIfAbsent(next.routeAttemptId(), next);
            if (previous != null) {
                List<String> mismatches = previous.compatibilityMismatches(next);
                if (!mismatches.isEmpty()) {
                    throw new IllegalStateException("Accepted lobby proofs disagreed for host route command "
                            + next.routeAttemptId()
                            + ": " + String.join(", ", mismatches));
                }
            }
        }
        return List.copyOf(expectations.values());
    }

    private static List<DeniedHostRouteCommandExpectation> deniedHostRouteCommandExpectations(
            List<DeniedRouteAttemptExpectation> deniedRouteAttemptExpectations) {
        return deniedRouteAttemptExpectations.stream()
                .map(DeniedHostRouteCommandExpectation::from)
                .toList();
    }

    private static List<VelocityProxyRouteCommand> proxyRouteCommands(String output) {
        return singleLineHostCommandPayloads(output, "proxy.route").stream()
                .map(VelocityProxyRouteCommand::parse)
                .toList();
    }

    private static List<PaperHostRoutePrepareCommand> paperHostRoutePrepareCommands(String output) {
        return singleLineHostCommandPayloads(output, "host.route.prepare").stream()
                .map(PaperHostRoutePrepareCommand::parse)
                .toList();
    }

    private static List<String> singleLineHostCommandPayloads(String output, String prefix) {
        return Objects.requireNonNull(output, "output").lines()
                .map(String::trim)
                .filter(line -> line.startsWith(prefix))
                .toList();
    }

    private static void verifyProxyRouteCommand(
            HostRouteCommandExpectation expectation,
            List<VelocityProxyRouteCommand> commands) {
        List<String> diagnostics = new ArrayList<>();
        for (VelocityProxyRouteCommand command : commands) {
            if (!expectation.routeAttemptId().equals(command.routeAttemptId())) {
                continue;
            }
            List<String> mismatches = proxyRouteCommandMismatches(expectation, command);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("proxy.route " + command.routeAttemptId()
                    + " mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Velocity proxy route command for "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " routeAttemptId=" + expectation.routeAttemptId()
                + " routeId=" + expectation.routeId().value()
                + " subjectId=" + expectation.subjectId().value()
                + " sessionId=" + expectation.sessionId().value()
                + " targetInstanceId=" + expectation.targetInstanceId().value()
                + ". Scanned commands=" + proxyRouteCommandIds(commands)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> proxyRouteCommandMismatches(
            HostRouteCommandExpectation expectation,
            VelocityProxyRouteCommand command) {
        List<String> mismatches = new ArrayList<>();
        if (!expectation.routeId().equals(command.routeId())) {
            mismatches.add("routeId expected " + expectation.routeId().value()
                    + " got " + command.routeId().value());
        }
        if (!expectation.subjectId().equals(command.subjectId())) {
            mismatches.add("subjectId expected " + expectation.subjectId().value()
                    + " got " + command.subjectId().value());
        }
        if (!expectation.sessionId().equals(command.targetSessionId())) {
            mismatches.add("sessionId expected " + expectation.sessionId().value()
                    + " got " + command.targetSessionId().value());
        }
        if (!expectation.targetInstanceId().equals(command.targetInstanceId())) {
            mismatches.add("targetInstanceId expected " + expectation.targetInstanceId().value()
                    + " got " + command.targetInstanceId().value());
        }
        if (!expectation.traceId().equals(command.traceId())) {
            mismatches.add("traceId expected " + expectation.traceId()
                    + " got " + command.traceId());
        }
        return mismatches;
    }

    private static String proxyRouteCommandIds(List<VelocityProxyRouteCommand> commands) {
        return commands.stream()
                .map(command -> command.routeAttemptId() + "/" + command.routeId().value())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static void verifyDeniedProxyRouteCommandsAbsent(
            DeniedHostRouteCommandExpectation expectation,
            List<VelocityProxyRouteCommand> commands) {
        List<String> found = commands.stream()
                .filter(command -> expectation.routeAttemptId().equals(command.routeAttemptId())
                        || expectation.subjectId().equals(command.subjectId()))
                .map(command -> command.routeAttemptId()
                        + "/routeId=" + command.routeId().value()
                        + "/subjectId=" + command.subjectId().value())
                .sorted()
                .toList();
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Expected Velocity proxy route command log to omit denied "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " routeAttemptId=" + expectation.routeAttemptId()
                + " subjectId=" + expectation.subjectId().value()
                + ", found " + String.join(", ", found));
    }

    private static void verifyPaperHostRoutePrepareCommand(
            HostRouteCommandExpectation expectation,
            List<PaperHostRoutePrepareCommand> commands) {
        List<String> diagnostics = new ArrayList<>();
        for (PaperHostRoutePrepareCommand command : commands) {
            if (!expectation.routeAttemptId().equals(command.routeAttemptId())) {
                continue;
            }
            List<String> mismatches = paperHostRoutePrepareCommandMismatches(expectation, command);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("host.route.prepare " + command.routeAttemptId()
                    + " mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Paper host route prepare command for "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " routeAttemptId=" + expectation.routeAttemptId()
                + " routeId=" + expectation.routeId().value()
                + " sessionId=" + expectation.sessionId().value()
                + " resolvedManifestId=" + expectation.resolvedManifestId().value()
                + ". Scanned commands=" + paperHostCommandIds(commands)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> paperHostRoutePrepareCommandMismatches(
            HostRouteCommandExpectation expectation,
            PaperHostRoutePrepareCommand command) {
        List<String> mismatches = new ArrayList<>();
        if (!expectation.routeId().equals(command.routeId())) {
            mismatches.add("routeId expected " + expectation.routeId().value()
                    + " got " + command.routeId().value());
        }
        if (!expectation.sessionId().equals(command.sessionId())) {
            mismatches.add("sessionId expected " + expectation.sessionId().value()
                    + " got " + command.sessionId().value());
        }
        if (!expectation.resolvedManifestId().equals(command.resolvedManifestId())) {
            mismatches.add("resolvedManifestId expected " + expectation.resolvedManifestId().value()
                    + " got " + command.resolvedManifestId().value());
        }
        if (!expectation.traceId().equals(command.traceId())) {
            mismatches.add("traceId expected " + expectation.traceId()
                    + " got " + command.traceId());
        }
        return mismatches;
    }

    private static String paperHostCommandIds(List<PaperHostRoutePrepareCommand> commands) {
        return commands.stream()
                .map(command -> command.routeAttemptId() + "/" + command.routeId().value())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static void verifyDeniedPaperHostRoutePrepareCommandsAbsent(
            DeniedHostRouteCommandExpectation expectation,
            List<PaperHostRoutePrepareCommand> commands) {
        List<String> found = commands.stream()
                .filter(command -> expectation.routeAttemptId().equals(command.routeAttemptId()))
                .map(command -> command.routeAttemptId()
                        + "/routeId=" + command.routeId().value()
                        + "/sessionId=" + command.sessionId().value())
                .sorted()
                .toList();
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Expected Paper host route prepare command log to omit denied "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " routeAttemptId=" + expectation.routeAttemptId()
                + " subjectId=" + expectation.subjectId().value()
                + ", found " + String.join(", ", found));
    }

    private static Optional<PresenceAuthorityStateResult> verifyPresenceAuthorityState(
            VerificationConfig config,
            List<PresenceAuthorityStateExpectation> expectations,
            List<DeniedPresenceAuthorityStateExpectation> deniedExpectations) throws IOException {
        if (!config.verifyPresenceAuthorityState()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.presenceAuthorityStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> output = consumePresenceAuthorityState(config);
                if (output.isPresent()) {
                    return Optional.of(verifyPresenceAuthorityStateOutput(
                            output.orElseThrow(),
                            expectations,
                            deniedExpectations));
                }
                failures.add("Presence authority state topic returned no records");
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for Presence authority LIVE state within "
                + config.presenceAuthorityStateTimeout()
                + " on " + config.presenceAuthorityStateTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static PresenceAuthorityStateResult verifyPresenceAuthorityStateOutput(
            String output,
            List<PresenceAuthorityStateExpectation> expectations,
            List<DeniedPresenceAuthorityStateExpectation> deniedExpectations) {
        List<PresenceAuthorityStateRecord> records = presenceAuthorityRecords(output);
        for (PresenceAuthorityStateExpectation expectation : expectations) {
            verifyPresenceAuthorityStateRecord(expectation, records);
        }
        for (DeniedPresenceAuthorityStateExpectation expectation : deniedExpectations) {
            verifyDeniedPresenceAuthorityStateRecordAbsent(expectation, records);
        }
        return new PresenceAuthorityStateResult(expectations.size(), records.size());
    }

    private static List<PresenceAuthorityStateExpectation> presenceAuthorityStateExpectations(
            List<RouteAttemptExpectation> routeAttemptExpectations,
            Instant minimumLeaseExpiresAt) {
        Map<SubjectId, PresenceAuthorityStateExpectation> expectations = new LinkedHashMap<>();
        for (RouteAttemptExpectation expectation : routeAttemptExpectations) {
            PresenceAuthorityStateExpectation next =
                    PresenceAuthorityStateExpectation.from(expectation, minimumLeaseExpiresAt);
            PresenceAuthorityStateExpectation previous = expectations.putIfAbsent(next.subjectId(), next);
            if (previous != null) {
                List<String> mismatches = previous.compatibilityMismatches(next);
                if (!mismatches.isEmpty()) {
                    throw new IllegalStateException("Accepted lobby proofs disagreed for Presence Subject "
                            + next.subjectId().value() + ": " + String.join(", ", mismatches));
                }
            }
        }
        return List.copyOf(expectations.values());
    }

    private static List<PresenceAuthorityStateRecord> presenceAuthorityRecords(String output) {
        return presenceAuthorityPayloads(output).stream()
                .map(PresenceAuthorityWireCodec::decodeState)
                .flatMap(state -> state.current().stream())
                .map(PresenceAuthorityStateRecord::from)
                .toList();
    }

    private static List<String> presenceAuthorityPayloads(String output) {
        List<String> payloads = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            if (line.startsWith("current=")) {
                if (!current.isEmpty()) {
                    payloads.add(current.toString());
                }
                current = new StringBuilder(line).append('\n');
            } else if (!current.isEmpty() && line.contains("=")) {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            payloads.add(current.toString());
        }
        return payloads;
    }

    private static void verifyPresenceAuthorityStateRecord(
            PresenceAuthorityStateExpectation expectation,
            List<PresenceAuthorityStateRecord> records) {
        List<String> diagnostics = new ArrayList<>();
        for (PresenceAuthorityStateRecord record : records) {
            if (!expectation.subjectId().equals(record.subjectId())) {
                continue;
            }
            List<String> mismatches = presenceAuthorityStateMismatches(expectation, record);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("Presence " + record.presenceId().value() + " mismatched: "
                    + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Presence authority LIVE state for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") with presenceId "
                + expectation.presenceId().value() + ", Session "
                + expectation.sessionId().value() + ", and Route "
                + expectation.routeId().value() + ". Diagnostics: "
                + String.join(" | ", diagnostics));
    }

    private static List<String> presenceAuthorityStateMismatches(
            PresenceAuthorityStateExpectation expectation,
            PresenceAuthorityStateRecord record) {
        List<String> mismatches = new ArrayList<>();
        if (!expectation.presenceId().equals(record.presenceId())) {
            mismatches.add("presenceId expected " + expectation.presenceId().value()
                    + " got " + record.presenceId().value());
        }
        if (record.status() != PresenceLifecycleStatus.LIVE) {
            mismatches.add("status expected LIVE got " + record.status());
        }
        if (!expectation.sessionId().equals(record.sessionId().orElse(null))) {
            mismatches.add("sessionId expected " + expectation.sessionId().value()
                    + " got " + record.sessionId().map(SessionId::value).orElse("<empty>"));
        }
        if (!expectation.routeId().equals(record.routeId().orElse(null))) {
            mismatches.add("routeId expected " + expectation.routeId().value()
                    + " got " + record.routeId().map(RouteId::value).orElse("<empty>"));
        }
        if (record.expiresAt().isBefore(expectation.minimumLeaseExpiresAt())) {
            mismatches.add("expiresAt expected at least " + expectation.minimumLeaseExpiresAt()
                    + " got " + record.expiresAt());
        }
        return mismatches;
    }

    private static void verifyDeniedPresenceAuthorityStateRecordAbsent(
            DeniedPresenceAuthorityStateExpectation expectation,
            List<PresenceAuthorityStateRecord> records) {
        for (PresenceAuthorityStateRecord record : records) {
            if (expectation.subjectId().equals(record.subjectId())
                    && record.status() == PresenceLifecycleStatus.LIVE
                    && !record.observedAt().isBefore(expectation.minimumObservedAt())) {
                throw new IllegalStateException("Expected Presence authority state to omit denied "
                        + expectation.label() + " (" + expectation.username() + ", Subject "
                        + expectation.subjectId().value() + ") after " + expectation.minimumObservedAt()
                        + ", but found LIVE Presence " + record.presenceId().value()
                        + " observedAt=" + record.observedAt()
                        + " sessionId=" + record.sessionId().map(SessionId::value).orElse("<empty>")
                        + " routeId=" + record.routeId().map(RouteId::value).orElse("<empty>"));
            }
        }
    }

    private static Optional<StandardCapabilityStateResult> verifyStandardCapabilityState(
            VerificationConfig config,
            List<StandardCapabilityStateExpectation> expectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation) throws IOException {
        if (!config.verifyStandardCapabilityState()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.standardCapabilityStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> profileOutput = consumePlayerProfileState(config);
                Optional<String> rankOutput = consumeRankState(config);
                Optional<String> punishmentOutput = punishmentExpectation.isPresent()
                        ? consumePunishmentState(config)
                        : Optional.empty();
                if (profileOutput.isPresent()
                        && rankOutput.isPresent()
                        && (punishmentExpectation.isEmpty() || punishmentOutput.isPresent())) {
                    return Optional.of(verifyStandardCapabilityStateOutput(
                            profileOutput.orElseThrow(),
                            rankOutput.orElseThrow(),
                            punishmentOutput.orElse(""),
                            expectations,
                            punishmentExpectation));
                }
                if (profileOutput.isEmpty()) {
                    failures.add("standard player-profile state topic returned no records");
                }
                if (rankOutput.isEmpty()) {
                    failures.add("standard rank state topic returned no records");
                }
                if (punishmentExpectation.isPresent() && punishmentOutput.isEmpty()) {
                    failures.add("standard punishment state topic returned no records");
                }
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for standard capability state within "
                + config.standardCapabilityStateTimeout()
                + ". Last failures: " + String.join(" | ", failures));
    }

    private static Optional<StandardCapabilityCommandLogResult> verifyStandardCapabilityCommandLog(
            VerificationConfig config,
            List<StandardCapabilityStateExpectation> expectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation) throws IOException {
        if (!config.verifyStandardCapabilityCommandLog()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.standardCapabilityStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> profileOutput = consumePlayerProfileCommandLog(config);
                Optional<String> rankOutput = consumeRankCommandLog(config);
                Optional<String> punishmentOutput = punishmentExpectation.isPresent()
                        ? consumePunishmentCommandLog(config)
                        : Optional.empty();
                if (profileOutput.isPresent()
                        && rankOutput.isPresent()
                        && (punishmentExpectation.isEmpty() || punishmentOutput.isPresent())) {
                    return Optional.of(verifyStandardCapabilityCommandLogOutput(
                            profileOutput.orElseThrow(),
                            rankOutput.orElseThrow(),
                            punishmentOutput.orElse(""),
                            expectations,
                            punishmentExpectation));
                }
                if (profileOutput.isEmpty()) {
                    failures.add("standard player-profile command topic returned no records");
                }
                if (rankOutput.isEmpty()) {
                    failures.add("standard rank command topic returned no records");
                }
                if (punishmentExpectation.isPresent() && punishmentOutput.isEmpty()) {
                    failures.add("standard punishment command topic returned no records");
                }
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for standard capability command logs within "
                + config.standardCapabilityStateTimeout()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static StandardCapabilityCommandLogResult verifyStandardCapabilityCommandLogOutput(
            String playerProfileOutput,
            String rankOutput,
            String punishmentOutput,
            List<StandardCapabilityStateExpectation> expectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation) {
        List<AuthorityCommand<UpsertPlayerProfile>> profileCommands = playerProfileCommands(playerProfileOutput);
        List<AuthorityCommand<GrantRank>> rankCommands = rankCommands(rankOutput);
        List<AuthorityCommand<IssuePunishment>> punishmentCommands = punishmentOutput.isBlank()
                ? List.of()
                : punishmentCommands(punishmentOutput);
        for (StandardCapabilityStateExpectation expectation : expectations) {
            verifyPlayerProfileCommand(expectation, profileCommands);
            verifyRankCommand(expectation, rankCommands);
        }
        punishmentExpectation.ifPresent(expectation -> verifyPunishmentCommand(expectation, punishmentCommands));
        return new StandardCapabilityCommandLogResult(
                expectations.size(),
                profileCommands.size(),
                rankCommands.size(),
                punishmentCommands.size());
    }

    private static List<AuthorityCommand<UpsertPlayerProfile>> playerProfileCommands(String output) {
        return authorityCommandPayloads(output).stream()
                .map(payload -> StandardCapabilityAuthorityWireCodec.decodePlayerProfileCommand(
                        new ConsumerRecord<>("", 0, 0L, null, payload)))
                .toList();
    }

    private static List<AuthorityCommand<GrantRank>> rankCommands(String output) {
        return authorityCommandPayloads(output).stream()
                .map(payload -> StandardCapabilityAuthorityWireCodec.decodeRankCommand(
                        new ConsumerRecord<>("", 0, 0L, null, payload)))
                .toList();
    }

    private static List<AuthorityCommand<IssuePunishment>> punishmentCommands(String output) {
        return authorityCommandPayloads(output).stream()
                .map(payload -> StandardCapabilityAuthorityWireCodec.decodePunishmentCommand(
                        new ConsumerRecord<>("", 0, 0L, null, payload)))
                .toList();
    }

    private static void verifyPlayerProfileCommand(
            StandardCapabilityStateExpectation expectation,
            List<AuthorityCommand<UpsertPlayerProfile>> commands) {
        List<String> diagnostics = new ArrayList<>();
        for (AuthorityCommand<UpsertPlayerProfile> command : commands) {
            UpsertPlayerProfile payload = command.envelope().payload();
            if (!expectation.subjectId().equals(payload.subjectId())) {
                continue;
            }
            List<String> mismatches = standardCapabilityCommandMismatches(
                    command,
                    StandardCapabilityAuthorityWireCodec.UPSERT_PROFILE_COMMAND,
                    PlayerProfileContracts.CONTRACT.value(),
                    expectation.subjectId());
            if (!expectation.displayName().equals(payload.displayName())) {
                mismatches.add("displayName expected " + expectation.displayName()
                        + " got " + payload.displayName());
            }
            if (payload.expectedRevision() != 0L) {
                mismatches.add("payloadExpectedRevision expected 0 got " + payload.expectedRevision());
            }
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("profile command mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected standard player-profile command for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") displayName=" + expectation.displayName()
                + ". Scanned subjects=" + profileCommandSubjects(commands)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyRankCommand(
            StandardCapabilityStateExpectation expectation,
            List<AuthorityCommand<GrantRank>> commands) {
        List<String> diagnostics = new ArrayList<>();
        for (AuthorityCommand<GrantRank> command : commands) {
            GrantRank payload = command.envelope().payload();
            if (!expectation.subjectId().equals(payload.subjectId())) {
                continue;
            }
            List<String> mismatches = standardCapabilityCommandMismatches(
                    command,
                    StandardCapabilityAuthorityWireCodec.GRANT_RANK_COMMAND,
                    RankContracts.CONTRACT.value(),
                    expectation.subjectId());
            if (!expectation.rankKey().equals(payload.rankKey())) {
                mismatches.add("rankKey expected " + expectation.rankKey()
                        + " got " + payload.rankKey());
            }
            if (payload.expectedRevision() != 0L) {
                mismatches.add("payloadExpectedRevision expected 0 got " + payload.expectedRevision());
            }
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("rank command mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected standard rank command for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") rankKey=" + expectation.rankKey()
                + ". Scanned subjects=" + rankCommandSubjects(commands)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyPunishmentCommand(
            PunishmentCapabilityStateExpectation expectation,
            List<AuthorityCommand<IssuePunishment>> commands) {
        List<String> diagnostics = new ArrayList<>();
        for (AuthorityCommand<IssuePunishment> command : commands) {
            IssuePunishment payload = command.envelope().payload();
            if (!expectation.subjectId().equals(payload.subjectId())) {
                continue;
            }
            List<String> mismatches = standardCapabilityCommandMismatches(
                    command,
                    StandardCapabilityAuthorityWireCodec.ISSUE_PUNISHMENT_COMMAND,
                    PunishmentContracts.CONTRACT.value(),
                    expectation.subjectId());
            if (!payload.expiresAt().isAfter(expectation.activeAt())) {
                mismatches.add("expiresAt expected after " + expectation.activeAt()
                        + " got " + payload.expiresAt());
            }
            expectation.reasonContains().ifPresent(expected -> {
                if (!payload.reason().contains(expected)) {
                    mismatches.add("reason expected to contain " + expected
                            + " got " + payload.reason());
                }
            });
            if (payload.expectedRevision() != 0L) {
                mismatches.add("payloadExpectedRevision expected 0 got " + payload.expectedRevision());
            }
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("punishment command mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected standard punishment command for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ")"
                + ". Scanned subjects=" + punishmentCommandSubjects(commands)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> standardCapabilityCommandMismatches(
            AuthorityCommand<?> command,
            String expectedCommandName,
            String expectedContractName,
            SubjectId subjectId) {
        List<String> mismatches = new ArrayList<>();
        if (!expectedCommandName.equals(command.envelope().commandName().value())) {
            mismatches.add("commandName expected " + expectedCommandName
                    + " got " + command.envelope().commandName().value());
        }
        if (!expectedContractName.equals(command.envelope().contractName().value())) {
            mismatches.add("contractName expected " + expectedContractName
                    + " got " + command.envelope().contractName().value());
        }
        if (!LobbyCapabilitySeedProvisioner.DEFAULT_PRINCIPAL_ID.equals(command.envelope().principalId().value())) {
            mismatches.add("principalId expected " + LobbyCapabilitySeedProvisioner.DEFAULT_PRINCIPAL_ID
                    + " got " + command.envelope().principalId().value());
        }
        if (!LobbyCapabilitySeedProvisioner.DEFAULT_PRINCIPAL_ID.equals(command.authenticatedPrincipal().value())) {
            mismatches.add("authenticatedPrincipal expected "
                    + LobbyCapabilitySeedProvisioner.DEFAULT_PRINCIPAL_ID
                    + " got " + command.authenticatedPrincipal().value());
        }
        if (command.fencingEpoch() != 1L) {
            mismatches.add("fencingEpoch expected 1 got " + command.fencingEpoch());
        }
        if (!command.expectedRevision().map(revision -> revision.value() == 0L).orElse(false)) {
            mismatches.add("expectedRevision expected 0 got "
                    + command.expectedRevision().map(revision -> Long.toString(revision.value())).orElse("<empty>"));
        }
        String expectedTracePrefix = "trace-lobby-capability-seed-" + shortSubject(subjectId);
        if (!expectedTracePrefix.equals(command.envelope().traceEnvelope().traceId())) {
            mismatches.add("traceId expected " + expectedTracePrefix
                    + " got " + command.envelope().traceEnvelope().traceId());
        }
        if (!"capability-seed-provisioner".equals(command.envelope().traceEnvelope().originService())) {
            mismatches.add("originService expected capability-seed-provisioner got "
                    + command.envelope().traceEnvelope().originService());
        }
        return mismatches;
    }

    private static String profileCommandSubjects(List<AuthorityCommand<UpsertPlayerProfile>> commands) {
        return commands.stream()
                .map(command -> command.envelope().payload().subjectId().value().toString())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static String rankCommandSubjects(List<AuthorityCommand<GrantRank>> commands) {
        return commands.stream()
                .map(command -> command.envelope().payload().subjectId().value().toString())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static String punishmentCommandSubjects(List<AuthorityCommand<IssuePunishment>> commands) {
        return commands.stream()
                .map(command -> command.envelope().payload().subjectId().value().toString())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static Optional<RewardCommandLogResult> verifyRewardCommandLog(
            VerificationConfig config,
            List<RewardCommandExpectation> expectations,
            List<DeniedRewardCommandExpectation> deniedExpectations) throws IOException {
        if (!config.verifyRewardCommandLog()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.standardCapabilityStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> economyOutput = consumeEconomyCommandLog(config);
                Optional<String> statsOutput = consumeStatsCommandLog(config);
                if (economyOutput.isPresent() && statsOutput.isPresent()) {
                    return Optional.of(verifyRewardCommandLogOutput(
                            economyOutput.orElseThrow(),
                            statsOutput.orElseThrow(),
                            expectations,
                            deniedExpectations));
                }
                if (economyOutput.isEmpty()) {
                    failures.add("standard economy command topic returned no records");
                }
                if (statsOutput.isEmpty()) {
                    failures.add("standard stats command topic returned no records");
                }
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for Paper reward command logs within "
                + config.standardCapabilityStateTimeout()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static RewardCommandLogResult verifyRewardCommandLogOutput(
            String economyOutput,
            String statsOutput,
            List<RewardCommandExpectation> expectations,
            List<DeniedRewardCommandExpectation> deniedExpectations) {
        List<AuthorityCommand<PostLedgerEntry>> economyCommands = economyCommands(economyOutput);
        List<AuthorityCommand<RecordStatDelta>> statsCommands = statsCommands(statsOutput);
        for (RewardCommandExpectation expectation : expectations) {
            verifyEconomyRewardCommand(expectation, economyCommands);
            verifyStatsRewardCommand(expectation, statsCommands);
        }
        for (DeniedRewardCommandExpectation expectation : deniedExpectations) {
            verifyDeniedRewardCommandsAbsent(expectation, economyCommands, statsCommands);
        }
        return new RewardCommandLogResult(expectations.size(), economyCommands.size(), statsCommands.size());
    }

    private static List<RewardCommandExpectation> rewardCommandExpectations(
            List<RouteAttemptExpectation> routeAttemptExpectations,
            VerificationConfig config) {
        return routeAttemptExpectations.stream()
                .map(expectation -> RewardCommandExpectation.from(expectation, config))
                .toList();
    }

    private static List<DeniedRewardCommandExpectation> deniedRewardCommandExpectations(
            List<DeniedRouteAttemptExpectation> deniedRouteAttemptExpectations) {
        return deniedRouteAttemptExpectations.stream()
                .map(DeniedRewardCommandExpectation::from)
                .toList();
    }

    private static List<AuthorityCommand<PostLedgerEntry>> economyCommands(String output) {
        return authorityCommandPayloads(output).stream()
                .map(payload -> StandardCapabilityAuthorityWireCodec.decodeEconomyCommand(
                        new ConsumerRecord<>("", 0, 0L, null, payload)))
                .toList();
    }

    private static List<AuthorityCommand<RecordStatDelta>> statsCommands(String output) {
        return authorityCommandPayloads(output).stream()
                .map(payload -> StandardCapabilityAuthorityWireCodec.decodeStatsCommand(
                        new ConsumerRecord<>("", 0, 0L, null, payload)))
                .toList();
    }

    private static void verifyEconomyRewardCommand(
            RewardCommandExpectation expectation,
            List<AuthorityCommand<PostLedgerEntry>> commands) {
        List<String> diagnostics = new ArrayList<>();
        int matches = 0;
        for (AuthorityCommand<PostLedgerEntry> command : commands) {
            PostLedgerEntry payload = command.envelope().payload();
            if (!expectation.subjectId().equals(payload.subjectId())) {
                continue;
            }
            List<String> mismatches = rewardCommandEnvelopeMismatches(
                    command,
                    StandardCapabilityAuthorityWireCodec.POST_LEDGER_ENTRY_COMMAND,
                    EconomyContracts.CONTRACT.value(),
                    EconomyAuthority.aggregateId(EconomyAuthority.accountId(
                            expectation.subjectId(),
                            expectation.currencyKey())).value(),
                    "economy",
                    expectation,
                    payload.currencyKey(),
                    payload.deltaMinorUnits(),
                    payload.occurredAt());
            if (!expectation.currencyKey().equals(payload.currencyKey())) {
                mismatches.add("currencyKey expected " + expectation.currencyKey()
                        + " got " + payload.currencyKey());
            }
            if (payload.deltaMinorUnits() != expectation.rewardAmountMinorUnits()) {
                mismatches.add("deltaMinorUnits expected " + expectation.rewardAmountMinorUnits()
                        + " got " + payload.deltaMinorUnits());
            }
            String expectedReason = "session-reward:" + expectation.sessionId().value();
            if (!expectedReason.equals(payload.reason())) {
                mismatches.add("reason expected " + expectedReason + " got " + payload.reason());
            }
            if (payload.expectedRevision() != 0L) {
                mismatches.add("payloadExpectedRevision expected 0 got " + payload.expectedRevision());
            }
            if (payload.occurredAt().isBefore(expectation.minimumOccurredAt())) {
                mismatches.add("occurredAt expected at or after " + expectation.minimumOccurredAt()
                        + " got " + payload.occurredAt());
            }
            if (mismatches.isEmpty()) {
                matches++;
                continue;
            }
            diagnostics.add("economy command mismatched " + String.join(", ", mismatches));
        }
        if (matches == expectation.expectedDeliveryCopies()) {
            return;
        }
        throw new IllegalStateException("Expected Paper economy reward command for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") sessionId=" + expectation.sessionId().value()
                + " currencyKey=" + expectation.currencyKey()
                + " deltaMinorUnits=" + expectation.rewardAmountMinorUnits()
                + " delivery copies=" + expectation.expectedDeliveryCopies()
                + " matched=" + matches
                + ". Scanned subjects=" + economyCommandSubjects(commands)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyStatsRewardCommand(
            RewardCommandExpectation expectation,
            List<AuthorityCommand<RecordStatDelta>> commands) {
        List<String> diagnostics = new ArrayList<>();
        int matches = 0;
        for (AuthorityCommand<RecordStatDelta> command : commands) {
            RecordStatDelta payload = command.envelope().payload();
            if (!expectation.subjectId().equals(payload.subjectId())) {
                continue;
            }
            List<String> mismatches = rewardCommandEnvelopeMismatches(
                    command,
                    StandardCapabilityAuthorityWireCodec.RECORD_STAT_DELTA_COMMAND,
                    StatsContracts.CONTRACT.value(),
                    StatsAuthority.aggregateId(StatsAuthority.counterId(
                            expectation.subjectId(),
                            expectation.statKey())).value(),
                    "stats",
                    expectation,
                    payload.statKey(),
                    payload.delta(),
                    payload.occurredAt());
            if (!expectation.experienceId().equals(payload.experienceId())) {
                mismatches.add("experienceId expected " + expectation.experienceId().value()
                        + " got " + payload.experienceId().value());
            }
            if (!expectation.statKey().equals(payload.statKey())) {
                mismatches.add("statKey expected " + expectation.statKey()
                        + " got " + payload.statKey());
            }
            if (payload.delta() != PAPER_REWARD_STATS_DELTA) {
                mismatches.add("delta expected " + PAPER_REWARD_STATS_DELTA + " got " + payload.delta());
            }
            if (payload.expectedRevision() != 0L) {
                mismatches.add("payloadExpectedRevision expected 0 got " + payload.expectedRevision());
            }
            if (payload.occurredAt().isBefore(expectation.minimumOccurredAt())) {
                mismatches.add("occurredAt expected at or after " + expectation.minimumOccurredAt()
                        + " got " + payload.occurredAt());
            }
            if (mismatches.isEmpty()) {
                matches++;
                continue;
            }
            diagnostics.add("stats command mismatched " + String.join(", ", mismatches));
        }
        if (matches == expectation.expectedDeliveryCopies()) {
            return;
        }
        throw new IllegalStateException("Expected Paper stats reward command for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") sessionId=" + expectation.sessionId().value()
                + " experienceId=" + expectation.experienceId().value()
                + " statKey=" + expectation.statKey()
                + " delivery copies=" + expectation.expectedDeliveryCopies()
                + " matched=" + matches
                + ". Scanned subjects=" + statsCommandSubjects(commands)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> rewardCommandEnvelopeMismatches(
            AuthorityCommand<?> command,
            String expectedCommandName,
            String expectedContractName,
            String expectedAggregateId,
            String family,
            RewardCommandExpectation expectation,
            String payloadKey,
            long payloadDelta,
            Instant occurredAt) {
        List<String> mismatches = new ArrayList<>();
        if (!expectedCommandName.equals(command.envelope().commandName().value())) {
            mismatches.add("commandName expected " + expectedCommandName
                    + " got " + command.envelope().commandName().value());
        }
        if (!expectedContractName.equals(command.envelope().contractName().value())) {
            mismatches.add("contractName expected " + expectedContractName
                    + " got " + command.envelope().contractName().value());
        }
        if (!expectedAggregateId.equals(command.envelope().aggregateId().value())) {
            mismatches.add("aggregateId expected " + expectedAggregateId
                    + " got " + command.envelope().aggregateId().value());
        }
        String suffix = rewardCommandSuffix(expectation);
        String expectedCommandId = "command-paper-reward-" + family + "-" + suffix;
        if (!expectedCommandId.equals(command.envelope().commandId().value())) {
            mismatches.add("commandId expected " + expectedCommandId
                    + " got " + command.envelope().commandId().value());
        }
        String expectedIdempotencyKey = "idem-paper-reward-" + family + "-" + suffix;
        if (!expectedIdempotencyKey.equals(command.envelope().idempotencyKey().value())) {
            mismatches.add("idempotencyKey expected " + expectedIdempotencyKey
                    + " got " + command.envelope().idempotencyKey().value());
        }
        if (!PAPER_AGENT_PRINCIPAL_ID.equals(command.envelope().principalId().value())) {
            mismatches.add("principalId expected " + PAPER_AGENT_PRINCIPAL_ID
                    + " got " + command.envelope().principalId().value());
        }
        if (!PAPER_AGENT_PRINCIPAL_ID.equals(command.authenticatedPrincipal().value())) {
            mismatches.add("authenticatedPrincipal expected " + PAPER_AGENT_PRINCIPAL_ID
                    + " got " + command.authenticatedPrincipal().value());
        }
        if (command.fencingEpoch() != 1L) {
            mismatches.add("fencingEpoch expected 1 got " + command.fencingEpoch());
        }
        if (command.expectedRevision().isPresent()) {
            mismatches.add("expectedRevision expected <empty> got "
                    + command.expectedRevision().map(revision -> Long.toString(revision.value())).orElse("<empty>"));
        }
        String expectedTraceId = expectedPaperAttachTraceId(expectation.subjectId());
        if (!expectedTraceId.equals(command.envelope().traceEnvelope().traceId())) {
            mismatches.add("traceId expected " + expectedTraceId
                    + " got " + command.envelope().traceEnvelope().traceId());
        }
        if (!PAPER_AGENT_ORIGIN_SERVICE.equals(command.envelope().traceEnvelope().originService())) {
            mismatches.add("originService expected " + PAPER_AGENT_ORIGIN_SERVICE + " got "
                    + command.envelope().traceEnvelope().originService());
        }
        if (!expectation.instanceId().equals(command.envelope().traceEnvelope().originInstanceId())) {
            mismatches.add("originInstanceId expected " + expectation.instanceId().value()
                    + " got " + command.envelope().traceEnvelope().originInstanceId().value());
        }
        if (!occurredAt.equals(command.envelope().traceEnvelope().createdAt())) {
            mismatches.add("traceCreatedAt expected " + occurredAt
                    + " got " + command.envelope().traceEnvelope().createdAt());
        }
        Instant expectedDeadlineAt = occurredAt.plusSeconds(30);
        if (!command.envelope().deadlineAt().map(expectedDeadlineAt::equals).orElse(false)) {
            mismatches.add("deadlineAt expected " + expectedDeadlineAt
                    + " got " + command.envelope().deadlineAt().map(Instant::toString).orElse("<empty>"));
        }
        if (!occurredAt.equals(command.receivedAt())) {
            mismatches.add("receivedAt expected " + occurredAt + " got " + command.receivedAt());
        }
        String expectedFingerprint = rewardPayloadFingerprint(
                family,
                expectation.sessionId(),
                expectation.routeId(),
                expectation.subjectId(),
                payloadKey,
                payloadDelta,
                occurredAt);
        if (!expectedFingerprint.equals(command.payloadFingerprint())) {
            mismatches.add("payloadFingerprint expected " + expectedFingerprint
                    + " got " + command.payloadFingerprint());
        }
        return mismatches;
    }

    private static void verifyDeniedRewardCommandsAbsent(
            DeniedRewardCommandExpectation expectation,
            List<AuthorityCommand<PostLedgerEntry>> economyCommands,
            List<AuthorityCommand<RecordStatDelta>> statsCommands) {
        List<String> found = new ArrayList<>();
        economyCommands.stream()
                .filter(command -> expectation.subjectId().equals(command.envelope().payload().subjectId()))
                .filter(command -> !command.envelope().payload().occurredAt().isBefore(expectation.minimumOccurredAt()))
                .map(command -> "economy/commandId=" + command.envelope().commandId().value()
                        + "/occurredAt=" + command.envelope().payload().occurredAt())
                .forEach(found::add);
        statsCommands.stream()
                .filter(command -> expectation.subjectId().equals(command.envelope().payload().subjectId()))
                .filter(command -> !command.envelope().payload().occurredAt().isBefore(expectation.minimumOccurredAt()))
                .map(command -> "stats/commandId=" + command.envelope().commandId().value()
                        + "/occurredAt=" + command.envelope().payload().occurredAt())
                .forEach(found::add);
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Expected Paper reward commands to omit denied "
                + expectation.label()
                + " (" + expectation.username() + ") subjectId=" + expectation.subjectId().value()
                + " occurred at or after " + expectation.minimumOccurredAt()
                + ", found " + String.join(", ", found));
    }

    private static String rewardCommandSuffix(RewardCommandExpectation expectation) {
        return compactValue(expectation.sessionId().value()) + "-" + compactSubject(expectation.subjectId());
    }

    private static String rewardPayloadFingerprint(
            String family,
            SessionId sessionId,
            RouteId routeId,
            SubjectId subjectId,
            String key,
            long delta,
            Instant occurredAt) {
        String value = new StringBuilder()
                .append("family=").append(family).append('\n')
                .append("sessionId=").append(sessionId.value()).append('\n')
                .append("routeId=").append(routeId.value()).append('\n')
                .append("subjectId=").append(subjectId.value()).append('\n')
                .append("key=").append(key).append('\n')
                .append("delta=").append(delta).append('\n')
                .append("occurredAt=").append(occurredAt).append('\n')
                .toString();
        return sha256(value);
    }

    private static String economyCommandSubjects(List<AuthorityCommand<PostLedgerEntry>> commands) {
        return commands.stream()
                .map(command -> command.envelope().payload().subjectId().value()
                        + "/reason=" + command.envelope().payload().reason())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static String statsCommandSubjects(List<AuthorityCommand<RecordStatDelta>> commands) {
        return commands.stream()
                .map(command -> command.envelope().payload().subjectId().value()
                        + "/statKey=" + command.envelope().payload().statKey())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static Optional<RewardStateResult> verifyRewardState(
            VerificationConfig config,
            List<RewardCommandExpectation> expectations,
            List<DeniedRewardCommandExpectation> deniedExpectations) throws IOException {
        if (!config.verifyRewardState()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.standardCapabilityStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> economyOutput = consumeEconomyState(config);
                Optional<String> statsOutput = consumeStatsState(config);
                if (economyOutput.isPresent() && statsOutput.isPresent()) {
                    return Optional.of(verifyRewardStateOutput(
                            economyOutput.orElseThrow(),
                            statsOutput.orElseThrow(),
                            expectations,
                            deniedExpectations));
                }
                if (economyOutput.isEmpty()) {
                    failures.add("standard economy state topic returned no records");
                }
                if (statsOutput.isEmpty()) {
                    failures.add("standard stats state topic returned no records");
                }
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for Paper reward authority state within "
                + config.standardCapabilityStateTimeout()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static RewardStateResult verifyRewardStateOutput(
            String economyOutput,
            String statsOutput,
            List<RewardCommandExpectation> expectations,
            List<DeniedRewardCommandExpectation> deniedExpectations) {
        List<EconomyBalanceSnapshot> economyRecords = economyStateRecords(economyOutput);
        List<StatsCounterSnapshot> statsRecords = statsStateRecords(statsOutput);
        for (RewardCommandExpectation expectation : expectations) {
            verifyEconomyRewardState(expectation, economyRecords);
            verifyStatsRewardState(expectation, statsRecords);
        }
        for (DeniedRewardCommandExpectation expectation : deniedExpectations) {
            verifyDeniedRewardStateAbsent(expectation, economyRecords, statsRecords);
        }
        return new RewardStateResult(expectations.size(), economyRecords.size(), statsRecords.size());
    }

    private static List<EconomyBalanceSnapshot> economyStateRecords(String output) {
        return standardCapabilityStatePayloads(output).stream()
                .map(StandardCapabilityAuthorityWireCodec::decodeEconomyState)
                .flatMap(state -> state.current().stream())
                .toList();
    }

    private static List<StatsCounterSnapshot> statsStateRecords(String output) {
        return standardCapabilityStatePayloads(output).stream()
                .map(StandardCapabilityAuthorityWireCodec::decodeStatsState)
                .flatMap(state -> state.current().stream())
                .toList();
    }

    private static void verifyEconomyRewardState(
            RewardCommandExpectation expectation,
            List<EconomyBalanceSnapshot> records) {
        List<String> diagnostics = new ArrayList<>();
        for (EconomyBalanceSnapshot record : records) {
            if (!expectation.subjectId().equals(record.accountId().subjectId())
                    || !expectation.currencyKey().equals(record.accountId().currencyKey())) {
                continue;
            }
            List<String> mismatches = new ArrayList<>();
            if (record.balanceMinorUnits() != expectation.rewardAmountMinorUnits()) {
                mismatches.add("balanceMinorUnits expected " + expectation.rewardAmountMinorUnits()
                        + " got " + record.balanceMinorUnits());
            }
            String expectedLastEntryId = "idem-paper-reward-economy-" + rewardCommandSuffix(expectation);
            if (!expectedLastEntryId.equals(record.lastEntryId())) {
                mismatches.add("lastEntryId expected " + expectedLastEntryId
                        + " got " + record.lastEntryId());
            }
            if (!PAPER_AGENT_PRINCIPAL_ID.equals(record.updatedBy().value())) {
                mismatches.add("updatedBy expected " + PAPER_AGENT_PRINCIPAL_ID
                        + " got " + record.updatedBy().value());
            }
            if (record.updatedAt().isBefore(expectation.minimumOccurredAt())) {
                mismatches.add("updatedAt expected at or after " + expectation.minimumOccurredAt()
                        + " got " + record.updatedAt());
            }
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("economy state mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Paper reward economy state for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") currencyKey=" + expectation.currencyKey()
                + " balanceMinorUnits=" + expectation.rewardAmountMinorUnits()
                + ". Scanned accounts=" + economyStateSubjects(records)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyStatsRewardState(
            RewardCommandExpectation expectation,
            List<StatsCounterSnapshot> records) {
        List<String> diagnostics = new ArrayList<>();
        for (StatsCounterSnapshot record : records) {
            if (!expectation.subjectId().equals(record.counterId().subjectId())
                    || !expectation.statKey().equals(record.counterId().statKey())) {
                continue;
            }
            List<String> mismatches = new ArrayList<>();
            if (record.total() != PAPER_REWARD_STATS_DELTA) {
                mismatches.add("total expected " + PAPER_REWARD_STATS_DELTA + " got " + record.total());
            }
            String expectedLastEntryId = "idem-paper-reward-stats-" + rewardCommandSuffix(expectation);
            if (!expectedLastEntryId.equals(record.lastEntryId())) {
                mismatches.add("lastEntryId expected " + expectedLastEntryId
                        + " got " + record.lastEntryId());
            }
            if (!PAPER_AGENT_PRINCIPAL_ID.equals(record.updatedBy().value())) {
                mismatches.add("updatedBy expected " + PAPER_AGENT_PRINCIPAL_ID
                        + " got " + record.updatedBy().value());
            }
            if (record.updatedAt().isBefore(expectation.minimumOccurredAt())) {
                mismatches.add("updatedAt expected at or after " + expectation.minimumOccurredAt()
                        + " got " + record.updatedAt());
            }
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("stats state mismatched " + String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Paper reward stats state for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") statKey=" + expectation.statKey()
                + " total=" + PAPER_REWARD_STATS_DELTA
                + ". Scanned counters=" + statsStateSubjects(records)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyDeniedRewardStateAbsent(
            DeniedRewardCommandExpectation expectation,
            List<EconomyBalanceSnapshot> economyRecords,
            List<StatsCounterSnapshot> statsRecords) {
        List<String> found = new ArrayList<>();
        economyRecords.stream()
                .filter(record -> expectation.subjectId().equals(record.accountId().subjectId()))
                .filter(record -> !record.updatedAt().isBefore(expectation.minimumOccurredAt()))
                .map(record -> "economy/currencyKey=" + record.accountId().currencyKey()
                        + "/balanceMinorUnits=" + record.balanceMinorUnits()
                        + "/updatedAt=" + record.updatedAt())
                .forEach(found::add);
        statsRecords.stream()
                .filter(record -> expectation.subjectId().equals(record.counterId().subjectId()))
                .filter(record -> !record.updatedAt().isBefore(expectation.minimumOccurredAt()))
                .map(record -> "stats/statKey=" + record.counterId().statKey()
                        + "/total=" + record.total()
                        + "/updatedAt=" + record.updatedAt())
                .forEach(found::add);
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Expected Paper reward authority state to omit denied "
                + expectation.label()
                + " (" + expectation.username() + ") subjectId=" + expectation.subjectId().value()
                + " updated at or after " + expectation.minimumOccurredAt()
                + ", found " + String.join(", ", found));
    }

    private static String economyStateSubjects(List<EconomyBalanceSnapshot> records) {
        return records.stream()
                .map(record -> record.accountId().subjectId().value()
                        + "/currencyKey=" + record.accountId().currencyKey()
                        + "/balanceMinorUnits=" + record.balanceMinorUnits())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static String statsStateSubjects(List<StatsCounterSnapshot> records) {
        return records.stream()
                .map(record -> record.counterId().subjectId().value()
                        + "/statKey=" + record.counterId().statKey()
                        + "/total=" + record.total())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static Optional<CassandraHotProjectionResult> verifyCassandraHotProjections(
            VerificationConfig config,
            List<PresenceAuthorityStateExpectation> presenceExpectations,
            List<DeniedPresenceAuthorityStateExpectation> deniedPresenceExpectations,
            List<RouteAuthorityStateExpectation> routeExpectations,
            List<DeniedRouteAuthorityStateExpectation> deniedRouteExpectations,
            List<SessionAuthorityStateExpectation> sessionExpectations,
            List<StandardCapabilityStateExpectation> standardCapabilityExpectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation,
            List<RewardCommandExpectation> rewardExpectations,
            List<DeniedRewardCommandExpectation> deniedRewardExpectations) throws IOException {
        if (!config.verifyCassandraHotProjections()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.cassandraHotProjectionTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                return Optional.of(verifyCassandraHotProjectionOutput(
                        copyCassandraTable(config, "Presence hot projection", "presence_hot", CASSANDRA_PRESENCE_COLUMNS),
                        copyCassandraTable(config, "Route hot projection", "route_hot", CASSANDRA_ROUTE_COLUMNS),
                        copyCassandraTable(config, "Session hot projection", "session_hot", CASSANDRA_SESSION_COLUMNS),
                        copyCassandraTable(
                                config,
                                "player-profile hot projection",
                                "standard_player_profile_effective_hot",
                                CASSANDRA_PROFILE_COLUMNS),
                        copyCassandraTable(
                                config,
                                "rank hot projection",
                                "standard_rank_effective_hot",
                                CASSANDRA_RANK_COLUMNS),
                        copyCassandraTable(
                                config,
                                "punishment hot projection",
                                "standard_punishment_active_hot",
                                CASSANDRA_PUNISHMENT_COLUMNS),
                        copyCassandraTable(
                                config,
                                "economy hot projection",
                                "standard_economy_balance_hot",
                                CASSANDRA_ECONOMY_COLUMNS),
                        copyCassandraTable(
                                config,
                                "stats hot projection",
                                "standard_stats_counter_hot",
                                CASSANDRA_STATS_COLUMNS),
                        config.expectedExperienceId(),
                        presenceExpectations,
                        deniedPresenceExpectations,
                        routeExpectations,
                        deniedRouteExpectations,
                        sessionExpectations,
                        standardCapabilityExpectations,
                        punishmentExpectation,
                        rewardExpectations,
                        deniedRewardExpectations));
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for Cassandra hot projections within "
                + config.cassandraHotProjectionTimeout()
                + " from " + config.cassandraPodName()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static CassandraHotProjectionResult verifyCassandraHotProjectionOutput(
            String presenceOutput,
            String routeOutput,
            String sessionOutput,
            String profileOutput,
            String rankOutput,
            String punishmentOutput,
            String economyOutput,
            String statsOutput,
            ExperienceId expectedExperienceId,
            List<PresenceAuthorityStateExpectation> presenceExpectations,
            List<DeniedPresenceAuthorityStateExpectation> deniedPresenceExpectations,
            List<RouteAuthorityStateExpectation> routeExpectations,
            List<DeniedRouteAuthorityStateExpectation> deniedRouteExpectations,
            List<SessionAuthorityStateExpectation> sessionExpectations,
            List<StandardCapabilityStateExpectation> standardCapabilityExpectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation,
            List<RewardCommandExpectation> rewardExpectations,
            List<DeniedRewardCommandExpectation> deniedRewardExpectations) {
        List<Map<String, String>> presenceRows = cassandraRows(presenceOutput, CASSANDRA_PRESENCE_COLUMNS);
        List<Map<String, String>> routeRows = cassandraRows(routeOutput, CASSANDRA_ROUTE_COLUMNS);
        List<Map<String, String>> sessionRows = cassandraRows(sessionOutput, CASSANDRA_SESSION_COLUMNS);
        List<Map<String, String>> profileRows = cassandraRows(profileOutput, CASSANDRA_PROFILE_COLUMNS);
        List<Map<String, String>> rankRows = cassandraRows(rankOutput, CASSANDRA_RANK_COLUMNS);
        List<Map<String, String>> punishmentRows = cassandraRows(punishmentOutput, CASSANDRA_PUNISHMENT_COLUMNS);
        List<Map<String, String>> economyRows = cassandraRows(economyOutput, CASSANDRA_ECONOMY_COLUMNS);
        List<Map<String, String>> statsRows = cassandraRows(statsOutput, CASSANDRA_STATS_COLUMNS);

        for (PresenceAuthorityStateExpectation expectation : presenceExpectations) {
            verifyCassandraPresenceRow(expectation, presenceRows);
        }
        for (DeniedPresenceAuthorityStateExpectation expectation : deniedPresenceExpectations) {
            verifyDeniedCassandraPresenceRowAbsent(expectation, presenceRows);
        }
        for (RouteAuthorityStateExpectation expectation : routeExpectations) {
            verifyCassandraRouteRow(expectation, routeRows);
        }
        for (DeniedRouteAuthorityStateExpectation expectation : deniedRouteExpectations) {
            verifyDeniedCassandraRouteRowAbsent(expectation, routeRows);
        }
        for (SessionAuthorityStateExpectation expectation : sessionExpectations) {
            verifyCassandraSessionRow(expectedExperienceId, expectation, sessionRows);
        }
        for (StandardCapabilityStateExpectation expectation : standardCapabilityExpectations) {
            verifyCassandraProfileRow(expectation, profileRows);
            verifyCassandraRankRow(expectation, rankRows);
        }
        punishmentExpectation.ifPresent(expectation -> verifyCassandraPunishmentRow(expectation, punishmentRows));
        for (RewardCommandExpectation expectation : rewardExpectations) {
            verifyCassandraEconomyRow(expectation, economyRows);
            verifyCassandraStatsRow(expectation, statsRows);
        }
        for (DeniedRewardCommandExpectation expectation : deniedRewardExpectations) {
            verifyDeniedCassandraRewardRowsAbsent(expectation, economyRows, statsRows);
        }
        return new CassandraHotProjectionResult(
                presenceExpectations.size(),
                presenceRows.size(),
                routeRows.size(),
                sessionRows.size(),
                profileRows.size(),
                rankRows.size(),
                punishmentRows.size(),
                economyRows.size(),
                statsRows.size());
    }

    private static String copyCassandraTable(
            VerificationConfig config,
            String label,
            String table,
            List<String> columns) throws IOException {
        String query = "COPY fulcrum." + table + " ("
                + String.join(", ", columns)
                + ") TO STDOUT WITH DELIMITER = '|' AND HEADER = false;";
        KubectlResult result = kubectlResult(
                config,
                label,
                "exec",
                config.cassandraPodName(),
                "-c",
                config.cassandraContainerName(),
                "--",
                config.cassandraCqlshPath(),
                "-e",
                query);
        if (result.exitCode() != 0) {
            throw new IOException("Failed to read " + label + " with `"
                    + String.join(" ", result.command()) + "`: " + result.output());
        }
        return result.output();
    }

    private static List<Map<String, String>> cassandraRows(String output, List<String> columns) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()
                    || trimmed.startsWith("Using ")
                    || trimmed.contains(" rows exported")
                    || !trimmed.contains("|")) {
                continue;
            }
            String[] parts = trimmed.split("\\|", -1);
            if (parts.length != columns.size()) {
                throw new IllegalArgumentException("Malformed Cassandra hot projection row: " + line);
            }
            if (isCassandraHeader(parts, columns)) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int index = 0; index < columns.size(); index++) {
                row.put(columns.get(index), parts[index].trim());
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static boolean isCassandraHeader(String[] parts, List<String> columns) {
        for (int index = 0; index < columns.size(); index++) {
            if (!columns.get(index).equals(parts[index].trim())) {
                return false;
            }
        }
        return true;
    }

    private static void verifyCassandraPresenceRow(
            PresenceAuthorityStateExpectation expectation,
            List<Map<String, String>> rows) {
        List<String> diagnostics = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (!expectation.subjectId().value().toString().equals(row.get("subject_id"))) {
                continue;
            }
            List<String> mismatches = new ArrayList<>();
            appendMismatch(mismatches, "presence_id", expectation.presenceId().value(), row.get("presence_id"));
            appendMismatch(mismatches, "lifecycle_status", PresenceLifecycleStatus.LIVE.name(), row.get("lifecycle_status"));
            appendMismatch(mismatches, "session_id", expectation.sessionId().value(), row.get("session_id"));
            appendMismatch(mismatches, "route_id", expectation.routeId().value(), row.get("route_id"));
            appendInstantFloorMismatch(
                    mismatches,
                    "expires_at",
                    expectation.minimumLeaseExpiresAt(),
                    row.get("expires_at"));
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Cassandra presence_hot row for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") presenceId=" + expectation.presenceId().value()
                + ". Scanned subjects=" + cassandraSubjects(rows, "subject_id")
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyDeniedCassandraPresenceRowAbsent(
            DeniedPresenceAuthorityStateExpectation expectation,
            List<Map<String, String>> rows) {
        for (Map<String, String> row : rows) {
            if (expectation.subjectId().value().toString().equals(row.get("subject_id"))
                    && PresenceLifecycleStatus.LIVE.name().equals(row.get("lifecycle_status"))
                    && !instantValue(row.get("observed_at"), "observed_at").isBefore(expectation.minimumObservedAt())) {
                throw new IllegalStateException("Expected Cassandra presence_hot to omit denied "
                        + expectation.label() + " (" + expectation.username() + ", Subject "
                        + expectation.subjectId().value() + "), found presenceId="
                        + row.get("presence_id") + " observed_at=" + row.get("observed_at"));
            }
        }
    }

    private static void verifyCassandraRouteRow(
            RouteAuthorityStateExpectation expectation,
            List<Map<String, String>> rows) {
        List<String> diagnostics = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (!expectation.routeId().value().equals(row.get("route_id"))) {
                continue;
            }
            List<String> mismatches = new ArrayList<>();
            appendMismatch(mismatches, "subject_id", expectation.subjectId().value().toString(), row.get("subject_id"));
            appendMismatch(mismatches, "target_session_id", expectation.sessionId().value(), row.get("target_session_id"));
            appendMismatch(mismatches, "target_instance_id", expectation.targetInstanceId().value(), row.get("target_instance_id"));
            appendMismatch(mismatches, "lifecycle_status", RouteLifecycleStatus.ACKNOWLEDGED.name(), row.get("lifecycle_status"));
            appendInstantFloorMismatch(
                    mismatches,
                    "completed_at",
                    expectation.minimumCompletedAt(),
                    row.get("completed_at"));
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Cassandra route_hot row for "
                + expectation.label() + " (" + expectation.username() + ") routeId="
                + expectation.routeId().value()
                + ". Scanned routeIds=" + cassandraSubjects(rows, "route_id")
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyDeniedCassandraRouteRowAbsent(
            DeniedRouteAuthorityStateExpectation expectation,
            List<Map<String, String>> rows) {
        for (Map<String, String> row : rows) {
            if (expectation.subjectId().value().toString().equals(row.get("subject_id"))
                    && !instantValue(row.get("requested_at"), "requested_at").isBefore(expectation.minimumUpdatedAt())) {
                throw new IllegalStateException("Expected Cassandra route_hot to omit denied "
                        + expectation.label() + " (" + expectation.username() + ", Subject "
                        + expectation.subjectId().value() + "), found routeId="
                        + row.get("route_id") + " status=" + row.get("lifecycle_status"));
            }
        }
    }

    private static void verifyCassandraSessionRow(
            ExperienceId expectedExperienceId,
            SessionAuthorityStateExpectation expectation,
            List<Map<String, String>> rows) {
        List<String> diagnostics = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (!expectation.sessionId().value().equals(row.get("session_id"))) {
                continue;
            }
            List<String> mismatches = new ArrayList<>();
            appendMismatch(mismatches, "experience_id", expectedExperienceId.value(), row.get("experience_id"));
            appendMismatch(mismatches, "slot_id", expectation.slotId().value(), row.get("slot_id"));
            appendMismatch(mismatches, "owner_instance_id", expectation.ownerInstanceId().value(), row.get("owner_instance_id"));
            appendMismatch(mismatches, "resolved_manifest_id", expectation.resolvedManifestId().value(), row.get("resolved_manifest_id"));
            appendMismatch(mismatches, "lifecycle_status", SessionLifecycleStatus.ACTIVE.name(), row.get("lifecycle_status"));
            appendInstantFloorMismatch(
                    mismatches,
                    "lease_expires_at",
                    expectation.minimumLeaseExpiresAt(),
                    row.get("lease_expires_at"));
            if (row.getOrDefault("activated_at", "").isBlank()) {
                mismatches.add("activated_at is missing");
            }
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Cassandra session_hot row for "
                + expectation.label() + " (" + expectation.username() + ") sessionId="
                + expectation.sessionId().value()
                + ". Scanned sessionIds=" + cassandraSubjects(rows, "session_id")
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyCassandraProfileRow(
            StandardCapabilityStateExpectation expectation,
            List<Map<String, String>> rows) {
        verifySubjectRow(
                rows,
                "standard_player_profile_effective_hot",
                expectation.subjectId(),
                expectation.label(),
                expectation.username(),
                row -> {
                    List<String> mismatches = new ArrayList<>();
                    appendMismatch(mismatches, "display_name", expectation.displayName(), row.get("display_name"));
                    appendMismatch(mismatches, "updated_by", LobbyCapabilitySeedProvisioner.DEFAULT_PRINCIPAL_ID, row.get("updated_by"));
                    return mismatches;
                });
    }

    private static void verifyCassandraRankRow(
            StandardCapabilityStateExpectation expectation,
            List<Map<String, String>> rows) {
        verifySubjectRow(
                rows,
                "standard_rank_effective_hot",
                expectation.subjectId(),
                expectation.label(),
                expectation.username(),
                row -> {
                    List<String> mismatches = new ArrayList<>();
                    appendMismatch(mismatches, "primary_rank_key", expectation.rankKey(), row.get("primary_rank_key"));
                    if (!row.getOrDefault("permissions", "").contains("rank:" + expectation.rankKey())) {
                        mismatches.add("permissions expected to contain rank:" + expectation.rankKey()
                                + " got " + row.getOrDefault("permissions", "<missing>"));
                    }
                    appendMismatch(mismatches, "updated_by", LobbyCapabilitySeedProvisioner.DEFAULT_PRINCIPAL_ID, row.get("updated_by"));
                    return mismatches;
                });
    }

    private static void verifyCassandraPunishmentRow(
            PunishmentCapabilityStateExpectation expectation,
            List<Map<String, String>> rows) {
        verifySubjectRow(
                rows,
                "standard_punishment_active_hot",
                expectation.subjectId(),
                expectation.label(),
                expectation.username(),
                row -> {
                    List<String> mismatches = new ArrayList<>();
                    expectation.reasonContains().ifPresent(expected -> {
                        if (!row.getOrDefault("reason", "").contains(expected)) {
                            mismatches.add("reason expected to contain " + expected
                                    + " got " + row.getOrDefault("reason", "<missing>"));
                        }
                    });
                    appendMismatch(mismatches, "issued_by", LobbyCapabilitySeedProvisioner.DEFAULT_PRINCIPAL_ID, row.get("issued_by"));
                    appendInstantFloorMismatch(mismatches, "expires_at", expectation.activeAt(), row.get("expires_at"));
                    return mismatches;
                });
    }

    private static void verifyCassandraEconomyRow(
            RewardCommandExpectation expectation,
            List<Map<String, String>> rows) {
        List<String> diagnostics = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (!expectation.subjectId().value().toString().equals(row.get("subject_id"))
                    || !expectation.currencyKey().equals(row.get("currency_key"))) {
                continue;
            }
            List<String> mismatches = new ArrayList<>();
            appendMismatch(
                    mismatches,
                    "balance_minor_units",
                    Long.toString(expectation.rewardAmountMinorUnits()),
                    row.get("balance_minor_units"));
            appendMismatch(
                    mismatches,
                    "last_entry_id",
                    "idem-paper-reward-economy-" + rewardCommandSuffix(expectation),
                    row.get("last_entry_id"));
            appendMismatch(mismatches, "updated_by", PAPER_AGENT_PRINCIPAL_ID, row.get("updated_by"));
            appendInstantFloorMismatch(mismatches, "updated_at", expectation.minimumOccurredAt(), row.get("updated_at"));
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Cassandra standard_economy_balance_hot row for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") currencyKey=" + expectation.currencyKey()
                + ". Scanned accounts=" + cassandraCompoundSubjects(rows, "subject_id", "currency_key")
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyCassandraStatsRow(
            RewardCommandExpectation expectation,
            List<Map<String, String>> rows) {
        List<String> diagnostics = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (!expectation.subjectId().value().toString().equals(row.get("subject_id"))
                    || !expectation.statKey().equals(row.get("stat_key"))) {
                continue;
            }
            List<String> mismatches = new ArrayList<>();
            appendMismatch(mismatches, "total", Long.toString(PAPER_REWARD_STATS_DELTA), row.get("total"));
            appendMismatch(
                    mismatches,
                    "last_entry_id",
                    "idem-paper-reward-stats-" + rewardCommandSuffix(expectation),
                    row.get("last_entry_id"));
            appendMismatch(mismatches, "updated_by", PAPER_AGENT_PRINCIPAL_ID, row.get("updated_by"));
            appendInstantFloorMismatch(mismatches, "updated_at", expectation.minimumOccurredAt(), row.get("updated_at"));
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Cassandra standard_stats_counter_hot row for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") statKey=" + expectation.statKey()
                + ". Scanned counters=" + cassandraCompoundSubjects(rows, "subject_id", "stat_key")
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyDeniedCassandraRewardRowsAbsent(
            DeniedRewardCommandExpectation expectation,
            List<Map<String, String>> economyRows,
            List<Map<String, String>> statsRows) {
        List<String> found = new ArrayList<>();
        economyRows.stream()
                .filter(row -> expectation.subjectId().value().toString().equals(row.get("subject_id")))
                .filter(row -> !instantValue(row.get("updated_at"), "updated_at").isBefore(expectation.minimumOccurredAt()))
                .map(row -> "economy/currencyKey=" + row.get("currency_key")
                        + "/balance=" + row.get("balance_minor_units"))
                .forEach(found::add);
        statsRows.stream()
                .filter(row -> expectation.subjectId().value().toString().equals(row.get("subject_id")))
                .filter(row -> !instantValue(row.get("updated_at"), "updated_at").isBefore(expectation.minimumOccurredAt()))
                .map(row -> "stats/statKey=" + row.get("stat_key") + "/total=" + row.get("total"))
                .forEach(found::add);
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Expected Cassandra reward hot projections to omit denied "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + "), found " + String.join(", ", found));
    }

    private interface CassandraRowVerifier {
        List<String> mismatches(Map<String, String> row);
    }

    private static void verifySubjectRow(
            List<Map<String, String>> rows,
            String table,
            SubjectId subjectId,
            String label,
            String username,
            CassandraRowVerifier verifier) {
        List<String> diagnostics = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (!subjectId.value().toString().equals(row.get("subject_id"))) {
                continue;
            }
            List<String> mismatches = verifier.mismatches(row);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected Cassandra " + table + " row for "
                + label + " (" + username + ", Subject " + subjectId.value()
                + "). Scanned subjects=" + cassandraSubjects(rows, "subject_id")
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void appendMismatch(List<String> mismatches, String label, String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            mismatches.add(label + " expected " + expected + " got " + (actual == null ? "<missing>" : actual));
        }
    }

    private static void appendInstantFloorMismatch(
            List<String> mismatches,
            String label,
            Instant floor,
            String actual) {
        if (actual == null || actual.isBlank()) {
            mismatches.add(label + " is missing");
            return;
        }
        Instant parsed = instantValue(actual, label);
        if (parsed.isBefore(floor)) {
            mismatches.add(label + " expected at or after " + floor + " got " + parsed);
        }
    }

    private static Instant instantValue(String value, String label) {
        try {
            return Instant.parse(Objects.requireNonNull(value, label).trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid " + label + " instant: " + value, exception);
        }
    }

    private static String cassandraSubjects(List<Map<String, String>> rows, String field) {
        return rows.stream()
                .map(row -> row.getOrDefault(field, "<missing>"))
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static String cassandraCompoundSubjects(List<Map<String, String>> rows, String first, String second) {
        return rows.stream()
                .map(row -> row.getOrDefault(first, "<missing>")
                        + "/" + row.getOrDefault(second, "<missing>"))
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static Optional<PostgresAuthorityRecordResult> verifyPostgresAuthorityRecords(
            VerificationConfig config,
            List<PresenceAuthorityStateExpectation> presenceExpectations,
            List<DeniedPresenceAuthorityStateExpectation> deniedPresenceExpectations,
            List<RouteAuthorityStateExpectation> routeExpectations,
            List<DeniedRouteAuthorityStateExpectation> deniedRouteExpectations,
            List<SessionAuthorityStateExpectation> sessionExpectations,
            List<StandardCapabilityStateExpectation> standardCapabilityExpectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation,
            List<RewardCommandExpectation> rewardExpectations,
            List<DeniedRewardCommandExpectation> deniedRewardExpectations) throws IOException {
        if (!config.verifyPostgresAuthorityRecords()) {
            return Optional.empty();
        }
        List<String> aggregateIds = postgresAuthorityAggregateIds(
                presenceExpectations,
                deniedPresenceExpectations,
                routeExpectations,
                deniedRouteExpectations,
                sessionExpectations,
                standardCapabilityExpectations,
                punishmentExpectation,
                rewardExpectations,
                deniedRewardExpectations);
        long deadline = System.nanoTime() + config.postgresAuthorityRecordTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                return Optional.of(verifyPostgresAuthorityRecordOutput(
                        copyPostgresAuthorityRecords(config, aggregateIds),
                        config.expectedExperienceId(),
                        presenceExpectations,
                        deniedPresenceExpectations,
                        routeExpectations,
                        deniedRouteExpectations,
                        sessionExpectations,
                        standardCapabilityExpectations,
                        punishmentExpectation,
                        rewardExpectations,
                        deniedRewardExpectations));
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for PostgreSQL authority records within "
                + config.postgresAuthorityRecordTimeout()
                + " from " + config.postgresPodName()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static PostgresAuthorityRecordResult verifyPostgresAuthorityRecordOutput(
            String output,
            ExperienceId expectedExperienceId,
            List<PresenceAuthorityStateExpectation> presenceExpectations,
            List<DeniedPresenceAuthorityStateExpectation> deniedPresenceExpectations,
            List<RouteAuthorityStateExpectation> routeExpectations,
            List<DeniedRouteAuthorityStateExpectation> deniedRouteExpectations,
            List<SessionAuthorityStateExpectation> sessionExpectations,
            List<StandardCapabilityStateExpectation> standardCapabilityExpectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation,
            List<RewardCommandExpectation> rewardExpectations,
            List<DeniedRewardCommandExpectation> deniedRewardExpectations) {
        List<PostgresAuthorityRecordRow> rows = postgresAuthorityRecordRows(output);
        for (PresenceAuthorityStateExpectation expectation : presenceExpectations) {
            verifyPostgresPresenceRecord(expectation, rows);
        }
        for (DeniedPresenceAuthorityStateExpectation expectation : deniedPresenceExpectations) {
            verifyPostgresAuthorityRecordAbsent(
                    expectation.label(),
                    expectation.username(),
                    PresenceAuthority.aggregateId(expectation.subjectId()).value(),
                    rows);
        }
        for (RouteAuthorityStateExpectation expectation : routeExpectations) {
            verifyPostgresRouteRecord(expectation, rows);
        }
        for (DeniedRouteAuthorityStateExpectation expectation : deniedRouteExpectations) {
            verifyPostgresAuthorityRecordAbsent(
                    expectation.label(),
                    expectation.username(),
                    RouteAuthority.aggregateId(expectedRouteId(expectation.username())).value(),
                    rows);
        }
        for (SessionAuthorityStateExpectation expectation : sessionExpectations) {
            verifyPostgresSessionRecord(expectedExperienceId, expectation, rows);
        }
        for (StandardCapabilityStateExpectation expectation : standardCapabilityExpectations) {
            verifyPostgresProfileRecord(expectation, rows);
            verifyPostgresRankRecord(expectation, rows);
        }
        punishmentExpectation.ifPresent(expectation -> verifyPostgresPunishmentRecord(expectation, rows));
        for (RewardCommandExpectation expectation : rewardExpectations) {
            verifyPostgresEconomyRecord(expectation, rows);
            verifyPostgresStatsRecord(expectation, rows);
        }
        String deniedRewardCurrencyKey = expectedRewardCurrencyKey(rewardExpectations);
        String deniedRewardStatKey = expectedRewardStatKey(rewardExpectations);
        for (DeniedRewardCommandExpectation expectation : deniedRewardExpectations) {
            verifyPostgresAuthorityRecordAbsent(
                    expectation.label(),
                    expectation.username(),
                    EconomyAuthority.aggregateId(
                            EconomyAuthority.accountId(expectation.subjectId(), deniedRewardCurrencyKey)).value(),
                    rows);
            verifyPostgresAuthorityRecordAbsent(
                    expectation.label(),
                    expectation.username(),
                    StatsAuthority.aggregateId(
                            StatsAuthority.counterId(expectation.subjectId(), deniedRewardStatKey)).value(),
                    rows);
        }
        return new PostgresAuthorityRecordResult(
                presenceExpectations.size(),
                presenceExpectations.size(),
                routeExpectations.size(),
                sessionExpectations.size(),
                standardCapabilityExpectations.size(),
                standardCapabilityExpectations.size(),
                punishmentExpectation.isPresent() ? 1 : 0,
                rewardExpectations.size(),
                rewardExpectations.size());
    }

    private static String copyPostgresAuthorityRecords(
            VerificationConfig config,
            List<String> aggregateIds) throws IOException {
        String aggregateList = aggregateIds.stream()
                .map(LobbyClusterE2eVerifier::sqlString)
                .collect(Collectors.joining(", "));
        String query = "COPY (SELECT aggregate_id, revision, fencing_epoch, "
                + "encode(convert_to(state_payload, 'UTF8'), 'hex') "
                + "FROM authority_records WHERE aggregate_id IN ("
                + aggregateList
                + ") ORDER BY aggregate_id) TO STDOUT WITH DELIMITER '|';";
        String command = "PGPASSWORD=\"${POSTGRES_PASSWORD:-}\" exec "
                + shellQuote(config.postgresPsqlPath())
                + " -U " + shellQuote(config.postgresUsername())
                + " -d " + shellQuote(config.postgresDatabase())
                + " -At -c " + shellQuote(query);
        KubectlResult result = kubectlResult(
                config,
                "PostgreSQL authority records",
                "exec",
                config.postgresPodName(),
                "-c",
                config.postgresContainerName(),
                "--",
                "sh",
                "-c",
                command);
        if (result.exitCode() != 0) {
            throw new IOException("Failed to read PostgreSQL authority records with `"
                    + String.join(" ", result.command()) + "`: " + result.output());
        }
        return result.output();
    }

    private static List<String> postgresAuthorityAggregateIds(
            List<PresenceAuthorityStateExpectation> presenceExpectations,
            List<DeniedPresenceAuthorityStateExpectation> deniedPresenceExpectations,
            List<RouteAuthorityStateExpectation> routeExpectations,
            List<DeniedRouteAuthorityStateExpectation> deniedRouteExpectations,
            List<SessionAuthorityStateExpectation> sessionExpectations,
            List<StandardCapabilityStateExpectation> standardCapabilityExpectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation,
            List<RewardCommandExpectation> rewardExpectations,
            List<DeniedRewardCommandExpectation> deniedRewardExpectations) {
        Map<String, Boolean> aggregateIds = new LinkedHashMap<>();
        presenceExpectations.forEach(expectation ->
                aggregateIds.put(PresenceAuthority.aggregateId(expectation.subjectId()).value(), true));
        deniedPresenceExpectations.forEach(expectation ->
                aggregateIds.put(PresenceAuthority.aggregateId(expectation.subjectId()).value(), true));
        routeExpectations.forEach(expectation ->
                aggregateIds.put(RouteAuthority.aggregateId(expectation.routeId()).value(), true));
        deniedRouteExpectations.forEach(expectation ->
                aggregateIds.put(RouteAuthority.aggregateId(expectedRouteId(expectation.username())).value(), true));
        sessionExpectations.forEach(expectation ->
                aggregateIds.put(SessionAuthority.aggregateId(expectation.sessionId()).value(), true));
        standardCapabilityExpectations.forEach(expectation -> {
            aggregateIds.put(PlayerProfileAuthority.aggregateId(expectation.subjectId()).value(), true);
            aggregateIds.put(RankAuthority.aggregateId(expectation.subjectId()).value(), true);
        });
        punishmentExpectation.ifPresent(expectation ->
                aggregateIds.put(PunishmentAuthority.aggregateId(expectation.subjectId()).value(), true));
        rewardExpectations.forEach(expectation -> {
            aggregateIds.put(EconomyAuthority.aggregateId(
                    EconomyAuthority.accountId(expectation.subjectId(), expectation.currencyKey())).value(), true);
            aggregateIds.put(StatsAuthority.aggregateId(
                    StatsAuthority.counterId(expectation.subjectId(), expectation.statKey())).value(), true);
        });
        String deniedRewardCurrencyKey = expectedRewardCurrencyKey(rewardExpectations);
        String deniedRewardStatKey = expectedRewardStatKey(rewardExpectations);
        deniedRewardExpectations.forEach(expectation -> {
            aggregateIds.put(EconomyAuthority.aggregateId(
                    EconomyAuthority.accountId(expectation.subjectId(), deniedRewardCurrencyKey)).value(), true);
            aggregateIds.put(StatsAuthority.aggregateId(
                    StatsAuthority.counterId(expectation.subjectId(), deniedRewardStatKey)).value(), true);
        });
        return List.copyOf(aggregateIds.keySet());
    }

    private static String expectedRewardCurrencyKey(List<RewardCommandExpectation> rewardExpectations) {
        return rewardExpectations.isEmpty()
                ? VerificationConfig.DEFAULT_EXPECTED_REWARD_CURRENCY_KEY
                : rewardExpectations.get(0).currencyKey();
    }

    private static String expectedRewardStatKey(List<RewardCommandExpectation> rewardExpectations) {
        return rewardExpectations.isEmpty()
                ? VerificationConfig.DEFAULT_EXPECTED_REWARD_STAT_KEY
                : rewardExpectations.get(0).statKey();
    }

    private static List<PostgresAuthorityRecordRow> postgresAuthorityRecordRows(String output) {
        List<PostgresAuthorityRecordRow> rows = new ArrayList<>();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.contains("|")) {
                continue;
            }
            String[] parts = trimmed.split("\\|", -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException("Malformed PostgreSQL authority record row: " + line);
            }
            rows.add(new PostgresAuthorityRecordRow(
                    parts[0].trim(),
                    Long.parseLong(parts[1].trim()),
                    Long.parseLong(parts[2].trim()),
                    new String(HexFormat.of().parseHex(parts[3].trim()), StandardCharsets.UTF_8)));
        }
        return List.copyOf(rows);
    }

    private static void verifyPostgresPresenceRecord(
            PresenceAuthorityStateExpectation expectation,
            List<PostgresAuthorityRecordRow> rows) {
        PostgresAuthorityRecordRow row = requirePostgresAuthorityRecord(
                PresenceAuthority.aggregateId(expectation.subjectId()).value(),
                rows);
        PresenceSnapshot snapshot = PresenceAuthorityWireCodec.decodeState(row.statePayload())
                .current()
                .orElseThrow(() -> new IllegalStateException("PostgreSQL Presence record is empty for "
                        + expectation.label()));
        List<String> mismatches = new ArrayList<>();
        verifyPostgresEnvelope(mismatches, row);
        appendMismatch(mismatches, "subjectId", expectation.subjectId().value().toString(), snapshot.subjectId().value().toString());
        appendMismatch(mismatches, "presenceId", expectation.presenceId().value(), snapshot.presenceId().value());
        appendMismatch(mismatches, "status", PresenceLifecycleStatus.LIVE.name(), snapshot.status().name());
        appendMismatch(mismatches, "sessionId", expectation.sessionId().value(), snapshot.sessionId().map(SessionId::value).orElse(""));
        appendMismatch(mismatches, "routeId", expectation.routeId().value(), snapshot.routeId().map(RouteId::value).orElse(""));
        if (snapshot.expiresAt().isBefore(expectation.minimumLeaseExpiresAt())) {
            mismatches.add("expiresAt expected at or after " + expectation.minimumLeaseExpiresAt()
                    + " got " + snapshot.expiresAt());
        }
        throwIfPostgresMismatches("presence", expectation.label(), expectation.username(), row.aggregateId(), mismatches);
    }

    private static void verifyPostgresRouteRecord(
            RouteAuthorityStateExpectation expectation,
            List<PostgresAuthorityRecordRow> rows) {
        PostgresAuthorityRecordRow row = requirePostgresAuthorityRecord(
                RouteAuthority.aggregateId(expectation.routeId()).value(),
                rows);
        RouteSnapshot snapshot = RouteAuthorityWireCodec.decodeState(row.statePayload())
                .current()
                .orElseThrow(() -> new IllegalStateException("PostgreSQL Route record is empty for "
                        + expectation.label()));
        List<String> mismatches = new ArrayList<>();
        verifyPostgresEnvelope(mismatches, row);
        appendMismatch(mismatches, "subjectId", expectation.subjectId().value().toString(), snapshot.subjectId().value().toString());
        appendMismatch(mismatches, "routeId", expectation.routeId().value(), snapshot.routeId().value());
        appendMismatch(mismatches, "targetSessionId", expectation.sessionId().value(), snapshot.targetSessionId().value());
        appendMismatch(mismatches, "targetInstanceId", expectation.targetInstanceId().value(), snapshot.targetInstanceId().value());
        appendMismatch(mismatches, "status", RouteLifecycleStatus.ACKNOWLEDGED.name(), snapshot.status().name());
        Instant completedAt = snapshot.completedAt().orElse(Instant.EPOCH);
        if (completedAt.isBefore(expectation.minimumCompletedAt())) {
            mismatches.add("completedAt expected at or after " + expectation.minimumCompletedAt()
                    + " got " + completedAt);
        }
        throwIfPostgresMismatches("route", expectation.label(), expectation.username(), row.aggregateId(), mismatches);
    }

    private static void verifyPostgresSessionRecord(
            ExperienceId expectedExperienceId,
            SessionAuthorityStateExpectation expectation,
            List<PostgresAuthorityRecordRow> rows) {
        PostgresAuthorityRecordRow row = requirePostgresAuthorityRecord(
                SessionAuthority.aggregateId(expectation.sessionId()).value(),
                rows);
        SessionSnapshot snapshot = SessionAuthorityWireCodec.decodeState(row.statePayload())
                .current()
                .orElseThrow(() -> new IllegalStateException("PostgreSQL Session record is empty for "
                        + expectation.label()));
        List<String> mismatches = new ArrayList<>();
        verifyPostgresEnvelope(mismatches, row);
        appendMismatch(mismatches, "sessionId", expectation.sessionId().value(), snapshot.sessionId().value());
        appendMismatch(mismatches, "experienceId", expectedExperienceId.value(), snapshot.experienceId().value());
        appendMismatch(mismatches, "slotId", expectation.slotId().value(), snapshot.slotId().value());
        appendMismatch(mismatches, "ownerInstanceId", expectation.ownerInstanceId().value(), snapshot.ownerInstanceId().value());
        appendMismatch(mismatches, "resolvedManifestId", expectation.resolvedManifestId().value(), snapshot.resolvedManifestId().value());
        appendMismatch(mismatches, "status", SessionLifecycleStatus.ACTIVE.name(), snapshot.status().name());
        if (snapshot.leaseExpiresAt().isBefore(expectation.minimumLeaseExpiresAt())) {
            mismatches.add("leaseExpiresAt expected at or after " + expectation.minimumLeaseExpiresAt()
                    + " got " + snapshot.leaseExpiresAt());
        }
        if (snapshot.activatedAt().isEmpty()) {
            mismatches.add("activatedAt is missing");
        }
        throwIfPostgresMismatches("session", expectation.label(), expectation.username(), row.aggregateId(), mismatches);
    }

    private static void verifyPostgresProfileRecord(
            StandardCapabilityStateExpectation expectation,
            List<PostgresAuthorityRecordRow> rows) {
        PostgresAuthorityRecordRow row = requirePostgresAuthorityRecord(
                PlayerProfileAuthority.aggregateId(expectation.subjectId()).value(),
                rows);
        PlayerProfileSnapshot snapshot = StandardCapabilityAuthorityWireCodec.decodePlayerProfileState(row.statePayload())
                .current()
                .orElseThrow(() -> new IllegalStateException("PostgreSQL player-profile record is empty for "
                        + expectation.label()));
        List<String> mismatches = new ArrayList<>();
        verifyPostgresEnvelope(mismatches, row);
        appendMismatch(mismatches, "subjectId", expectation.subjectId().value().toString(), snapshot.subjectId().value().toString());
        appendMismatch(mismatches, "displayName", expectation.displayName(), snapshot.displayName());
        appendMismatch(mismatches, "updatedBy", LobbyCapabilitySeedProvisioner.DEFAULT_PRINCIPAL_ID, snapshot.updatedBy().value());
        throwIfPostgresMismatches("player-profile", expectation.label(), expectation.username(), row.aggregateId(), mismatches);
    }

    private static void verifyPostgresRankRecord(
            StandardCapabilityStateExpectation expectation,
            List<PostgresAuthorityRecordRow> rows) {
        PostgresAuthorityRecordRow row = requirePostgresAuthorityRecord(
                RankAuthority.aggregateId(expectation.subjectId()).value(),
                rows);
        EffectiveRankSnapshot snapshot = StandardCapabilityAuthorityWireCodec.decodeRankState(row.statePayload())
                .current()
                .orElseThrow(() -> new IllegalStateException("PostgreSQL rank record is empty for "
                        + expectation.label()));
        List<String> mismatches = new ArrayList<>();
        verifyPostgresEnvelope(mismatches, row);
        appendMismatch(mismatches, "subjectId", expectation.subjectId().value().toString(), snapshot.subjectId().value().toString());
        appendMismatch(mismatches, "primaryRankKey", expectation.rankKey(), snapshot.primaryRankKey());
        if (!snapshot.permissions().contains("rank:" + expectation.rankKey())) {
            mismatches.add("permissions expected to contain rank:" + expectation.rankKey()
                    + " got " + snapshot.permissions());
        }
        appendMismatch(mismatches, "updatedBy", LobbyCapabilitySeedProvisioner.DEFAULT_PRINCIPAL_ID, snapshot.updatedBy().value());
        throwIfPostgresMismatches("rank", expectation.label(), expectation.username(), row.aggregateId(), mismatches);
    }

    private static void verifyPostgresPunishmentRecord(
            PunishmentCapabilityStateExpectation expectation,
            List<PostgresAuthorityRecordRow> rows) {
        PostgresAuthorityRecordRow row = requirePostgresAuthorityRecord(
                PunishmentAuthority.aggregateId(expectation.subjectId()).value(),
                rows);
        ActivePunishmentSnapshot snapshot = StandardCapabilityAuthorityWireCodec.decodePunishmentState(row.statePayload())
                .active()
                .orElseThrow(() -> new IllegalStateException("PostgreSQL punishment record is empty for "
                        + expectation.label()));
        List<String> mismatches = new ArrayList<>();
        verifyPostgresEnvelope(mismatches, row);
        appendMismatch(mismatches, "subjectId", expectation.subjectId().value().toString(), snapshot.subjectId().value().toString());
        expectation.reasonContains().ifPresent(expected -> {
            if (!snapshot.reason().contains(expected)) {
                mismatches.add("reason expected to contain " + expected + " got " + snapshot.reason());
            }
        });
        appendMismatch(mismatches, "issuedBy", LobbyCapabilitySeedProvisioner.DEFAULT_PRINCIPAL_ID, snapshot.issuedBy().value());
        if (snapshot.expiresAt().isBefore(expectation.activeAt())) {
            mismatches.add("expiresAt expected at or after " + expectation.activeAt()
                    + " got " + snapshot.expiresAt());
        }
        throwIfPostgresMismatches("punishment", expectation.label(), expectation.username(), row.aggregateId(), mismatches);
    }

    private static void verifyPostgresEconomyRecord(
            RewardCommandExpectation expectation,
            List<PostgresAuthorityRecordRow> rows) {
        PostgresAuthorityRecordRow row = requirePostgresAuthorityRecord(
                EconomyAuthority.aggregateId(
                        EconomyAuthority.accountId(expectation.subjectId(), expectation.currencyKey())).value(),
                rows);
        EconomyBalanceSnapshot snapshot = StandardCapabilityAuthorityWireCodec.decodeEconomyState(row.statePayload())
                .current()
                .orElseThrow(() -> new IllegalStateException("PostgreSQL economy record is empty for "
                        + expectation.label()));
        List<String> mismatches = new ArrayList<>();
        verifyPostgresEnvelope(mismatches, row);
        appendMismatch(mismatches, "subjectId", expectation.subjectId().value().toString(), snapshot.accountId().subjectId().value().toString());
        appendMismatch(mismatches, "currencyKey", expectation.currencyKey(), snapshot.accountId().currencyKey());
        appendMismatch(mismatches, "balanceMinorUnits", Long.toString(expectation.rewardAmountMinorUnits()), Long.toString(snapshot.balanceMinorUnits()));
        appendMismatch(mismatches, "lastEntryId", "idem-paper-reward-economy-" + rewardCommandSuffix(expectation), snapshot.lastEntryId());
        appendMismatch(mismatches, "updatedBy", PAPER_AGENT_PRINCIPAL_ID, snapshot.updatedBy().value());
        if (snapshot.updatedAt().isBefore(expectation.minimumOccurredAt())) {
            mismatches.add("updatedAt expected at or after " + expectation.minimumOccurredAt()
                    + " got " + snapshot.updatedAt());
        }
        throwIfPostgresMismatches("economy", expectation.label(), expectation.username(), row.aggregateId(), mismatches);
    }

    private static void verifyPostgresStatsRecord(
            RewardCommandExpectation expectation,
            List<PostgresAuthorityRecordRow> rows) {
        PostgresAuthorityRecordRow row = requirePostgresAuthorityRecord(
                StatsAuthority.aggregateId(
                        StatsAuthority.counterId(expectation.subjectId(), expectation.statKey())).value(),
                rows);
        StatsCounterSnapshot snapshot = StandardCapabilityAuthorityWireCodec.decodeStatsState(row.statePayload())
                .current()
                .orElseThrow(() -> new IllegalStateException("PostgreSQL stats record is empty for "
                        + expectation.label()));
        List<String> mismatches = new ArrayList<>();
        verifyPostgresEnvelope(mismatches, row);
        appendMismatch(mismatches, "subjectId", expectation.subjectId().value().toString(), snapshot.counterId().subjectId().value().toString());
        appendMismatch(mismatches, "statKey", expectation.statKey(), snapshot.counterId().statKey());
        appendMismatch(mismatches, "total", Long.toString(PAPER_REWARD_STATS_DELTA), Long.toString(snapshot.total()));
        appendMismatch(mismatches, "lastEntryId", "idem-paper-reward-stats-" + rewardCommandSuffix(expectation), snapshot.lastEntryId());
        appendMismatch(mismatches, "updatedBy", PAPER_AGENT_PRINCIPAL_ID, snapshot.updatedBy().value());
        if (snapshot.updatedAt().isBefore(expectation.minimumOccurredAt())) {
            mismatches.add("updatedAt expected at or after " + expectation.minimumOccurredAt()
                    + " got " + snapshot.updatedAt());
        }
        throwIfPostgresMismatches("stats", expectation.label(), expectation.username(), row.aggregateId(), mismatches);
    }

    private static PostgresAuthorityRecordRow requirePostgresAuthorityRecord(
            String aggregateId,
            List<PostgresAuthorityRecordRow> rows) {
        return rows.stream()
                .filter(row -> aggregateId.equals(row.aggregateId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Expected PostgreSQL authority_records row for "
                        + aggregateId + ". Scanned aggregateIds=" + postgresAggregateIds(rows)));
    }

    private static void verifyPostgresAuthorityRecordAbsent(
            String label,
            String username,
            String aggregateId,
            List<PostgresAuthorityRecordRow> rows) {
        rows.stream()
                .filter(row -> aggregateId.equals(row.aggregateId()))
                .findFirst()
                .ifPresent(row -> {
                    throw new IllegalStateException("Expected PostgreSQL authority_records to omit denied "
                            + label + " (" + username + ") aggregateId=" + aggregateId);
                });
    }

    private static void verifyPostgresEnvelope(List<String> mismatches, PostgresAuthorityRecordRow row) {
        if (row.revision() <= 0) {
            mismatches.add("revision must be positive, got " + row.revision());
        }
        if (row.fencingEpoch() != 1L) {
            mismatches.add("fencingEpoch expected 1 got " + row.fencingEpoch());
        }
    }

    private static void throwIfPostgresMismatches(
            String family,
            String label,
            String username,
            String aggregateId,
            List<String> mismatches) {
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException("PostgreSQL " + family + " authority record mismatch for "
                    + label + " (" + username + ") aggregateId=" + aggregateId
                    + ": " + String.join(", ", mismatches));
        }
    }

    private static String postgresAggregateIds(List<PostgresAuthorityRecordRow> rows) {
        return rows.stream()
                .map(PostgresAuthorityRecordRow::aggregateId)
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static String sqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static Optional<ValkeyCacheResult> verifyValkeyCache(
            VerificationConfig config,
            List<PresenceAuthorityStateExpectation> presenceExpectations,
            List<DeniedPresenceAuthorityStateExpectation> deniedPresenceExpectations,
            List<StandardCapabilityStateExpectation> standardCapabilityExpectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation,
            List<RewardCommandExpectation> rewardExpectations,
            List<DeniedRewardCommandExpectation> deniedRewardExpectations) throws IOException {
        if (!config.verifyValkeyCache()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.valkeyCacheTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                return Optional.of(verifyValkeyCacheValues(
                        copyValkeyCacheValues(config, valkeyCacheKeys(
                                presenceExpectations,
                                deniedPresenceExpectations,
                                standardCapabilityExpectations,
                                punishmentExpectation,
                                rewardExpectations,
                                deniedRewardExpectations)),
                        presenceExpectations,
                        deniedPresenceExpectations,
                        standardCapabilityExpectations,
                        punishmentExpectation,
                        rewardExpectations,
                        deniedRewardExpectations));
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for Valkey cache entries within "
                + config.valkeyCacheTimeout()
                + " from " + config.valkeyResourceName()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static ValkeyCacheResult verifyValkeyCacheValues(
            Map<String, String> values,
            List<PresenceAuthorityStateExpectation> presenceExpectations,
            List<DeniedPresenceAuthorityStateExpectation> deniedPresenceExpectations,
            List<StandardCapabilityStateExpectation> standardCapabilityExpectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation,
            List<RewardCommandExpectation> rewardExpectations,
            List<DeniedRewardCommandExpectation> deniedRewardExpectations) {
        Objects.requireNonNull(values, "values");
        for (PresenceAuthorityStateExpectation expectation : presenceExpectations) {
            verifyValkeyPresenceCache(expectation, values);
        }
        for (DeniedPresenceAuthorityStateExpectation expectation : deniedPresenceExpectations) {
            verifyDeniedValkeyPresenceCacheAbsent(expectation, values);
        }
        for (StandardCapabilityStateExpectation expectation : standardCapabilityExpectations) {
            verifyValkeyPlayerProfileCache(expectation, values);
            verifyValkeyRankCache(expectation, values);
        }
        punishmentExpectation.ifPresent(expectation -> verifyValkeyPunishmentCache(expectation, values));
        for (RewardCommandExpectation expectation : rewardExpectations) {
            verifyValkeyEconomyCache(expectation, values);
            verifyValkeyStatsCache(expectation, values);
        }
        String deniedRewardCurrencyKey = expectedRewardCurrencyKey(rewardExpectations);
        String deniedRewardStatKey = expectedRewardStatKey(rewardExpectations);
        for (DeniedRewardCommandExpectation expectation : deniedRewardExpectations) {
            verifyDeniedValkeyRewardCacheAbsent(expectation, deniedRewardCurrencyKey, deniedRewardStatKey, values);
        }
        return new ValkeyCacheResult(
                presenceExpectations.size(),
                valkeyCacheKeys(
                        presenceExpectations,
                        deniedPresenceExpectations,
                        standardCapabilityExpectations,
                        punishmentExpectation,
                        rewardExpectations,
                        deniedRewardExpectations).size(),
                presenceExpectations.size(),
                standardCapabilityExpectations.size(),
                standardCapabilityExpectations.size(),
                punishmentExpectation.isPresent() ? 1 : 0,
                rewardExpectations.size(),
                rewardExpectations.size());
    }

    private static Map<String, String> copyValkeyCacheValues(
            VerificationConfig config,
            List<String> cacheKeys) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String cacheKey : cacheKeys) {
            Optional<String> value = getValkeyCacheValue(config, cacheKey);
            value.ifPresent(payload -> values.put(cacheKey, payload));
        }
        return Map.copyOf(values);
    }

    private static Optional<String> getValkeyCacheValue(VerificationConfig config, String cacheKey)
            throws IOException {
        KubectlResult result = kubectlResult(
                config,
                "Valkey cache key " + cacheKey,
                "exec",
                config.valkeyResourceName(),
                "-c",
                config.valkeyContainerName(),
                "--",
                config.valkeyCliPath(),
                "--raw",
                "GET",
                cacheKey);
        if (result.exitCode() != 0) {
            throw new IOException("Failed to read Valkey cache key with `"
                    + String.join(" ", result.command()) + "`: " + result.output());
        }
        if (result.output().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(result.output());
    }

    private static List<String> valkeyCacheKeys(
            List<PresenceAuthorityStateExpectation> presenceExpectations,
            List<DeniedPresenceAuthorityStateExpectation> deniedPresenceExpectations,
            List<StandardCapabilityStateExpectation> standardCapabilityExpectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation,
            List<RewardCommandExpectation> rewardExpectations,
            List<DeniedRewardCommandExpectation> deniedRewardExpectations) {
        Map<String, Boolean> cacheKeys = new LinkedHashMap<>();
        presenceExpectations.forEach(expectation ->
                cacheKeys.put(PresenceAuthority.cacheKey(expectation.subjectId()), true));
        deniedPresenceExpectations.forEach(expectation ->
                cacheKeys.put(PresenceAuthority.cacheKey(expectation.subjectId()), true));
        standardCapabilityExpectations.forEach(expectation -> {
            cacheKeys.put(PlayerProfileAuthority.cacheKey(expectation.subjectId()), true);
            cacheKeys.put(RankAuthority.cacheKey(expectation.subjectId()), true);
        });
        punishmentExpectation.ifPresent(expectation ->
                cacheKeys.put(PunishmentAuthority.cacheKey(expectation.subjectId()), true));
        rewardExpectations.forEach(expectation -> {
            cacheKeys.put(EconomyAuthority.cacheKey(
                    EconomyAuthority.accountId(expectation.subjectId(), expectation.currencyKey())), true);
            cacheKeys.put(StatsAuthority.cacheKey(
                    StatsAuthority.counterId(expectation.subjectId(), expectation.statKey())), true);
        });
        String deniedRewardCurrencyKey = expectedRewardCurrencyKey(rewardExpectations);
        String deniedRewardStatKey = expectedRewardStatKey(rewardExpectations);
        deniedRewardExpectations.forEach(expectation -> {
            cacheKeys.put(EconomyAuthority.cacheKey(
                    EconomyAuthority.accountId(expectation.subjectId(), deniedRewardCurrencyKey)), true);
            cacheKeys.put(StatsAuthority.cacheKey(
                    StatsAuthority.counterId(expectation.subjectId(), deniedRewardStatKey)), true);
        });
        return List.copyOf(cacheKeys.keySet());
    }

    private static void verifyValkeyPresenceCache(
            PresenceAuthorityStateExpectation expectation,
            Map<String, String> values) {
        String cacheKey = PresenceAuthority.cacheKey(expectation.subjectId());
        PresenceSnapshot snapshot = PresenceAuthorityWireCodec.decodeState(requireValkeyCacheValue(
                        expectation.label(),
                        expectation.username(),
                        cacheKey,
                        values))
                .current()
                .orElseThrow(() -> new IllegalStateException("Valkey Presence cache is empty for "
                        + expectation.label() + " (" + expectation.username() + ") key=" + cacheKey));
        verifyPresenceAuthorityStateRecord(expectation, List.of(PresenceAuthorityStateRecord.from(snapshot)));
    }

    private static void verifyDeniedValkeyPresenceCacheAbsent(
            DeniedPresenceAuthorityStateExpectation expectation,
            Map<String, String> values) {
        String payload = values.get(PresenceAuthority.cacheKey(expectation.subjectId()));
        if (payload == null || payload.isBlank()) {
            return;
        }
        List<PresenceAuthorityStateRecord> records = PresenceAuthorityWireCodec.decodeState(payload)
                .current()
                .map(PresenceAuthorityStateRecord::from)
                .map(List::of)
                .orElseGet(List::of);
        verifyDeniedPresenceAuthorityStateRecordAbsent(expectation, records);
    }

    private static void verifyValkeyPlayerProfileCache(
            StandardCapabilityStateExpectation expectation,
            Map<String, String> values) {
        String cacheKey = PlayerProfileAuthority.cacheKey(expectation.subjectId());
        PlayerProfileSnapshot snapshot = StandardCapabilityAuthorityWireCodec.decodePlayerProfileState(requireValkeyCacheValue(
                        expectation.label(),
                        expectation.username(),
                        cacheKey,
                        values))
                .current()
                .orElseThrow(() -> new IllegalStateException("Valkey player-profile cache is empty for "
                        + expectation.label() + " (" + expectation.username() + ") key=" + cacheKey));
        verifyPlayerProfileStateRecord(expectation, List.of(snapshot));
    }

    private static void verifyValkeyRankCache(
            StandardCapabilityStateExpectation expectation,
            Map<String, String> values) {
        String cacheKey = RankAuthority.cacheKey(expectation.subjectId());
        EffectiveRankSnapshot snapshot = StandardCapabilityAuthorityWireCodec.decodeRankState(requireValkeyCacheValue(
                        expectation.label(),
                        expectation.username(),
                        cacheKey,
                        values))
                .current()
                .orElseThrow(() -> new IllegalStateException("Valkey rank cache is empty for "
                        + expectation.label() + " (" + expectation.username() + ") key=" + cacheKey));
        verifyRankStateRecord(expectation, List.of(snapshot));
    }

    private static void verifyValkeyPunishmentCache(
            PunishmentCapabilityStateExpectation expectation,
            Map<String, String> values) {
        String cacheKey = PunishmentAuthority.cacheKey(expectation.subjectId());
        ActivePunishmentSnapshot snapshot = StandardCapabilityAuthorityWireCodec.decodePunishmentState(requireValkeyCacheValue(
                        expectation.label(),
                        expectation.username(),
                        cacheKey,
                        values))
                .active()
                .orElseThrow(() -> new IllegalStateException("Valkey punishment cache is empty for "
                        + expectation.label() + " (" + expectation.username() + ") key=" + cacheKey));
        verifyPunishmentStateRecord(expectation, List.of(snapshot));
    }

    private static void verifyValkeyEconomyCache(
            RewardCommandExpectation expectation,
            Map<String, String> values) {
        String cacheKey = EconomyAuthority.cacheKey(
                EconomyAuthority.accountId(expectation.subjectId(), expectation.currencyKey()));
        EconomyBalanceSnapshot snapshot = StandardCapabilityAuthorityWireCodec.decodeEconomyState(requireValkeyCacheValue(
                        expectation.label(),
                        expectation.username(),
                        cacheKey,
                        values))
                .current()
                .orElseThrow(() -> new IllegalStateException("Valkey economy cache is empty for "
                        + expectation.label() + " (" + expectation.username() + ") key=" + cacheKey));
        verifyEconomyRewardState(expectation, List.of(snapshot));
    }

    private static void verifyValkeyStatsCache(
            RewardCommandExpectation expectation,
            Map<String, String> values) {
        String cacheKey = StatsAuthority.cacheKey(
                StatsAuthority.counterId(expectation.subjectId(), expectation.statKey()));
        StatsCounterSnapshot snapshot = StandardCapabilityAuthorityWireCodec.decodeStatsState(requireValkeyCacheValue(
                        expectation.label(),
                        expectation.username(),
                        cacheKey,
                        values))
                .current()
                .orElseThrow(() -> new IllegalStateException("Valkey stats cache is empty for "
                        + expectation.label() + " (" + expectation.username() + ") key=" + cacheKey));
        verifyStatsRewardState(expectation, List.of(snapshot));
    }

    private static void verifyDeniedValkeyRewardCacheAbsent(
            DeniedRewardCommandExpectation expectation,
            String currencyKey,
            String statKey,
            Map<String, String> values) {
        String economyPayload = values.get(EconomyAuthority.cacheKey(
                EconomyAuthority.accountId(expectation.subjectId(), currencyKey)));
        String statsPayload = values.get(StatsAuthority.cacheKey(
                StatsAuthority.counterId(expectation.subjectId(), statKey)));
        List<EconomyBalanceSnapshot> economyRecords = economyPayload == null || economyPayload.isBlank()
                ? List.of()
                : StandardCapabilityAuthorityWireCodec.decodeEconomyState(economyPayload)
                        .current()
                        .stream()
                        .toList();
        List<StatsCounterSnapshot> statsRecords = statsPayload == null || statsPayload.isBlank()
                ? List.of()
                : StandardCapabilityAuthorityWireCodec.decodeStatsState(statsPayload)
                        .current()
                        .stream()
                        .toList();
        verifyDeniedRewardStateAbsent(expectation, economyRecords, statsRecords);
    }

    private static String requireValkeyCacheValue(
            String label,
            String username,
            String cacheKey,
            Map<String, String> values) {
        String payload = values.get(cacheKey);
        if (payload == null || payload.isBlank()) {
            throw new IllegalStateException("Expected Valkey cache key for "
                    + label + " (" + username + ") key=" + cacheKey
                    + ". Available keys=" + values.keySet().stream().sorted().collect(Collectors.joining(", ")));
        }
        return payload;
    }

    private static Optional<ProjectionConsistencyResult> verifyProjectionConsistency(
            VerificationConfig config,
            Optional<RouteAttemptStateResult> routeAttemptState,
            Optional<RouteAuthorityStateResult> routeAuthorityState,
            Optional<QueueRosterStateResult> queueRosterState,
            Optional<LifecycleTraceStateResult> lifecycleTraceState,
            Optional<PresenceAuthorityStateResult> presenceAuthorityState,
            Optional<StandardCapabilityStateResult> standardCapabilityState,
            Optional<RewardStateResult> rewardState,
            Optional<SessionAuthorityStateResult> sessionAuthorityState,
            Optional<SharedShardAllocationStateResult> sharedShardAllocationState,
            Optional<CassandraHotProjectionResult> cassandraHotProjections,
            Optional<PostgresAuthorityRecordResult> postgresAuthorityRecords,
            Optional<ValkeyCacheResult> valkeyCache) {
        if (!config.verifyProjectionConsistency()) {
            return Optional.empty();
        }
        return Optional.of(verifyProjectionConsistencyEvidence(
                routeAttemptState,
                routeAuthorityState,
                queueRosterState,
                lifecycleTraceState,
                presenceAuthorityState,
                standardCapabilityState,
                rewardState,
                sessionAuthorityState,
                sharedShardAllocationState,
                cassandraHotProjections,
                postgresAuthorityRecords,
                valkeyCache));
    }

    static ProjectionConsistencyResult verifyProjectionConsistencyEvidence(
            Optional<RouteAttemptStateResult> routeAttemptState,
            Optional<RouteAuthorityStateResult> routeAuthorityState,
            Optional<QueueRosterStateResult> queueRosterState,
            Optional<LifecycleTraceStateResult> lifecycleTraceState,
            Optional<PresenceAuthorityStateResult> presenceAuthorityState,
            Optional<StandardCapabilityStateResult> standardCapabilityState,
            Optional<RewardStateResult> rewardState,
            Optional<SessionAuthorityStateResult> sessionAuthorityState,
            Optional<SharedShardAllocationStateResult> sharedShardAllocationState,
            Optional<CassandraHotProjectionResult> cassandraHotProjections,
            Optional<PostgresAuthorityRecordResult> postgresAuthorityRecords,
            Optional<ValkeyCacheResult> valkeyCache) {
        List<String> missing = new ArrayList<>();
        requirePositiveProjectionEvidence(
                missing,
                "RouteAttempt Kafka state",
                routeAttemptState,
                RouteAttemptStateResult::matchedCount);
        requirePositiveProjectionEvidence(
                missing,
                "Route authority Kafka state",
                routeAuthorityState,
                RouteAuthorityStateResult::matchedCount);
        requirePositiveProjectionEvidence(
                missing,
                "queue-roster Kafka state",
                queueRosterState,
                QueueRosterStateResult::matchedCount);
        requirePositiveProjectionEvidence(
                missing,
                "lifecycle trace Kafka state",
                lifecycleTraceState,
                LifecycleTraceStateResult::matchedCount);
        requirePositiveProjectionEvidence(
                missing,
                "Presence authority Kafka state",
                presenceAuthorityState,
                PresenceAuthorityStateResult::matchedCount);
        requirePositiveProjectionEvidence(
                missing,
                "standard capability Kafka state",
                standardCapabilityState,
                StandardCapabilityStateResult::matchedCount);
        requirePositiveProjectionEvidence(
                missing,
                "reward authority Kafka state",
                rewardState,
                RewardStateResult::matchedCount);
        requirePositiveProjectionEvidence(
                missing,
                "Session authority Kafka state",
                sessionAuthorityState,
                SessionAuthorityStateResult::matchedCount);
        requirePositiveProjectionEvidence(
                missing,
                "shared-shard allocation Kafka state",
                sharedShardAllocationState,
                SharedShardAllocationStateResult::matchedCount);
        requirePositiveProjectionEvidence(
                missing,
                "Cassandra hot projections",
                cassandraHotProjections,
                CassandraHotProjectionResult::matchedCount);
        requirePositiveProjectionEvidence(
                missing,
                "PostgreSQL authority records",
                postgresAuthorityRecords,
                PostgresAuthorityRecordResult::matchedCount);
        requirePositiveProjectionEvidence(
                missing,
                "Valkey cache",
                valkeyCache,
                ValkeyCacheResult::matchedCount);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Projection consistency verification requires all live state, projection, "
                    + "canonical-record, and cache evidence to be present with positive matches. Missing: "
                    + String.join(", ", missing));
        }
        int acceptedLoginMatches = routeAttemptState.orElseThrow().matchedCount();
        List<String> mismatched = new ArrayList<>();
        requireSubjectProjectionMatchCount(
                mismatched,
                "Route authority Kafka state",
                acceptedLoginMatches,
                routeAuthorityState.orElseThrow().matchedCount());
        requireSubjectProjectionMatchCount(
                mismatched,
                "queue-roster Kafka state",
                acceptedLoginMatches,
                queueRosterState.orElseThrow().matchedCount());
        requireSubjectProjectionMatchCount(
                mismatched,
                "lifecycle trace Kafka state",
                acceptedLoginMatches,
                lifecycleTraceState.orElseThrow().matchedCount());
        requireSubjectProjectionMatchCount(
                mismatched,
                "Presence authority Kafka state",
                acceptedLoginMatches,
                presenceAuthorityState.orElseThrow().matchedCount());
        requireSubjectProjectionMatchCount(
                mismatched,
                "standard capability Kafka state",
                acceptedLoginMatches,
                standardCapabilityState.orElseThrow().matchedCount());
        requireSubjectProjectionMatchCount(
                mismatched,
                "reward authority Kafka state",
                acceptedLoginMatches,
                rewardState.orElseThrow().matchedCount());
        requireSubjectProjectionMatchCount(
                mismatched,
                "Cassandra hot projections",
                acceptedLoginMatches,
                cassandraHotProjections.orElseThrow().matchedCount());
        requireSubjectProjectionMatchCount(
                mismatched,
                "PostgreSQL authority records",
                acceptedLoginMatches,
                postgresAuthorityRecords.orElseThrow().matchedCount());
        requireSubjectProjectionMatchCount(
                mismatched,
                "Valkey cache",
                acceptedLoginMatches,
                valkeyCache.orElseThrow().matchedCount());
        if (!mismatched.isEmpty()) {
            throw new IllegalStateException("Projection consistency verification requires subject-level evidence "
                    + "to match accepted login count " + acceptedLoginMatches + ". Mismatched: "
                    + String.join(", ", mismatched));
        }
        int uniqueSessionMatches = sharedShardAllocationState.orElseThrow().matchedCount();
        List<String> sessionMismatched = new ArrayList<>();
        requireUniqueSessionEvidenceMatchCount(
                sessionMismatched,
                "Session authority state",
                uniqueSessionMatches,
                sessionAuthorityState.orElseThrow().matchedCount());
        if (!sessionMismatched.isEmpty()) {
            throw new IllegalStateException("Projection consistency verification requires Session-level evidence "
                    + "to match shared-shard allocation count " + uniqueSessionMatches + ". Mismatched: "
                    + String.join(", ", sessionMismatched));
        }
        return new ProjectionConsistencyResult(
                acceptedLoginMatches,
                routeAuthorityState.orElseThrow().matchedCount(),
                queueRosterState.orElseThrow().matchedCount(),
                lifecycleTraceState.orElseThrow().matchedCount(),
                presenceAuthorityState.orElseThrow().matchedCount(),
                standardCapabilityState.orElseThrow().matchedCount(),
                rewardState.orElseThrow().matchedCount(),
                sessionAuthorityState.orElseThrow().matchedCount(),
                sharedShardAllocationState.orElseThrow().matchedCount(),
                cassandraHotProjections.orElseThrow().matchedCount(),
                postgresAuthorityRecords.orElseThrow().matchedCount(),
                valkeyCache.orElseThrow().matchedCount());
    }

    private static <T> void requirePositiveProjectionEvidence(
            List<String> missing,
            String label,
            Optional<T> evidence,
            ToIntFunction<T> matchedCount) {
        if (evidence.isEmpty()) {
            missing.add(label);
            return;
        }
        if (matchedCount.applyAsInt(evidence.orElseThrow()) <= 0) {
            missing.add(label);
        }
    }

    private static void requireSubjectProjectionMatchCount(
            List<String> mismatched,
            String label,
            int expected,
            int actual) {
        if (actual != expected) {
            mismatched.add(label + " expected " + expected + " got " + actual);
        }
    }

    private static Optional<TraceCorrelationResult> verifyTraceCorrelation(
            VerificationConfig config,
            Optional<RouteAttemptStateResult> routeAttemptState,
            Optional<RouteAuthorityCommandLogResult> routeAuthorityCommandLog,
            Optional<RouteAuthorityStateResult> routeAuthorityState,
            Optional<LoginRoutingCommandLogResult> loginRoutingCommandLog,
            Optional<LifecycleTraceStateResult> lifecycleTraceState,
            Optional<HostRouteCommandLogResult> hostRouteCommandLog,
            Optional<HostObservationLogResult> hostObservationLog,
            Optional<StandardCapabilityCommandLogResult> standardCapabilityCommandLog,
            Optional<RewardCommandLogResult> rewardCommandLog,
            Optional<SessionAuthorityCommandLogResult> sessionAuthorityCommandLog,
            Optional<SharedShardAllocationCommandLogResult> sharedShardAllocationCommandLog,
            Optional<SharedShardAllocationStateResult> sharedShardAllocationState,
            Optional<AgonesFleetStateResult> agonesFleetState) {
        if (!config.verifyTraceCorrelation()) {
            return Optional.empty();
        }
        return Optional.of(verifyTraceCorrelationEvidence(
                routeAttemptState,
                routeAuthorityCommandLog,
                routeAuthorityState,
                loginRoutingCommandLog,
                lifecycleTraceState,
                hostRouteCommandLog,
                hostObservationLog,
                standardCapabilityCommandLog,
                rewardCommandLog,
                sessionAuthorityCommandLog,
                sharedShardAllocationCommandLog,
                sharedShardAllocationState,
                agonesFleetState));
    }

    static TraceCorrelationResult verifyTraceCorrelationEvidence(
            Optional<RouteAttemptStateResult> routeAttemptState,
            Optional<RouteAuthorityCommandLogResult> routeAuthorityCommandLog,
            Optional<RouteAuthorityStateResult> routeAuthorityState,
            Optional<LoginRoutingCommandLogResult> loginRoutingCommandLog,
            Optional<LifecycleTraceStateResult> lifecycleTraceState,
            Optional<HostRouteCommandLogResult> hostRouteCommandLog,
            Optional<HostObservationLogResult> hostObservationLog,
            Optional<StandardCapabilityCommandLogResult> standardCapabilityCommandLog,
            Optional<RewardCommandLogResult> rewardCommandLog,
            Optional<SessionAuthorityCommandLogResult> sessionAuthorityCommandLog,
            Optional<SharedShardAllocationCommandLogResult> sharedShardAllocationCommandLog,
            Optional<SharedShardAllocationStateResult> sharedShardAllocationState,
            Optional<AgonesFleetStateResult> agonesFleetState) {
        List<String> missing = new ArrayList<>();
        requirePositiveTraceEvidence(
                missing,
                "RouteAttempt controller state",
                routeAttemptState,
                RouteAttemptStateResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "Route authority command log",
                routeAuthorityCommandLog,
                RouteAuthorityCommandLogResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "Route authority state",
                routeAuthorityState,
                RouteAuthorityStateResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "Velocity login-routing command logs",
                loginRoutingCommandLog,
                LoginRoutingCommandLogResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "lifecycle trace state",
                lifecycleTraceState,
                LifecycleTraceStateResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "host route command logs",
                hostRouteCommandLog,
                HostRouteCommandLogResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "host observation log",
                hostObservationLog,
                HostObservationLogResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "standard capability command logs",
                standardCapabilityCommandLog,
                StandardCapabilityCommandLogResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "reward command logs",
                rewardCommandLog,
                RewardCommandLogResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "Session authority command log",
                sessionAuthorityCommandLog,
                SessionAuthorityCommandLogResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "shared-shard allocation command log",
                sharedShardAllocationCommandLog,
                SharedShardAllocationCommandLogResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "shared-shard allocation state",
                sharedShardAllocationState,
                SharedShardAllocationStateResult::matchedCount);
        requirePositiveTraceEvidence(
                missing,
                "Agones GameServer metadata",
                agonesFleetState,
                AgonesFleetStateResult::allocatedGameServers);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Trace correlation verification requires all traced command, "
                    + "observation, state, and Agones metadata evidence to be present with positive matches. Missing: "
                    + String.join(", ", missing));
        }
        int acceptedLoginMatches = routeAttemptState.orElseThrow().matchedCount();
        List<String> mismatched = new ArrayList<>();
        requireSubjectTraceMatchCount(
                mismatched,
                "Route authority command log",
                acceptedLoginMatches,
                routeAuthorityCommandLog.orElseThrow().matchedCount());
        requireSubjectTraceMatchCount(
                mismatched,
                "Route authority state",
                acceptedLoginMatches,
                routeAuthorityState.orElseThrow().matchedCount());
        requireSubjectTraceMatchCount(
                mismatched,
                "Velocity login-routing command logs",
                acceptedLoginMatches,
                loginRoutingCommandLog.orElseThrow().matchedCount());
        requireSubjectTraceMatchCount(
                mismatched,
                "lifecycle trace state",
                acceptedLoginMatches,
                lifecycleTraceState.orElseThrow().matchedCount());
        requireSubjectTraceMatchCount(
                mismatched,
                "host route command logs",
                acceptedLoginMatches,
                hostRouteCommandLog.orElseThrow().matchedCount());
        requireSubjectTraceMatchCount(
                mismatched,
                "host observation log",
                acceptedLoginMatches,
                hostObservationLog.orElseThrow().matchedCount());
        requireSubjectTraceMatchCount(
                mismatched,
                "standard capability command logs",
                acceptedLoginMatches,
                standardCapabilityCommandLog.orElseThrow().matchedCount());
        requireSubjectTraceMatchCount(
                mismatched,
                "reward command logs",
                acceptedLoginMatches,
                rewardCommandLog.orElseThrow().matchedCount());
        if (!mismatched.isEmpty()) {
            throw new IllegalStateException("Trace correlation verification requires subject-level trace evidence "
                    + "to match accepted login count " + acceptedLoginMatches + ". Mismatched: "
                    + String.join(", ", mismatched));
        }
        int uniqueSessionMatches = sharedShardAllocationState.orElseThrow().matchedCount();
        List<String> sessionMismatched = new ArrayList<>();
        requireUniqueSessionEvidenceMatchCount(
                sessionMismatched,
                "Session authority command log",
                uniqueSessionMatches,
                sessionAuthorityCommandLog.orElseThrow().matchedCount());
        requireUniqueSessionEvidenceMatchCount(
                sessionMismatched,
                "shared-shard allocation command log",
                uniqueSessionMatches,
                sharedShardAllocationCommandLog.orElseThrow().matchedCount());
        requireUniqueSessionEvidenceMatchCount(
                sessionMismatched,
                "Agones GameServer metadata",
                uniqueSessionMatches,
                agonesFleetState.orElseThrow().allocatedGameServers());
        if (!sessionMismatched.isEmpty()) {
            throw new IllegalStateException("Trace correlation verification requires Session-level trace evidence "
                    + "to match shared-shard allocation count " + uniqueSessionMatches + ". Mismatched: "
                    + String.join(", ", sessionMismatched));
        }
        return new TraceCorrelationResult(
                acceptedLoginMatches,
                routeAuthorityCommandLog.orElseThrow().matchedCount(),
                routeAuthorityState.orElseThrow().matchedCount(),
                loginRoutingCommandLog.orElseThrow().matchedCount(),
                lifecycleTraceState.orElseThrow().matchedCount(),
                hostRouteCommandLog.orElseThrow().matchedCount(),
                hostObservationLog.orElseThrow().matchedCount(),
                standardCapabilityCommandLog.orElseThrow().matchedCount(),
                rewardCommandLog.orElseThrow().matchedCount(),
                sessionAuthorityCommandLog.orElseThrow().matchedCount(),
                sharedShardAllocationCommandLog.orElseThrow().matchedCount(),
                sharedShardAllocationState.orElseThrow().matchedCount(),
                agonesFleetState.orElseThrow().allocatedGameServers());
    }

    private static <T> void requirePositiveTraceEvidence(
            List<String> missing,
            String label,
            Optional<T> evidence,
            ToIntFunction<T> matchedCount) {
        if (evidence.isEmpty()) {
            missing.add(label);
            return;
        }
        int count = matchedCount.applyAsInt(evidence.orElseThrow());
        if (count <= 0) {
            missing.add(label + " positive match count");
        }
    }

    private static void requireSubjectTraceMatchCount(
            List<String> mismatched,
            String label,
            int expected,
            int actual) {
        if (actual != expected) {
            mismatched.add(label + " expected " + expected + " got " + actual);
        }
    }

    private static void requireUniqueSessionEvidenceMatchCount(
            List<String> mismatched,
            String label,
            int expected,
            int actual) {
        if (actual != expected) {
            mismatched.add(label + " expected " + expected + " got " + actual);
        }
    }

    private static Optional<ObjectStoreArtifactResult> verifyObjectStoreArtifact(VerificationConfig config)
            throws IOException {
        if (!config.verifyObjectStoreArtifact()) {
            return Optional.empty();
        }
        ArtifactPin artifactPin = objectStoreArtifactPin(config);
        ArtifactObjectAddress address = ArtifactBlobLayout.objectAddress(config.objectStoreBucket(), artifactPin);
        String accessKey = objectStoreSecretValue(config, config.objectStoreAccessKeySecretKey(), "access key");
        String secretKey = objectStoreSecretValue(config, config.objectStoreSecretKeySecretKey(), "secret key");
        try (KubectlPortForward portForward = openObjectStorePortForward(config)) {
            S3ObjectStorageAdapter objectStorage = new S3ObjectStorageAdapter(
                    URI.create("http://127.0.0.1:" + portForward.localPort() + "/"),
                    config.objectStoreRegion(),
                    accessKey,
                    secretKey,
                    config.objectStoreBucket());
            long deadline = System.nanoTime() + config.objectStoreArtifactTimeout().toNanos();
            List<String> failures = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                try {
                    byte[] bytes = objectStorage.read(address)
                            .orElseThrow(() -> new IOException("object address was not found: " + address.value()));
                    return Optional.of(verifyObjectStoreArtifactBytes(config, address, bytes));
                } catch (IOException | RuntimeException exception) {
                    failures.add(failureMessage(exception));
                }
                sleepBeforeRetry(deadline);
            }
            throw new IOException("Timed out waiting for object-store lobby world artifact within "
                    + config.objectStoreArtifactTimeout()
                    + " at " + address.value()
                    + ". Last failures: " + String.join(" | ", failures));
        }
    }

    static ObjectStoreArtifactResult verifyObjectStoreArtifactBytes(
            VerificationConfig config,
            ArtifactObjectAddress address,
            byte[] bytes) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(address, "address");
        byte[] copiedBytes = Objects.requireNonNull(bytes, "bytes").clone();
        ArtifactPin artifactPin = objectStoreArtifactPin(config);
        ArtifactObjectAddress expectedAddress = ArtifactBlobLayout.objectAddress(config.objectStoreBucket(), artifactPin);
        if (!expectedAddress.equals(address)) {
            throw new IllegalStateException("Object-store artifact address mismatch: expected "
                    + expectedAddress.value() + " got " + address.value());
        }
        if (copiedBytes.length == 0) {
            throw new IllegalStateException("Object-store artifact " + address.value() + " was empty");
        }
        String digest = sha256(copiedBytes);
        if (!config.expectedLobbyWorldArtifactDigest().equals(digest)) {
            throw new IllegalStateException("Object-store artifact digest mismatch for " + address.value()
                    + ": expected sha-256:" + config.expectedLobbyWorldArtifactDigest()
                    + " got sha-256:" + digest);
        }
        return new ObjectStoreArtifactResult(address, copiedBytes.length, digest);
    }

    private static ArtifactPin objectStoreArtifactPin(VerificationConfig config) {
        return new ArtifactPin(
                config.expectedLobbyWorldArtifactId(),
                config.expectedLobbyWorldArtifactDigest(),
                config.expectedLobbyWorldArtifactCompatibility());
    }

    private static String objectStoreSecretValue(
            VerificationConfig config,
            String secretKey,
            String label) throws IOException {
        String encoded = kubectl(
                config,
                "object-store " + label,
                "get",
                "secret",
                config.objectStoreSecretName(),
                "-o",
                "jsonpath={.data." + secretKey + "}")
                .orElseThrow(() -> new IOException("Object-store Kubernetes Secret "
                        + config.objectStoreSecretName() + " did not contain " + secretKey));
        try {
            return requireNonBlank(
                    new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8),
                    "object-store " + label);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Object-store Kubernetes Secret "
                    + config.objectStoreSecretName() + " contained invalid base64 for " + secretKey, exception);
        }
    }

    static StandardCapabilityStateResult verifyStandardCapabilityStateOutput(
            String playerProfileOutput,
            String rankOutput,
            String punishmentOutput,
            List<StandardCapabilityStateExpectation> expectations,
            Optional<PunishmentCapabilityStateExpectation> punishmentExpectation) {
        List<PlayerProfileSnapshot> profileRecords = playerProfileStateRecords(playerProfileOutput);
        List<EffectiveRankSnapshot> rankRecords = rankStateRecords(rankOutput);
        List<ActivePunishmentSnapshot> punishmentRecords = punishmentOutput.isBlank()
                ? List.of()
                : punishmentStateRecords(punishmentOutput);
        for (StandardCapabilityStateExpectation expectation : expectations) {
            verifyPlayerProfileStateRecord(expectation, profileRecords);
            verifyRankStateRecord(expectation, rankRecords);
        }
        punishmentExpectation.ifPresent(expectation -> verifyPunishmentStateRecord(expectation, punishmentRecords));
        return new StandardCapabilityStateResult(
                expectations.size(),
                profileRecords.size(),
                rankRecords.size(),
                punishmentRecords.size());
    }

    private static List<PlayerProfileSnapshot> playerProfileStateRecords(String output) {
        return standardCapabilityStatePayloads(output).stream()
                .map(StandardCapabilityAuthorityWireCodec::decodePlayerProfileState)
                .flatMap(state -> state.current().stream())
                .toList();
    }

    private static List<EffectiveRankSnapshot> rankStateRecords(String output) {
        return standardCapabilityStatePayloads(output).stream()
                .map(StandardCapabilityAuthorityWireCodec::decodeRankState)
                .flatMap(state -> state.current().stream())
                .toList();
    }

    private static List<ActivePunishmentSnapshot> punishmentStateRecords(String output) {
        return standardCapabilityStatePayloads(output).stream()
                .map(StandardCapabilityAuthorityWireCodec::decodePunishmentState)
                .flatMap(state -> state.active().stream())
                .toList();
    }

    private static List<String> standardCapabilityStatePayloads(String output) {
        List<String> payloads = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            if (line.startsWith("subjectId=") || line.startsWith("empty=")) {
                if (!current.isEmpty()) {
                    payloads.add(current.toString());
                }
                current = new StringBuilder(line).append('\n');
            } else if (!current.isEmpty() && line.contains("=")) {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            payloads.add(current.toString());
        }
        return payloads;
    }

    private static void verifyPlayerProfileStateRecord(
            StandardCapabilityStateExpectation expectation,
            List<PlayerProfileSnapshot> records) {
        List<String> diagnostics = new ArrayList<>();
        for (PlayerProfileSnapshot record : records) {
            if (!expectation.subjectId().equals(record.subjectId())) {
                continue;
            }
            if (expectation.displayName().equals(record.displayName())) {
                return;
            }
            diagnostics.add("displayName expected " + expectation.displayName()
                    + " got " + record.displayName());
        }
        throw new IllegalStateException("Expected standard player-profile state for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") displayName=" + expectation.displayName()
                + ". Scanned subjects=" + profileSubjects(records)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyRankStateRecord(
            StandardCapabilityStateExpectation expectation,
            List<EffectiveRankSnapshot> records) {
        List<String> diagnostics = new ArrayList<>();
        for (EffectiveRankSnapshot record : records) {
            if (!expectation.subjectId().equals(record.subjectId())) {
                continue;
            }
            List<String> mismatches = new ArrayList<>();
            if (!expectation.rankKey().equals(record.primaryRankKey())) {
                mismatches.add("primaryRankKey expected " + expectation.rankKey()
                        + " got " + record.primaryRankKey());
            }
            String expectedPermission = "rank:" + expectation.rankKey();
            if (!record.permissions().contains(expectedPermission)) {
                mismatches.add("permissions expected to contain " + expectedPermission
                        + " got " + record.permissions());
            }
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected standard rank state for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ") rankKey=" + expectation.rankKey()
                + ". Scanned subjects=" + rankSubjects(records)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyPunishmentStateRecord(
            PunishmentCapabilityStateExpectation expectation,
            List<ActivePunishmentSnapshot> records) {
        List<String> diagnostics = new ArrayList<>();
        for (ActivePunishmentSnapshot record : records) {
            if (!expectation.subjectId().equals(record.subjectId())) {
                continue;
            }
            List<String> mismatches = new ArrayList<>();
            if (!record.activeAt(expectation.activeAt())) {
                mismatches.add("expiresAt expected after " + expectation.activeAt()
                        + " got " + record.expiresAt());
            }
            expectation.reasonContains().ifPresent(expected -> {
                if (!record.reason().contains(expected)) {
                    mismatches.add("reason expected to contain " + expected
                            + " got " + record.reason());
                }
            });
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(String.join(", ", mismatches));
        }
        throw new IllegalStateException("Expected active standard punishment state for "
                + expectation.label() + " (" + expectation.username() + ", Subject "
                + expectation.subjectId().value() + ")"
                + ". Scanned subjects=" + punishmentSubjects(records)
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static String profileSubjects(List<PlayerProfileSnapshot> records) {
        return records.stream()
                .map(record -> record.subjectId().value().toString())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static String rankSubjects(List<EffectiveRankSnapshot> records) {
        return records.stream()
                .map(record -> record.subjectId().value().toString())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static String punishmentSubjects(List<ActivePunishmentSnapshot> records) {
        return records.stream()
                .map(record -> record.subjectId().value().toString())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static Optional<SessionAuthorityStateResult> verifySessionAuthorityState(
            VerificationConfig config,
            List<SessionAuthorityStateExpectation> expectations) throws IOException {
        if (!config.verifySessionAuthorityState()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.sessionAuthorityStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> output = consumeSessionAuthorityState(config);
                if (output.isPresent()) {
                    return Optional.of(verifySessionAuthorityStateOutput(output.orElseThrow(), expectations));
                }
                failures.add("Session authority state topic returned no records");
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for Session authority ACTIVE state within "
                + config.sessionAuthorityStateTimeout()
                + " on " + config.sessionAuthorityStateTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    private static Optional<SessionAuthorityCommandLogResult> verifySessionAuthorityCommandLog(
            VerificationConfig config,
            List<SessionAuthorityCommandExpectation> expectations) throws IOException {
        if (!config.verifySessionAuthorityCommandLog()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.sessionAuthorityStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> output = consumeSessionAuthorityCommandLog(config);
                if (output.isPresent()) {
                    return Optional.of(verifySessionAuthorityCommandLogOutput(output.orElseThrow(), expectations));
                }
                failures.add("Session authority command topic returned no records");
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for Session authority open/activate commands within "
                + config.sessionAuthorityStateTimeout()
                + " on " + config.sessionAuthorityCommandTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static SessionAuthorityCommandLogResult verifySessionAuthorityCommandLogOutput(
            String output,
            List<SessionAuthorityCommandExpectation> expectations) {
        List<AuthorityCommand<SessionCommand>> commands = sessionAuthorityCommands(output);
        for (SessionAuthorityCommandExpectation expectation : expectations) {
            verifySessionAuthorityCommand(expectation, commands, SessionAuthorityWireCodec.OPEN_COMMAND);
            verifySessionAuthorityCommand(expectation, commands, SessionAuthorityWireCodec.ACTIVATE_COMMAND);
        }
        return new SessionAuthorityCommandLogResult(expectations.size(), commands.size());
    }

    private static List<SessionAuthorityCommandExpectation> sessionAuthorityCommandExpectations(
            List<RouteAttemptExpectation> routeAttemptExpectations,
            Instant minimumLeaseExpiresAt) {
        Map<SessionId, SessionAuthorityCommandExpectation> expectations = new LinkedHashMap<>();
        for (RouteAttemptExpectation expectation : routeAttemptExpectations) {
            SessionAuthorityCommandExpectation next =
                    SessionAuthorityCommandExpectation.from(expectation, minimumLeaseExpiresAt);
            SessionAuthorityCommandExpectation previous = expectations.putIfAbsent(next.sessionId(), next);
            if (previous != null) {
                List<String> mismatches = previous.compatibilityMismatches(next);
                if (!mismatches.isEmpty()) {
                    throw new IllegalStateException("Accepted lobby proofs disagreed for Session "
                            + next.sessionId().value()
                            + ": " + String.join(", ", mismatches));
                }
            }
        }
        return List.copyOf(expectations.values());
    }

    private static List<AuthorityCommand<SessionCommand>> sessionAuthorityCommands(String output) {
        return authorityCommandPayloads(output).stream()
                .map(payload -> SessionAuthorityWireCodec.decodeCommand(
                        new ConsumerRecord<>("", 0, 0L, null, payload)))
                .toList();
    }

    private static List<String> authorityCommandPayloads(String output) {
        List<String> payloads = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            if (line.startsWith("commandId=")) {
                if (!current.isEmpty()) {
                    payloads.add(current.toString());
                }
                current = new StringBuilder(line).append('\n');
            } else if (!current.isEmpty() && line.contains("=")) {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            payloads.add(current.toString());
        }
        return payloads;
    }

    private static void verifySessionAuthorityCommand(
            SessionAuthorityCommandExpectation expectation,
            List<AuthorityCommand<SessionCommand>> commands,
            String commandName) {
        List<String> diagnostics = new ArrayList<>();
        for (AuthorityCommand<SessionCommand> command : commands) {
            if (!commandName.equals(command.envelope().commandName().value())) {
                continue;
            }
            if (!expectation.sessionId().equals(command.envelope().payload().sessionId())) {
                continue;
            }
            List<String> mismatches = sessionAuthorityCommandMismatches(expectation, command, commandName);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add(commandName + " command for Session " + expectation.sessionId().value()
                    + " mismatched " + String.join(", ", mismatches));
        }
        String scanned = commands.stream()
                .map(command -> command.envelope().commandName().value()
                        + "/" + command.envelope().payload().sessionId().value())
                .sorted()
                .collect(Collectors.joining(", "));
        throw new IllegalStateException("Expected Session authority " + commandName
                + " command for " + expectation.label()
                + " (" + expectation.username() + ")"
                + " sessionId=" + expectation.sessionId().value()
                + " slotId=" + expectation.slotId().value()
                + " ownerInstanceId=" + expectation.ownerInstanceId().value()
                + " traceId=" + expectation.traceId()
                + ". Scanned commands=" + scanned
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> sessionAuthorityCommandMismatches(
            SessionAuthorityCommandExpectation expectation,
            AuthorityCommand<SessionCommand> command,
            String commandName) {
        List<String> mismatches = new ArrayList<>();
        if (!SessionAuthority.aggregateId(expectation.sessionId()).equals(command.envelope().aggregateId())) {
            mismatches.add("aggregateId expected "
                    + SessionAuthority.aggregateId(expectation.sessionId()).value()
                    + " got " + command.envelope().aggregateId().value());
        }
        if (!SessionAuthorityWireCodec.CONTRACT.equals(command.envelope().contractName().value())) {
            mismatches.add("contractName expected " + SessionAuthorityWireCodec.CONTRACT
                    + " got " + command.envelope().contractName().value());
        }
        if (!expectation.traceId().equals(command.envelope().traceEnvelope().traceId())) {
            mismatches.add("traceId expected " + expectation.traceId()
                    + " got " + command.envelope().traceEnvelope().traceId());
        }
        SessionCommand payload = command.envelope().payload();
        if (SessionAuthorityWireCodec.OPEN_COMMAND.equals(commandName)) {
            if (payload instanceof OpenSession open) {
                appendOpenSessionMismatches(expectation, open, mismatches);
            } else {
                mismatches.add("payload expected OpenSession got " + payload.getClass().getSimpleName());
            }
        } else if (SessionAuthorityWireCodec.ACTIVATE_COMMAND.equals(commandName)) {
            if (payload instanceof ActivateSession activate) {
                appendActivateSessionMismatches(expectation, activate, mismatches);
            } else {
                mismatches.add("payload expected ActivateSession got " + payload.getClass().getSimpleName());
            }
        } else {
            mismatches.add("unsupported commandName " + commandName);
        }
        return mismatches;
    }

    private static void appendOpenSessionMismatches(
            SessionAuthorityCommandExpectation expectation,
            OpenSession open,
            List<String> mismatches) {
        if (!expectation.slotId().equals(open.slotId())) {
            mismatches.add("slotId expected " + expectation.slotId().value()
                    + " got " + open.slotId().value());
        }
        if (!expectation.ownerInstanceId().equals(open.ownerInstanceId())) {
            mismatches.add("ownerInstanceId expected " + expectation.ownerInstanceId().value()
                    + " got " + open.ownerInstanceId().value());
        }
        if (!expectation.resolvedManifestId().equals(open.resolvedManifestId())) {
            mismatches.add("resolvedManifestId expected " + expectation.resolvedManifestId().value()
                    + " got " + open.resolvedManifestId().value());
        }
        if (open.leaseExpiresAt().isBefore(expectation.minimumLeaseExpiresAt())) {
            mismatches.add("leaseExpiresAt expected at or after " + expectation.minimumLeaseExpiresAt()
                    + " got " + open.leaseExpiresAt());
        }
    }

    private static void appendActivateSessionMismatches(
            SessionAuthorityCommandExpectation expectation,
            ActivateSession activate,
            List<String> mismatches) {
        if (activate.leaseExpiresAt().isBefore(expectation.minimumLeaseExpiresAt())) {
            mismatches.add("leaseExpiresAt expected at or after " + expectation.minimumLeaseExpiresAt()
                    + " got " + activate.leaseExpiresAt());
        }
    }

    static SessionAuthorityStateResult verifySessionAuthorityStateOutput(
            String output,
            List<SessionAuthorityStateExpectation> expectations) {
        List<SessionAuthorityStateRecord> records = sessionAuthorityRecords(output);
        for (SessionAuthorityStateExpectation expectation : expectations) {
            verifySessionAuthorityStateRecord(expectation, records);
        }
        verifyOneActiveSessionPerPaperInstance(expectations, records);
        return new SessionAuthorityStateResult(expectations.size(), records.size());
    }

    private static List<SessionAuthorityStateExpectation> sessionAuthorityStateExpectations(
            List<RouteAttemptExpectation> routeAttemptExpectations,
            Instant minimumLeaseExpiresAt) {
        Map<SessionId, SessionAuthorityStateExpectation> expectations = new LinkedHashMap<>();
        for (RouteAttemptExpectation expectation : routeAttemptExpectations) {
            SessionAuthorityStateExpectation next =
                    SessionAuthorityStateExpectation.from(expectation, minimumLeaseExpiresAt);
            SessionAuthorityStateExpectation previous = expectations.putIfAbsent(next.sessionId(), next);
            if (previous != null) {
                List<String> mismatches = previous.compatibilityMismatches(next);
                if (!mismatches.isEmpty()) {
                    throw new IllegalStateException("Accepted lobby proofs disagreed for Session "
                            + next.sessionId().value()
                            + ": " + String.join(", ", mismatches));
                }
            }
        }
        return List.copyOf(expectations.values());
    }

    private static List<SessionAuthorityStateRecord> sessionAuthorityRecords(String output) {
        return sessionAuthorityPayloads(output).stream()
                .map(LobbyClusterE2eVerifier::sessionAuthorityRecord)
                .toList();
    }

    private static List<String> sessionAuthorityPayloads(String output) {
        List<String> payloads = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            if (line.startsWith("sessionId=")) {
                if (!current.isEmpty()) {
                    payloads.add(current.toString());
                }
                current = new StringBuilder(line).append('\n');
            } else if (!current.isEmpty() && line.contains("=")) {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            payloads.add(current.toString());
        }
        return payloads;
    }

    private static SessionAuthorityStateRecord sessionAuthorityRecord(String payload) {
        Map<String, String> fields = wireFields(payload, "Session authority state");
        return new SessionAuthorityStateRecord(
                new SessionId(requiredField(fields, "sessionId", "Session authority state")),
                new SlotId(requiredField(fields, "slotId", "Session authority state")),
                new InstanceId(requiredField(fields, "ownerInstanceId", "Session authority state")),
                new ResolvedManifestId(requiredField(fields, "resolvedManifestId", "Session authority state")),
                SessionLifecycleStatus.valueOf(requiredField(fields, "status", "Session authority state")),
                instantField(fields, "openedAt", "Session authority state"),
                instantField(fields, "leaseExpiresAt", "Session authority state"),
                optionalInstantField(fields, "activatedAt"));
    }

    private static void verifySessionAuthorityStateRecord(
            SessionAuthorityStateExpectation expectation,
            List<SessionAuthorityStateRecord> records) {
        List<String> diagnostics = new ArrayList<>();
        for (SessionAuthorityStateRecord record : records) {
            if (!expectation.sessionId().equals(record.sessionId())) {
                continue;
            }
            List<String> mismatches = sessionAuthorityStateMismatches(expectation, record);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("Session " + expectation.sessionId().value()
                    + " mismatched " + String.join(", ", mismatches));
        }
        Set<SessionId> scannedSessionIds = records.stream()
                .map(SessionAuthorityStateRecord::sessionId)
                .collect(Collectors.toSet());
        throw new IllegalStateException("Expected Session authority ACTIVE state for "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " sessionId=" + expectation.sessionId().value()
                + " slotId=" + expectation.slotId().value()
                + " ownerInstanceId=" + expectation.ownerInstanceId().value()
                + ". Scanned sessionIds=" + scannedSessionIds.stream()
                .map(SessionId::value)
                .sorted()
                .collect(Collectors.joining(", "))
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static void verifyOneActiveSessionPerPaperInstance(
            List<SessionAuthorityStateExpectation> expectations,
            List<SessionAuthorityStateRecord> records) {
        Map<InstanceId, SessionAuthorityStateExpectation> expectedByInstance = new LinkedHashMap<>();
        for (SessionAuthorityStateExpectation expectation : expectations) {
            SessionAuthorityStateExpectation previous =
                    expectedByInstance.putIfAbsent(expectation.ownerInstanceId(), expectation);
            if (previous != null && !previous.sessionId().equals(expectation.sessionId())) {
                throw new IllegalStateException("Accepted lobby proofs require Paper Instance "
                        + expectation.ownerInstanceId().value()
                        + " to host exactly one Session, but expectations included "
                        + previous.sessionId().value()
                        + " and "
                        + expectation.sessionId().value());
            }
        }

        Map<InstanceId, Set<SessionId>> freshActiveSessionsByInstance = new LinkedHashMap<>();
        for (SessionAuthorityStateRecord record : records) {
            SessionAuthorityStateExpectation expectation = expectedByInstance.get(record.ownerInstanceId());
            if (expectation == null
                    || record.status() != SessionLifecycleStatus.ACTIVE
                    || record.leaseExpiresAt().isBefore(expectation.minimumLeaseExpiresAt())) {
                continue;
            }
            freshActiveSessionsByInstance
                    .computeIfAbsent(record.ownerInstanceId(), ignored -> new LinkedHashSet<>())
                    .add(record.sessionId());
        }

        List<String> conflicts = freshActiveSessionsByInstance.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey().value() + " sessions="
                        + entry.getValue().stream()
                                .map(SessionId::value)
                                .sorted()
                                .collect(Collectors.joining(", ")))
                .toList();
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("Session authority state requires each proved Paper Instance to host "
                    + "exactly one fresh ACTIVE Session. Conflicts: " + String.join(" | ", conflicts));
        }
    }

    private static List<String> sessionAuthorityStateMismatches(
            SessionAuthorityStateExpectation expectation,
            SessionAuthorityStateRecord record) {
        List<String> mismatches = new ArrayList<>();
        if (record.status() != SessionLifecycleStatus.ACTIVE) {
            mismatches.add("status expected ACTIVE got " + record.status());
        }
        if (!expectation.slotId().equals(record.slotId())) {
            mismatches.add("slotId expected " + expectation.slotId().value()
                    + " got " + record.slotId().value());
        }
        if (!expectation.ownerInstanceId().equals(record.ownerInstanceId())) {
            mismatches.add("ownerInstanceId expected " + expectation.ownerInstanceId().value()
                    + " got " + record.ownerInstanceId().value());
        }
        if (!expectation.resolvedManifestId().equals(record.resolvedManifestId())) {
            mismatches.add("resolvedManifestId expected " + expectation.resolvedManifestId().value()
                    + " got " + record.resolvedManifestId().value());
        }
        if (record.activatedAt().isEmpty()) {
            mismatches.add("activatedAt is missing");
        }
        if (record.leaseExpiresAt().isBefore(expectation.minimumLeaseExpiresAt())) {
            mismatches.add("leaseExpiresAt expected at or after " + expectation.minimumLeaseExpiresAt()
                    + " got " + record.leaseExpiresAt());
        }
        return mismatches;
    }

    private static Optional<SharedShardAllocationCommandLogResult> verifySharedShardAllocationCommandLog(
            VerificationConfig config,
            List<SharedShardAllocationStateExpectation> expectations) throws IOException {
        if (!config.verifySharedShardAllocationCommandLog()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.sharedShardAllocationStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> output = consumeSharedShardAllocationCommandLog(config);
                if (output.isPresent()) {
                    return Optional.of(verifySharedShardAllocationCommandLogOutput(
                            output.orElseThrow(),
                            config,
                            expectations));
                }
                failures.add("shared-shard allocation command topic returned no records");
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for controller shared-shard allocation command log within "
                + config.sharedShardAllocationStateTimeout()
                + " on " + config.sharedShardAllocationCommandTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static SharedShardAllocationCommandLogResult verifySharedShardAllocationCommandLogOutput(
            String output,
            VerificationConfig config,
            List<SharedShardAllocationStateExpectation> expectations) {
        List<SharedShardAllocationRequest> requests = sharedShardAllocationCommandRequests(output);
        for (SharedShardAllocationStateExpectation expectation : expectations) {
            verifySharedShardAllocationCommand(config, expectation, requests);
        }
        return new SharedShardAllocationCommandLogResult(expectations.size(), requests.size());
    }

    private static Optional<SharedShardAllocationStateResult> verifySharedShardAllocationState(
            VerificationConfig config,
            List<SharedShardAllocationStateExpectation> expectations) throws IOException {
        if (!config.verifySharedShardAllocationState()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.sharedShardAllocationStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> output = consumeSharedShardAllocationState(config);
                if (output.isPresent()) {
                    return Optional.of(verifySharedShardAllocationStateOutput(
                            output.orElseThrow(),
                            config,
                            expectations));
                }
                failures.add("shared-shard allocation state topic returned no records");
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for controller shared-shard allocation state within "
                + config.sharedShardAllocationStateTimeout()
                + " on " + config.sharedShardAllocationStateTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static SharedShardAllocationStateResult verifySharedShardAllocationStateOutput(
            String output,
            VerificationConfig config,
            List<SharedShardAllocationStateExpectation> expectations) {
        List<ExternalControllerWorkerCatalog.StoredSharedShardAllocation> records =
                sharedShardAllocationRecords(output);
        for (SharedShardAllocationStateExpectation expectation : expectations) {
            verifySharedShardAllocationStateRecord(config, expectation, records);
        }
        return new SharedShardAllocationStateResult(expectations.size(), records.size());
    }

    private static List<SharedShardAllocationStateExpectation> sharedShardAllocationStateExpectations(
            List<RouteAttemptExpectation> routeAttemptExpectations) {
        Map<SessionId, SharedShardAllocationStateExpectation> expectations = new LinkedHashMap<>();
        for (RouteAttemptExpectation expectation : routeAttemptExpectations) {
            SharedShardAllocationStateExpectation next =
                    SharedShardAllocationStateExpectation.from(expectation);
            SharedShardAllocationStateExpectation previous = expectations.putIfAbsent(next.sessionId(), next);
            if (previous != null) {
                List<String> mismatches = previous.compatibilityMismatches(next);
                if (!mismatches.isEmpty()) {
                    throw new IllegalStateException("Accepted lobby proofs disagreed for allocation Session "
                            + next.sessionId().value()
                            + ": " + String.join(", ", mismatches));
                }
            }
        }
        return List.copyOf(expectations.values());
    }

    private static List<SharedShardAllocationRequest> sharedShardAllocationCommandRequests(String output) {
        return sharedShardAllocationCommandPayloads(output).stream()
                .map(payload -> ControlCommandWireCodec.decodeSharedShardAllocationRequest(
                        new ConsumerRecord<>("", 0, 0L, null, payload)))
                .toList();
    }

    private static List<String> sharedShardAllocationCommandPayloads(String output) {
        List<String> payloads = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            if (line.startsWith("experienceId=")) {
                if (!current.isEmpty()) {
                    payloads.add(current.toString());
                }
                current = new StringBuilder(line).append('\n');
            } else if (!current.isEmpty() && line.contains("=")) {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            payloads.add(current.toString());
        }
        return payloads;
    }

    private static void verifySharedShardAllocationCommand(
            VerificationConfig config,
            SharedShardAllocationStateExpectation expectation,
            List<SharedShardAllocationRequest> requests) {
        List<String> diagnostics = new ArrayList<>();
        for (SharedShardAllocationRequest request : requests) {
            if (!expectation.sessionId().equals(request.sessionId())) {
                continue;
            }
            List<String> mismatches = sharedShardAllocationCommandMismatches(config, expectation, request);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("Session " + expectation.sessionId().value()
                    + " mismatched " + String.join(", ", mismatches));
        }
        Set<SessionId> scannedSessionIds = requests.stream()
                .map(SharedShardAllocationRequest::sessionId)
                .collect(Collectors.toSet());
        throw new IllegalStateException("Expected controller shared-shard allocation command for "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " sessionId=" + expectation.sessionId().value()
                + " resolvedManifestId=" + expectation.resolvedManifestId().value()
                + ". Scanned sessionIds=" + scannedSessionIds.stream()
                .map(SessionId::value)
                .sorted()
                .collect(Collectors.joining(", "))
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> sharedShardAllocationCommandMismatches(
            VerificationConfig config,
            SharedShardAllocationStateExpectation expectation,
            SharedShardAllocationRequest request) {
        List<String> mismatches = new ArrayList<>();
        if (!config.expectedExperienceId().equals(request.experienceId())) {
            mismatches.add("experienceId expected " + config.expectedExperienceId().value()
                    + " got " + request.experienceId().value());
        }
        if (!config.expectedPoolId().equals(request.poolId())) {
            mismatches.add("poolId expected " + config.expectedPoolId().value()
                    + " got " + request.poolId().value());
        }
        if (!expectation.resolvedManifestId().equals(request.resolvedManifestId())) {
            mismatches.add("resolvedManifestId expected " + expectation.resolvedManifestId().value()
                    + " got " + request.resolvedManifestId().value());
        }
        String originService = request.traceEnvelope().originService();
        if (!Set.of(
                LobbySharedShardAllocationProvisioner.ORIGIN_SERVICE,
                VELOCITY_LOGIN_ORIGIN_SERVICE).contains(originService)) {
            mismatches.add("originService expected "
                    + LobbySharedShardAllocationProvisioner.ORIGIN_SERVICE
                    + " or " + VELOCITY_LOGIN_ORIGIN_SERVICE
                    + " got " + originService);
        } else if (LobbySharedShardAllocationProvisioner.ORIGIN_SERVICE.equals(originService)) {
            if (!LobbySharedShardAllocationProvisioner.DEFAULT_TRACE_ID.equals(request.traceEnvelope().traceId())) {
                mismatches.add("traceId expected "
                        + LobbySharedShardAllocationProvisioner.DEFAULT_TRACE_ID
                        + " got " + request.traceEnvelope().traceId());
            }
            if (!LobbySharedShardAllocationProvisioner.DEFAULT_SPAN_ID.equals(request.traceEnvelope().spanId())) {
                mismatches.add("spanId expected "
                        + LobbySharedShardAllocationProvisioner.DEFAULT_SPAN_ID
                        + " got " + request.traceEnvelope().spanId());
            }
        } else {
            if (!request.traceEnvelope().traceId().startsWith("trace-velocity-login-")) {
                mismatches.add("traceId expected velocity login trace got "
                        + request.traceEnvelope().traceId());
            }
            if (!request.traceEnvelope().spanId().startsWith("span-shared-shard-allocation-")) {
                mismatches.add("spanId expected shared-shard allocation child span got "
                        + request.traceEnvelope().spanId());
            }
            if (request.traceEnvelope().parentSpanId().isEmpty()) {
                mismatches.add("parentSpanId is missing for velocity-derived allocation command");
            }
        }
        return mismatches;
    }

    private static List<ExternalControllerWorkerCatalog.StoredSharedShardAllocation> sharedShardAllocationRecords(
            String output) {
        return routeAttemptPayloads(output).stream()
                .filter(payload -> ControllerStateWireCodec.isRecordType(
                        payload,
                        ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION))
                .map(ControllerStateWireCodec::decodeSharedShardAllocation)
                .toList();
    }

    private static void verifySharedShardAllocationStateRecord(
            VerificationConfig config,
            SharedShardAllocationStateExpectation expectation,
            List<ExternalControllerWorkerCatalog.StoredSharedShardAllocation> records) {
        List<String> diagnostics = new ArrayList<>();
        for (ExternalControllerWorkerCatalog.StoredSharedShardAllocation record : records) {
            if (!expectation.sessionId().equals(record.request().sessionId())) {
                continue;
            }
            List<String> mismatches = sharedShardAllocationStateMismatches(config, expectation, record);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("Session " + expectation.sessionId().value()
                    + " mismatched " + String.join(", ", mismatches));
        }
        Set<SessionId> scannedSessionIds = records.stream()
                .map(record -> record.request().sessionId())
                .collect(Collectors.toSet());
        throw new IllegalStateException("Expected controller shared-shard allocation state for "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " sessionId=" + expectation.sessionId().value()
                + " slotId=" + expectation.slotId().value()
                + " instanceId=" + expectation.instanceId().value()
                + ". Scanned sessionIds=" + scannedSessionIds.stream()
                .map(SessionId::value)
                .sorted()
                .collect(Collectors.joining(", "))
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> sharedShardAllocationStateMismatches(
            VerificationConfig config,
            SharedShardAllocationStateExpectation expectation,
            ExternalControllerWorkerCatalog.StoredSharedShardAllocation record) {
        List<String> mismatches = new ArrayList<>();
        if (!config.expectedExperienceId().equals(record.request().experienceId())) {
            mismatches.add("experienceId expected " + config.expectedExperienceId().value()
                    + " got " + record.request().experienceId().value());
        }
        if (!config.expectedPoolId().equals(record.request().poolId())) {
            mismatches.add("poolId expected " + config.expectedPoolId().value()
                    + " got " + record.request().poolId().value());
        }
        if (!expectation.resolvedManifestId().equals(record.request().resolvedManifestId())) {
            mismatches.add("request resolvedManifestId expected " + expectation.resolvedManifestId().value()
                    + " got " + record.request().resolvedManifestId().value());
        }
        if (!expectation.sessionId().equals(record.claim().sessionId())) {
            mismatches.add("claim sessionId expected " + expectation.sessionId().value()
                    + " got " + record.claim().sessionId().value());
        }
        if (!expectation.slotId().equals(record.claim().slotId())) {
            mismatches.add("claim slotId expected " + expectation.slotId().value()
                    + " got " + record.claim().slotId().value());
        }
        if (!expectation.instanceId().equals(record.claim().instanceIdentity().instanceId())) {
            mismatches.add("claim instanceId expected " + expectation.instanceId().value()
                    + " got " + record.claim().instanceIdentity().instanceId().value());
        }
        if (!config.expectedPoolId().equals(record.claim().instanceIdentity().poolId())) {
            mismatches.add("claim poolId expected " + config.expectedPoolId().value()
                    + " got " + record.claim().instanceIdentity().poolId().value());
        }
        if (!"paper".equals(record.claim().instanceIdentity().instanceKind())) {
            mismatches.add("claim instanceKind expected paper got "
                    + record.claim().instanceIdentity().instanceKind());
        }
        if (!expectation.resolvedManifestId().equals(record.claim().resolvedManifestId())) {
            mismatches.add("claim resolvedManifestId expected " + expectation.resolvedManifestId().value()
                    + " got " + record.claim().resolvedManifestId().value());
        }
        return mismatches;
    }

    private static Optional<HostObservationLogResult> verifyHostObservationLog(
            VerificationConfig config,
            List<HostObservationExpectation> expectations,
            List<DeniedHostObservationExpectation> deniedExpectations) throws IOException {
        if (!config.verifyHostObservationLog()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + config.routeAttemptStateTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                Optional<String> output = consumeHostObservationLog(config);
                if (output.isPresent()) {
                    return Optional.of(verifyHostObservationLogOutput(
                            output.orElseThrow(),
                            expectations,
                            deniedExpectations));
                }
                failures.add("host observation topic returned no session-attached observations");
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
            }
            sleepBeforeRetry(deadline);
        }
        throw new IOException("Timed out waiting for Paper host session-attached observations within "
                + config.routeAttemptStateTimeout()
                + " on " + config.hostObservationTopic()
                + ". Last failures: " + String.join(" | ", failures));
    }

    static HostObservationLogResult verifyHostObservationLogOutput(
            String output,
            List<HostObservationExpectation> expectations,
            List<DeniedHostObservationExpectation> deniedExpectations) {
        List<HostObservation> observations = hostObservations(output);
        for (HostObservationExpectation expectation : expectations) {
            verifyHostObservation(expectation, observations);
        }
        for (DeniedHostObservationExpectation expectation : deniedExpectations) {
            verifyDeniedHostObservationAbsent(expectation, observations);
        }
        return new HostObservationLogResult(expectations.size(), observations.size());
    }

    private static List<HostObservationExpectation> hostObservationExpectations(
            List<RouteAttemptExpectation> routeAttemptExpectations,
            PoolId poolId,
            Instant minimumObservedAt) {
        return routeAttemptExpectations.stream()
                .map(expectation -> HostObservationExpectation.from(expectation, poolId, minimumObservedAt))
                .toList();
    }

    private static List<DeniedHostObservationExpectation> deniedHostObservationExpectations(
            List<DeniedRouteAttemptExpectation> deniedRouteAttemptExpectations,
            Instant minimumObservedAt) {
        return deniedRouteAttemptExpectations.stream()
                .map(expectation -> DeniedHostObservationExpectation.from(expectation, minimumObservedAt))
                .toList();
    }

    private static List<HostObservation> hostObservations(String output) {
        return hostObservationPayloads(output).stream()
                .map(HostObservationWireCodec::decode)
                .filter(observation -> HostObservationTypes.SESSION_ATTACHED.equals(observation.observationType()))
                .toList();
    }

    private static List<String> hostObservationPayloads(String output) {
        List<String> payloads = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            if (line.startsWith("instanceId=")) {
                if (!current.isEmpty()) {
                    payloads.add(current.toString());
                }
                current = new StringBuilder(line).append('\n');
            } else if (!current.isEmpty() && line.contains("=")) {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            payloads.add(current.toString());
        }
        return payloads;
    }

    private static void verifyHostObservation(
            HostObservationExpectation expectation,
            List<HostObservation> observations) {
        List<String> diagnostics = new ArrayList<>();
        for (HostObservation observation : observations) {
            if (!expectation.subjectId().value().toString().equals(observation.attributes().get("subjectId"))) {
                continue;
            }
            List<String> mismatches = hostObservationMismatches(expectation, observation);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("subject " + expectation.subjectId().value()
                    + " mismatched " + String.join(", ", mismatches));
        }
        String scanned = observations.stream()
                .map(observation -> observation.observationType()
                        + "/subject=" + observation.attributes().getOrDefault("subjectId", "<missing>")
                        + "/route=" + observation.attributes().getOrDefault("routeId", "<missing>"))
                .sorted()
                .collect(Collectors.joining(", "));
        throw new IllegalStateException("Expected Paper host session-attached observation for "
                + expectation.label()
                + " (" + expectation.username() + ")"
                + " subjectId=" + expectation.subjectId().value()
                + " routeId=" + expectation.routeId().value()
                + " sessionId=" + expectation.sessionId().value()
                + " instanceId=" + expectation.instanceId().value()
                + ". Scanned observations=" + scanned
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> hostObservationMismatches(
            HostObservationExpectation expectation,
            HostObservation observation) {
        List<String> mismatches = new ArrayList<>();
        if (!expectation.instanceId().equals(observation.instanceId())) {
            mismatches.add("instanceId expected " + expectation.instanceId().value()
                    + " got " + observation.instanceId().value());
        }
        appendAttributeMismatch(mismatches, observation, "instanceKind", "paper");
        appendAttributeMismatch(mismatches, observation, "poolId", expectation.poolId().value());
        appendAttributeMismatch(mismatches, observation, "routeId", expectation.routeId().value());
        appendAttributeMismatch(mismatches, observation, "sessionId", expectation.sessionId().value());
        if (!expectation.traceId().equals(observation.traceEnvelope().traceId())) {
            mismatches.add("traceId expected " + expectation.traceId()
                    + " got " + observation.traceEnvelope().traceId());
        }
        if (observation.observedAt().isBefore(expectation.minimumObservedAt())) {
            mismatches.add("observedAt expected at or after " + expectation.minimumObservedAt()
                    + " got " + observation.observedAt());
        }
        return mismatches;
    }

    private static void verifyDeniedHostObservationAbsent(
            DeniedHostObservationExpectation expectation,
            List<HostObservation> observations) {
        List<String> found = observations.stream()
                .filter(observation -> expectation.subjectId().value().toString()
                        .equals(observation.attributes().get("subjectId")))
                .filter(observation -> !observation.observedAt().isBefore(expectation.minimumObservedAt()))
                .map(observation -> observation.observationType()
                        + "/instanceId=" + observation.instanceId().value()
                        + "/routeId=" + observation.attributes().getOrDefault("routeId", "<missing>")
                        + "/observedAt=" + observation.observedAt())
                .sorted()
                .toList();
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Expected Paper host session-attached observations to omit denied "
                + expectation.label()
                + " (" + expectation.username() + ") subjectId=" + expectation.subjectId().value()
                + " observed at or after " + expectation.minimumObservedAt()
                + ", found " + String.join(", ", found));
    }

    private static void appendAttributeMismatch(
            List<String> mismatches,
            HostObservation observation,
            String key,
            String expected) {
        String actual = observation.attributes().get(key);
        if (!expected.equals(actual)) {
            mismatches.add("attribute." + key + " expected " + expected
                    + " got " + (actual == null ? "<missing>" : actual));
        }
    }

    static RouteAttemptStateResult verifyRouteAttemptStateOutput(
            String output,
            List<RouteAttemptExpectation> expectations) {
        return verifyRouteAttemptStateOutput(output, expectations, List.of());
    }

    static RouteAttemptStateResult verifyRouteAttemptStateOutput(
            String output,
            List<RouteAttemptExpectation> expectations,
            List<DeniedRouteAttemptExpectation> deniedExpectations) {
        List<RouteAttemptControlRecord> records = routeAttemptRecords(output);
        for (RouteAttemptExpectation expectation : expectations) {
            verifyRouteAttemptStateRecord(expectation, records);
        }
        for (DeniedRouteAttemptExpectation expectation : deniedExpectations) {
            verifyDeniedRouteAttemptStateRecordAbsent(expectation, records);
        }
        return new RouteAttemptStateResult(expectations.size(), records.size());
    }

    private static List<RouteAttemptControlRecord> routeAttemptRecords(String output) {
        return routeAttemptPayloads(output).stream()
                .filter(payload -> ControllerStateWireCodec.isRecordType(payload, ControllerWorkerCatalog.ROUTE_ATTEMPT))
                .map(ControllerStateWireCodec::decodeRouteAttempt)
                .toList();
    }

    private static List<String> routeAttemptPayloads(String output) {
        List<String> payloads = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            if (line.startsWith("recordType=")) {
                if (!current.isEmpty()) {
                    payloads.add(current.toString());
                }
                current = new StringBuilder(line).append('\n');
            } else if (!current.isEmpty() && line.contains("=")) {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            payloads.add(current.toString());
        }
        return payloads;
    }

    private static void verifyRouteAttemptStateRecord(
            RouteAttemptExpectation expectation,
            List<RouteAttemptControlRecord> records) {
        List<String> diagnostics = new ArrayList<>();
        for (RouteAttemptControlRecord record : records) {
            Optional<RouteAttemptSnapshot> snapshot = record.snapshot();
            if (snapshot.isEmpty() || !expectation.routeId().equals(snapshot.orElseThrow().routeId())) {
                continue;
            }
            RouteAttemptSnapshot value = snapshot.orElseThrow();
            if (value.status() != RouteAttemptLifecycleStatus.ACKED) {
                diagnostics.add("route " + expectation.routeId().value()
                        + " was present with status " + value.status());
                continue;
            }
            List<String> mismatches = routeAttemptMismatches(expectation, value);
            if (mismatches.isEmpty()) {
                return;
            }
            diagnostics.add("ACKED route " + expectation.routeId().value()
                    + " mismatched " + String.join(", ", mismatches));
        }
        Set<RouteId> scannedRouteIds = records.stream()
                .flatMap(record -> record.snapshot().stream())
                .map(RouteAttemptSnapshot::routeId)
                .collect(Collectors.toSet());
        throw new IllegalStateException("Expected controller route-attempt ACK state for "
                + expectation.label()
                + " (" + expectation.username() + ") routeId=" + expectation.routeId().value()
                + " sessionId=" + expectation.sessionId().value()
                + " slotId=" + expectation.slotId().value()
                + ". Scanned routeIds=" + scannedRouteIds.stream()
                .map(RouteId::value)
                .sorted()
                .collect(Collectors.joining(", "))
                + ". Diagnostics: " + String.join(" | ", diagnostics));
    }

    private static List<String> routeAttemptMismatches(
            RouteAttemptExpectation expectation,
            RouteAttemptSnapshot snapshot) {
        List<String> mismatches = new ArrayList<>();
        if (!snapshot.subjectIds().contains(expectation.subjectId())) {
            mismatches.add("subjectIds does not contain " + expectation.subjectId().value());
        }
        if (!expectation.sessionId().equals(snapshot.sessionId())) {
            mismatches.add("sessionId expected " + expectation.sessionId().value()
                    + " got " + snapshot.sessionId().value());
        }
        if (!expectation.slotId().equals(snapshot.allocationSlotId())) {
            mismatches.add("allocationSlotId expected " + expectation.slotId().value()
                    + " got " + snapshot.allocationSlotId().value());
        }
        if (!expectation.targetInstanceId().equals(snapshot.targetInstanceId())) {
            mismatches.add("targetInstanceId expected " + expectation.targetInstanceId().value()
                    + " got " + snapshot.targetInstanceId().value());
        }
        if (!expectation.targetResolvedManifestId().equals(snapshot.targetResolvedManifestId())) {
            mismatches.add("targetResolvedManifestId expected " + expectation.targetResolvedManifestId().value()
                    + " got " + snapshot.targetResolvedManifestId().value());
        }
        if (!expectation.traceId().equals(snapshot.traceEnvelope().traceId())) {
            mismatches.add("traceId expected " + expectation.traceId()
                    + " got " + snapshot.traceEnvelope().traceId());
        }
        if (snapshot.updatedAt().isBefore(expectation.minimumUpdatedAt())) {
            mismatches.add("updatedAt expected at or after " + expectation.minimumUpdatedAt()
                    + " got " + snapshot.updatedAt());
        }
        return mismatches;
    }

    private static void verifyDeniedRouteAttemptStateRecordAbsent(
            DeniedRouteAttemptExpectation expectation,
            List<RouteAttemptControlRecord> records) {
        List<RouteAttemptSnapshot> matches = records.stream()
                .flatMap(record -> record.snapshot().stream())
                .filter(snapshot -> snapshot.subjectIds().contains(expectation.subjectId()))
                .filter(snapshot -> !snapshot.updatedAt().isBefore(expectation.minimumUpdatedAt()))
                .toList();
        if (matches.isEmpty()) {
            return;
        }
        String found = matches.stream()
                .map(snapshot -> snapshot.routeId().value() + " status=" + snapshot.status())
                .sorted()
                .collect(Collectors.joining(", "));
        throw new IllegalStateException("Expected controller route-attempt state to omit denied "
                + expectation.label()
                + " (" + expectation.username() + ") subjectId=" + expectation.subjectId().value()
                + " updatedAt at or after " + expectation.minimumUpdatedAt()
                + ", found " + found);
    }

    private static Optional<AgonesFleetStateResult> verifyAgonesFleetState(
            VerificationConfig config,
            List<RouteAttemptExpectation> expectations) throws IOException {
        if (!config.verifyAgonesFleetState()) {
            return Optional.empty();
        }
        Optional<String> allocatedReplicas = kubectl(config, "Agones Fleet allocated replicas",
                "get", "fleet", config.agonesFleetName(), "-o", "jsonpath={.status.allocatedReplicas}");
        String value = allocatedReplicas.orElseThrow(() -> new IOException("Agones Fleet "
                + config.namespace() + "/" + config.agonesFleetName()
                + " did not report status.allocatedReplicas"));
        int replicas = parseNonNegativeInteger(value, "Agones Fleet allocated replicas");
        if (replicas != config.expectedAgonesAllocatedReplicas()) {
            throw new IOException("Expected Agones Fleet " + config.namespace() + "/"
                    + config.agonesFleetName() + " allocatedReplicas="
                    + config.expectedAgonesAllocatedReplicas() + ", got " + replicas);
        }
        Optional<String> gameServers = kubectl(config, "Agones Fleet GameServer metadata",
                "get", "gameservers", "-l", "agones.dev/fleet=" + config.agonesFleetName(),
                "-o", "jsonpath={range .items[*]}{.metadata.name}{\"|\"}{.status.state}{\"|\"}"
                        + "{.metadata.labels.sh\\.harold\\.fulcrum/pool-id}{\"|\"}"
                        + "{.metadata.annotations.sh\\.harold\\.fulcrum/session-id}{\"|\"}"
                        + "{.metadata.annotations.sh\\.harold\\.fulcrum/slot-id}{\"|\"}"
                        + "{.metadata.annotations.sh\\.harold\\.fulcrum/resolved-manifest-id}{\"|\"}"
                        + "{.metadata.annotations.sh\\.harold\\.fulcrum/trace-id}{\"\\n\"}{end}");
        AgonesGameServerStateResult gameServerState = verifyAgonesGameServerStateOutput(
                gameServers.orElse(""),
                expectations,
                config.expectedAgonesAllocatedReplicas(),
                config.expectedPoolId());
        return Optional.of(new AgonesFleetStateResult(
                replicas,
                gameServerState.allocatedGameServers(),
                gameServerState.proofInstanceIds()));
    }

    static AgonesGameServerStateResult verifyAgonesGameServerStateOutput(
            String output,
            List<RouteAttemptExpectation> expectations,
            int expectedAllocatedGameServers,
            PoolId expectedPoolId) {
        if (expectedAllocatedGameServers < 0) {
            throw new IllegalArgumentException("expectedAllocatedGameServers must be non-negative");
        }
        Objects.requireNonNull(expectedPoolId, "expectedPoolId");
        Map<InstanceId, AgonesGameServerRecord> records = agonesGameServerRecords(output);
        Set<InstanceId> allocatedGameServers = records.values().stream()
                .filter(record -> "Allocated".equals(record.state()))
                .map(AgonesGameServerRecord::instanceId)
                .collect(Collectors.toSet());
        if (allocatedGameServers.size() != expectedAllocatedGameServers) {
            throw new IllegalStateException("Expected " + expectedAllocatedGameServers
                    + " Allocated Agones GameServers, got " + allocatedGameServers.size()
                    + ". States: " + formatAgonesStates(records));
        }
        Map<InstanceId, RouteAttemptExpectation> proofExpectations = agonesGameServerExpectations(expectations);
        for (Map.Entry<InstanceId, RouteAttemptExpectation> entry : proofExpectations.entrySet()) {
            InstanceId proofInstanceId = entry.getKey();
            AgonesGameServerRecord record = records.get(proofInstanceId);
            if (record == null || !"Allocated".equals(record.state())) {
                throw new IllegalStateException("Expected lobby proof Paper Instance "
                        + proofInstanceId.value()
                        + " to be an Allocated Agones GameServer, got "
                        + (record == null ? "<missing>" : record.state())
                        + ". States: " + formatAgonesStates(records));
            }
            List<String> mismatches = agonesGameServerMetadataMismatches(entry.getValue(), expectedPoolId, record);
            if (!mismatches.isEmpty()) {
                throw new IllegalStateException("Expected lobby proof Paper Instance "
                        + proofInstanceId.value()
                        + " to carry matching Agones GameServer metadata. Mismatches: "
                        + String.join(", ", mismatches)
                        + ". States: " + formatAgonesStates(records));
            }
        }
        return new AgonesGameServerStateResult(allocatedGameServers.size(), proofExpectations.keySet());
    }

    private static Map<InstanceId, RouteAttemptExpectation> agonesGameServerExpectations(
            List<RouteAttemptExpectation> expectations) {
        Map<InstanceId, RouteAttemptExpectation> byInstance = new LinkedHashMap<>();
        for (RouteAttemptExpectation expectation : expectations) {
            RouteAttemptExpectation previous = byInstance.putIfAbsent(expectation.targetInstanceId(), expectation);
            if (previous != null) {
                List<String> mismatches = new ArrayList<>();
                if (!previous.sessionId().equals(expectation.sessionId())) {
                    mismatches.add("sessionId " + previous.sessionId().value()
                            + " vs " + expectation.sessionId().value());
                }
                if (!previous.slotId().equals(expectation.slotId())) {
                    mismatches.add("slotId " + previous.slotId().value()
                            + " vs " + expectation.slotId().value());
                }
                if (!previous.targetResolvedManifestId().equals(expectation.targetResolvedManifestId())) {
                    mismatches.add("resolvedManifestId " + previous.targetResolvedManifestId().value()
                            + " vs " + expectation.targetResolvedManifestId().value());
                }
                if (!previous.traceId().equals(expectation.traceId())) {
                    mismatches.add("traceId " + previous.traceId() + " vs " + expectation.traceId());
                }
                if (!mismatches.isEmpty()) {
                    throw new IllegalStateException("Accepted lobby proofs disagreed for Agones GameServer "
                            + expectation.targetInstanceId().value()
                            + ": " + String.join(", ", mismatches));
                }
            }
        }
        return byInstance;
    }

    private static List<String> agonesGameServerMetadataMismatches(
            RouteAttemptExpectation expectation,
            PoolId expectedPoolId,
            AgonesGameServerRecord record) {
        List<String> mismatches = new ArrayList<>();
        appendOptionalMismatch(mismatches, "poolId", expectedPoolId.value(), record.poolId().map(PoolId::value));
        appendOptionalMismatch(mismatches, "sessionId", expectation.sessionId().value(),
                record.sessionId().map(SessionId::value));
        appendOptionalMismatch(mismatches, "slotId", expectation.slotId().value(), record.slotId().map(SlotId::value));
        appendOptionalMismatch(mismatches, "resolvedManifestId", expectation.targetResolvedManifestId().value(),
                record.resolvedManifestId().map(ResolvedManifestId::value));
        appendOptionalMismatch(mismatches, "traceId", expectation.traceId(), record.traceId());
        return mismatches;
    }

    private static void appendOptionalMismatch(
            List<String> mismatches,
            String label,
            String expected,
            Optional<String> actual) {
        if (actual.isEmpty() || !expected.equals(actual.orElseThrow())) {
            mismatches.add(label + " expected " + expected + " got " + actual.orElse("<missing>"));
        }
    }

    private static Map<InstanceId, AgonesGameServerRecord> agonesGameServerRecords(String output) {
        Map<InstanceId, AgonesGameServerRecord> records = new LinkedHashMap<>();
        for (String line : Objects.requireNonNull(output, "output").split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\|", -1);
            if (parts.length != 7 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("Malformed Agones GameServer state line: " + line);
            }
            records.put(new InstanceId(parts[0]), new AgonesGameServerRecord(
                    new InstanceId(parts[0]),
                    parts[1],
                    optionalAgonesField(parts[2]).map(PoolId::new),
                    optionalAgonesField(parts[3]).map(SessionId::new),
                    optionalAgonesField(parts[4]).map(SlotId::new),
                    optionalAgonesField(parts[5]).map(ResolvedManifestId::new),
                    optionalAgonesField(parts[6])));
        }
        return records;
    }

    private static Optional<String> optionalAgonesField(String value) {
        if (value == null || value.isBlank() || "<no value>".equals(value)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private static String formatAgonesStates(Map<InstanceId, AgonesGameServerRecord> records) {
        if (records.isEmpty()) {
            return "<none>";
        }
        return records.values().stream()
                .map(record -> record.instanceId().value()
                        + "=" + record.state()
                        + "[pool=" + record.poolId().map(PoolId::value).orElse("<missing>")
                        + ",session=" + record.sessionId().map(SessionId::value).orElse("<missing>")
                        + ",slot=" + record.slotId().map(SlotId::value).orElse("<missing>")
                        + ",manifest=" + record.resolvedManifestId().map(ResolvedManifestId::value).orElse("<missing>")
                        + ",trace=" + record.traceId().orElse("<missing>")
                        + "]")
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static ResolvedMinecraftEndpoint resolveEndpoint(VerificationConfig config) throws IOException {
        if (config.endpointHost().isPresent()) {
            return new ResolvedMinecraftEndpoint(config.endpointHost().orElseThrow(), config.endpointPort());
        }
        Optional<String> loadBalancerIp = kubectl(config, "service load balancer IP",
                "get", "service", config.serviceName(), "-o", "jsonpath={.status.loadBalancer.ingress[0].ip}");
        if (loadBalancerIp.isPresent()) {
            return new ResolvedMinecraftEndpoint(loadBalancerIp.orElseThrow(), config.endpointPort());
        }
        Optional<String> loadBalancerHost = kubectl(config, "service load balancer hostname",
                "get", "service", config.serviceName(), "-o", "jsonpath={.status.loadBalancer.ingress[0].hostname}");
        if (loadBalancerHost.isPresent()) {
            return new ResolvedMinecraftEndpoint(loadBalancerHost.orElseThrow(), config.endpointPort());
        }
        Optional<String> nodePort = kubectl(config, "service nodePort",
                "get", "service", config.serviceName(), "-o", "jsonpath={.spec.ports[0].nodePort}");
        if (nodePort.isPresent()) {
            return new ResolvedMinecraftEndpoint(config.nodeHost(), Integer.parseInt(nodePort.orElseThrow()));
        }
        throw new IOException("Velocity L4 Service " + config.namespace() + "/" + config.serviceName()
                + " did not expose a load-balancer address or nodePort");
    }

    private static Optional<String> kubectl(VerificationConfig config, String label, String... args)
            throws IOException {
        KubectlResult result = kubectlResult(config, label, args);
        if (result.exitCode() != 0) {
            throw new IOException("Failed to resolve " + label + " with `"
                    + String.join(" ", result.command()) + "`: " + result.output());
        }
        if (result.output().isBlank() || "<none>".equals(result.output())) {
            return Optional.empty();
        }
        return Optional.of(result.output());
    }

    private static KubectlResult kubectlResult(VerificationConfig config, String label, String... args)
            throws IOException {
        List<String> command = kubectlCommand(config, args);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean finished;
        try {
            finished = process.waitFor(config.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving " + label + " with kubectl", exception);
        }
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timed out resolving " + label + " with `" + String.join(" ", command) + "`");
        }
        return new KubectlResult(command, process.exitValue(), output);
    }

    private static KubectlPortForward openObjectStorePortForward(VerificationConfig config) throws IOException {
        List<String> command = kubectlCommand(
                config,
                "port-forward",
                "--address",
                "127.0.0.1",
                config.objectStoreResourceName(),
                ":" + config.objectStorePort());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        CompletableFuture<Integer> localPort = new CompletableFuture<>();
        Thread reader = new Thread(
                () -> readPortForwardOutput(process, output, localPort),
                "fulcrum-object-store-port-forward-reader");
        reader.setDaemon(true);
        reader.start();
        process.onExit().thenAccept(exited -> {
            if (!localPort.isDone()) {
                localPort.completeExceptionally(new IOException("kubectl port-forward exited before it was ready: "
                        + outputSnapshot(output)));
            }
        });
        try {
            return new KubectlPortForward(
                    command,
                    process,
                    reader,
                    localPort.get(config.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS),
                    output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while starting object-store port-forward", exception);
        } catch (TimeoutException exception) {
            process.destroyForcibly();
            throw new IOException("Timed out starting object-store port-forward with `"
                    + String.join(" ", command) + "`: " + outputSnapshot(output), exception);
        } catch (ExecutionException exception) {
            process.destroyForcibly();
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to start object-store port-forward with `"
                    + String.join(" ", command) + "`: " + outputSnapshot(output), cause);
        }
    }

    static List<String> kubectlCommand(VerificationConfig config, String... args) {
        List<String> command = new ArrayList<>();
        command.add("kubectl");
        if (config.kubeconfig().isPresent()) {
            command.add("--kubeconfig");
            command.add(config.kubeconfig().orElseThrow());
        } else {
            config.kubeContext().ifPresent(context -> {
                command.add("--context");
                command.add(context);
            });
        }
        command.add("-n");
        command.add(config.namespace());
        command.addAll(List.of(args));
        return List.copyOf(command);
    }

    private static void readPortForwardOutput(
            Process process,
            StringBuilder output,
            CompletableFuture<Integer> localPort) {
        try (java.io.BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            while (line != null) {
                synchronized (output) {
                    output.append(line).append(System.lineSeparator());
                }
                Matcher matcher = PORT_FORWARD_READY.matcher(line);
                if (matcher.find()) {
                    localPort.complete(Integer.parseInt(matcher.group(1)));
                }
                line = reader.readLine();
            }
            if (!localPort.isDone()) {
                localPort.completeExceptionally(new IOException("kubectl port-forward ended without a ready line: "
                        + outputSnapshot(output)));
            }
        } catch (IOException | RuntimeException exception) {
            if (!localPort.isDone()) {
                localPort.completeExceptionally(exception);
            }
        }
    }

    private static String outputSnapshot(StringBuilder output) {
        synchronized (output) {
            return output.toString().trim();
        }
    }

    private static int parseNonNegativeInteger(String value, String label) throws IOException {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                throw new IOException(label + " must be non-negative, got " + value);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IOException(label + " must be an integer, got " + value, exception);
        }
    }

    private static Map<String, String> wireFields(String payload, String label) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : Objects.requireNonNull(payload, "payload").split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed " + label + " line: " + line);
            }
            fields.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return fields;
    }

    private static Map<String, String> pipeFields(String wireValue, String expectedPrefix, String label) {
        Objects.requireNonNull(wireValue, "wireValue");
        String[] parts = wireValue.split("\\|", -1);
        if (parts.length == 0 || !expectedPrefix.equals(parts[0])) {
            throw new IllegalArgumentException(label + " must start with " + expectedPrefix);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 1; index < parts.length; index++) {
            int separator = parts[index].indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed " + label + " field " + parts[index]);
            }
            fields.put(parts[index].substring(0, separator), parts[index].substring(separator + 1));
        }
        return fields;
    }

    private static String requiredField(Map<String, String> fields, String key, String label) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + label + " field " + key);
        }
        return value;
    }

    private static Instant instantField(Map<String, String> fields, String key, String label) {
        return Instant.parse(requiredField(fields, key, label));
    }

    private static Optional<Instant> optionalInstantField(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Instant.parse(value));
    }

    record VerificationConfig(
            Optional<String> endpointHost,
            int endpointPort,
            String namespace,
            String serviceName,
            Optional<String> kubeContext,
            Optional<String> kubeconfig,
            String nodeHost,
            String agonesFleetName,
            boolean verifyAgonesFleetState,
            int expectedAgonesAllocatedReplicas,
            boolean verifyRouteAttemptState,
            String routeAttemptStateTopic,
            boolean verifyLoginRoutingCommandLog,
            String queueRosterCommandTopic,
            boolean verifyQueueRosterState,
            String queueRosterStateTopic,
            String presenceAuthorityCommandTopic,
            String sharedShardPlacementCommandTopic,
            String routeAttemptCommandTopic,
            String lifecycleTraceCommandTopic,
            boolean verifyLifecycleTraceState,
            String lifecycleTraceStateTopic,
            boolean verifyRouteAuthorityCommandLog,
            String routeAuthorityCommandTopic,
            boolean verifyRouteAuthorityState,
            String routeAuthorityStateTopic,
            boolean verifyHostRouteCommandLogs,
            String proxyRouteCommandTopic,
            String paperHostCommandTopic,
            boolean verifyHostObservationLog,
            String hostObservationTopic,
            boolean verifyPresenceAuthorityState,
            String presenceAuthorityStateTopic,
            boolean verifyStandardCapabilityState,
            String playerProfileStateTopic,
            String rankStateTopic,
            String punishmentStateTopic,
            boolean verifyStandardCapabilityCommandLog,
            String playerProfileCommandTopic,
            String rankCommandTopic,
            String punishmentCommandTopic,
            boolean verifyRewardState,
            String economyStateTopic,
            String statsStateTopic,
            boolean verifyRewardCommandLog,
            String economyCommandTopic,
            String statsCommandTopic,
            String expectedRewardCurrencyKey,
            long expectedRewardAmountMinorUnits,
            String expectedRewardStatKey,
            int expectedRewardCommandDeliveryCopies,
            boolean verifyCassandraHotProjections,
            String cassandraPodName,
            String cassandraContainerName,
            String cassandraCqlshPath,
            boolean verifyPostgresAuthorityRecords,
            String postgresPodName,
            String postgresContainerName,
            String postgresPsqlPath,
            String postgresDatabase,
            String postgresUsername,
            boolean verifyValkeyCache,
            String valkeyResourceName,
            String valkeyContainerName,
            String valkeyCliPath,
            boolean verifyProjectionConsistency,
            boolean verifyTraceCorrelation,
            boolean verifyObjectStoreArtifact,
            String objectStoreResourceName,
            int objectStorePort,
            String objectStoreRegion,
            String objectStoreBucket,
            String objectStoreSecretName,
            String objectStoreAccessKeySecretKey,
            String objectStoreSecretKeySecretKey,
            ArtifactId expectedLobbyWorldArtifactId,
            String expectedLobbyWorldArtifactDigest,
            String expectedLobbyWorldArtifactCompatibility,
            boolean verifySessionAuthorityState,
            String sessionAuthorityStateTopic,
            boolean verifySessionAuthorityCommandLog,
            String sessionAuthorityCommandTopic,
            boolean verifySharedShardAllocationCommandLog,
            String sharedShardAllocationCommandTopic,
            boolean verifySharedShardAllocationState,
            String sharedShardAllocationStateTopic,
            ExperienceId expectedExperienceId,
            PoolId expectedPoolId,
            String kafkaPodName,
            String kafkaContainerName,
            String kafkaBootstrapServer,
            String kafkaConsoleConsumerPath,
            int protocolVersion,
            String loginUsername,
            String secondLoginUsername,
            String expectedSpawnBlock,
            String expectedSpawnWorld,
            ResolvedManifestId expectedResolvedManifestId,
            String expectedTraceId,
            int expectedBedrockBlockX,
            int expectedBedrockBlockY,
            int expectedBedrockBlockZ,
            double expectedPlayerX,
            double expectedPlayerY,
            double expectedPlayerZ,
            double expectedPlayerYaw,
            double expectedPlayerPitch,
            String expectedDisplayName,
            String expectedRankLabel,
            String expectedDecoratedChatContains,
            String expectedSecondDisplayName,
            String expectedSecondRankLabel,
            String expectedSecondDecoratedChatContains,
            boolean verifyScaleOut,
            String scaleOutTriggerLoginUsername,
            Optional<String> scaleOutTriggerDeniedReasonContains,
            String scaleOutLoginUsername,
            String expectedScaleOutDisplayName,
            String expectedScaleOutRankLabel,
            String expectedScaleOutDecoratedChatContains,
            Duration scaleOutTimeout,
            Optional<String> deniedLoginUsername,
            Optional<String> deniedLoginReasonContains,
            Duration endpointReadyTimeout,
            Duration routeAttemptStateTimeout,
            Duration routeAttemptStateFreshnessSkew,
            Duration presenceAuthorityStateTimeout,
            Duration presenceAuthorityStateFreshnessSkew,
            Duration standardCapabilityStateTimeout,
            Duration cassandraHotProjectionTimeout,
            Duration postgresAuthorityRecordTimeout,
            Duration valkeyCacheTimeout,
            Duration objectStoreArtifactTimeout,
            Duration sessionAuthorityStateTimeout,
            Duration sessionAuthorityStateFreshnessSkew,
            Duration sharedShardAllocationStateTimeout,
            Duration timeout) {
        private static final int DEFAULT_MINECRAFT_PORT = 25_565;
        private static final int DEFAULT_PROTOCOL_VERSION = 0;
        private static final String DEFAULT_LOGIN_USERNAME = "FulcrumBotOne";
        private static final String DEFAULT_SECOND_LOGIN_USERNAME = "FulcrumBotTwo";
        private static final String DEFAULT_AGONES_FLEET_NAME = "fulcrum-lobby-paper";
        private static final int DEFAULT_EXPECTED_AGONES_ALLOCATED_REPLICAS = 1;
        private static final String DEFAULT_ROUTE_ATTEMPT_STATE_TOPIC = "ctrl.state.route-attempt";
        private static final String DEFAULT_QUEUE_ROSTER_COMMAND_TOPIC = "ctrl.cmd.queue-roster";
        private static final String DEFAULT_QUEUE_ROSTER_STATE_TOPIC = "ctrl.state.queue-roster";
        private static final String DEFAULT_PRESENCE_AUTHORITY_COMMAND_TOPIC = "cmd.presence";
        private static final String DEFAULT_SHARED_SHARD_PLACEMENT_COMMAND_TOPIC =
                "ctrl.cmd.shared-shard-placement";
        private static final String DEFAULT_ROUTE_ATTEMPT_COMMAND_TOPIC = "ctrl.cmd.route-attempt";
        private static final String DEFAULT_LIFECYCLE_TRACE_COMMAND_TOPIC = "ctrl.cmd.lifecycle-trace";
        private static final String DEFAULT_LIFECYCLE_TRACE_STATE_TOPIC = "ctrl.state.lifecycle-trace";
        private static final String DEFAULT_ROUTE_AUTHORITY_COMMAND_TOPIC = "cmd.route";
        private static final String DEFAULT_ROUTE_AUTHORITY_STATE_TOPIC = "state.route";
        private static final String DEFAULT_PROXY_ROUTE_COMMAND_TOPIC = "host.velocity.routes";
        private static final String DEFAULT_PAPER_HOST_COMMAND_TOPIC = "host.paper.commands";
        private static final String DEFAULT_HOST_OBSERVATION_TOPIC = "host.observation";
        private static final String DEFAULT_PRESENCE_AUTHORITY_STATE_TOPIC = "state.presence";
        private static final String DEFAULT_PLAYER_PROFILE_STATE_TOPIC = PlayerProfileContracts.STATE_TOPIC;
        private static final String DEFAULT_RANK_STATE_TOPIC = RankContracts.STATE_TOPIC;
        private static final String DEFAULT_PUNISHMENT_STATE_TOPIC = PunishmentContracts.STATE_TOPIC;
        private static final String DEFAULT_PLAYER_PROFILE_COMMAND_TOPIC = PlayerProfileContracts.COMMAND_TOPIC;
        private static final String DEFAULT_RANK_COMMAND_TOPIC = RankContracts.COMMAND_TOPIC;
        private static final String DEFAULT_PUNISHMENT_COMMAND_TOPIC = PunishmentContracts.COMMAND_TOPIC;
        private static final String DEFAULT_ECONOMY_STATE_TOPIC = EconomyContracts.STATE_TOPIC;
        private static final String DEFAULT_STATS_STATE_TOPIC = StatsContracts.STATE_TOPIC;
        private static final String DEFAULT_ECONOMY_COMMAND_TOPIC = EconomyContracts.COMMAND_TOPIC;
        private static final String DEFAULT_STATS_COMMAND_TOPIC = StatsContracts.COMMAND_TOPIC;
        private static final String DEFAULT_EXPECTED_REWARD_CURRENCY_KEY = "coins";
        private static final long DEFAULT_EXPECTED_REWARD_AMOUNT_MINOR_UNITS = 250L;
        private static final String DEFAULT_EXPECTED_REWARD_STAT_KEY = "session-completions";
        private static final int DEFAULT_EXPECTED_REWARD_COMMAND_DELIVERY_COPIES = 1;
        private static final String DEFAULT_CASSANDRA_POD_NAME = "fulcrum-cassandra-0";
        private static final String DEFAULT_CASSANDRA_CONTAINER_NAME = "cassandra";
        private static final String DEFAULT_CASSANDRA_CQLSH_PATH = "cqlsh";
        private static final String DEFAULT_POSTGRES_POD_NAME = "fulcrum-postgres-0";
        private static final String DEFAULT_POSTGRES_CONTAINER_NAME = "postgres";
        private static final String DEFAULT_POSTGRES_PSQL_PATH = "psql";
        private static final String DEFAULT_POSTGRES_DATABASE = "fulcrum";
        private static final String DEFAULT_POSTGRES_USERNAME = "fulcrum";
        private static final String DEFAULT_VALKEY_RESOURCE_NAME = "deployment/fulcrum-valkey";
        private static final String DEFAULT_VALKEY_CONTAINER_NAME = "valkey";
        private static final String DEFAULT_VALKEY_CLI_PATH = "valkey-cli";
        private static final String DEFAULT_OBJECT_STORE_RESOURCE_NAME = "service/fulcrum-object-store";
        private static final int DEFAULT_OBJECT_STORE_PORT = 9_000;
        private static final String DEFAULT_OBJECT_STORE_REGION = "us-east-1";
        private static final String DEFAULT_OBJECT_STORE_BUCKET = LobbyWorldArtifactProvisioner.DEFAULT_BUCKET;
        private static final String DEFAULT_OBJECT_STORE_SECRET_NAME = "fulcrum-object-store-credentials";
        private static final String DEFAULT_OBJECT_STORE_ACCESS_KEY_SECRET_KEY = "FULCRUM_OBJECT_STORE_ACCESS_KEY";
        private static final String DEFAULT_OBJECT_STORE_SECRET_KEY_SECRET_KEY = "FULCRUM_OBJECT_STORE_SECRET_KEY";
        private static final ArtifactId DEFAULT_EXPECTED_LOBBY_WORLD_ARTIFACT_ID =
                new ArtifactId(LobbyWorldArtifactProvisioner.DEFAULT_ARTIFACT_ID);
        private static final String DEFAULT_EXPECTED_LOBBY_WORLD_ARTIFACT_DIGEST =
                LobbyWorldArtifactProvisioner.defaultArchiveDigest();
        private static final String DEFAULT_EXPECTED_LOBBY_WORLD_ARTIFACT_COMPATIBILITY =
                LobbyWorldArtifactProvisioner.DEFAULT_COMPATIBILITY;
        private static final String DEFAULT_SESSION_AUTHORITY_STATE_TOPIC = "state.session";
        private static final String DEFAULT_SESSION_AUTHORITY_COMMAND_TOPIC = "cmd.session";
        private static final String DEFAULT_SHARED_SHARD_ALLOCATION_COMMAND_TOPIC =
                "ctrl.cmd.shared-shard-allocation";
        private static final String DEFAULT_SHARED_SHARD_ALLOCATION_STATE_TOPIC =
                "ctrl.state.shared-shard-allocation";
        private static final String DEFAULT_KAFKA_POD_NAME = "fulcrum-kafka-0";
        private static final String DEFAULT_KAFKA_CONTAINER_NAME = "kafka";
        private static final String DEFAULT_KAFKA_BOOTSTRAP_SERVER = "localhost:9092";
        private static final String DEFAULT_KAFKA_CONSOLE_CONSUMER_PATH = "/opt/kafka/bin/kafka-console-consumer.sh";
        private static final String DEFAULT_EXPECTED_SPAWN_WORLD = "world";
        private static final ExperienceId DEFAULT_EXPECTED_EXPERIENCE_ID = new ExperienceId("experience-lobby");
        private static final PoolId DEFAULT_EXPECTED_POOL_ID = new PoolId("pool-lobby");
        private static final ResolvedManifestId DEFAULT_EXPECTED_RESOLVED_MANIFEST_ID =
                new ResolvedManifestId("manifest-lobby-bedrock-v1");
        private static final String DEFAULT_EXPECTED_TRACE_ID = "trace-paper-session-lobby-shared";
        private static final int DEFAULT_EXPECTED_BEDROCK_BLOCK_X = 0;
        private static final int DEFAULT_EXPECTED_BEDROCK_BLOCK_Y = 64;
        private static final int DEFAULT_EXPECTED_BEDROCK_BLOCK_Z = 0;
        private static final double DEFAULT_EXPECTED_PLAYER_X = 0.5D;
        private static final double DEFAULT_EXPECTED_PLAYER_Y = 65.0D;
        private static final double DEFAULT_EXPECTED_PLAYER_Z = 0.5D;
        private static final double DEFAULT_EXPECTED_PLAYER_YAW = 0.0D;
        private static final double DEFAULT_EXPECTED_PLAYER_PITCH = 0.0D;
        private static final String DEFAULT_EXPECTED_DISPLAY_NAME = "Fulcrum Bot One";
        private static final String DEFAULT_EXPECTED_RANK_LABEL = "Admin";
        private static final String DEFAULT_EXPECTED_DECORATED_CHAT_CONTAINS =
                "[Admin] Fulcrum Bot One: " + PaperLobbyProofMessage.PROOF_CHAT_MESSAGE;
        private static final String DEFAULT_EXPECTED_SECOND_DISPLAY_NAME = "Fulcrum Bot Two";
        private static final String DEFAULT_EXPECTED_SECOND_RANK_LABEL = "Admin";
        private static final String DEFAULT_EXPECTED_SECOND_DECORATED_CHAT_CONTAINS =
                "[Admin] Fulcrum Bot Two: " + PaperLobbyProofMessage.PROOF_CHAT_MESSAGE;
        private static final String DEFAULT_SCALE_OUT_TRIGGER_LOGIN_USERNAME = "FulcrumBotThree";
        private static final String DEFAULT_SCALE_OUT_LOGIN_USERNAME = "FulcrumBotFour";
        private static final String DEFAULT_EXPECTED_SCALE_OUT_DISPLAY_NAME = "Fulcrum Bot Four";
        private static final String DEFAULT_EXPECTED_SCALE_OUT_RANK_LABEL = "Admin";
        private static final String DEFAULT_EXPECTED_SCALE_OUT_DECORATED_CHAT_CONTAINS =
                "[Admin] Fulcrum Bot Four: " + PaperLobbyProofMessage.PROOF_CHAT_MESSAGE;

        VerificationConfig {
            endpointHost = Objects.requireNonNull(endpointHost, "endpointHost")
                    .filter(value -> !value.isBlank());
            namespace = requireNonBlank(namespace, "namespace");
            serviceName = requireNonBlank(serviceName, "serviceName");
            kubeContext = Objects.requireNonNull(kubeContext, "kubeContext")
                    .filter(value -> !value.isBlank());
            kubeconfig = Objects.requireNonNull(kubeconfig, "kubeconfig")
                    .filter(value -> !value.isBlank());
            nodeHost = requireNonBlank(nodeHost, "nodeHost");
            agonesFleetName = requireNonBlank(agonesFleetName, "agonesFleetName");
            routeAttemptStateTopic = requireNonBlank(routeAttemptStateTopic, "routeAttemptStateTopic");
            queueRosterCommandTopic = requireNonBlank(queueRosterCommandTopic, "queueRosterCommandTopic");
            queueRosterStateTopic = requireNonBlank(queueRosterStateTopic, "queueRosterStateTopic");
            presenceAuthorityCommandTopic = requireNonBlank(
                    presenceAuthorityCommandTopic,
                    "presenceAuthorityCommandTopic");
            sharedShardPlacementCommandTopic = requireNonBlank(
                    sharedShardPlacementCommandTopic,
                    "sharedShardPlacementCommandTopic");
            routeAttemptCommandTopic = requireNonBlank(routeAttemptCommandTopic, "routeAttemptCommandTopic");
            lifecycleTraceCommandTopic = requireNonBlank(lifecycleTraceCommandTopic, "lifecycleTraceCommandTopic");
            lifecycleTraceStateTopic = requireNonBlank(lifecycleTraceStateTopic, "lifecycleTraceStateTopic");
            routeAuthorityCommandTopic = requireNonBlank(
                    routeAuthorityCommandTopic,
                    "routeAuthorityCommandTopic");
            routeAuthorityStateTopic = requireNonBlank(
                    routeAuthorityStateTopic,
                    "routeAuthorityStateTopic");
            proxyRouteCommandTopic = requireNonBlank(proxyRouteCommandTopic, "proxyRouteCommandTopic");
            paperHostCommandTopic = requireNonBlank(paperHostCommandTopic, "paperHostCommandTopic");
            hostObservationTopic = requireNonBlank(hostObservationTopic, "hostObservationTopic");
            presenceAuthorityStateTopic = requireNonBlank(
                    presenceAuthorityStateTopic,
                    "presenceAuthorityStateTopic");
            playerProfileStateTopic = requireNonBlank(playerProfileStateTopic, "playerProfileStateTopic");
            rankStateTopic = requireNonBlank(rankStateTopic, "rankStateTopic");
            punishmentStateTopic = requireNonBlank(punishmentStateTopic, "punishmentStateTopic");
            playerProfileCommandTopic = requireNonBlank(playerProfileCommandTopic, "playerProfileCommandTopic");
            rankCommandTopic = requireNonBlank(rankCommandTopic, "rankCommandTopic");
            punishmentCommandTopic = requireNonBlank(punishmentCommandTopic, "punishmentCommandTopic");
            economyStateTopic = requireNonBlank(economyStateTopic, "economyStateTopic");
            statsStateTopic = requireNonBlank(statsStateTopic, "statsStateTopic");
            economyCommandTopic = requireNonBlank(economyCommandTopic, "economyCommandTopic");
            statsCommandTopic = requireNonBlank(statsCommandTopic, "statsCommandTopic");
            expectedRewardCurrencyKey = requireNonBlank(
                    expectedRewardCurrencyKey,
                    "expectedRewardCurrencyKey").toLowerCase(Locale.ROOT);
            expectedRewardStatKey = requireNonBlank(expectedRewardStatKey, "expectedRewardStatKey")
                    .toLowerCase(Locale.ROOT);
            cassandraPodName = requireNonBlank(cassandraPodName, "cassandraPodName");
            cassandraContainerName = requireNonBlank(cassandraContainerName, "cassandraContainerName");
            cassandraCqlshPath = requireNonBlank(cassandraCqlshPath, "cassandraCqlshPath");
            postgresPodName = requireNonBlank(postgresPodName, "postgresPodName");
            postgresContainerName = requireNonBlank(postgresContainerName, "postgresContainerName");
            postgresPsqlPath = requireNonBlank(postgresPsqlPath, "postgresPsqlPath");
            postgresDatabase = requireNonBlank(postgresDatabase, "postgresDatabase");
            postgresUsername = requireNonBlank(postgresUsername, "postgresUsername");
            valkeyResourceName = requireNonBlank(valkeyResourceName, "valkeyResourceName");
            valkeyContainerName = requireNonBlank(valkeyContainerName, "valkeyContainerName");
            valkeyCliPath = requireNonBlank(valkeyCliPath, "valkeyCliPath");
            objectStoreResourceName = requireNonBlank(objectStoreResourceName, "objectStoreResourceName");
            objectStoreRegion = requireNonBlank(objectStoreRegion, "objectStoreRegion");
            objectStoreBucket = requireNonBlank(objectStoreBucket, "objectStoreBucket").toLowerCase(Locale.ROOT);
            objectStoreSecretName = requireNonBlank(objectStoreSecretName, "objectStoreSecretName");
            objectStoreAccessKeySecretKey = requireNonBlank(
                    objectStoreAccessKeySecretKey,
                    "objectStoreAccessKeySecretKey");
            objectStoreSecretKeySecretKey = requireNonBlank(
                    objectStoreSecretKeySecretKey,
                    "objectStoreSecretKeySecretKey");
            expectedLobbyWorldArtifactId = Objects.requireNonNull(
                    expectedLobbyWorldArtifactId,
                    "expectedLobbyWorldArtifactId");
            expectedLobbyWorldArtifactDigest = requireNonBlank(
                    expectedLobbyWorldArtifactDigest,
                    "expectedLobbyWorldArtifactDigest").toLowerCase(Locale.ROOT);
            expectedLobbyWorldArtifactCompatibility = requireNonBlank(
                    expectedLobbyWorldArtifactCompatibility,
                    "expectedLobbyWorldArtifactCompatibility");
            sessionAuthorityStateTopic = requireNonBlank(
                    sessionAuthorityStateTopic,
                    "sessionAuthorityStateTopic");
            sessionAuthorityCommandTopic = requireNonBlank(
                    sessionAuthorityCommandTopic,
                    "sessionAuthorityCommandTopic");
            sharedShardAllocationCommandTopic = requireNonBlank(
                    sharedShardAllocationCommandTopic,
                    "sharedShardAllocationCommandTopic");
            sharedShardAllocationStateTopic = requireNonBlank(
                    sharedShardAllocationStateTopic,
                    "sharedShardAllocationStateTopic");
            expectedExperienceId = Objects.requireNonNull(expectedExperienceId, "expectedExperienceId");
            expectedPoolId = Objects.requireNonNull(expectedPoolId, "expectedPoolId");
            kafkaPodName = requireNonBlank(kafkaPodName, "kafkaPodName");
            kafkaContainerName = requireNonBlank(kafkaContainerName, "kafkaContainerName");
            kafkaBootstrapServer = requireNonBlank(kafkaBootstrapServer, "kafkaBootstrapServer");
            kafkaConsoleConsumerPath = requireNonBlank(kafkaConsoleConsumerPath, "kafkaConsoleConsumerPath");
            loginUsername = requireNonBlank(loginUsername, "loginUsername");
            secondLoginUsername = requireNonBlank(secondLoginUsername, "secondLoginUsername");
            expectedSpawnBlock = requireNonBlank(expectedSpawnBlock, "expectedSpawnBlock");
            expectedSpawnWorld = requireNonBlank(expectedSpawnWorld, "expectedSpawnWorld");
            expectedResolvedManifestId = Objects.requireNonNull(
                    expectedResolvedManifestId,
                    "expectedResolvedManifestId");
            expectedTraceId = requireNonBlank(expectedTraceId, "expectedTraceId");
            expectedDisplayName = requireNonBlank(expectedDisplayName, "expectedDisplayName");
            expectedRankLabel = requireNonBlank(expectedRankLabel, "expectedRankLabel");
            expectedDecoratedChatContains = requireNonBlank(
                    expectedDecoratedChatContains,
                    "expectedDecoratedChatContains");
            expectedSecondDisplayName = requireNonBlank(expectedSecondDisplayName, "expectedSecondDisplayName");
            expectedSecondRankLabel = requireNonBlank(expectedSecondRankLabel, "expectedSecondRankLabel");
            expectedSecondDecoratedChatContains = requireNonBlank(
                    expectedSecondDecoratedChatContains,
                    "expectedSecondDecoratedChatContains");
            scaleOutTriggerLoginUsername = requireNonBlank(
                    scaleOutTriggerLoginUsername,
                    "scaleOutTriggerLoginUsername");
            scaleOutTriggerDeniedReasonContains = Objects.requireNonNull(
                    scaleOutTriggerDeniedReasonContains,
                    "scaleOutTriggerDeniedReasonContains")
                    .filter(value -> !value.isBlank());
            scaleOutLoginUsername = requireNonBlank(scaleOutLoginUsername, "scaleOutLoginUsername");
            expectedScaleOutDisplayName = requireNonBlank(
                    expectedScaleOutDisplayName,
                    "expectedScaleOutDisplayName");
            expectedScaleOutRankLabel = requireNonBlank(expectedScaleOutRankLabel, "expectedScaleOutRankLabel");
            expectedScaleOutDecoratedChatContains = requireNonBlank(
                    expectedScaleOutDecoratedChatContains,
                    "expectedScaleOutDecoratedChatContains");
            scaleOutTimeout = Objects.requireNonNull(scaleOutTimeout, "scaleOutTimeout");
            deniedLoginUsername = Objects.requireNonNull(deniedLoginUsername, "deniedLoginUsername")
                    .filter(value -> !value.isBlank());
            deniedLoginReasonContains = Objects.requireNonNull(
                    deniedLoginReasonContains,
                    "deniedLoginReasonContains")
                    .filter(value -> !value.isBlank());
            endpointReadyTimeout = Objects.requireNonNull(endpointReadyTimeout, "endpointReadyTimeout");
            routeAttemptStateTimeout = Objects.requireNonNull(routeAttemptStateTimeout, "routeAttemptStateTimeout");
            routeAttemptStateFreshnessSkew = Objects.requireNonNull(
                    routeAttemptStateFreshnessSkew,
                    "routeAttemptStateFreshnessSkew");
            presenceAuthorityStateTimeout = Objects.requireNonNull(
                    presenceAuthorityStateTimeout,
                    "presenceAuthorityStateTimeout");
            presenceAuthorityStateFreshnessSkew = Objects.requireNonNull(
                    presenceAuthorityStateFreshnessSkew,
                    "presenceAuthorityStateFreshnessSkew");
            standardCapabilityStateTimeout = Objects.requireNonNull(
                    standardCapabilityStateTimeout,
                    "standardCapabilityStateTimeout");
            cassandraHotProjectionTimeout = Objects.requireNonNull(
                    cassandraHotProjectionTimeout,
                    "cassandraHotProjectionTimeout");
            postgresAuthorityRecordTimeout = Objects.requireNonNull(
                    postgresAuthorityRecordTimeout,
                    "postgresAuthorityRecordTimeout");
            valkeyCacheTimeout = Objects.requireNonNull(
                    valkeyCacheTimeout,
                    "valkeyCacheTimeout");
            sessionAuthorityStateTimeout = Objects.requireNonNull(
                    sessionAuthorityStateTimeout,
                    "sessionAuthorityStateTimeout");
            sessionAuthorityStateFreshnessSkew = Objects.requireNonNull(
                    sessionAuthorityStateFreshnessSkew,
                    "sessionAuthorityStateFreshnessSkew");
            sharedShardAllocationStateTimeout = Objects.requireNonNull(
                    sharedShardAllocationStateTimeout,
                    "sharedShardAllocationStateTimeout");
            timeout = Objects.requireNonNull(timeout, "timeout");
            if (endpointPort < 1 || endpointPort > 65_535) {
                throw new IllegalArgumentException("endpointPort out of range: " + endpointPort);
            }
            if (endpointReadyTimeout.isNegative() || endpointReadyTimeout.isZero()) {
                throw new IllegalArgumentException("endpointReadyTimeout must be positive");
            }
            if (routeAttemptStateTimeout.isNegative() || routeAttemptStateTimeout.isZero()) {
                throw new IllegalArgumentException("routeAttemptStateTimeout must be positive");
            }
            if (routeAttemptStateFreshnessSkew.isNegative()) {
                throw new IllegalArgumentException("routeAttemptStateFreshnessSkew must not be negative");
            }
            if (presenceAuthorityStateTimeout.isNegative() || presenceAuthorityStateTimeout.isZero()) {
                throw new IllegalArgumentException("presenceAuthorityStateTimeout must be positive");
            }
            if (presenceAuthorityStateFreshnessSkew.isNegative()) {
                throw new IllegalArgumentException("presenceAuthorityStateFreshnessSkew must not be negative");
            }
            if (standardCapabilityStateTimeout.isNegative() || standardCapabilityStateTimeout.isZero()) {
                throw new IllegalArgumentException("standardCapabilityStateTimeout must be positive");
            }
            if (cassandraHotProjectionTimeout.isNegative() || cassandraHotProjectionTimeout.isZero()) {
                throw new IllegalArgumentException("cassandraHotProjectionTimeout must be positive");
            }
            if (postgresAuthorityRecordTimeout.isNegative() || postgresAuthorityRecordTimeout.isZero()) {
                throw new IllegalArgumentException("postgresAuthorityRecordTimeout must be positive");
            }
            if (valkeyCacheTimeout.isNegative() || valkeyCacheTimeout.isZero()) {
                throw new IllegalArgumentException("valkeyCacheTimeout must be positive");
            }
            objectStoreArtifactTimeout = Objects.requireNonNull(
                    objectStoreArtifactTimeout,
                    "objectStoreArtifactTimeout");
            if (objectStoreArtifactTimeout.isNegative() || objectStoreArtifactTimeout.isZero()) {
                throw new IllegalArgumentException("objectStoreArtifactTimeout must be positive");
            }
            if (sessionAuthorityStateTimeout.isNegative() || sessionAuthorityStateTimeout.isZero()) {
                throw new IllegalArgumentException("sessionAuthorityStateTimeout must be positive");
            }
            if (sessionAuthorityStateFreshnessSkew.isNegative()) {
                throw new IllegalArgumentException("sessionAuthorityStateFreshnessSkew must not be negative");
            }
            if (sharedShardAllocationStateTimeout.isNegative() || sharedShardAllocationStateTimeout.isZero()) {
                throw new IllegalArgumentException("sharedShardAllocationStateTimeout must be positive");
            }
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (scaleOutTimeout.isNegative() || scaleOutTimeout.isZero()) {
                throw new IllegalArgumentException("scaleOutTimeout must be positive");
            }
            if (expectedAgonesAllocatedReplicas < 0) {
                throw new IllegalArgumentException("expectedAgonesAllocatedReplicas must be non-negative");
            }
            if (expectedRewardAmountMinorUnits <= 0) {
                throw new IllegalArgumentException("expectedRewardAmountMinorUnits must be positive");
            }
            if (expectedRewardCommandDeliveryCopies <= 0) {
                throw new IllegalArgumentException("expectedRewardCommandDeliveryCopies must be positive");
            }
            if (objectStorePort < 1 || objectStorePort > 65_535) {
                throw new IllegalArgumentException("objectStorePort out of range: " + objectStorePort);
            }
            new ArtifactPin(
                    expectedLobbyWorldArtifactId,
                    expectedLobbyWorldArtifactDigest,
                    expectedLobbyWorldArtifactCompatibility);
            requireFinite(expectedPlayerX, "expectedPlayerX");
            requireFinite(expectedPlayerY, "expectedPlayerY");
            requireFinite(expectedPlayerZ, "expectedPlayerZ");
            requireFinite(expectedPlayerYaw, "expectedPlayerYaw");
            requireFinite(expectedPlayerPitch, "expectedPlayerPitch");
        }

        static VerificationConfig parse(String[] args) {
            Optional<String> endpointHost = Optional.empty();
            int endpointPort = DEFAULT_MINECRAFT_PORT;
            String namespace = "fulcrum-lobby";
            String serviceName = "fulcrum-velocity-l4";
            Optional<String> kubeContext = Optional.empty();
            Optional<String> kubeconfig = Optional.empty();
            String nodeHost = "127.0.0.1";
            int protocolVersion = DEFAULT_PROTOCOL_VERSION;
            String loginUsername = DEFAULT_LOGIN_USERNAME;
            String secondLoginUsername = DEFAULT_SECOND_LOGIN_USERNAME;
            String agonesFleetName = DEFAULT_AGONES_FLEET_NAME;
            Optional<Boolean> verifyAgonesFleetState = Optional.empty();
            int expectedAgonesAllocatedReplicas = DEFAULT_EXPECTED_AGONES_ALLOCATED_REPLICAS;
            Optional<Boolean> verifyRouteAttemptState = Optional.empty();
            String routeAttemptStateTopic = DEFAULT_ROUTE_ATTEMPT_STATE_TOPIC;
            Optional<Boolean> verifyLoginRoutingCommandLog = Optional.empty();
            String queueRosterCommandTopic = DEFAULT_QUEUE_ROSTER_COMMAND_TOPIC;
            Optional<Boolean> verifyQueueRosterState = Optional.empty();
            String queueRosterStateTopic = DEFAULT_QUEUE_ROSTER_STATE_TOPIC;
            String presenceAuthorityCommandTopic = DEFAULT_PRESENCE_AUTHORITY_COMMAND_TOPIC;
            String sharedShardPlacementCommandTopic = DEFAULT_SHARED_SHARD_PLACEMENT_COMMAND_TOPIC;
            String routeAttemptCommandTopic = DEFAULT_ROUTE_ATTEMPT_COMMAND_TOPIC;
            String lifecycleTraceCommandTopic = DEFAULT_LIFECYCLE_TRACE_COMMAND_TOPIC;
            Optional<Boolean> verifyLifecycleTraceState = Optional.empty();
            String lifecycleTraceStateTopic = DEFAULT_LIFECYCLE_TRACE_STATE_TOPIC;
            Optional<Boolean> verifyRouteAuthorityCommandLog = Optional.empty();
            String routeAuthorityCommandTopic = DEFAULT_ROUTE_AUTHORITY_COMMAND_TOPIC;
            Optional<Boolean> verifyRouteAuthorityState = Optional.empty();
            String routeAuthorityStateTopic = DEFAULT_ROUTE_AUTHORITY_STATE_TOPIC;
            Optional<Boolean> verifyHostRouteCommandLogs = Optional.empty();
            String proxyRouteCommandTopic = DEFAULT_PROXY_ROUTE_COMMAND_TOPIC;
            String paperHostCommandTopic = DEFAULT_PAPER_HOST_COMMAND_TOPIC;
            Optional<Boolean> verifyHostObservationLog = Optional.empty();
            String hostObservationTopic = DEFAULT_HOST_OBSERVATION_TOPIC;
            Optional<Boolean> verifyPresenceAuthorityState = Optional.empty();
            String presenceAuthorityStateTopic = DEFAULT_PRESENCE_AUTHORITY_STATE_TOPIC;
            Optional<Boolean> verifyStandardCapabilityState = Optional.empty();
            String playerProfileStateTopic = DEFAULT_PLAYER_PROFILE_STATE_TOPIC;
            String rankStateTopic = DEFAULT_RANK_STATE_TOPIC;
            String punishmentStateTopic = DEFAULT_PUNISHMENT_STATE_TOPIC;
            Optional<Boolean> verifyStandardCapabilityCommandLog = Optional.empty();
            String playerProfileCommandTopic = DEFAULT_PLAYER_PROFILE_COMMAND_TOPIC;
            String rankCommandTopic = DEFAULT_RANK_COMMAND_TOPIC;
            String punishmentCommandTopic = DEFAULT_PUNISHMENT_COMMAND_TOPIC;
            Optional<Boolean> verifyRewardState = Optional.empty();
            String economyStateTopic = DEFAULT_ECONOMY_STATE_TOPIC;
            String statsStateTopic = DEFAULT_STATS_STATE_TOPIC;
            Optional<Boolean> verifyRewardCommandLog = Optional.empty();
            String economyCommandTopic = DEFAULT_ECONOMY_COMMAND_TOPIC;
            String statsCommandTopic = DEFAULT_STATS_COMMAND_TOPIC;
            String expectedRewardCurrencyKey = DEFAULT_EXPECTED_REWARD_CURRENCY_KEY;
            long expectedRewardAmountMinorUnits = DEFAULT_EXPECTED_REWARD_AMOUNT_MINOR_UNITS;
            String expectedRewardStatKey = DEFAULT_EXPECTED_REWARD_STAT_KEY;
            int expectedRewardCommandDeliveryCopies = DEFAULT_EXPECTED_REWARD_COMMAND_DELIVERY_COPIES;
            Optional<Boolean> verifyCassandraHotProjections = Optional.empty();
            String cassandraPodName = DEFAULT_CASSANDRA_POD_NAME;
            String cassandraContainerName = DEFAULT_CASSANDRA_CONTAINER_NAME;
            String cassandraCqlshPath = DEFAULT_CASSANDRA_CQLSH_PATH;
            Optional<Boolean> verifyPostgresAuthorityRecords = Optional.empty();
            String postgresPodName = DEFAULT_POSTGRES_POD_NAME;
            String postgresContainerName = DEFAULT_POSTGRES_CONTAINER_NAME;
            String postgresPsqlPath = DEFAULT_POSTGRES_PSQL_PATH;
            String postgresDatabase = DEFAULT_POSTGRES_DATABASE;
            String postgresUsername = DEFAULT_POSTGRES_USERNAME;
            Optional<Boolean> verifyValkeyCache = Optional.empty();
            String valkeyResourceName = DEFAULT_VALKEY_RESOURCE_NAME;
            String valkeyContainerName = DEFAULT_VALKEY_CONTAINER_NAME;
            String valkeyCliPath = DEFAULT_VALKEY_CLI_PATH;
            Optional<Boolean> verifyProjectionConsistency = Optional.empty();
            Optional<Boolean> verifyTraceCorrelation = Optional.empty();
            Optional<Boolean> verifyObjectStoreArtifact = Optional.empty();
            String objectStoreResourceName = DEFAULT_OBJECT_STORE_RESOURCE_NAME;
            int objectStorePort = DEFAULT_OBJECT_STORE_PORT;
            String objectStoreRegion = DEFAULT_OBJECT_STORE_REGION;
            String objectStoreBucket = DEFAULT_OBJECT_STORE_BUCKET;
            String objectStoreSecretName = DEFAULT_OBJECT_STORE_SECRET_NAME;
            String objectStoreAccessKeySecretKey = DEFAULT_OBJECT_STORE_ACCESS_KEY_SECRET_KEY;
            String objectStoreSecretKeySecretKey = DEFAULT_OBJECT_STORE_SECRET_KEY_SECRET_KEY;
            ArtifactId expectedLobbyWorldArtifactId = DEFAULT_EXPECTED_LOBBY_WORLD_ARTIFACT_ID;
            String expectedLobbyWorldArtifactDigest = DEFAULT_EXPECTED_LOBBY_WORLD_ARTIFACT_DIGEST;
            String expectedLobbyWorldArtifactCompatibility = DEFAULT_EXPECTED_LOBBY_WORLD_ARTIFACT_COMPATIBILITY;
            Optional<Boolean> verifySessionAuthorityState = Optional.empty();
            String sessionAuthorityStateTopic = DEFAULT_SESSION_AUTHORITY_STATE_TOPIC;
            Optional<Boolean> verifySessionAuthorityCommandLog = Optional.empty();
            String sessionAuthorityCommandTopic = DEFAULT_SESSION_AUTHORITY_COMMAND_TOPIC;
            Optional<Boolean> verifySharedShardAllocationCommandLog = Optional.empty();
            String sharedShardAllocationCommandTopic = DEFAULT_SHARED_SHARD_ALLOCATION_COMMAND_TOPIC;
            Optional<Boolean> verifySharedShardAllocationState = Optional.empty();
            String sharedShardAllocationStateTopic = DEFAULT_SHARED_SHARD_ALLOCATION_STATE_TOPIC;
            ExperienceId expectedExperienceId = DEFAULT_EXPECTED_EXPERIENCE_ID;
            PoolId expectedPoolId = DEFAULT_EXPECTED_POOL_ID;
            String kafkaPodName = DEFAULT_KAFKA_POD_NAME;
            String kafkaContainerName = DEFAULT_KAFKA_CONTAINER_NAME;
            String kafkaBootstrapServer = DEFAULT_KAFKA_BOOTSTRAP_SERVER;
            String kafkaConsoleConsumerPath = DEFAULT_KAFKA_CONSOLE_CONSUMER_PATH;
            String expectedSpawnBlock = PaperLobbyProofMessage.SPAWN_BLOCK;
            String expectedSpawnWorld = DEFAULT_EXPECTED_SPAWN_WORLD;
            ResolvedManifestId expectedResolvedManifestId = DEFAULT_EXPECTED_RESOLVED_MANIFEST_ID;
            String expectedTraceId = DEFAULT_EXPECTED_TRACE_ID;
            int expectedBedrockBlockX = DEFAULT_EXPECTED_BEDROCK_BLOCK_X;
            int expectedBedrockBlockY = DEFAULT_EXPECTED_BEDROCK_BLOCK_Y;
            int expectedBedrockBlockZ = DEFAULT_EXPECTED_BEDROCK_BLOCK_Z;
            double expectedPlayerX = DEFAULT_EXPECTED_PLAYER_X;
            double expectedPlayerY = DEFAULT_EXPECTED_PLAYER_Y;
            double expectedPlayerZ = DEFAULT_EXPECTED_PLAYER_Z;
            double expectedPlayerYaw = DEFAULT_EXPECTED_PLAYER_YAW;
            double expectedPlayerPitch = DEFAULT_EXPECTED_PLAYER_PITCH;
            String expectedDisplayName = DEFAULT_EXPECTED_DISPLAY_NAME;
            String expectedRankLabel = DEFAULT_EXPECTED_RANK_LABEL;
            String expectedDecoratedChatContains = DEFAULT_EXPECTED_DECORATED_CHAT_CONTAINS;
            String expectedSecondDisplayName = DEFAULT_EXPECTED_SECOND_DISPLAY_NAME;
            String expectedSecondRankLabel = DEFAULT_EXPECTED_SECOND_RANK_LABEL;
            String expectedSecondDecoratedChatContains = DEFAULT_EXPECTED_SECOND_DECORATED_CHAT_CONTAINS;
            boolean verifyScaleOut = false;
            String scaleOutTriggerLoginUsername = DEFAULT_SCALE_OUT_TRIGGER_LOGIN_USERNAME;
            Optional<String> scaleOutTriggerDeniedReasonContains =
                    Optional.of(VelocityLoginRoutingEvaluator.NO_LOBBY_ROUTE_REASON);
            String scaleOutLoginUsername = DEFAULT_SCALE_OUT_LOGIN_USERNAME;
            String expectedScaleOutDisplayName = DEFAULT_EXPECTED_SCALE_OUT_DISPLAY_NAME;
            String expectedScaleOutRankLabel = DEFAULT_EXPECTED_SCALE_OUT_RANK_LABEL;
            String expectedScaleOutDecoratedChatContains = DEFAULT_EXPECTED_SCALE_OUT_DECORATED_CHAT_CONTAINS;
            Duration scaleOutTimeout = Duration.ofSeconds(60);
            Optional<String> deniedLoginUsername = Optional.empty();
            Optional<String> deniedLoginReasonContains = Optional.empty();
            Duration endpointReadyTimeout = Duration.ofSeconds(120);
            Duration routeAttemptStateTimeout = Duration.ofSeconds(60);
            Duration routeAttemptStateFreshnessSkew = Duration.ofSeconds(5);
            Duration presenceAuthorityStateTimeout = Duration.ofSeconds(60);
            Duration presenceAuthorityStateFreshnessSkew = Duration.ofSeconds(5);
            Duration standardCapabilityStateTimeout = Duration.ofSeconds(60);
            Duration cassandraHotProjectionTimeout = Duration.ofSeconds(60);
            Duration postgresAuthorityRecordTimeout = Duration.ofSeconds(60);
            Duration valkeyCacheTimeout = Duration.ofSeconds(60);
            Duration objectStoreArtifactTimeout = Duration.ofSeconds(60);
            Duration sessionAuthorityStateTimeout = Duration.ofSeconds(60);
            Duration sessionAuthorityStateFreshnessSkew = Duration.ofSeconds(5);
            Duration sharedShardAllocationStateTimeout = Duration.ofSeconds(60);
            Duration timeout = Duration.ofSeconds(10);

            for (String arg : args) {
                if (arg.startsWith("--endpoint-host=")) {
                    endpointHost = Optional.of(value(arg));
                } else if (arg.startsWith("--endpoint-port=")) {
                    endpointPort = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--namespace=")) {
                    namespace = value(arg);
                } else if (arg.startsWith("--service=")) {
                    serviceName = value(arg);
                } else if (arg.startsWith("--kube-context=")) {
                    kubeContext = Optional.of(value(arg));
                } else if (arg.startsWith("--kubeconfig=")) {
                    kubeconfig = Optional.of(value(arg));
                } else if (arg.startsWith("--node-host=")) {
                    nodeHost = value(arg);
                } else if (arg.startsWith("--protocol-version=")) {
                    protocolVersion = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--login-username=")) {
                    loginUsername = value(arg);
                } else if (arg.startsWith("--second-login-username=")) {
                    secondLoginUsername = value(arg);
                } else if (arg.startsWith("--agones-fleet-name=")) {
                    agonesFleetName = value(arg);
                } else if (arg.startsWith("--verify-agones-fleet-state=")) {
                    verifyAgonesFleetState = Optional.of(parseBoolean(value(arg), "verifyAgonesFleetState"));
                } else if (arg.startsWith("--expected-agones-allocated-replicas=")) {
                    expectedAgonesAllocatedReplicas = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--verify-route-attempt-state=")) {
                    verifyRouteAttemptState = Optional.of(parseBoolean(value(arg), "verifyRouteAttemptState"));
                } else if (arg.startsWith("--route-attempt-state-topic=")) {
                    routeAttemptStateTopic = value(arg);
                } else if (arg.startsWith("--verify-login-routing-command-log=")) {
                    verifyLoginRoutingCommandLog =
                            Optional.of(parseBoolean(value(arg), "verifyLoginRoutingCommandLog"));
                } else if (arg.startsWith("--queue-roster-command-topic=")) {
                    queueRosterCommandTopic = value(arg);
                } else if (arg.startsWith("--verify-queue-roster-state=")) {
                    verifyQueueRosterState = Optional.of(parseBoolean(value(arg), "verifyQueueRosterState"));
                } else if (arg.startsWith("--queue-roster-state-topic=")) {
                    queueRosterStateTopic = value(arg);
                } else if (arg.startsWith("--presence-authority-command-topic=")) {
                    presenceAuthorityCommandTopic = value(arg);
                } else if (arg.startsWith("--shared-shard-placement-command-topic=")) {
                    sharedShardPlacementCommandTopic = value(arg);
                } else if (arg.startsWith("--route-attempt-command-topic=")) {
                    routeAttemptCommandTopic = value(arg);
                } else if (arg.startsWith("--lifecycle-trace-command-topic=")) {
                    lifecycleTraceCommandTopic = value(arg);
                } else if (arg.startsWith("--verify-lifecycle-trace-state=")) {
                    verifyLifecycleTraceState =
                            Optional.of(parseBoolean(value(arg), "verifyLifecycleTraceState"));
                } else if (arg.startsWith("--lifecycle-trace-state-topic=")) {
                    lifecycleTraceStateTopic = value(arg);
                } else if (arg.startsWith("--verify-route-authority-command-log=")) {
                    verifyRouteAuthorityCommandLog =
                            Optional.of(parseBoolean(value(arg), "verifyRouteAuthorityCommandLog"));
                } else if (arg.startsWith("--route-authority-command-topic=")) {
                    routeAuthorityCommandTopic = value(arg);
                } else if (arg.startsWith("--verify-route-authority-state=")) {
                    verifyRouteAuthorityState =
                            Optional.of(parseBoolean(value(arg), "verifyRouteAuthorityState"));
                } else if (arg.startsWith("--route-authority-state-topic=")) {
                    routeAuthorityStateTopic = value(arg);
                } else if (arg.startsWith("--verify-host-route-command-logs=")) {
                    verifyHostRouteCommandLogs = Optional.of(parseBoolean(value(arg), "verifyHostRouteCommandLogs"));
                } else if (arg.startsWith("--proxy-route-command-topic=")) {
                    proxyRouteCommandTopic = value(arg);
                } else if (arg.startsWith("--paper-host-command-topic=")) {
                    paperHostCommandTopic = value(arg);
                } else if (arg.startsWith("--verify-host-observation-log=")) {
                    verifyHostObservationLog = Optional.of(parseBoolean(value(arg), "verifyHostObservationLog"));
                } else if (arg.startsWith("--host-observation-topic=")) {
                    hostObservationTopic = value(arg);
                } else if (arg.startsWith("--verify-presence-authority-state=")) {
                    verifyPresenceAuthorityState =
                            Optional.of(parseBoolean(value(arg), "verifyPresenceAuthorityState"));
                } else if (arg.startsWith("--presence-authority-state-topic=")) {
                    presenceAuthorityStateTopic = value(arg);
                } else if (arg.startsWith("--verify-standard-capability-state=")) {
                    verifyStandardCapabilityState =
                            Optional.of(parseBoolean(value(arg), "verifyStandardCapabilityState"));
                } else if (arg.startsWith("--player-profile-state-topic=")) {
                    playerProfileStateTopic = value(arg);
                } else if (arg.startsWith("--rank-state-topic=")) {
                    rankStateTopic = value(arg);
                } else if (arg.startsWith("--punishment-state-topic=")) {
                    punishmentStateTopic = value(arg);
                } else if (arg.startsWith("--verify-standard-capability-command-log=")) {
                    verifyStandardCapabilityCommandLog =
                            Optional.of(parseBoolean(value(arg), "verifyStandardCapabilityCommandLog"));
                } else if (arg.startsWith("--player-profile-command-topic=")) {
                    playerProfileCommandTopic = value(arg);
                } else if (arg.startsWith("--rank-command-topic=")) {
                    rankCommandTopic = value(arg);
                } else if (arg.startsWith("--punishment-command-topic=")) {
                    punishmentCommandTopic = value(arg);
                } else if (arg.startsWith("--verify-reward-state=")) {
                    verifyRewardState = Optional.of(parseBoolean(value(arg), "verifyRewardState"));
                } else if (arg.startsWith("--economy-state-topic=")) {
                    economyStateTopic = value(arg);
                } else if (arg.startsWith("--stats-state-topic=")) {
                    statsStateTopic = value(arg);
                } else if (arg.startsWith("--verify-reward-command-log=")) {
                    verifyRewardCommandLog =
                            Optional.of(parseBoolean(value(arg), "verifyRewardCommandLog"));
                } else if (arg.startsWith("--economy-command-topic=")) {
                    economyCommandTopic = value(arg);
                } else if (arg.startsWith("--stats-command-topic=")) {
                    statsCommandTopic = value(arg);
                } else if (arg.startsWith("--expected-reward-currency-key=")) {
                    expectedRewardCurrencyKey = value(arg);
                } else if (arg.startsWith("--expected-reward-amount-minor-units=")) {
                    expectedRewardAmountMinorUnits = Long.parseLong(value(arg));
                } else if (arg.startsWith("--expected-reward-stat-key=")) {
                    expectedRewardStatKey = value(arg);
                } else if (arg.startsWith("--expected-reward-command-delivery-copies=")) {
                    expectedRewardCommandDeliveryCopies = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--verify-cassandra-hot-projections=")) {
                    verifyCassandraHotProjections =
                            Optional.of(parseBoolean(value(arg), "verifyCassandraHotProjections"));
                } else if (arg.startsWith("--cassandra-pod-name=")) {
                    cassandraPodName = value(arg);
                } else if (arg.startsWith("--cassandra-container-name=")) {
                    cassandraContainerName = value(arg);
                } else if (arg.startsWith("--cassandra-cqlsh-path=")) {
                    cassandraCqlshPath = value(arg);
                } else if (arg.startsWith("--verify-postgres-authority-records=")) {
                    verifyPostgresAuthorityRecords =
                            Optional.of(parseBoolean(value(arg), "verifyPostgresAuthorityRecords"));
                } else if (arg.startsWith("--postgres-pod-name=")) {
                    postgresPodName = value(arg);
                } else if (arg.startsWith("--postgres-container-name=")) {
                    postgresContainerName = value(arg);
                } else if (arg.startsWith("--postgres-psql-path=")) {
                    postgresPsqlPath = value(arg);
                } else if (arg.startsWith("--postgres-database=")) {
                    postgresDatabase = value(arg);
                } else if (arg.startsWith("--postgres-username=")) {
                    postgresUsername = value(arg);
                } else if (arg.startsWith("--verify-valkey-cache=")) {
                    verifyValkeyCache =
                            Optional.of(parseBoolean(value(arg), "verifyValkeyCache"));
                } else if (arg.startsWith("--valkey-resource-name=")) {
                    valkeyResourceName = value(arg);
                } else if (arg.startsWith("--valkey-container-name=")) {
                    valkeyContainerName = value(arg);
                } else if (arg.startsWith("--valkey-cli-path=")) {
                    valkeyCliPath = value(arg);
                } else if (arg.startsWith("--verify-projection-consistency=")) {
                    verifyProjectionConsistency =
                            Optional.of(parseBoolean(value(arg), "verifyProjectionConsistency"));
                } else if (arg.startsWith("--verify-trace-correlation=")) {
                    verifyTraceCorrelation =
                            Optional.of(parseBoolean(value(arg), "verifyTraceCorrelation"));
                } else if (arg.startsWith("--verify-object-store-artifact=")) {
                    verifyObjectStoreArtifact =
                            Optional.of(parseBoolean(value(arg), "verifyObjectStoreArtifact"));
                } else if (arg.startsWith("--object-store-resource-name=")) {
                    objectStoreResourceName = value(arg);
                } else if (arg.startsWith("--object-store-port=")) {
                    objectStorePort = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--object-store-region=")) {
                    objectStoreRegion = value(arg);
                } else if (arg.startsWith("--object-store-bucket=")) {
                    objectStoreBucket = value(arg);
                } else if (arg.startsWith("--object-store-secret-name=")) {
                    objectStoreSecretName = value(arg);
                } else if (arg.startsWith("--object-store-access-key-secret-key=")) {
                    objectStoreAccessKeySecretKey = value(arg);
                } else if (arg.startsWith("--object-store-secret-key-secret-key=")) {
                    objectStoreSecretKeySecretKey = value(arg);
                } else if (arg.startsWith("--expected-lobby-world-artifact-id=")) {
                    expectedLobbyWorldArtifactId = new ArtifactId(value(arg));
                } else if (arg.startsWith("--expected-lobby-world-artifact-digest=")) {
                    expectedLobbyWorldArtifactDigest = value(arg);
                } else if (arg.startsWith("--expected-lobby-world-artifact-compatibility=")) {
                    expectedLobbyWorldArtifactCompatibility = value(arg);
                } else if (arg.startsWith("--verify-session-authority-state=")) {
                    verifySessionAuthorityState =
                            Optional.of(parseBoolean(value(arg), "verifySessionAuthorityState"));
                } else if (arg.startsWith("--session-authority-state-topic=")) {
                    sessionAuthorityStateTopic = value(arg);
                } else if (arg.startsWith("--verify-session-authority-command-log=")) {
                    verifySessionAuthorityCommandLog =
                            Optional.of(parseBoolean(value(arg), "verifySessionAuthorityCommandLog"));
                } else if (arg.startsWith("--session-authority-command-topic=")) {
                    sessionAuthorityCommandTopic = value(arg);
                } else if (arg.startsWith("--verify-shared-shard-allocation-command-log=")) {
                    verifySharedShardAllocationCommandLog =
                            Optional.of(parseBoolean(value(arg), "verifySharedShardAllocationCommandLog"));
                } else if (arg.startsWith("--shared-shard-allocation-command-topic=")) {
                    sharedShardAllocationCommandTopic = value(arg);
                } else if (arg.startsWith("--verify-shared-shard-allocation-state=")) {
                    verifySharedShardAllocationState =
                            Optional.of(parseBoolean(value(arg), "verifySharedShardAllocationState"));
                } else if (arg.startsWith("--shared-shard-allocation-state-topic=")) {
                    sharedShardAllocationStateTopic = value(arg);
                } else if (arg.startsWith("--kafka-pod-name=")) {
                    kafkaPodName = value(arg);
                } else if (arg.startsWith("--kafka-container-name=")) {
                    kafkaContainerName = value(arg);
                } else if (arg.startsWith("--kafka-bootstrap-server=")) {
                    kafkaBootstrapServer = value(arg);
                } else if (arg.startsWith("--kafka-console-consumer-path=")) {
                    kafkaConsoleConsumerPath = value(arg);
                } else if (arg.startsWith("--expected-lobby-spawn-block=")) {
                    expectedSpawnBlock = value(arg);
                } else if (arg.startsWith("--expected-lobby-spawn-world=")) {
                    expectedSpawnWorld = value(arg);
                } else if (arg.startsWith("--expected-lobby-experience-id=")) {
                    expectedExperienceId = new ExperienceId(value(arg));
                } else if (arg.startsWith("--expected-lobby-pool-id=")) {
                    expectedPoolId = new PoolId(value(arg));
                } else if (arg.startsWith("--expected-lobby-resolved-manifest-id=")) {
                    expectedResolvedManifestId = new ResolvedManifestId(value(arg));
                } else if (arg.startsWith("--expected-lobby-trace-id=")) {
                    expectedTraceId = value(arg);
                } else if (arg.startsWith("--expected-lobby-bedrock-block-x=")) {
                    expectedBedrockBlockX = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--expected-lobby-bedrock-block-y=")) {
                    expectedBedrockBlockY = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--expected-lobby-bedrock-block-z=")) {
                    expectedBedrockBlockZ = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--expected-lobby-player-x=")) {
                    expectedPlayerX = Double.parseDouble(value(arg));
                } else if (arg.startsWith("--expected-lobby-player-y=")) {
                    expectedPlayerY = Double.parseDouble(value(arg));
                } else if (arg.startsWith("--expected-lobby-player-z=")) {
                    expectedPlayerZ = Double.parseDouble(value(arg));
                } else if (arg.startsWith("--expected-lobby-player-yaw=")) {
                    expectedPlayerYaw = Double.parseDouble(value(arg));
                } else if (arg.startsWith("--expected-lobby-player-pitch=")) {
                    expectedPlayerPitch = Double.parseDouble(value(arg));
                } else if (arg.startsWith("--expected-lobby-display-name=")) {
                    expectedDisplayName = value(arg);
                } else if (arg.startsWith("--expected-lobby-rank-label=")) {
                    expectedRankLabel = value(arg);
                } else if (arg.startsWith("--expected-lobby-decorated-chat-contains=")) {
                    expectedDecoratedChatContains = value(arg);
                } else if (arg.startsWith("--expected-second-lobby-display-name=")) {
                    expectedSecondDisplayName = value(arg);
                } else if (arg.startsWith("--expected-second-lobby-rank-label=")) {
                    expectedSecondRankLabel = value(arg);
                } else if (arg.startsWith("--expected-second-lobby-decorated-chat-contains=")) {
                    expectedSecondDecoratedChatContains = value(arg);
                } else if (arg.startsWith("--verify-scale-out=")) {
                    verifyScaleOut = parseBoolean(value(arg), "verifyScaleOut");
                } else if (arg.startsWith("--scale-out-trigger-login-username=")) {
                    scaleOutTriggerLoginUsername = value(arg);
                } else if (arg.startsWith("--scale-out-trigger-denied-reason-contains=")) {
                    scaleOutTriggerDeniedReasonContains = Optional.of(value(arg));
                } else if (arg.startsWith("--scale-out-login-username=")) {
                    scaleOutLoginUsername = value(arg);
                } else if (arg.startsWith("--expected-scale-out-lobby-display-name=")) {
                    expectedScaleOutDisplayName = value(arg);
                } else if (arg.startsWith("--expected-scale-out-lobby-rank-label=")) {
                    expectedScaleOutRankLabel = value(arg);
                } else if (arg.startsWith("--expected-scale-out-lobby-decorated-chat-contains=")) {
                    expectedScaleOutDecoratedChatContains = value(arg);
                } else if (arg.startsWith("--scale-out-timeout=")) {
                    scaleOutTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--denied-login-username=")) {
                    deniedLoginUsername = Optional.of(value(arg));
                } else if (arg.startsWith("--denied-login-reason-contains=")) {
                    deniedLoginReasonContains = Optional.of(value(arg));
                } else if (arg.startsWith("--endpoint-ready-timeout=")) {
                    endpointReadyTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--route-attempt-state-timeout=")) {
                    routeAttemptStateTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--route-attempt-state-freshness-skew=")) {
                    routeAttemptStateFreshnessSkew = Duration.parse(value(arg));
                } else if (arg.startsWith("--presence-authority-state-timeout=")) {
                    presenceAuthorityStateTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--presence-authority-state-freshness-skew=")) {
                    presenceAuthorityStateFreshnessSkew = Duration.parse(value(arg));
                } else if (arg.startsWith("--standard-capability-state-timeout=")) {
                    standardCapabilityStateTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--cassandra-hot-projection-timeout=")) {
                    cassandraHotProjectionTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--postgres-authority-record-timeout=")) {
                    postgresAuthorityRecordTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--valkey-cache-timeout=")) {
                    valkeyCacheTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--object-store-artifact-timeout=")) {
                    objectStoreArtifactTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--session-authority-state-timeout=")) {
                    sessionAuthorityStateTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--session-authority-state-freshness-skew=")) {
                    sessionAuthorityStateFreshnessSkew = Duration.parse(value(arg));
                } else if (arg.startsWith("--shared-shard-allocation-state-timeout=")) {
                    sharedShardAllocationStateTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--timeout=")) {
                    timeout = Duration.parse(value(arg));
                } else {
                    throw new IllegalArgumentException("Unsupported lobby cluster E2E verifier argument: " + arg);
                }
            }
            return new VerificationConfig(
                    endpointHost,
                    endpointPort,
                    namespace,
                    serviceName,
                    kubeContext,
                    kubeconfig,
                    nodeHost,
                    agonesFleetName,
                    verifyAgonesFleetState.orElse(endpointHost.isEmpty()),
                    expectedAgonesAllocatedReplicas,
                    verifyRouteAttemptState.orElse(endpointHost.isEmpty()),
                    routeAttemptStateTopic,
                    verifyLoginRoutingCommandLog.orElse(endpointHost.isEmpty()),
                    queueRosterCommandTopic,
                    verifyQueueRosterState.orElse(endpointHost.isEmpty()),
                    queueRosterStateTopic,
                    presenceAuthorityCommandTopic,
                    sharedShardPlacementCommandTopic,
                    routeAttemptCommandTopic,
                    lifecycleTraceCommandTopic,
                    verifyLifecycleTraceState.orElse(endpointHost.isEmpty()),
                    lifecycleTraceStateTopic,
                    verifyRouteAuthorityCommandLog.orElse(endpointHost.isEmpty()),
                    routeAuthorityCommandTopic,
                    verifyRouteAuthorityState.orElse(endpointHost.isEmpty()),
                    routeAuthorityStateTopic,
                    verifyHostRouteCommandLogs.orElse(endpointHost.isEmpty()),
                    proxyRouteCommandTopic,
                    paperHostCommandTopic,
                    verifyHostObservationLog.orElse(endpointHost.isEmpty()),
                    hostObservationTopic,
                    verifyPresenceAuthorityState.orElse(endpointHost.isEmpty()),
                    presenceAuthorityStateTopic,
                    verifyStandardCapabilityState.orElse(endpointHost.isEmpty()),
                    playerProfileStateTopic,
                    rankStateTopic,
                    punishmentStateTopic,
                    verifyStandardCapabilityCommandLog.orElse(endpointHost.isEmpty()),
                    playerProfileCommandTopic,
                    rankCommandTopic,
                    punishmentCommandTopic,
                    verifyRewardState.orElse(endpointHost.isEmpty()),
                    economyStateTopic,
                    statsStateTopic,
                    verifyRewardCommandLog.orElse(endpointHost.isEmpty()),
                    economyCommandTopic,
                    statsCommandTopic,
                    expectedRewardCurrencyKey,
                    expectedRewardAmountMinorUnits,
                    expectedRewardStatKey,
                    expectedRewardCommandDeliveryCopies,
                    verifyCassandraHotProjections.orElse(endpointHost.isEmpty()),
                    cassandraPodName,
                    cassandraContainerName,
                    cassandraCqlshPath,
                    verifyPostgresAuthorityRecords.orElse(endpointHost.isEmpty()),
                    postgresPodName,
                    postgresContainerName,
                    postgresPsqlPath,
                    postgresDatabase,
                    postgresUsername,
                    verifyValkeyCache.orElse(endpointHost.isEmpty()),
                    valkeyResourceName,
                    valkeyContainerName,
                    valkeyCliPath,
                    verifyProjectionConsistency.orElse(endpointHost.isEmpty()),
                    verifyTraceCorrelation.orElse(endpointHost.isEmpty()),
                    verifyObjectStoreArtifact.orElse(endpointHost.isEmpty()),
                    objectStoreResourceName,
                    objectStorePort,
                    objectStoreRegion,
                    objectStoreBucket,
                    objectStoreSecretName,
                    objectStoreAccessKeySecretKey,
                    objectStoreSecretKeySecretKey,
                    expectedLobbyWorldArtifactId,
                    expectedLobbyWorldArtifactDigest,
                    expectedLobbyWorldArtifactCompatibility,
                    verifySessionAuthorityState.orElse(endpointHost.isEmpty()),
                    sessionAuthorityStateTopic,
                    verifySessionAuthorityCommandLog.orElse(endpointHost.isEmpty()),
                    sessionAuthorityCommandTopic,
                    verifySharedShardAllocationCommandLog.orElse(endpointHost.isEmpty()),
                    sharedShardAllocationCommandTopic,
                    verifySharedShardAllocationState.orElse(endpointHost.isEmpty()),
                    sharedShardAllocationStateTopic,
                    expectedExperienceId,
                    expectedPoolId,
                    kafkaPodName,
                    kafkaContainerName,
                    kafkaBootstrapServer,
                    kafkaConsoleConsumerPath,
                    protocolVersion,
                    loginUsername,
                    secondLoginUsername,
                    expectedSpawnBlock,
                    expectedSpawnWorld,
                    expectedResolvedManifestId,
                    expectedTraceId,
                    expectedBedrockBlockX,
                    expectedBedrockBlockY,
                    expectedBedrockBlockZ,
                    expectedPlayerX,
                    expectedPlayerY,
                    expectedPlayerZ,
                    expectedPlayerYaw,
                    expectedPlayerPitch,
                    expectedDisplayName,
                    expectedRankLabel,
                    expectedDecoratedChatContains,
                    expectedSecondDisplayName,
                    expectedSecondRankLabel,
                    expectedSecondDecoratedChatContains,
                    verifyScaleOut,
                    scaleOutTriggerLoginUsername,
                    scaleOutTriggerDeniedReasonContains,
                    scaleOutLoginUsername,
                    expectedScaleOutDisplayName,
                    expectedScaleOutRankLabel,
                    expectedScaleOutDecoratedChatContains,
                    scaleOutTimeout,
                    deniedLoginUsername,
                    deniedLoginReasonContains,
                    endpointReadyTimeout,
                    routeAttemptStateTimeout,
                    routeAttemptStateFreshnessSkew,
                    presenceAuthorityStateTimeout,
                    presenceAuthorityStateFreshnessSkew,
                    standardCapabilityStateTimeout,
                    cassandraHotProjectionTimeout,
                    postgresAuthorityRecordTimeout,
                    valkeyCacheTimeout,
                    objectStoreArtifactTimeout,
                    sessionAuthorityStateTimeout,
                    sessionAuthorityStateFreshnessSkew,
                    sharedShardAllocationStateTimeout,
                    timeout);
        }

        private static String value(String arg) {
            return arg.substring(arg.indexOf('=') + 1);
        }

        private static boolean parseBoolean(String value, String name) {
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            throw new IllegalArgumentException(name + " must be true or false, got " + value);
        }
    }

    record ResolvedMinecraftEndpoint(String host, int port) {
        ResolvedMinecraftEndpoint {
            host = requireNonBlank(host, "host");
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
        }
    }

    private record EndpointStatus(
            ResolvedMinecraftEndpoint endpoint,
            MinecraftStatusSnapshot status) {
        private EndpointStatus {
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            status = Objects.requireNonNull(status, "status");
        }
    }

    private record KubectlPortForward(
            List<String> command,
            Process process,
            Thread reader,
            int localPort,
            StringBuilder output) implements AutoCloseable {
        private KubectlPortForward {
            command = List.copyOf(Objects.requireNonNull(command, "command"));
            process = Objects.requireNonNull(process, "process");
            reader = Objects.requireNonNull(reader, "reader");
            if (localPort < 1 || localPort > 65_535) {
                throw new IllegalArgumentException("localPort out of range: " + localPort);
            }
            output = Objects.requireNonNull(output, "output");
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            try {
                reader.join(1_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record KubectlResult(
            List<String> command,
            int exitCode,
            String output) {
        private KubectlResult {
            command = List.copyOf(Objects.requireNonNull(command, "command"));
            output = Objects.requireNonNull(output, "output");
        }
    }

    record RouteAttemptExpectation(
            String label,
            String username,
            SubjectId subjectId,
            RouteId routeId,
            SessionId sessionId,
            SlotId slotId,
            InstanceId targetInstanceId,
            ResolvedManifestId targetResolvedManifestId,
            String traceId,
            Instant minimumUpdatedAt) {
        RouteAttemptExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            routeId = Objects.requireNonNull(routeId, "routeId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            slotId = Objects.requireNonNull(slotId, "slotId");
            targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
            targetResolvedManifestId = Objects.requireNonNull(targetResolvedManifestId, "targetResolvedManifestId");
            traceId = requireNonBlank(traceId, "traceId");
            minimumUpdatedAt = Objects.requireNonNull(minimumUpdatedAt, "minimumUpdatedAt");
        }

        static RouteAttemptExpectation from(
                String label,
                String username,
                PaperLobbyProofMessage proof) {
            return from(label, username, proof, Instant.EPOCH);
        }

        static RouteAttemptExpectation from(
                String label,
                String username,
                PaperLobbyProofMessage proof,
                Instant minimumUpdatedAt) {
            Objects.requireNonNull(proof, "proof");
            return new RouteAttemptExpectation(
                    label,
                    username,
                    proof.subjectId(),
                    proof.routeId(),
                    proof.sessionId(),
                    proof.slotId(),
                    proof.instanceId(),
                    proof.resolvedManifestId(),
                    proof.traceId(),
                    minimumUpdatedAt);
        }
    }

    record DeniedRouteAttemptExpectation(
            String label,
            String username,
            SubjectId subjectId,
            Instant minimumUpdatedAt) {
        DeniedRouteAttemptExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            minimumUpdatedAt = Objects.requireNonNull(minimumUpdatedAt, "minimumUpdatedAt");
        }

        static DeniedRouteAttemptExpectation from(String label, String username) {
            return from(label, username, Instant.EPOCH);
        }

        static DeniedRouteAttemptExpectation from(String label, String username, Instant minimumUpdatedAt) {
            return new DeniedRouteAttemptExpectation(
                    label,
                    username,
                    LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username),
                    minimumUpdatedAt);
        }
    }

    record RouteAuthorityCommandExpectation(
            String label,
            String username,
            SubjectId subjectId,
            RouteId routeId,
            SessionId sessionId,
            InstanceId targetInstanceId,
            String traceId,
            Instant minimumRouteExpiresAt,
            Instant minimumAcknowledgedAt) {
        RouteAuthorityCommandExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            routeId = Objects.requireNonNull(routeId, "routeId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
            traceId = requireNonBlank(traceId, "traceId");
            minimumRouteExpiresAt = Objects.requireNonNull(minimumRouteExpiresAt, "minimumRouteExpiresAt");
            minimumAcknowledgedAt = Objects.requireNonNull(minimumAcknowledgedAt, "minimumAcknowledgedAt");
        }

        static RouteAuthorityCommandExpectation from(RouteAttemptExpectation expectation) {
            Objects.requireNonNull(expectation, "expectation");
            return new RouteAuthorityCommandExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    expectation.routeId(),
                    expectation.sessionId(),
                    expectation.targetInstanceId(),
                    expectation.traceId(),
                    expectation.minimumUpdatedAt(),
                    expectation.minimumUpdatedAt());
        }

        List<String> compatibilityMismatches(RouteAuthorityCommandExpectation other) {
            Objects.requireNonNull(other, "other");
            List<String> mismatches = new ArrayList<>();
            if (!subjectId.equals(other.subjectId())) {
                mismatches.add("subjectId " + subjectId.value() + " vs " + other.subjectId().value());
            }
            if (!sessionId.equals(other.sessionId())) {
                mismatches.add("sessionId " + sessionId.value() + " vs " + other.sessionId().value());
            }
            if (!targetInstanceId.equals(other.targetInstanceId())) {
                mismatches.add("targetInstanceId " + targetInstanceId.value()
                        + " vs " + other.targetInstanceId().value());
            }
            if (!traceId.equals(other.traceId())) {
                mismatches.add("traceId " + traceId + " vs " + other.traceId());
            }
            return mismatches;
        }
    }

    record DeniedRouteAuthorityCommandExpectation(
            String label,
            String username,
            SubjectId subjectId,
            Instant minimumReceivedAt) {
        DeniedRouteAuthorityCommandExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            minimumReceivedAt = Objects.requireNonNull(minimumReceivedAt, "minimumReceivedAt");
        }

        static DeniedRouteAuthorityCommandExpectation from(DeniedRouteAttemptExpectation expectation) {
            Objects.requireNonNull(expectation, "expectation");
            return new DeniedRouteAuthorityCommandExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    expectation.minimumUpdatedAt());
        }
    }

    record RouteAuthorityStateExpectation(
            String label,
            String username,
            SubjectId subjectId,
            RouteId routeId,
            SessionId sessionId,
            InstanceId targetInstanceId,
            Instant minimumRouteExpiresAt,
            Instant minimumCompletedAt) {
        RouteAuthorityStateExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            routeId = Objects.requireNonNull(routeId, "routeId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
            minimumRouteExpiresAt = Objects.requireNonNull(minimumRouteExpiresAt, "minimumRouteExpiresAt");
            minimumCompletedAt = Objects.requireNonNull(minimumCompletedAt, "minimumCompletedAt");
        }

        static RouteAuthorityStateExpectation from(RouteAttemptExpectation expectation) {
            Objects.requireNonNull(expectation, "expectation");
            return new RouteAuthorityStateExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    expectation.routeId(),
                    expectation.sessionId(),
                    expectation.targetInstanceId(),
                    expectation.minimumUpdatedAt(),
                    expectation.minimumUpdatedAt());
        }

        List<String> compatibilityMismatches(RouteAuthorityStateExpectation other) {
            Objects.requireNonNull(other, "other");
            List<String> mismatches = new ArrayList<>();
            if (!subjectId.equals(other.subjectId())) {
                mismatches.add("subjectId " + subjectId.value() + " vs " + other.subjectId().value());
            }
            if (!sessionId.equals(other.sessionId())) {
                mismatches.add("sessionId " + sessionId.value() + " vs " + other.sessionId().value());
            }
            if (!targetInstanceId.equals(other.targetInstanceId())) {
                mismatches.add("targetInstanceId " + targetInstanceId.value()
                        + " vs " + other.targetInstanceId().value());
            }
            return mismatches;
        }
    }

    record DeniedRouteAuthorityStateExpectation(
            String label,
            String username,
            SubjectId subjectId,
            Instant minimumUpdatedAt) {
        DeniedRouteAuthorityStateExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            minimumUpdatedAt = Objects.requireNonNull(minimumUpdatedAt, "minimumUpdatedAt");
        }

        static DeniedRouteAuthorityStateExpectation from(DeniedRouteAttemptExpectation expectation) {
            Objects.requireNonNull(expectation, "expectation");
            return new DeniedRouteAuthorityStateExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    expectation.minimumUpdatedAt());
        }
    }

    record HostRouteCommandExpectation(
            String label,
            String username,
            SubjectId subjectId,
            String routeAttemptId,
            RouteId routeId,
            SessionId sessionId,
            InstanceId targetInstanceId,
            ResolvedManifestId resolvedManifestId,
            String traceId) {
        HostRouteCommandExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            routeAttemptId = requireNonBlank(routeAttemptId, "routeAttemptId");
            routeId = Objects.requireNonNull(routeId, "routeId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
            resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
            traceId = requireNonBlank(traceId, "traceId");
        }

        static HostRouteCommandExpectation from(RouteAttemptExpectation expectation) {
            Objects.requireNonNull(expectation, "expectation");
            return new HostRouteCommandExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    expectedRouteAttemptId(expectation.subjectId()),
                    expectation.routeId(),
                    expectation.sessionId(),
                    expectation.targetInstanceId(),
                    expectation.targetResolvedManifestId(),
                    expectation.traceId());
        }

        List<String> compatibilityMismatches(HostRouteCommandExpectation other) {
            Objects.requireNonNull(other, "other");
            List<String> mismatches = new ArrayList<>();
            if (!subjectId.equals(other.subjectId())) {
                mismatches.add("subjectId " + subjectId.value() + " vs " + other.subjectId().value());
            }
            if (!routeId.equals(other.routeId())) {
                mismatches.add("routeId " + routeId.value() + " vs " + other.routeId().value());
            }
            if (!sessionId.equals(other.sessionId())) {
                mismatches.add("sessionId " + sessionId.value() + " vs " + other.sessionId().value());
            }
            if (!targetInstanceId.equals(other.targetInstanceId())) {
                mismatches.add("targetInstanceId " + targetInstanceId.value()
                        + " vs " + other.targetInstanceId().value());
            }
            if (!resolvedManifestId.equals(other.resolvedManifestId())) {
                mismatches.add("resolvedManifestId " + resolvedManifestId.value()
                        + " vs " + other.resolvedManifestId().value());
            }
            if (!traceId.equals(other.traceId())) {
                mismatches.add("traceId " + traceId + " vs " + other.traceId());
            }
            return mismatches;
        }
    }

    record DeniedHostRouteCommandExpectation(
            String label,
            String username,
            SubjectId subjectId,
            String routeAttemptId) {
        DeniedHostRouteCommandExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            routeAttemptId = requireNonBlank(routeAttemptId, "routeAttemptId");
        }

        static DeniedHostRouteCommandExpectation from(DeniedRouteAttemptExpectation expectation) {
            Objects.requireNonNull(expectation, "expectation");
            return new DeniedHostRouteCommandExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    expectedRouteAttemptId(expectation.subjectId()));
        }
    }

    record LoginRoutingCommandExpectation(
            String label,
            String username,
            SubjectId subjectId,
            String subjectSuffix,
            PresenceId presenceId,
            String placementAttemptId,
            String routeAttemptId,
            RouteId routeId,
            SessionId sessionId,
            SlotId slotId,
            InstanceId targetInstanceId,
            ResolvedManifestId targetResolvedManifestId,
            Instant minimumLeaseExpiresAt) {
        LoginRoutingCommandExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            subjectSuffix = requireNonBlank(subjectSuffix, "subjectSuffix");
            presenceId = Objects.requireNonNull(presenceId, "presenceId");
            placementAttemptId = requireNonBlank(placementAttemptId, "placementAttemptId");
            routeAttemptId = requireNonBlank(routeAttemptId, "routeAttemptId");
            routeId = Objects.requireNonNull(routeId, "routeId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            slotId = Objects.requireNonNull(slotId, "slotId");
            targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
            targetResolvedManifestId = Objects.requireNonNull(targetResolvedManifestId, "targetResolvedManifestId");
            minimumLeaseExpiresAt = Objects.requireNonNull(minimumLeaseExpiresAt, "minimumLeaseExpiresAt");
        }

        static LoginRoutingCommandExpectation from(RouteAttemptExpectation expectation) {
            Objects.requireNonNull(expectation, "expectation");
            String suffix = compactSubject(expectation.subjectId());
            return new LoginRoutingCommandExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    suffix,
                    velocityPresenceId(expectation.subjectId()),
                    "placement-velocity-login-" + suffix,
                    expectedRouteAttemptId(expectation.subjectId()),
                    expectation.routeId(),
                    expectation.sessionId(),
                    expectation.slotId(),
                    expectation.targetInstanceId(),
                    expectation.targetResolvedManifestId(),
                    expectation.minimumUpdatedAt());
        }

        List<String> compatibilityMismatches(LoginRoutingCommandExpectation other) {
            Objects.requireNonNull(other, "other");
            List<String> mismatches = new ArrayList<>();
            if (!subjectId.equals(other.subjectId())) {
                mismatches.add("subjectId " + subjectId.value() + " vs " + other.subjectId().value());
            }
            if (!presenceId.equals(other.presenceId())) {
                mismatches.add("presenceId " + presenceId.value() + " vs " + other.presenceId().value());
            }
            if (!routeId.equals(other.routeId())) {
                mismatches.add("routeId " + routeId.value() + " vs " + other.routeId().value());
            }
            if (!sessionId.equals(other.sessionId())) {
                mismatches.add("sessionId " + sessionId.value() + " vs " + other.sessionId().value());
            }
            if (!slotId.equals(other.slotId())) {
                mismatches.add("slotId " + slotId.value() + " vs " + other.slotId().value());
            }
            if (!targetInstanceId.equals(other.targetInstanceId())) {
                mismatches.add("targetInstanceId " + targetInstanceId.value()
                        + " vs " + other.targetInstanceId().value());
            }
            if (!targetResolvedManifestId.equals(other.targetResolvedManifestId())) {
                mismatches.add("targetResolvedManifestId " + targetResolvedManifestId.value()
                        + " vs " + other.targetResolvedManifestId().value());
            }
            return mismatches;
        }
    }

    record DeniedLoginRoutingCommandExpectation(
            String label,
            String username,
            SubjectId subjectId,
            String routeAttemptId) {
        DeniedLoginRoutingCommandExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            routeAttemptId = requireNonBlank(routeAttemptId, "routeAttemptId");
        }

        static DeniedLoginRoutingCommandExpectation from(DeniedRouteAttemptExpectation expectation) {
            Objects.requireNonNull(expectation, "expectation");
            return new DeniedLoginRoutingCommandExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    expectedRouteAttemptId(expectation.subjectId()));
        }
    }

    record HostObservationExpectation(
            String label,
            String username,
            SubjectId subjectId,
            RouteId routeId,
            SessionId sessionId,
            InstanceId instanceId,
            PoolId poolId,
            String traceId,
            Instant minimumObservedAt) {
        HostObservationExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            routeId = Objects.requireNonNull(routeId, "routeId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
            poolId = Objects.requireNonNull(poolId, "poolId");
            traceId = requireNonBlank(traceId, "traceId");
            minimumObservedAt = Objects.requireNonNull(minimumObservedAt, "minimumObservedAt");
        }

        static HostObservationExpectation from(
                RouteAttemptExpectation expectation,
                PoolId poolId,
                Instant minimumObservedAt) {
            Objects.requireNonNull(expectation, "expectation");
            return new HostObservationExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    expectation.routeId(),
                    expectation.sessionId(),
                    expectation.targetInstanceId(),
                    poolId,
                    expectedPaperAttachTraceId(expectation.subjectId()),
                    minimumObservedAt);
        }
    }

    record DeniedHostObservationExpectation(
            String label,
            String username,
            SubjectId subjectId,
            Instant minimumObservedAt) {
        DeniedHostObservationExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            minimumObservedAt = Objects.requireNonNull(minimumObservedAt, "minimumObservedAt");
        }

        static DeniedHostObservationExpectation from(
                DeniedRouteAttemptExpectation expectation,
                Instant minimumObservedAt) {
            Objects.requireNonNull(expectation, "expectation");
            return new DeniedHostObservationExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    minimumObservedAt);
        }
    }

    record PresenceAuthorityStateExpectation(
            String label,
            String username,
            SubjectId subjectId,
            PresenceId presenceId,
            SessionId sessionId,
            RouteId routeId,
            Instant minimumLeaseExpiresAt) {
        PresenceAuthorityStateExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            presenceId = Objects.requireNonNull(presenceId, "presenceId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            routeId = Objects.requireNonNull(routeId, "routeId");
            minimumLeaseExpiresAt = Objects.requireNonNull(minimumLeaseExpiresAt, "minimumLeaseExpiresAt");
        }

        static PresenceAuthorityStateExpectation from(
                RouteAttemptExpectation expectation,
                Instant minimumLeaseExpiresAt) {
            Objects.requireNonNull(expectation, "expectation");
            return new PresenceAuthorityStateExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    velocityPresenceId(expectation.subjectId()),
                    expectation.sessionId(),
                    expectation.routeId(),
                    minimumLeaseExpiresAt);
        }

        List<String> compatibilityMismatches(PresenceAuthorityStateExpectation other) {
            Objects.requireNonNull(other, "other");
            List<String> mismatches = new ArrayList<>();
            if (!presenceId.equals(other.presenceId())) {
                mismatches.add("presenceId " + presenceId.value() + " vs " + other.presenceId().value());
            }
            if (!sessionId.equals(other.sessionId())) {
                mismatches.add("sessionId " + sessionId.value() + " vs " + other.sessionId().value());
            }
            if (!routeId.equals(other.routeId())) {
                mismatches.add("routeId " + routeId.value() + " vs " + other.routeId().value());
            }
            return mismatches;
        }
    }

    record DeniedPresenceAuthorityStateExpectation(
            String label,
            String username,
            SubjectId subjectId,
            Instant minimumObservedAt) {
        DeniedPresenceAuthorityStateExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            minimumObservedAt = Objects.requireNonNull(minimumObservedAt, "minimumObservedAt");
        }

        static DeniedPresenceAuthorityStateExpectation from(
                String label,
                String username,
                Instant minimumObservedAt) {
            return new DeniedPresenceAuthorityStateExpectation(
                    label,
                    username,
                    LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username),
                    minimumObservedAt);
        }
    }

    record StandardCapabilityStateExpectation(
            String label,
            String username,
            SubjectId subjectId,
            String displayName,
            String rankKey) {
        StandardCapabilityStateExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            displayName = requireNonBlank(displayName, "displayName");
            rankKey = requireNonBlank(rankKey, "rankKey");
        }

        static StandardCapabilityStateExpectation from(
                String label,
                String username,
                PaperLobbyProofMessage proof,
                String displayName,
                String rankKey) {
            Objects.requireNonNull(proof, "proof");
            return new StandardCapabilityStateExpectation(
                    label,
                    username,
                    proof.subjectId(),
                    displayName,
                    rankKey);
        }
    }

    record PunishmentCapabilityStateExpectation(
            String label,
            String username,
            SubjectId subjectId,
            Optional<String> reasonContains,
            Instant activeAt) {
        PunishmentCapabilityStateExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            reasonContains = reasonContains == null ? Optional.empty() : reasonContains.filter(value -> !value.isBlank());
            activeAt = Objects.requireNonNull(activeAt, "activeAt");
        }

        static PunishmentCapabilityStateExpectation from(
                String label,
                String username,
                Optional<String> reasonContains,
                Instant activeAt) {
            return new PunishmentCapabilityStateExpectation(
                    label,
                    username,
                    LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username),
                    reasonContains,
                    activeAt);
        }
    }

    record RewardCommandExpectation(
            String label,
            String username,
            SubjectId subjectId,
            RouteId routeId,
            SessionId sessionId,
            InstanceId instanceId,
            ExperienceId experienceId,
            String currencyKey,
            long rewardAmountMinorUnits,
            String statKey,
            int expectedDeliveryCopies,
            Instant minimumOccurredAt) {
        RewardCommandExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            routeId = Objects.requireNonNull(routeId, "routeId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
            experienceId = Objects.requireNonNull(experienceId, "experienceId");
            currencyKey = requireNonBlank(currencyKey, "currencyKey").toLowerCase(Locale.ROOT);
            if (rewardAmountMinorUnits <= 0) {
                throw new IllegalArgumentException("rewardAmountMinorUnits must be positive");
            }
            statKey = requireNonBlank(statKey, "statKey").toLowerCase(Locale.ROOT);
            if (expectedDeliveryCopies <= 0) {
                throw new IllegalArgumentException("expectedDeliveryCopies must be positive");
            }
            minimumOccurredAt = Objects.requireNonNull(minimumOccurredAt, "minimumOccurredAt");
        }

        static RewardCommandExpectation from(
                RouteAttemptExpectation expectation,
                VerificationConfig config) {
            Objects.requireNonNull(expectation, "expectation");
            Objects.requireNonNull(config, "config");
            return new RewardCommandExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    expectation.routeId(),
                    expectation.sessionId(),
                    expectation.targetInstanceId(),
                    config.expectedExperienceId(),
                    config.expectedRewardCurrencyKey(),
                    config.expectedRewardAmountMinorUnits(),
                    config.expectedRewardStatKey(),
                    config.expectedRewardCommandDeliveryCopies(),
                    expectation.minimumUpdatedAt());
        }
    }

    record DeniedRewardCommandExpectation(
            String label,
            String username,
            SubjectId subjectId,
            Instant minimumOccurredAt) {
        DeniedRewardCommandExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            minimumOccurredAt = Objects.requireNonNull(minimumOccurredAt, "minimumOccurredAt");
        }

        static DeniedRewardCommandExpectation from(DeniedRouteAttemptExpectation expectation) {
            Objects.requireNonNull(expectation, "expectation");
            return new DeniedRewardCommandExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.subjectId(),
                    expectation.minimumUpdatedAt());
        }
    }

    private static PresenceId velocityPresenceId(SubjectId subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        return new PresenceId("presence-velocity-login-" + compactSubject(subjectId));
    }

    private static String expectedRouteAttemptId(SubjectId subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        return "route-attempt-velocity-login-" + compactSubject(subjectId);
    }

    private static String expectedVelocityLifecycleTraceId(LoginRoutingCommandExpectation expectation) {
        return "trace-velocity-login-" + expectation.subjectSuffix();
    }

    private static String compactSubject(SubjectId subjectId) {
        return Objects.requireNonNull(subjectId, "subjectId").value().toString().replace("-", "");
    }

    private static String compactValue(String value) {
        return requireNonBlank(value, "value").replace("-", "");
    }

    private static String compactRouteAttemptId(RouteAttemptId routeAttemptId) {
        return Objects.requireNonNull(routeAttemptId, "routeAttemptId").value().replace("-", "");
    }

    private static String shortSubject(SubjectId subjectId) {
        return Objects.requireNonNull(subjectId, "subjectId").value().toString().replace("-", "").substring(0, 16);
    }

    private static String expectedPaperAttachTraceId(SubjectId subjectId) {
        return "trace-paper-attach-" + Objects.requireNonNull(subjectId, "subjectId").value();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private static String failureMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    record SessionAuthorityCommandExpectation(
            String label,
            String username,
            SessionId sessionId,
            SlotId slotId,
            InstanceId ownerInstanceId,
            ResolvedManifestId resolvedManifestId,
            String traceId,
            Instant minimumLeaseExpiresAt) {
        SessionAuthorityCommandExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            slotId = Objects.requireNonNull(slotId, "slotId");
            ownerInstanceId = Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
            resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
            traceId = requireNonBlank(traceId, "traceId");
            minimumLeaseExpiresAt = Objects.requireNonNull(minimumLeaseExpiresAt, "minimumLeaseExpiresAt");
        }

        static SessionAuthorityCommandExpectation from(
                RouteAttemptExpectation expectation,
                Instant minimumLeaseExpiresAt) {
            Objects.requireNonNull(expectation, "expectation");
            return new SessionAuthorityCommandExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.sessionId(),
                    expectation.slotId(),
                    expectation.targetInstanceId(),
                    expectation.targetResolvedManifestId(),
                    expectation.traceId(),
                    minimumLeaseExpiresAt);
        }

        List<String> compatibilityMismatches(SessionAuthorityCommandExpectation other) {
            Objects.requireNonNull(other, "other");
            List<String> mismatches = new ArrayList<>();
            if (!slotId.equals(other.slotId())) {
                mismatches.add("slotId " + slotId.value() + " vs " + other.slotId().value());
            }
            if (!ownerInstanceId.equals(other.ownerInstanceId())) {
                mismatches.add("ownerInstanceId " + ownerInstanceId.value()
                        + " vs " + other.ownerInstanceId().value());
            }
            if (!resolvedManifestId.equals(other.resolvedManifestId())) {
                mismatches.add("resolvedManifestId " + resolvedManifestId.value()
                        + " vs " + other.resolvedManifestId().value());
            }
            if (!traceId.equals(other.traceId())) {
                mismatches.add("traceId " + traceId + " vs " + other.traceId());
            }
            return mismatches;
        }
    }

    record SessionAuthorityStateExpectation(
            String label,
            String username,
            SessionId sessionId,
            SlotId slotId,
            InstanceId ownerInstanceId,
            ResolvedManifestId resolvedManifestId,
            Instant minimumLeaseExpiresAt) {
        SessionAuthorityStateExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            slotId = Objects.requireNonNull(slotId, "slotId");
            ownerInstanceId = Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
            resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
            minimumLeaseExpiresAt = Objects.requireNonNull(minimumLeaseExpiresAt, "minimumLeaseExpiresAt");
        }

        static SessionAuthorityStateExpectation from(
                RouteAttemptExpectation expectation,
                Instant minimumLeaseExpiresAt) {
            Objects.requireNonNull(expectation, "expectation");
            return new SessionAuthorityStateExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.sessionId(),
                    expectation.slotId(),
                    expectation.targetInstanceId(),
                    expectation.targetResolvedManifestId(),
                    minimumLeaseExpiresAt);
        }

        List<String> compatibilityMismatches(SessionAuthorityStateExpectation other) {
            Objects.requireNonNull(other, "other");
            List<String> mismatches = new ArrayList<>();
            if (!slotId.equals(other.slotId())) {
                mismatches.add("slotId " + slotId.value() + " vs " + other.slotId().value());
            }
            if (!ownerInstanceId.equals(other.ownerInstanceId())) {
                mismatches.add("ownerInstanceId " + ownerInstanceId.value()
                        + " vs " + other.ownerInstanceId().value());
            }
            if (!resolvedManifestId.equals(other.resolvedManifestId())) {
                mismatches.add("resolvedManifestId " + resolvedManifestId.value()
                        + " vs " + other.resolvedManifestId().value());
            }
            return mismatches;
        }
    }

    record SharedShardAllocationStateExpectation(
            String label,
            String username,
            SessionId sessionId,
            SlotId slotId,
            InstanceId instanceId,
            ResolvedManifestId resolvedManifestId) {
        SharedShardAllocationStateExpectation {
            label = requireNonBlank(label, "label");
            username = requireNonBlank(username, "username");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            slotId = Objects.requireNonNull(slotId, "slotId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
            resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        }

        static SharedShardAllocationStateExpectation from(RouteAttemptExpectation expectation) {
            Objects.requireNonNull(expectation, "expectation");
            return new SharedShardAllocationStateExpectation(
                    expectation.label(),
                    expectation.username(),
                    expectation.sessionId(),
                    expectation.slotId(),
                    expectation.targetInstanceId(),
                    expectation.targetResolvedManifestId());
        }

        List<String> compatibilityMismatches(SharedShardAllocationStateExpectation other) {
            Objects.requireNonNull(other, "other");
            List<String> mismatches = new ArrayList<>();
            if (!slotId.equals(other.slotId())) {
                mismatches.add("slotId " + slotId.value() + " vs " + other.slotId().value());
            }
            if (!instanceId.equals(other.instanceId())) {
                mismatches.add("instanceId " + instanceId.value() + " vs " + other.instanceId().value());
            }
            if (!resolvedManifestId.equals(other.resolvedManifestId())) {
                mismatches.add("resolvedManifestId " + resolvedManifestId.value()
                        + " vs " + other.resolvedManifestId().value());
            }
            return mismatches;
        }
    }

    record SessionAuthorityStateRecord(
            SessionId sessionId,
            SlotId slotId,
            InstanceId ownerInstanceId,
            ResolvedManifestId resolvedManifestId,
            SessionLifecycleStatus status,
            Instant openedAt,
            Instant leaseExpiresAt,
            Optional<Instant> activatedAt) {
        SessionAuthorityStateRecord {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            slotId = Objects.requireNonNull(slotId, "slotId");
            ownerInstanceId = Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
            resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
            status = Objects.requireNonNull(status, "status");
            openedAt = Objects.requireNonNull(openedAt, "openedAt");
            leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
            activatedAt = activatedAt == null ? Optional.empty() : activatedAt;
        }
    }

    record PresenceAuthorityStateRecord(
            PresenceId presenceId,
            SubjectId subjectId,
            InstanceId ownerInstanceId,
            PresenceLifecycleStatus status,
            Optional<SessionId> sessionId,
            Optional<RouteId> routeId,
            Instant observedAt,
            Instant expiresAt) {
        PresenceAuthorityStateRecord {
            presenceId = Objects.requireNonNull(presenceId, "presenceId");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            ownerInstanceId = Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
            status = Objects.requireNonNull(status, "status");
            sessionId = sessionId == null ? Optional.empty() : sessionId;
            routeId = routeId == null ? Optional.empty() : routeId;
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }

        static PresenceAuthorityStateRecord from(PresenceSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            return new PresenceAuthorityStateRecord(
                    snapshot.presenceId(),
                    snapshot.subjectId(),
                    snapshot.ownerInstanceId(),
                    snapshot.status(),
                    snapshot.sessionId(),
                    snapshot.routeId(),
                    snapshot.observedAt(),
                    snapshot.expiresAt());
        }
    }

    record RouteAttemptStateResult(
            int matchedCount,
            int recordsScanned) {
        RouteAttemptStateResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must be non-negative");
            }
        }
    }

    record RouteAuthorityCommandLogResult(
            int matchedCount,
            int recordsScanned) {
        RouteAuthorityCommandLogResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must be non-negative");
            }
        }
    }

    record RouteAuthorityStateResult(
            int matchedCount,
            int recordsScanned) {
        RouteAuthorityStateResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must be non-negative");
            }
        }
    }

    record HostRouteCommandLogResult(
            int matchedCount,
            int proxyRecordsScanned,
            int paperRecordsScanned) {
        HostRouteCommandLogResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (proxyRecordsScanned < 0) {
                throw new IllegalArgumentException("proxyRecordsScanned must be non-negative");
            }
            if (paperRecordsScanned < 0) {
                throw new IllegalArgumentException("paperRecordsScanned must be non-negative");
            }
        }
    }

    record LoginRoutingCommandLogResult(
            int matchedCount,
            int queueRosterCommandsScanned,
            int presenceCommandsScanned,
            int placementRequestsScanned,
            int routeAttemptCommandsScanned,
            int lifecycleTraceCommandsScanned) {
        LoginRoutingCommandLogResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (queueRosterCommandsScanned < 0) {
                throw new IllegalArgumentException("queueRosterCommandsScanned must be non-negative");
            }
            if (presenceCommandsScanned < 0) {
                throw new IllegalArgumentException("presenceCommandsScanned must be non-negative");
            }
            if (placementRequestsScanned < 0) {
                throw new IllegalArgumentException("placementRequestsScanned must be non-negative");
            }
            if (routeAttemptCommandsScanned < 0) {
                throw new IllegalArgumentException("routeAttemptCommandsScanned must be non-negative");
            }
            if (lifecycleTraceCommandsScanned < 0) {
                throw new IllegalArgumentException("lifecycleTraceCommandsScanned must be non-negative");
            }
        }
    }

    record QueueRosterStateResult(
            int matchedCount,
            int recordsScanned) {
        QueueRosterStateResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must be non-negative");
            }
        }
    }

    record LifecycleTraceStateResult(
            int matchedCount,
            int recordsScanned) {
        LifecycleTraceStateResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must be non-negative");
            }
        }
    }

    record HostObservationLogResult(
            int matchedCount,
            int recordsScanned) {
        HostObservationLogResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must be non-negative");
            }
        }
    }

    record PresenceAuthorityStateResult(
            int matchedCount,
            int recordsScanned) {
        PresenceAuthorityStateResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must be non-negative");
            }
        }
    }

    record StandardCapabilityStateResult(
            int matchedCount,
            int profileRecordsScanned,
            int rankRecordsScanned,
            int punishmentRecordsScanned) {
        StandardCapabilityStateResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (profileRecordsScanned < 0) {
                throw new IllegalArgumentException("profileRecordsScanned must be non-negative");
            }
            if (rankRecordsScanned < 0) {
                throw new IllegalArgumentException("rankRecordsScanned must be non-negative");
            }
            if (punishmentRecordsScanned < 0) {
                throw new IllegalArgumentException("punishmentRecordsScanned must be non-negative");
            }
        }
    }

    record StandardCapabilityCommandLogResult(
            int matchedCount,
            int profileCommandsScanned,
            int rankCommandsScanned,
            int punishmentCommandsScanned) {
        StandardCapabilityCommandLogResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (profileCommandsScanned < 0) {
                throw new IllegalArgumentException("profileCommandsScanned must be non-negative");
            }
            if (rankCommandsScanned < 0) {
                throw new IllegalArgumentException("rankCommandsScanned must be non-negative");
            }
            if (punishmentCommandsScanned < 0) {
                throw new IllegalArgumentException("punishmentCommandsScanned must be non-negative");
            }
        }
    }

    record RewardCommandLogResult(
            int matchedCount,
            int economyCommandsScanned,
            int statsCommandsScanned) {
        RewardCommandLogResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (economyCommandsScanned < 0) {
                throw new IllegalArgumentException("economyCommandsScanned must be non-negative");
            }
            if (statsCommandsScanned < 0) {
                throw new IllegalArgumentException("statsCommandsScanned must be non-negative");
            }
        }
    }

    record RewardStateResult(
            int matchedCount,
            int economyRecordsScanned,
            int statsRecordsScanned) {
        RewardStateResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (economyRecordsScanned < 0) {
                throw new IllegalArgumentException("economyRecordsScanned must be non-negative");
            }
            if (statsRecordsScanned < 0) {
                throw new IllegalArgumentException("statsRecordsScanned must be non-negative");
            }
        }
    }

    record CassandraHotProjectionResult(
            int matchedCount,
            int presenceRowsScanned,
            int routeRowsScanned,
            int sessionRowsScanned,
            int profileRowsScanned,
            int rankRowsScanned,
            int punishmentRowsScanned,
            int economyRowsScanned,
            int statsRowsScanned) {
        CassandraHotProjectionResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (presenceRowsScanned < 0) {
                throw new IllegalArgumentException("presenceRowsScanned must be non-negative");
            }
            if (routeRowsScanned < 0) {
                throw new IllegalArgumentException("routeRowsScanned must be non-negative");
            }
            if (sessionRowsScanned < 0) {
                throw new IllegalArgumentException("sessionRowsScanned must be non-negative");
            }
            if (profileRowsScanned < 0) {
                throw new IllegalArgumentException("profileRowsScanned must be non-negative");
            }
            if (rankRowsScanned < 0) {
                throw new IllegalArgumentException("rankRowsScanned must be non-negative");
            }
            if (punishmentRowsScanned < 0) {
                throw new IllegalArgumentException("punishmentRowsScanned must be non-negative");
            }
            if (economyRowsScanned < 0) {
                throw new IllegalArgumentException("economyRowsScanned must be non-negative");
            }
            if (statsRowsScanned < 0) {
                throw new IllegalArgumentException("statsRowsScanned must be non-negative");
            }
        }
    }

    record PostgresAuthorityRecordResult(
            int matchedCount,
            int presenceRowsScanned,
            int routeRowsScanned,
            int sessionRowsScanned,
            int profileRowsScanned,
            int rankRowsScanned,
            int punishmentRowsScanned,
            int economyRowsScanned,
            int statsRowsScanned) {
        PostgresAuthorityRecordResult {
            if (matchedCount < 0
                    || presenceRowsScanned < 0
                    || routeRowsScanned < 0
                    || sessionRowsScanned < 0
                    || profileRowsScanned < 0
                    || rankRowsScanned < 0
                    || punishmentRowsScanned < 0
                    || economyRowsScanned < 0
                    || statsRowsScanned < 0) {
                throw new IllegalArgumentException("PostgresAuthorityRecordResult counts must be non-negative");
            }
        }
    }

    record ValkeyCacheResult(
            int matchedCount,
            int cacheKeysChecked,
            int presenceKeysChecked,
            int profileKeysChecked,
            int rankKeysChecked,
            int punishmentKeysChecked,
            int economyKeysChecked,
            int statsKeysChecked) {
        ValkeyCacheResult {
            if (matchedCount < 0
                    || cacheKeysChecked < 0
                    || presenceKeysChecked < 0
                    || profileKeysChecked < 0
                    || rankKeysChecked < 0
                    || punishmentKeysChecked < 0
                    || economyKeysChecked < 0
                    || statsKeysChecked < 0) {
                throw new IllegalArgumentException("ValkeyCacheResult counts must be non-negative");
            }
        }
    }

    record ProjectionConsistencyResult(
            int routeAttemptStateMatches,
            int routeAuthorityStateMatches,
            int queueRosterStateMatches,
            int lifecycleTraceStateMatches,
            int presenceAuthorityStateMatches,
            int standardCapabilityStateMatches,
            int rewardStateMatches,
            int sessionAuthorityStateMatches,
            int sharedShardAllocationStateMatches,
            int cassandraHotProjectionMatches,
            int postgresAuthorityRecordMatches,
            int valkeyCacheMatches) {
        ProjectionConsistencyResult {
            if (routeAttemptStateMatches < 0
                    || routeAuthorityStateMatches < 0
                    || queueRosterStateMatches < 0
                    || lifecycleTraceStateMatches < 0
                    || presenceAuthorityStateMatches < 0
                    || standardCapabilityStateMatches < 0
                    || rewardStateMatches < 0
                    || sessionAuthorityStateMatches < 0
                    || sharedShardAllocationStateMatches < 0
                    || cassandraHotProjectionMatches < 0
                    || postgresAuthorityRecordMatches < 0
                    || valkeyCacheMatches < 0) {
                throw new IllegalArgumentException("ProjectionConsistencyResult counts must be non-negative");
            }
        }
    }

    record TraceCorrelationResult(
            int routeAttemptStateMatches,
            int routeAuthorityCommandMatches,
            int routeAuthorityStateMatches,
            int loginRoutingCommandMatches,
            int lifecycleTraceStateMatches,
            int hostRouteCommandMatches,
            int hostObservationMatches,
            int standardCapabilityCommandMatches,
            int rewardCommandMatches,
            int sessionAuthorityCommandMatches,
            int sharedShardAllocationCommandMatches,
            int sharedShardAllocationStateMatches,
            int agonesGameServerMetadataMatches) {
        TraceCorrelationResult {
            if (routeAttemptStateMatches < 0
                    || routeAuthorityCommandMatches < 0
                    || routeAuthorityStateMatches < 0
                    || loginRoutingCommandMatches < 0
                    || lifecycleTraceStateMatches < 0
                    || hostRouteCommandMatches < 0
                    || hostObservationMatches < 0
                    || standardCapabilityCommandMatches < 0
                    || rewardCommandMatches < 0
                    || sessionAuthorityCommandMatches < 0
                    || sharedShardAllocationCommandMatches < 0
                    || sharedShardAllocationStateMatches < 0
                    || agonesGameServerMetadataMatches < 0) {
                throw new IllegalArgumentException("TraceCorrelationResult counts must be non-negative");
            }
        }
    }

    record ObjectStoreArtifactResult(
            ArtifactObjectAddress address,
            int byteLength,
            String digest) {
        ObjectStoreArtifactResult {
            address = Objects.requireNonNull(address, "address");
            if (byteLength <= 0) {
                throw new IllegalArgumentException("byteLength must be positive");
            }
            digest = requireNonBlank(digest, "digest");
        }
    }

    private record PostgresAuthorityRecordRow(
            String aggregateId,
            long revision,
            long fencingEpoch,
            String statePayload) {
        private PostgresAuthorityRecordRow {
            aggregateId = requireNonBlank(aggregateId, "aggregateId");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            if (fencingEpoch < 0) {
                throw new IllegalArgumentException("fencingEpoch must be non-negative");
            }
            statePayload = requireNonBlank(statePayload, "statePayload");
        }
    }

    record SessionAuthorityStateResult(
            int matchedCount,
            int recordsScanned) {
        SessionAuthorityStateResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must be non-negative");
            }
        }
    }

    record SessionAuthorityCommandLogResult(
            int matchedCount,
            int recordsScanned) {
        SessionAuthorityCommandLogResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must be non-negative");
            }
        }
    }

    record SharedShardAllocationCommandLogResult(
            int matchedCount,
            int recordsScanned) {
        SharedShardAllocationCommandLogResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must be non-negative");
            }
        }
    }

    record SharedShardAllocationStateResult(
            int matchedCount,
            int recordsScanned) {
        SharedShardAllocationStateResult {
            if (matchedCount < 0) {
                throw new IllegalArgumentException("matchedCount must be non-negative");
            }
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must be non-negative");
            }
        }
    }

    record AgonesFleetStateResult(
            int allocatedReplicas,
            int allocatedGameServers,
            Set<InstanceId> proofInstanceIds) {
        AgonesFleetStateResult {
            if (allocatedReplicas < 0) {
                throw new IllegalArgumentException("allocatedReplicas must be non-negative");
            }
            if (allocatedGameServers < 0) {
                throw new IllegalArgumentException("allocatedGameServers must be non-negative");
            }
            proofInstanceIds = Set.copyOf(Objects.requireNonNull(proofInstanceIds, "proofInstanceIds"));
        }
    }

    record AgonesGameServerRecord(
            InstanceId instanceId,
            String state,
            Optional<PoolId> poolId,
            Optional<SessionId> sessionId,
            Optional<SlotId> slotId,
            Optional<ResolvedManifestId> resolvedManifestId,
            Optional<String> traceId) {
        AgonesGameServerRecord {
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
            state = requireNonBlank(state, "state");
            poolId = poolId == null ? Optional.empty() : poolId;
            sessionId = sessionId == null ? Optional.empty() : sessionId;
            slotId = slotId == null ? Optional.empty() : slotId;
            resolvedManifestId = resolvedManifestId == null ? Optional.empty() : resolvedManifestId;
            traceId = traceId == null ? Optional.empty() : traceId.filter(value -> !value.isBlank());
        }
    }

    record AgonesGameServerStateResult(
            int allocatedGameServers,
            Set<InstanceId> proofInstanceIds) {
        AgonesGameServerStateResult {
            if (allocatedGameServers < 0) {
                throw new IllegalArgumentException("allocatedGameServers must be non-negative");
            }
            proofInstanceIds = Set.copyOf(Objects.requireNonNull(proofInstanceIds, "proofInstanceIds"));
        }
    }

    record PaperHostRoutePrepareCommand(
            String routeAttemptId,
            RouteId routeId,
            SessionId sessionId,
            ResolvedManifestId resolvedManifestId,
            String traceId) {
        PaperHostRoutePrepareCommand {
            routeAttemptId = requireNonBlank(routeAttemptId, "routeAttemptId");
            routeId = Objects.requireNonNull(routeId, "routeId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
            traceId = requireNonBlank(traceId, "traceId");
        }

        static PaperHostRoutePrepareCommand parse(String wireValue) {
            Map<String, String> fields = pipeFields(wireValue, "host.route.prepare", "Paper host route prepare");
            return new PaperHostRoutePrepareCommand(
                    requiredField(fields, "routeAttemptId", "Paper host route prepare"),
                    new RouteId(requiredField(fields, "routeId", "Paper host route prepare")),
                    new SessionId(requiredField(fields, "sessionId", "Paper host route prepare")),
                    new ResolvedManifestId(requiredField(fields, "resolvedManifestId", "Paper host route prepare")),
                    requiredField(fields, "traceId", "Paper host route prepare"));
        }
    }

    private record ScaleOutProof(
            LoginAttemptResult triggerDeniedLogin,
            PaperLobbyProofMessage acceptedLoginProof) {
        private ScaleOutProof {
            triggerDeniedLogin = Objects.requireNonNull(triggerDeniedLogin, "triggerDeniedLogin");
            acceptedLoginProof = Objects.requireNonNull(acceptedLoginProof, "acceptedLoginProof");
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
