package sh.harold.fulcrum.minigame;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagContexts;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagService;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.minigame.data.MinigameDataRegistry;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService.MatchEnvironment;
import sh.harold.fulcrum.minigame.match.MatchHistoryWriter;
import sh.harold.fulcrum.minigame.match.MatchLogWriter;
import sh.harold.fulcrum.minigame.match.MinigameMatch;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Runtime engine that manages active minigame matches.
 */
public final class MinigameEngine {
    private static final long MATCH_TEARDOWN_DELAY_TICKS = 100L;
    private final JavaPlugin plugin;
    private final PlayerRouteRegistry routeRegistry;
    private final MinigameEnvironmentService environmentService;
    private final SimpleSlotOrchestrator slotOrchestrator;
    private final ActionFlagService actionFlags;
    private final PlayerSessionService sessionService;
    private final MinigameDataRegistry dataRegistry;
    private final MatchHistoryWriter matchHistoryWriter;
    private final MatchLogWriter matchLogWriter;
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
    private final Map<UUID, MatchContext> matchContexts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> matchStartTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, UUID>> matchPlayerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, List<MatchLogWriter.Event>> matchEvents = new ConcurrentHashMap<>();
    private final Set<UUID> recordedMatches = ConcurrentHashMap.newKeySet();
    private BukkitTask tickTask;

    public MinigameEngine(JavaPlugin plugin,
                          PlayerRouteRegistry routeRegistry,
                          MinigameEnvironmentService environmentService,
                          SimpleSlotOrchestrator slotOrchestrator,
                          ActionFlagService actionFlags,
                          PlayerSessionService sessionService,
                          MinigameDataRegistry dataRegistry,
                          MatchHistoryWriter matchHistoryWriter,
                          MatchLogWriter matchLogWriter) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.routeRegistry = routeRegistry;
        this.environmentService = environmentService;
        this.slotOrchestrator = slotOrchestrator;
        this.actionFlags = actionFlags;
        this.sessionService = sessionService;
        this.dataRegistry = dataRegistry;
        this.matchHistoryWriter = matchHistoryWriter;
        this.matchLogWriter = matchLogWriter;
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
        BukkitTask pending = teardownTasks.remove(matchId);
        if (pending != null) {
            pending.cancel();
        }

        MinigameMatch removedMatch = activeMatches.remove(matchId);
        matchStates.remove(matchId);

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

