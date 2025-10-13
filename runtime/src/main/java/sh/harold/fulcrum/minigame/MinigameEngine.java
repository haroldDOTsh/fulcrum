package sh.harold.fulcrum.minigame;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagService;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService.MatchEnvironment;
import sh.harold.fulcrum.minigame.match.MinigameMatch;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;

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
    private BukkitTask tickTask;

    public MinigameEngine(JavaPlugin plugin,
                          PlayerRouteRegistry routeRegistry,
                          MinigameEnvironmentService environmentService,
                          SimpleSlotOrchestrator slotOrchestrator,
                          ActionFlagService actionFlags) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.routeRegistry = routeRegistry;
        this.environmentService = environmentService;
        this.slotOrchestrator = slotOrchestrator;
        this.actionFlags = actionFlags;
    }

    public void registerRegistration(MinigameRegistration registration) {
        registrations.put(registration.getFamilyId(), registration);
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

            if (slotOrchestrator != null) {
                Map<String, String> removalMetadata = new HashMap<>();
                if (metadataSnapshot != null && !metadataSnapshot.isEmpty()) {
                    removalMetadata.putAll(metadataSnapshot);
                }
                removalMetadata.put("phase", "teardown");
                removalMetadata.put("teardownAt", Long.toString(System.currentTimeMillis()));
                slotOrchestrator.removeSlot(slotId, SlotLifecycleStatus.COOLDOWN, removalMetadata);
            }

            if (environmentService != null) {
                environmentService.cleanup(slotId);
            }
        }

        if (removedMatch != null) {
            removedMatch.getContext().removeAttribute(MinigameAttributes.SLOT_ID);
            removedMatch.getContext().removeAttribute(MinigameAttributes.SLOT_METADATA);
            removedMatch.getContext().removeAttribute(MinigameAttributes.MATCH_ENVIRONMENT);
            removedMatch.getContext().clearFlagsForRoster();
        }
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
        }
    }

    public void removePlayer(UUID matchId, UUID playerId) {
        MinigameMatch match = activeMatches.get(matchId);
        if (match != null) {
            match.removePlayer(playerId);
        }
    }

    public Optional<MinigameMatch> findMatchByPlayer(UUID playerId) {
        return activeMatches.values().stream()
                .filter(match -> match.getContext().isPlayerRegistered(playerId))
                .findFirst();
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
        MinigameMatch match = new MinigameMatch(plugin, matchId, blueprint, registration, players,
                stateId -> onStateChange(matchId, familyId, stateId),
                actionFlags);
        activeMatches.put(matchId, match);
        matchStates.put(matchId, blueprint.getStartStateId());
        if (slotContext != null) {
            match.getContext().setAttribute(MinigameAttributes.SLOT_ID, slotContext.slotId);

            Map<String, String> metadataSnapshot = new HashMap<>();
            if (slotContext.metadata != null && !slotContext.metadata.isEmpty()) {
                metadataSnapshot.putAll(slotContext.metadata);
            }
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
        matchStates.put(matchId, stateId);
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

        if ((slotOrchestrator == null || slotId == null) && MinigameBlueprint.STATE_END_GAME.equals(stateId)) {
            scheduleMatchTeardown(matchId, slotId);
        }
    }

    private record SlotContext(String slotId, Map<String, String> metadata) {
    }
}
