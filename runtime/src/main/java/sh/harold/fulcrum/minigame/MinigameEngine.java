package sh.harold.fulcrum.minigame;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.api.messagebus.messages.match.MatchRosterCreatedMessage;
import sh.harold.fulcrum.api.messagebus.messages.match.MatchRosterEndedMessage;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagService;
import sh.harold.fulcrum.fundamentals.routing.EnvironmentRoutingService;
import sh.harold.fulcrum.fundamentals.routing.EnvironmentRoutingService.RouteOptions;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.minigame.data.MinigameDataRegistry;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService.MatchEnvironment;
import sh.harold.fulcrum.minigame.match.MatchHistoryWriter;
import sh.harold.fulcrum.minigame.match.MatchLogWriter;
import sh.harold.fulcrum.minigame.match.MinigameMatch;
import sh.harold.fulcrum.minigame.match.RosterManager;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;
import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;
import sh.harold.fulcrum.minigame.team.TeamPlanner;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Runtime engine that manages active minigame matches.
 */
public final class MinigameEngine {
    private static final long POST_MATCH_VICTORY_DELAY_TICKS = 15L * 20L;
    private static final long POST_MATCH_SPECTATOR_DURATION_TICKS = 15L * 20L;
    private static final long MATCH_TEARDOWN_DELAY_TICKS =
            POST_MATCH_VICTORY_DELAY_TICKS + POST_MATCH_SPECTATOR_DURATION_TICKS;
    private static final long MATCH_IDLE_TEARDOWN_DELAY_TICKS = 1_200L;
    private static final long LOBBY_ROUTE_RETRY_INTERVAL_TICKS = 60L;
    private static final int LOBBY_ROUTE_MAX_ATTEMPTS = 3;
    private static final long POST_MATCH_CLEANUP_DELAY_TICKS =
            LOBBY_ROUTE_RETRY_INTERVAL_TICKS * (LOBBY_ROUTE_MAX_ATTEMPTS + 1L);
    private final JavaPlugin plugin;
    private final PlayerRouteRegistry routeRegistry;
    private final MinigameEnvironmentService environmentService;
    private final SimpleSlotOrchestrator slotOrchestrator;
    private final ActionFlagService actionFlags;
    private final PlayerSessionService sessionService;
    private final MinigameDataRegistry dataRegistry;
    private final MatchHistoryWriter matchHistoryWriter;
    private final MatchLogWriter matchLogWriter;
    private final MessageBus messageBus;
    private final Map<String, Set<UUID>> rejoinWatchers = new ConcurrentHashMap<>();
    private final Set<String> provisioningSlots = ConcurrentHashMap.newKeySet();
    private final Map<String, MinigameRegistration> registrations = new ConcurrentHashMap<>();
    private final Map<UUID, MinigameMatch> activeMatches = new ConcurrentHashMap<>();
    private final Map<UUID, String> matchStates = new ConcurrentHashMap<>();
    private final Map<UUID, String> matchSlotIds = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, String>> matchSlotMetadata = new ConcurrentHashMap<>();
    private final Map<String, UUID> slotMatches = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> teardownTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean ticking = new AtomicBoolean(false);
    private final Map<UUID, UUID> playerMatchIndex = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerSlotIndex = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNameIndex = new ConcurrentHashMap<>();
    private final Set<VisibilityKey> slotHiddenPairs = ConcurrentHashMap.newKeySet();
    private final Map<UUID, MatchContext> matchContexts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> matchStartTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, UUID>> matchPlayerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, List<MatchLogWriter.Event>> matchEvents = new ConcurrentHashMap<>();
    private final Set<UUID> recordedMatches = ConcurrentHashMap.newKeySet();
    private final Set<UUID> publishedRosters = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PostMatchTimeline> postMatchTimelines = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> idleTeardownTasks = new ConcurrentHashMap<>();
    private final Set<String> cleaningSlots = ConcurrentHashMap.newKeySet();
    private volatile EnvironmentRoutingService environmentRoutingService;
    private BukkitTask tickTask;

    public MinigameEngine(JavaPlugin plugin,
                          PlayerRouteRegistry routeRegistry,
                          MinigameEnvironmentService environmentService,
                          SimpleSlotOrchestrator slotOrchestrator,
                          ActionFlagService actionFlags,
                          PlayerSessionService sessionService,
                          MinigameDataRegistry dataRegistry,
                          MatchHistoryWriter matchHistoryWriter,
                          MatchLogWriter matchLogWriter,
                          MessageBus messageBus,
                          EnvironmentRoutingService environmentRoutingService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.routeRegistry = routeRegistry;
        this.environmentService = environmentService;
        this.slotOrchestrator = slotOrchestrator;
        this.actionFlags = actionFlags;
        this.sessionService = sessionService;
        this.dataRegistry = dataRegistry;
        this.matchHistoryWriter = matchHistoryWriter;
        this.matchLogWriter = matchLogWriter;
        this.messageBus = messageBus;
        this.environmentRoutingService = environmentRoutingService;
    }

    public void setEnvironmentRoutingService(EnvironmentRoutingService environmentRoutingService) {
        this.environmentRoutingService = environmentRoutingService;
    }

    public void registerRegistration(MinigameRegistration registration) {
        registrations.put(registration.getFamilyId(), registration);
        if (dataRegistry != null) {
            var defaultCollection = dataRegistry.registerDefault(registration.getFamilyId());
            registration.getRegistrationHandler().ifPresent(handler ->
                    handler.accept(new MinigameRegistration.RegistrationContext(
                            registration.getFamilyId(),
                            dataRegistry,
                            defaultCollection
                    )));
        }
    }

    public Optional<MinigameRegistration> getRegistration(String familyId) {
        return Optional.ofNullable(registrations.get(familyId));
    }

    public Optional<UUID> startMatchForFamily(String familyId, Collection<Player> players) {
        MinigameRegistration registration = registrations.get(familyId);
        if (registration == null) {
            plugin.getLogger().warning("Attempted to start match with unregistered family " + familyId);
            return Optional.empty();
        }
        MinigameBlueprint blueprint = registration.getBlueprint();
        ensureTicking();
        return Optional.of(startMatchInternal(familyId, blueprint, registration, players));
    }

    public UUID startAdHocMatch(MinigameBlueprint blueprint, Collection<Player> players) {
        ensureTicking();
        return startMatchInternal("adhoc", blueprint, null, players);
    }