            if (environmentService != null) {
                environmentService.cleanup(slotId);
            }
        }

        if (removedMatch != null) {
            removedMatch.getContext().getActivePlayers().forEach(playerMatchIndex::remove);
            removedMatch.getContext().removeAttribute(MinigameAttributes.SLOT_ID);
            removedMatch.getContext().removeAttribute(MinigameAttributes.SLOT_METADATA);
            removedMatch.getContext().removeAttribute(MinigameAttributes.MATCH_ENVIRONMENT);
            removedMatch.getContext().clearFlagsForRoster();
            if (!removedMatch.getContext().getActivePlayers().isEmpty()) {
                removedMatch.getContext().applyFlagContext(ActionFlagContexts.LOBBY_DEFAULT);
            }
            if (sessionService != null) {
                removedMatch.getRoster().all().forEach(entry ->
                        sessionService.clearTrackedMatch(entry.getPlayerId()));
            }
        }
        matchContexts.remove(matchId);
        matchStartTimes.remove(matchId);
        matchPlayerSessions.remove(matchId);
        recordedMatches.remove(matchId);
        matchEvents.remove(matchId);
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
        }
    }

    public void removePlayer(UUID matchId, UUID playerId) {
        MinigameMatch match = activeMatches.get(matchId);
        if (match != null) {
            match.removePlayer(playerId);
            playerMatchIndex.remove(playerId);
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
        UUID playerId = player.getUniqueId();
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

        UUID existingMatch = slotMatches.get(slotId);
        if (existingMatch != null) {
            addPlayer(existingMatch, player, false);
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
        activeMatches.clear();
        provisioningSlots.clear();
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
                markSlotFault(slot.slotId(), "environment-unavailable");
                return;
            }

            Location lobbySpawn = environment.lobbySpawn();
            String worldName = environment.worldName();
            if (lobbySpawn == null || worldName == null || worldName.isBlank()) {
                plugin.getLogger().warning("Environment for slot " + slot.slotId() + " did not provide a valid spawn location");
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
        if (teardownTasks.containsKey(matchId)) {
            return;
        }
        if (slotId == null || slotId.isBlank()) {
            endMatch(matchId);
            return;
        }

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            teardownTasks.remove(matchId);
            endMatch(matchId);
        }, MATCH_TEARDOWN_DELAY_TICKS);
        teardownTasks.put(matchId, task);
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
        UUID matchId = UUID.randomUUID();
        SlotContext slotContext = resolveSlotContext(players);
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

        MinigameMatch match = new MinigameMatch(plugin, matchId, blueprint, registration, players,
                stateId -> onStateChange(matchId, familyId, stateId),
                actionFlags);
        activeMatches.put(matchId, match);
        matchStates.put(matchId, blueprint.getStartStateId());
        for (Player player : players) {
            UUID playerId = player.getUniqueId();
            playerMatchIndex.put(playerId, matchId);
            captureSessionId(matchId, playerId);
            tagPlayerSegment(matchId, playerId, context, true);
            if (sessionService != null) {
                sessionService.setActiveMatchId(playerId, matchId);
            }
        }
        if (slotContext != null) {
            match.getContext().setAttribute(MinigameAttributes.SLOT_ID, slotContext.slotId);

            if (!metadataSnapshot.isEmpty()) {
                match.getContext().setAttribute(MinigameAttributes.SLOT_METADATA, new HashMap<>(metadataSnapshot));
            }

            matchSlotIds.put(matchId, slotContext.slotId);
            slotMatches.put(slotContext.slotId, matchId);

            if (environmentService != null) {
                environmentService.getEnvironment(slotContext.slotId)
                        .ifPresent(env -> match.getContext().setAttribute(MinigameAttributes.MATCH_ENVIRONMENT, env));
            }

            if (slotOrchestrator != null) {
                Map<String, String> statusMetadata = new HashMap<>(metadataSnapshot);
                statusMetadata.putIfAbsent("phase", "pre_lobby");
                slotOrchestrator.updateSlotStatus(slotContext.slotId,
                        SlotLifecycleStatus.ALLOCATED,
                        players != null ? players.size() : 0,
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
                    recordMatchHistory(matchId, match);
                    recordMatchLog(matchId, match);
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
            if (sessionId != null) {
                participants.add(new MatchHistoryWriter.Participant(playerId, sessionId));
            }
        });
        if (participants.isEmpty()) {
            return;
        }
        long startedAt = matchStartTimes.getOrDefault(matchId, System.currentTimeMillis());
        long endedAt = System.currentTimeMillis();
        matchHistoryWriter.recordMatch(matchId, startedAt, endedAt, context.family(), context.variant(), context.mapId(), participants);
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

        List<MatchLogWriter.RosterEntry> roster = new ArrayList<>();
        match.getRoster().all().forEach(entry -> roster.add(
                new MatchLogWriter.RosterEntry(entry.getPlayerId(),
                        entry.getState() != null ? entry.getState().name() : null,
                        entry.isRespawnAllowed())
        ));

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
                roster,
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

    private record SlotContext(String slotId, Map<String, String> metadata) {
    }

    private record MatchContext(String family, String variant, String mapId, String environment, String slotId) {
    }

    public void logMatchEvent(UUID matchId, MatchLogWriter.Event event) {
        if (matchId == null || event == null || matchLogWriter == null) {
            return;
        }
        matchEvents.computeIfAbsent(matchId, id -> Collections.synchronizedList(new ArrayList<>())).add(event);
    }
}