    public void endMatch(UUID matchId) {
        PostMatchTimeline timeline = postMatchTimelines.remove(matchId);
        if (timeline != null) {
            timeline.cancel();
        }

        BukkitTask pending = teardownTasks.remove(matchId);
        if (pending != null) {
            pending.cancel();
        }

        BukkitTask idle = idleTeardownTasks.remove(matchId);
        if (idle != null) {
            idle.cancel();
        }

        MinigameMatch removedMatch = activeMatches.remove(matchId);
        matchStates.remove(matchId);
        boolean visibilityRefreshNeeded = false;

        if (removedMatch != null) {
            routePlayersToLobby(removedMatch);
            for (UUID playerId : new ArrayList<>(removedMatch.getContext().getActivePlayers())) {
                playerMatchIndex.remove(playerId);
                if (playerSlotIndex.remove(playerId) != null) {
                    visibilityRefreshNeeded = true;
                }
                playerNameIndex.remove(playerId);
            }
        }

        String slotId = matchSlotIds.remove(matchId);
        Map<String, String> metadataSnapshot = matchSlotMetadata.remove(matchId);

        if (slotId != null) {
            slotMatches.remove(slotId);

            Map<String, String> removalMetadata = metadataSnapshot != null
                    ? new HashMap<>(metadataSnapshot)
                    : new HashMap<>();
            removalMetadata.put("phase", "terminated");
            removalMetadata.put("removed", "true");
            removalMetadata.put("teardownAt", Long.toString(System.currentTimeMillis()));

            if (slotOrchestrator != null) {
                slotOrchestrator.removeSlot(slotId, SlotLifecycleStatus.AVAILABLE, removalMetadata);
            }

            scheduleEnvironmentCleanup(slotId);

            publishRosterEnded(matchId, slotId);
        }

        if (removedMatch != null) {
            removedMatch.shutdown();
            removedMatch.getContext().clearSlotId();
            removedMatch.getContext().removeAttribute(MinigameAttributes.SLOT_ID);
            removedMatch.getContext().removeAttribute(MinigameAttributes.SLOT_METADATA);
            removedMatch.getContext().removeAttribute(MinigameAttributes.MATCH_ENVIRONMENT);
            removedMatch.getContext().clearFlagsForRoster();
            if (sessionService != null) {
                removedMatch.getRoster().all().forEach(entry ->
                        sessionService.clearTrackedMatch(entry.getPlayerId()));
            }
        }
        if (visibilityRefreshNeeded) {
            refreshSlotVisibility();
        }
        matchContexts.remove(matchId);
        matchStartTimes.remove(matchId);
        matchPlayerSessions.remove(matchId);
        recordedMatches.remove(matchId);
        matchEvents.remove(matchId);
        publishedRosters.remove(matchId);
    }

    public void publishEvent(UUID matchId, MinigameEvent event) {
        MinigameMatch match = activeMatches.get(matchId);
        if (match != null) {
            match.publishEvent(event);
        }
    }

    public void addPlayer(UUID matchId, Player player, boolean respawnAllowed) {
        MinigameMatch match = activeMatches.get(matchId);
        if (match != null) {
            match.addPlayer(player, respawnAllowed);
            UUID playerId = player.getUniqueId();
            playerMatchIndex.put(playerId, matchId);
            captureSessionId(matchId, playerId);
            MatchContext context = matchContexts.get(matchId);
            boolean queuePhase = !MinigameBlueprint.STATE_IN_GAME.equals(match.getContext().currentStateId());
            tagPlayerSegment(matchId, playerId, context, queuePhase);
            if (sessionService != null) {
                sessionService.setActiveMatchId(playerId, matchId);
            }
            playerNameIndex.put(playerId, player.getName());
            String slotId = matchSlotIds.get(matchId);
            if (slotId == null) {
                match.getContext().getSlotId().ifPresent(id -> {
                    String previous = playerSlotIndex.put(playerId, id);
                    if (!id.equals(previous)) {
                        refreshSlotVisibility();
                    }
                });
            } else {
                String previous = playerSlotIndex.put(playerId, slotId);
                if (!slotId.equals(previous)) {
                    refreshSlotVisibility();
                }
            }
            if (slotId != null && !slotId.isBlank() && MinigameBlueprint.STATE_PRE_LOBBY.equals(match.getContext().currentStateId())) {
                trackRejoinWatcher(slotId, playerId);
                if (sessionService != null) {
                    sessionService.updateRejoinStage(playerId, "pre_lobby");
                }
            }
            cancelIdleTeardown(matchId);

            if (MinigameBlueprint.STATE_PRE_LOBBY.equals(match.getContext().currentStateId())) {
                publishedRosters.remove(matchId);
                publishMatchRoster(matchId, match, true);
            }
        }
    }

    public void removePlayer(UUID matchId, UUID playerId) {
        MinigameMatch match = activeMatches.get(matchId);
        boolean slotUnregistered = playerSlotIndex.remove(playerId) != null;
        playerNameIndex.remove(playerId);
        if (match != null) {
            String slotId = matchSlotIds.get(matchId);
            match.removePlayer(playerId);
            playerMatchIndex.remove(playerId);
            if (slotUnregistered) {
                refreshSlotVisibility();
            }
            long activePlayers = match.getRoster().activeCount();
            if (activePlayers > 0 && MinigameBlueprint.STATE_PRE_LOBBY.equals(match.getContext().currentStateId())) {
                publishMatchRoster(matchId, match, true);
            }
            if (activePlayers <= 0) {
                if (MinigameBlueprint.STATE_PRE_LOBBY.equals(match.getContext().currentStateId())) {
                    match.resetPreLobbyCountdown();
                    if (slotId != null && !slotId.isBlank()) {
                        publishRosterEnded(matchId, slotId);
                        publishedRosters.remove(matchId);
                    }
                }
                scheduleIdleTeardown(matchId);
            } else if (slotId != null && !slotId.isBlank() && MinigameBlueprint.STATE_PRE_LOBBY.equals(match.getContext().currentStateId())) {
                trackRejoinWatcher(slotId, playerId);
                if (sessionService != null) {
                    sessionService.updateRejoinStage(playerId, "pre_lobby");
                }
            }
        } else if (slotUnregistered) {
            refreshSlotVisibility();
        }
    }

    public Optional<MinigameMatch> findMatchByPlayer(UUID playerId) {
        return activeMatches.values().stream()
                .filter(match -> match.getContext().isPlayerRegistered(playerId))
                .findFirst();
    }

    public void handlePlayerQuit(Player player) {
        if (player == null) {
            return;
        }
        detachPlayerFromMatch(player.getUniqueId());
    }

    public void handleLocalRoute(Player player) {
        if (player == null) {
            return;
        }
        detachPlayerFromMatch(player.getUniqueId());
    }

    private void detachPlayerFromMatch(UUID playerId) {
        if (playerId == null) {
            return;
        }
        UUID matchId = playerMatchIndex.remove(playerId);
        MinigameMatch match = matchId != null ? activeMatches.get(matchId) : null;
        if (match == null) {
            match = findMatchByPlayer(playerId).orElse(null);
            if (match != null) {
                matchId = match.getMatchId();
            }
        }
        if (match == null || matchId == null) {
            return;
        }
        removePlayer(matchId, playerId);
    }

    private void ensureDetachedFromOtherSlots(UUID playerId, String targetSlotId) {
        if (playerId == null) {
            return;
        }
        UUID currentMatchId = playerMatchIndex.get(playerId);
        if (currentMatchId == null) {
            currentMatchId = findMatchByPlayer(playerId)
                    .map(MinigameMatch::getMatchId)
                    .orElse(null);
        }
        if (currentMatchId == null) {
            return;
        }
        String currentSlotId = matchSlotIds.get(currentMatchId);
        if (currentSlotId != null
                && currentSlotId.equalsIgnoreCase(targetSlotId)) {
            return;
        }
        removePlayer(currentMatchId, playerId);
    }

    private void trackRejoinWatcher(String slotId, UUID playerId) {
        if (slotId == null || slotId.isBlank() || playerId == null) {
            return;
        }
        rejoinWatchers.computeIfAbsent(slotId, key -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    private void updateRejoinStage(String slotId, String stage, boolean clear) {
        if (sessionService == null || slotId == null || slotId.isBlank() || stage == null || stage.isBlank()) {
            if (clear) {
                rejoinWatchers.remove(slotId);
            }
            return;
        }
        Set<UUID> watchers = rejoinWatchers.get(slotId);
        if (watchers != null) {
            for (UUID watcher : watchers) {
                sessionService.updateRejoinStage(watcher, stage);
                if ("closed".equalsIgnoreCase(stage)) {
                    sessionService.clearRejoinState(watcher);
                }
            }
        }
        if (clear) {
            rejoinWatchers.remove(slotId);
        }
    }

    public void handleRoutedPlayer(Player player, PlayerRouteRegistry.RouteAssignment assignment) {
        if (player == null || assignment == null) {
            return;
        }

        String slotId = assignment.slotId();
        if (slotId == null || slotId.isBlank()) {
            plugin.getLogger().warning("Routed player " + player.getName()
                    + " missing slot id; ignoring match registration.");
            return;
        }

        if (cleaningSlots.contains(slotId)) {
            plugin.getLogger().severe("Received route for slot " + slotId
                    + " while cleanup is still running; rerouting " + player.getName() + " to lobby.");
            EnvironmentRoutingService routingService = this.environmentRoutingService;
            if (routingService != null) {
                routingService.routePlayer(
                        player,
                        "lobby",
                        RouteOptions.builder()
                                .failureMode(RouteOptions.FailureMode.FAIL_WITH_KICK)
                                .reason("minigame:slot-cleaning")
                                .metadata("source", "engine-slot-cleanup")
                                .build()
                );
            }
            return;
        }

        ensureDetachedFromOtherSlots(player.getUniqueId(), slotId);

        UUID existingMatch = slotMatches.get(slotId);
        if (existingMatch != null) {
            addPlayer(existingMatch, player, false);
            if (sessionService != null) {
                sessionService.updateRejoinStage(player.getUniqueId(), "pre_lobby");
            }
            return;
        }

        String familyId = assignment.familyId();
        if (familyId == null || familyId.isBlank()) {
            plugin.getLogger().warning("Routed player " + player.getName()
                    + " missing family id; cannot start match for slot " + slotId);
            return;
        }

        if (startMatchForFamily(familyId, List.of(player)).isEmpty()) {
            plugin.getLogger().warning("Failed to start match for family " + familyId
                    + " (slot=" + slotId + ", player=" + player.getName() + ")");
        }
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (environmentService != null) {
            environmentService.shutdown();
        }
        activeMatches.clear();
        provisioningSlots.clear();
        cleaningSlots.clear();
        playerSlotIndex.clear();
        playerNameIndex.clear();
        slotHiddenPairs.clear();
        ticking.set(false);
    }

    public void handleProvisionedSlot(SimpleSlotOrchestrator.ProvisionedSlot slot) {
        if (slot == null) {
            return;
        }
        if (slotOrchestrator == null) {
            plugin.getLogger().warning("Ignoring provisioned slot " + slot.slotId() + " because orchestrator is unavailable.");
            return;
        }
        if (!provisioningSlots.add(slot.slotId())) {
            plugin.getLogger().fine("Provision event already in progress for slot " + slot.slotId());
            return;
        }
        plugin.getLogger().info("Finalizing provisioning for slot " + slot.slotId() + " (family=" + slot.familyId() + ")");
        plugin.getServer().getScheduler().runTask(plugin, () -> processProvisionedSlot(slot));
    }

    private void processProvisionedSlot(SimpleSlotOrchestrator.ProvisionedSlot slot) {
        final String slotId = slot.slotId();
        try {
            Optional<MinigameRegistration> registrationOpt = getRegistration(slot.familyId());
            if (registrationOpt.isEmpty()) {
                plugin.getLogger().warning("Provisioned slot " + slot.slotId()
                        + " has no registered family " + slot.familyId());
                markSlotFault(slot.slotId(), "unknown-family");
                return;
            }

            Map<String, String> metadata = new HashMap<>(slot.metadata());
            metadata.putIfAbsent("family", slot.familyId());
            if (slot.variant() != null && !slot.variant().isBlank()) {
                metadata.putIfAbsent("variant", slot.variant());
            }

            if (environmentService == null) {
                plugin.getLogger().warning("Environment service unavailable; cannot prepare world for slot " + slot.slotId());
                markSlotFault(slot.slotId(), "environment-service-unavailable");
                return;
            }

            MatchEnvironment environment = environmentService.prepareEnvironment(slot.slotId(), metadata);
            if (environment == null) {
                plugin.getLogger().warning("Failed to prepare environment for slot " + slot.slotId()
                        + " (family=" + slot.familyId() + ", map=" + metadata.getOrDefault("mapId", "unknown") + ")");
                if (slotOrchestrator != null) {
                    slotOrchestrator.disableFamily(slot.familyId(), "environment-unavailable");
                }
                cleanupEnvironment(slotId);
                markSlotFault(slot.slotId(), "environment-unavailable");
                return;
            }
            if (slotOrchestrator != null) {
                slotOrchestrator.enableFamily(slot.familyId());
            }

            Location lobbySpawn = environment.lobbySpawn();
            String worldName = environment.worldName();
            if (lobbySpawn == null || worldName == null || worldName.isBlank()) {
                plugin.getLogger().warning("Environment for slot " + slot.slotId() + " did not provide a valid spawn location");
                cleanupEnvironment(slotId);
                markSlotFault(slot.slotId(), "spawn-unavailable");
                return;
            }

            metadata.putIfAbsent("mapId", environment.mapId());
            metadata.put("targetWorld", worldName);
            metadata.put("spawnX", Double.toString(lobbySpawn.getX()));
            metadata.put("spawnY", Double.toString(lobbySpawn.getY()));
            metadata.put("spawnZ", Double.toString(lobbySpawn.getZ()));
            metadata.put("spawnYaw", Float.toString(lobbySpawn.getYaw()));
            metadata.put("spawnPitch", Float.toString(lobbySpawn.getPitch()));

            slotOrchestrator.updateSlotStatus(slot.slotId(), SlotLifecycleStatus.AVAILABLE, 0, metadata);
            plugin.getLogger().info("Provisioned slot " + slot.slotId() + " for family " + slot.familyId()
                    + " (world=" + worldName + ")");
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to finalize provisioning for slot " + slot.slotId(), exception);
            cleanupEnvironment(slotId);
            markSlotFault(slot.slotId(), "provisioning-error");
        } finally {
            provisioningSlots.remove(slot.slotId());
        }
    }

    private void markSlotFault(String slotId, String reason) {
        if (slotOrchestrator != null) {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("provisioningError", reason);
            if (!slotOrchestrator.removeSlot(slotId, SlotLifecycleStatus.FAULTED, metadata)) {
                slotOrchestrator.updateSlotStatus(slotId, SlotLifecycleStatus.FAULTED, 0, metadata);
            }
        }
        cleanupEnvironment(slotId);
    }

    private void ensureTicking() {
        if (ticking.compareAndSet(false, true)) {
            tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickMatches, 1L, 1L);
        }
    }

    private void tickMatches() {
        if (activeMatches.isEmpty()) {
            if (tickTask != null) {
                tickTask.cancel();
                tickTask = null;
            }
            ticking.set(false);
            return;
        }
        activeMatches.values().forEach(MinigameMatch::tick);
    }

    private void scheduleMatchTeardown(UUID matchId, String slotId) {
        if (teardownTasks.containsKey(matchId) || postMatchTimelines.containsKey(matchId)) {
            return;
        }

        MinigameMatch match = activeMatches.get(matchId);
        if (match == null) {
            endMatch(matchId);
            return;
        }

        PostMatchTimeline timeline = new PostMatchTimeline();
        BukkitTask victoryTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!postMatchTimelines.containsKey(matchId)) {
                return;
            }
            beginPostMatchSpectatorPhase(matchId);
        }, POST_MATCH_VICTORY_DELAY_TICKS);
        timeline.setVictoryTask(victoryTask);

        long teardownDelay = MATCH_TEARDOWN_DELAY_TICKS;
        BukkitTask finalTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> endMatch(matchId), teardownDelay);
        timeline.setTeardownTask(finalTask);

        postMatchTimelines.put(matchId, timeline);
        teardownTasks.put(matchId, finalTask);
    }

    private void beginPostMatchSpectatorPhase(UUID matchId) {
        MinigameMatch match = activeMatches.get(matchId);
        if (match == null) {
            return;
        }
        StateContext context = match.getContext();
        if (context == null) {
            return;
        }
        RosterManager roster = match.getRoster();
        roster.all().forEach(entry -> {
            UUID playerId = entry.getPlayerId();
            context.setRespawnAllowed(playerId, false);
            context.transitionPlayerToSpectator(playerId);
        });
        context.broadcast(ChatColor.GOLD + "Match complete! " + ChatColor.YELLOW
                + "Right click the paper to queue again or the bed to head back to the lobby."
                + ChatColor.RESET);
    }

    private void scheduleIdleTeardown(UUID matchId) {
        if (idleTeardownTasks.containsKey(matchId)) {
            return;
        }
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            idleTeardownTasks.remove(matchId);
            String slotId = matchSlotIds.get(matchId);
            if (slotId == null || slotId.isBlank()) {
                endMatch(matchId);
            } else {
                scheduleMatchTeardown(matchId, slotId);
            }
        }, MATCH_IDLE_TEARDOWN_DELAY_TICKS);
        idleTeardownTasks.put(matchId, task);
    }

    private void cancelIdleTeardown(UUID matchId) {
        BukkitTask task = idleTeardownTasks.remove(matchId);
        if (task != null) {
            task.cancel();
        }
    }

    private void routePlayersToLobby(MinigameMatch match) {
        EnvironmentRoutingService routingService = this.environmentRoutingService;
        if (match == null || routingService == null) {
            return;
        }
        List<Player> players = match.getContext().getActivePlayers().stream()
                .map(plugin.getServer()::getPlayer)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (players.isEmpty()) {
            return;
        }
        Map<UUID, String> originalWorlds = new LinkedHashMap<>();
        for (Player player : players) {
            String worldName = player.getWorld() != null ? player.getWorld().getName() : "";
            originalWorlds.put(player.getUniqueId(), worldName);
        }
        routingService.routePlayers(
                players,
                "lobby",
                RouteOptions.builder()
                        .failureMode(RouteOptions.FailureMode.FAIL_WITH_KICK)
                        .reason("minigame:auto-teardown")
                        .metadata("source", "engine-teardown")
                        .build()
        );
        scheduleLobbyRetry(originalWorlds, 1);
    }

    private void scheduleLobbyRetry(Map<UUID, String> originalWorlds, int attempt) {
        EnvironmentRoutingService routingService = this.environmentRoutingService;
        if (routingService == null || originalWorlds == null || originalWorlds.isEmpty()) {
            return;
        }
        if (attempt > LOBBY_ROUTE_MAX_ATTEMPTS) {
            originalWorlds.forEach((playerId, originalWorld) -> {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player == null || !player.isOnline() || playerMatchIndex.containsKey(playerId)) {
                    return;
                }
                String currentWorld = player.getWorld() != null ? player.getWorld().getName() : "";
                if (originalWorld != null && !originalWorld.isBlank()
                        && currentWorld != null && !currentWorld.isBlank()
                        && currentWorld.equalsIgnoreCase(originalWorld)) {
                    player.kickPlayer("Routing to lobby failed. Please reconnect.");
                }
            });
            return;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Map<UUID, String> remaining = new LinkedHashMap<>();
            for (Map.Entry<UUID, String> entry : originalWorlds.entrySet()) {
                UUID playerId = entry.getKey();
                Player player = plugin.getServer().getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    continue;
                }
                if (playerMatchIndex.containsKey(playerId)) {
                    continue;
                }
                String originalWorld = entry.getValue() != null ? entry.getValue() : "";
                String currentWorld = player.getWorld() != null ? player.getWorld().getName() : "";
                if (!originalWorld.isBlank() && !currentWorld.isBlank()
                        && !currentWorld.equalsIgnoreCase(originalWorld)) {
                    continue;
                }
                RouteOptions options = RouteOptions.builder()
                        .failureMode(RouteOptions.FailureMode.REPORT_ONLY)
                        .reason("minigame:auto-teardown-retry")
                        .metadata("source", "engine-teardown")
                        .metadata("attempt", Integer.toString(attempt))
                        .build();
                routingService.routePlayer(player, "lobby", options);
                remaining.put(playerId, originalWorld);
            }
            if (!remaining.isEmpty()) {
                scheduleLobbyRetry(remaining, attempt + 1);
            }
        }, LOBBY_ROUTE_RETRY_INTERVAL_TICKS);
    }

    private void scheduleEnvironmentCleanup(String slotId) {
        if (environmentService == null || slotId == null || slotId.isBlank()) {
            return;
        }
        cleaningSlots.add(slotId);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                if (slotMatches.containsKey(slotId)) {
                    plugin.getLogger().severe(() -> "Slot " + slotId + " was reused before cleanup finished. "
                            + "Routing players away and marking environment for reprovision.");
                    UUID conflictingMatchId = slotMatches.get(slotId);
                    if (conflictingMatchId != null) {
                        MinigameMatch conflicting = activeMatches.get(conflictingMatchId);
                        if (conflicting != null) {
                            routePlayersToLobby(conflicting);
                            endMatch(conflictingMatchId);
                        }
                    }
                    markSlotFault(slotId, "cleanup-interrupted");
                    return;
                }
                environmentService.cleanup(slotId);
            } finally {
                cleaningSlots.remove(slotId);
            }
        }, POST_MATCH_CLEANUP_DELAY_TICKS);
    }

    private void cleanupEnvironment(String slotId) {
        if (environmentService == null || slotId == null || slotId.isBlank()) {
            return;
        }
        try {
            environmentService.cleanup(slotId);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to cleanup environment for slot " + slotId, exception);
        }
    }

    public void refreshSlotMetadata(String slotId, Map<String, String> metadata) {
        if (slotId == null || slotId.isBlank() || metadata == null || metadata.isEmpty()) {
            return;
        }
        UUID matchId = slotMatches.get(slotId);
        if (matchId == null) {
            return;
        }
        matchSlotMetadata.compute(matchId, (id, existing) -> {
            Map<String, String> updated = existing != null && !existing.isEmpty()
                    ? new ConcurrentHashMap<>(existing)
                    : new ConcurrentHashMap<>();
            updated.putAll(metadata);
            return updated;
        });
    }

    private UUID startMatchInternal(String familyId,
                                    MinigameBlueprint blueprint,
                                    MinigameRegistration registration,
                                    Collection<Player> players) {
        Objects.requireNonNull(players, "players");
        List<Player> initialPlayers = new ArrayList<>(players);
        UUID matchId = UUID.randomUUID();
        SlotContext slotContext = resolveSlotContext(initialPlayers);
        Map<String, String> metadataSnapshot = new HashMap<>();
        if (slotContext != null && slotContext.metadata != null && !slotContext.metadata.isEmpty()) {
            metadataSnapshot.putAll(slotContext.metadata);
        }
        MatchContext context = buildMatchContext(familyId, registration, metadataSnapshot, slotContext != null ? slotContext.slotId : null);
        metadataSnapshot.putIfAbsent("family", context.family());
        metadataSnapshot.putIfAbsent("variant", context.variant());
        metadataSnapshot.putIfAbsent("mapId", context.mapId());
        metadataSnapshot.putIfAbsent("environment", context.environment());
        matchContexts.put(matchId, context);

        Map<UUID, Map<String, String>> playerMetadata = collectPlayerMetadata(initialPlayers);
        TeamPlanner.TeamPlan teamPlan;
        try {
            teamPlan = TeamPlanner.plan(matchId, initialPlayers, metadataSnapshot, playerMetadata);
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to compute team plan for match " + matchId + ": " + exception.getMessage());
            Map<String, String> fallbackMetadata = new HashMap<>(metadataSnapshot);
            fallbackMetadata.put("team.max", Integer.toString(Math.max(1, initialPlayers.size())));
            fallbackMetadata.put("team.count", "1");
            teamPlan = TeamPlanner.plan(matchId, initialPlayers, fallbackMetadata, playerMetadata);
        }

        String slotId = slotContext != null ? slotContext.slotId : null;
        MinigameMatch match = new MinigameMatch(plugin, matchId, blueprint, registration, initialPlayers,
                stateId -> onStateChange(matchId, familyId, stateId),
                actionFlags,
                teamPlan,
                slotId,
                metadataSnapshot);
        activeMatches.put(matchId, match);
        matchStates.put(matchId, blueprint.getStartStateId());
        for (Player player : initialPlayers) {
            UUID playerId = player.getUniqueId();
            playerMatchIndex.put(playerId, matchId);
            captureSessionId(matchId, playerId);
            tagPlayerSegment(matchId, playerId, context, true);
            if (sessionService != null) {
                sessionService.setActiveMatchId(playerId, matchId);
            }
            playerNameIndex.put(playerId, player.getName());
            if (slotId != null) {
                playerSlotIndex.put(playerId, slotId);
            }
        }
        if (slotId != null) {
            matchSlotIds.put(matchId, slotId);
            slotMatches.put(slotId, matchId);
            publishedRosters.remove(matchId);
            refreshSlotVisibility();
        }
        if (slotContext != null) {

            if (environmentService != null) {
                environmentService.getEnvironment(slotId)
                        .ifPresent(env -> match.getContext().setAttribute(MinigameAttributes.MATCH_ENVIRONMENT, env));
            }

            if (slotOrchestrator != null) {
                Map<String, String> statusMetadata = new HashMap<>(metadataSnapshot);
                statusMetadata.putIfAbsent("phase", "pre_lobby");
                slotOrchestrator.updateSlotStatus(slotId,
                        SlotLifecycleStatus.ALLOCATED,
                        initialPlayers.size(),
                        statusMetadata);
                matchSlotMetadata.put(matchId, new ConcurrentHashMap<>(statusMetadata));
            } else {
                matchSlotMetadata.put(matchId, new ConcurrentHashMap<>(metadataSnapshot));
            }
        }
        plugin.getLogger().info("Started minigame match " + matchId + " (family=" + familyId + ")");
        return matchId;
    }

    private SlotContext resolveSlotContext(Collection<Player> players) {
        if (routeRegistry == null || players == null || players.isEmpty()) {
            return null;
        }
        for (Player player : players) {
            Optional<PlayerRouteRegistry.RouteAssignment> assignment = routeRegistry.get(player.getUniqueId());
            if (assignment.isPresent()) {
                PlayerRouteRegistry.RouteAssignment data = assignment.get();
                String slotId = data.slotId();
                if (slotId == null || slotId.isBlank()) {
                    continue;
                }
                Map<String, String> metadata = data.metadata() != null
                        ? data.metadata()
                        : Map.of();
                return new SlotContext(slotId, metadata);
            }
        }
        return null;
    }

    private Map<UUID, Map<String, String>> collectPlayerMetadata(Collection<Player> players) {
        Map<UUID, Map<String, String>> result = new HashMap<>();
        if (routeRegistry == null || players == null) {
            return result;
        }
        for (Player player : players) {
            routeRegistry.get(player.getUniqueId()).ifPresent(assignment -> {
                Map<String, String> metadata = assignment.metadata() != null
                        ? new HashMap<>(assignment.metadata())
                        : new HashMap<>();
                result.put(player.getUniqueId(), metadata);
            });
        }
        return result;
    }

    private void onStateChange(UUID matchId, String familyId, String stateId) {
        String previousState = matchStates.put(matchId, stateId);
        plugin.getLogger().fine(() -> "Match " + matchId + " (" + familyId + ") transitioned to " + stateId);

        String slotId = matchSlotIds.get(matchId);
        MinigameMatch match = activeMatches.get(matchId);

        if (slotOrchestrator != null && slotId != null) {
            Map<String, String> metadataSnapshot = new HashMap<>();
            Map<String, String> stored = matchSlotMetadata.get(matchId);
            if (stored != null && !stored.isEmpty()) {
                metadataSnapshot.putAll(stored);
            }
            metadataSnapshot.put("phase", stateId);

            switch (stateId) {
                case MinigameBlueprint.STATE_IN_GAME -> {
                    int activePlayers = match != null ? (int) match.getRoster().activeCount() : 0;
                    slotOrchestrator.updateSlotStatus(slotId, SlotLifecycleStatus.IN_GAME, activePlayers, metadataSnapshot);
                    matchSlotMetadata.put(matchId, new ConcurrentHashMap<>(metadataSnapshot));
                    if (match != null) {
                        publishMatchRoster(matchId, match, true);
                    }
                    updateRejoinStage(slotId, "in_game", false);
                }
                case MinigameBlueprint.STATE_END_GAME -> {
                    slotOrchestrator.updateSlotStatus(slotId, SlotLifecycleStatus.COOLDOWN, 0, metadataSnapshot);
                    matchSlotMetadata.put(matchId, new ConcurrentHashMap<>(metadataSnapshot));
                    scheduleMatchTeardown(matchId, slotId);
                }
                case MinigameBlueprint.STATE_PRE_LOBBY -> {
                    int rosterCount = match != null ? (int) match.getRoster().activeCount() : 0;
                    slotOrchestrator.updateSlotStatus(slotId, SlotLifecycleStatus.ALLOCATED, rosterCount, metadataSnapshot);
                    matchSlotMetadata.put(matchId, new ConcurrentHashMap<>(metadataSnapshot));
                    publishMatchRoster(matchId, match);
                    updateRejoinStage(slotId, "pre_lobby", false);
                }
                default -> matchSlotMetadata.put(matchId, new ConcurrentHashMap<>(metadataSnapshot));
            }
        }

        if (match != null && sessionService != null) {
            MatchContext context = matchContexts.get(matchId);
            switch (stateId) {
                case MinigameBlueprint.STATE_IN_GAME -> {
                    matchStartTimes.put(matchId, System.currentTimeMillis());
                    markActiveGameplay(matchId, match);
                    if (context != null) {
                        match.getRoster().all().forEach(entry ->
                                tagPlayerSegment(matchId, entry.getPlayerId(), context, false));
                    }
                }
                case MinigameBlueprint.STATE_END_GAME -> {
                    match.getRoster().all().forEach(entry ->
                            sessionService.updateActiveSegmentMetadata(entry.getPlayerId(), metadata -> metadata.put("phase", MinigameBlueprint.STATE_END_GAME)));
                    recordMatchLog(matchId, match);
                    recordMatchHistory(matchId, match);
                }
                default -> {
                }
            }
        }

        if ((slotOrchestrator == null || slotId == null) && MinigameBlueprint.STATE_END_GAME.equals(stateId)) {
            scheduleMatchTeardown(matchId, slotId);
        }

        if (MinigameBlueprint.STATE_PRE_LOBBY.equals(previousState)
                && !MinigameBlueprint.STATE_PRE_LOBBY.equals(stateId)
                && !MinigameBlueprint.STATE_IN_GAME.equals(stateId)
                && match != null
                && sessionService != null) {
            matchStartTimes.put(matchId, System.currentTimeMillis());
            markActiveGameplay(matchId, match);
        }
    }

    private void publishMatchRoster(UUID matchId, MinigameMatch match) {
        publishMatchRoster(matchId, match, false);
    }

    private void publishMatchRoster(UUID matchId, MinigameMatch match, boolean force) {
        if (messageBus == null || match == null) {
            return;
        }
        if (!force && !publishedRosters.add(matchId)) {
            return;
        }

        if (force) {
            publishedRosters.add(matchId);
        }

        String slotId = matchSlotIds.get(matchId);
        if (slotId == null || slotId.isBlank()) {
            if (!force) {
                publishedRosters.remove(matchId);
            }
            return;
        }

        Set<UUID> players = match.getRoster().all().stream()
                .map(entry -> entry != null ? entry.getPlayerId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (players.isEmpty()) {
            publishedRosters.remove(matchId);
            return;
        }

        MatchContext context = matchContexts.get(matchId);
        MatchRosterCreatedMessage message = new MatchRosterCreatedMessage();
        message.setMatchId(matchId);
        message.setSlotId(slotId);
        String currentServerId = messageBus.currentServerId();
        if (currentServerId != null && !currentServerId.isBlank()) {
            message.setServerId(currentServerId);
        }
        if (context != null) {
            message.setFamilyId(context.family());
            message.setVariantId(context.variant());
        }
        message.setPlayers(players);
        message.setCreatedAt(System.currentTimeMillis());
        try {
            message.validate();
            messageBus.broadcast(ChannelConstants.MATCH_ROSTER_CREATED, message);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to publish match roster for " + matchId, exception);
        }
    }

    private void publishRosterEnded(UUID matchId, String slotId) {
        if (messageBus == null || slotId == null || slotId.isBlank()) {
            return;
        }
        updateRejoinStage(slotId, "closed", true);
        try {
            MatchRosterEndedMessage message = new MatchRosterEndedMessage();
            message.setMatchId(matchId);
            message.setSlotId(slotId);
            String currentServerId = messageBus.currentServerId();
            if (currentServerId != null && !currentServerId.isBlank()) {
                message.setServerId(currentServerId);
            }
            message.validate();
            messageBus.broadcast(ChannelConstants.MATCH_ROSTER_ENDED, message);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to publish match roster end for " + matchId, exception);
        }
    }

    private MatchContext buildMatchContext(String familyId,
                                           MinigameRegistration registration,
                                           Map<String, String> metadata,
                                           String slotId) {
        String variant = metadata != null ? metadata.get("variant") : null;
        if ((variant == null || variant.isBlank()) && registration != null) {
            variant = registration.getDescriptor().getMetadata().get("variant");
        }
        if (variant == null || variant.isBlank()) {
            variant = "default";
        }

        String mapId = metadata != null ? metadata.get("mapId") : null;
        if ((mapId == null || mapId.isBlank()) && registration != null) {
            mapId = registration.getDescriptor().getMetadata().get("mapId");
        }
        if (mapId == null || mapId.isBlank()) {
            mapId = "unknown";
        }

        String environment = metadata != null ? metadata.get("environment") : null;
        if (environment == null || environment.isBlank()) {
            environment = "unknown";
        }

        return new MatchContext(familyId, variant, mapId, environment, slotId);
    }

    private void captureSessionId(UUID matchId, UUID playerId) {
        if (sessionService == null || playerId == null) {
            return;
        }
        sessionService.getActiveSession(playerId).ifPresent(record -> {
            String session = record.getSessionId();
            if (session == null || session.isBlank()) {
                return;
            }
            try {
                UUID sessionUuid = UUID.fromString(session);
                matchPlayerSessions.computeIfAbsent(matchId, id -> new ConcurrentHashMap<>())
                        .put(playerId, sessionUuid);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().fine("Invalid session id '" + session + "' for player " + playerId);
            }
        });
    }

    private UUID resolveSessionId(UUID matchId, UUID playerId) {
        Map<UUID, UUID> sessions = matchPlayerSessions.get(matchId);
        if (sessions != null) {
            UUID existing = sessions.get(playerId);
            if (existing != null) {
                return existing;
            }
        }
        if (sessionService == null) {
            return null;
        }
        Optional<PlayerSessionRecord> record = sessionService.getActiveSession(playerId);
        if (record.isPresent()) {
            String session = record.get().getSessionId();
            if (session != null && !session.isBlank()) {
                try {
                    UUID parsed = UUID.fromString(session);
                    matchPlayerSessions.computeIfAbsent(matchId, id -> new ConcurrentHashMap<>())
                            .put(playerId, parsed);
                    return parsed;
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().fine("Invalid session id '" + session + "' for player " + playerId);
                }
            }
        }
        return null;
    }

    private void tagPlayerSegment(UUID matchId, UUID playerId, MatchContext context, boolean queuePhase) {
        if (sessionService == null || playerId == null) {
            return;
        }
        sessionService.updateActiveSegmentMetadata(playerId, metadata -> {
            metadata.put("matchId", matchId.toString());
            if (context != null) {
                metadata.putIfAbsent("family", context.family());
                metadata.putIfAbsent("variant", context.variant());
                metadata.putIfAbsent("mapId", context.mapId());
            }
            metadata.put("queue", queuePhase);
            metadata.put("phase", queuePhase ? MinigameBlueprint.STATE_PRE_LOBBY : MinigameBlueprint.STATE_IN_GAME);
            if (!queuePhase) {
                metadata.put("playStartedAt", System.currentTimeMillis());
            }
        });
    }

    private void recordMatchHistory(UUID matchId, MinigameMatch match) {
        if (matchHistoryWriter == null || recordedMatches.contains(matchId)) {
            return;
        }
        MatchContext context = matchContexts.get(matchId);
        if (context == null) {
            return;
        }
        List<MatchHistoryWriter.Participant> participants = new ArrayList<>();
        match.getRoster().all().forEach(entry -> {
            UUID playerId = entry.getPlayerId();
            UUID sessionId = resolveSessionId(matchId, playerId);
            participants.add(new MatchHistoryWriter.Participant(playerId, sessionId));
        });
        if (participants.isEmpty()) {
            return;
        }
        matchHistoryWriter.recordMatch(matchId, participants);
        recordedMatches.add(matchId);
    }

    private void recordMatchLog(UUID matchId, MinigameMatch match) {
        if (matchLogWriter == null) {
            matchEvents.remove(matchId);
            return;
        }
        MatchContext context = matchContexts.get(matchId);
        if (context == null) {
            matchEvents.remove(matchId);
            return;
        }
        List<MatchLogWriter.Event> events = matchEvents.remove(matchId);
        if (events == null) {
            events = List.of();
        } else {
            events = List.copyOf(events);
        }

        long startedAt = matchStartTimes.getOrDefault(matchId, System.currentTimeMillis());
        long endedAt = System.currentTimeMillis();

        matchLogWriter.recordMatch(matchId,
                context.family(),
                context.variant(),
                context.mapId(),
                context.environment(),
                context.slotId(),
                startedAt,
                endedAt,
                events);
    }

    private void markActiveGameplay(UUID matchId, MinigameMatch match) {
        if (sessionService == null || match == null) {
            return;
        }
        long playStart = System.currentTimeMillis();
        match.getContext().getActivePlayers().forEach(playerId ->
                sessionService.updateActiveSegmentMetadata(playerId, metadata -> {
                    metadata.put("phase", MinigameBlueprint.STATE_IN_GAME);
                    metadata.put("queue", Boolean.FALSE);
                    metadata.put("playStartedAt", playStart);
                    metadata.putIfAbsent("matchId", matchId.toString());
                })
        );
    }

    public Optional<String> resolveSlotId(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        String slotId = playerSlotIndex.get(playerId);
        if (slotId != null) {
            return Optional.of(slotId);
        }
        UUID matchId = playerMatchIndex.get(playerId);
        if (matchId != null) {
            String matchSlot = matchSlotIds.get(matchId);
            if (matchSlot != null) {
                return Optional.of(matchSlot);
            }
            MinigameMatch match = activeMatches.get(matchId);
            if (match != null) {
                return match.getContext().getSlotId();
            }
        }
        return Optional.empty();
    }

    public Optional<String> resolveSlotId(World world) {
        if (world == null) {
            return Optional.empty();
        }
        if (environmentService != null) {
            Optional<String> slot = environmentService.resolveSlotIdByWorld(world.getName());
            if (slot.isPresent()) {
                return slot;
            }
        }
        for (Map.Entry<String, UUID> entry : slotMatches.entrySet()) {
            UUID matchId = entry.getValue();
            MinigameMatch match = activeMatches.get(matchId);
            if (match == null) {
                continue;
            }
            MatchEnvironment environment = match.getContext()
                    .getAttributeOptional(MinigameAttributes.MATCH_ENVIRONMENT, MatchEnvironment.class)
                    .orElse(null);
            if (environment != null && world.getName().equalsIgnoreCase(environment.worldName())) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    public Set<UUID> getPlayerIdsInSlot(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return Set.of();
        }
        Set<UUID> result = new HashSet<>();
        for (Map.Entry<UUID, String> entry : playerSlotIndex.entrySet()) {
            if (entry.getValue() != null && slotId.equalsIgnoreCase(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        if (result.isEmpty()) {
            UUID matchId = slotMatches.get(slotId);
            if (matchId != null) {
                MinigameMatch match = activeMatches.get(matchId);
                if (match != null) {
                    result.addAll(match.getContext().getActivePlayers());
                }
            }
        }
        return result;
    }

    public Collection<Player> getPlayersInSlot(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return List.of();
        }
        List<Player> players = new ArrayList<>();
        for (UUID playerId : getPlayerIdsInSlot(slotId)) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    public List<String> getPlayerNamesInSlot(String slotId) {
        return getPlayersInSlot(slotId).stream()
                .map(Player::getName)
                .filter(Objects::nonNull)
                .toList();
    }

    public Set<String> getPlayerNameSnapshotInSlot(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return Set.of();
        }
        Set<UUID> ids = getPlayerIdsInSlot(slotId);
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (UUID id : ids) {
            String name = playerNameIndex.get(id);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private void refreshSlotVisibility() {
        if (!plugin.getServer().isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, this::refreshSlotVisibilitySync);
        } else {
            refreshSlotVisibilitySync();
        }
    }

    private void refreshSlotVisibilitySync() {
        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        for (Player viewer : players) {
            UUID viewerId = viewer.getUniqueId();
            String viewerSlot = playerSlotIndex.get(viewerId);
            for (Player target : players) {
                if (viewer == target) {
                    continue;
                }
                UUID targetId = target.getUniqueId();
                String targetSlot = playerSlotIndex.get(targetId);
                VisibilityKey key = new VisibilityKey(viewerId, targetId);
                if (viewerSlot != null && targetSlot != null && !viewerSlot.equalsIgnoreCase(targetSlot)) {
                    if (slotHiddenPairs.add(key)) {
                        viewer.hidePlayer(plugin, target);
                    }
                } else {
                    if (slotHiddenPairs.remove(key)) {
                        viewer.showPlayer(plugin, target);
                    }
                }
            }
        }
        slotHiddenPairs.removeIf(key -> plugin.getServer().getPlayer(key.viewer()) == null
                || plugin.getServer().getPlayer(key.target()) == null);
    }

    private static final class PostMatchTimeline {
        private BukkitTask victoryTask;
        private BukkitTask teardownTask;

        void setVictoryTask(BukkitTask victoryTask) {
            this.victoryTask = victoryTask;
        }

        void setTeardownTask(BukkitTask teardownTask) {
            this.teardownTask = teardownTask;
        }

        void cancel() {
            if (victoryTask != null) {
                victoryTask.cancel();
            }
            if (teardownTask != null) {
                teardownTask.cancel();
            }
        }
    }

    private record SlotContext(String slotId, Map<String, String> metadata) {
    }

    private record MatchContext(String family, String variant, String mapId, String environment, String slotId) {
    }

    private record VisibilityKey(UUID viewer, UUID target) {
    }

    public void logMatchEvent(UUID matchId, MatchLogWriter.Event event) {
        if (matchId == null || event == null || matchLogWriter == null) {
            return;
        }
        Map<String, Object> details = new HashMap<>(event.details());
        MinigameMatch match = activeMatches.get(matchId);
        if (match != null) {
            match.getContext().team(event.actor()).ifPresent(team -> details.put("teamId", team.getId()));
        }
        MatchLogWriter.Event enriched = new MatchLogWriter.Event(event.timestamp(), event.type(), event.actor(), event.target(), details);
        matchEvents.computeIfAbsent(matchId, id -> Collections.synchronizedList(new ArrayList<>())).add(enriched);
    }
}
