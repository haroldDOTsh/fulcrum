package sh.harold.fulcrum.minigame.defaults;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.MinigameAttributes;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService.MatchEnvironment;
import sh.harold.fulcrum.minigame.pregame.PregameLobbyManager;
import sh.harold.fulcrum.minigame.pregame.PregameLobbyManager.PregameLobbyInstance;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;
import sh.harold.fulcrum.minigame.state.AbstractMinigameState;
import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;

/**
 * Countdown-driven lobby before a match begins.
 */
public final class DefaultPreLobbyState extends AbstractMinigameState {
    public static final String NEXT_STATE_ATTRIBUTE = "preLobby.nextState";
    private static final String LOBBY_ATTRIBUTE = "minigame.preLobby.instance";
    private static final String SPAWN_ATTRIBUTE = "minigame.preLobby.spawn";

    private final PreLobbyOptions options;
    private final String nextStateId;

    private BukkitTask countdownTask;

    public DefaultPreLobbyState(PreLobbyOptions options, String nextStateId) {
        this.options = options;
        this.nextStateId = nextStateId;
    }

    @Override
    public void onEnter(StateContext context) {
        spawnPreLobby(context);

        Duration countdown = options.getCountdown();
        AtomicInteger remainingSeconds = new AtomicInteger((int) countdown.getSeconds());
        AtomicBoolean accelerated = new AtomicBoolean(false);
        int maxPlayers = context.getRegistration()
            .map(MinigameRegistration::getDescriptor)
            .map(descriptor -> descriptor.getMaxPlayers())
            .orElse(0);

        if (remainingSeconds.get() <= 0) {
            context.requestTransition(nextStateId);
            return;
        }

        countdownTask = context.scheduleRepeatingTask(() -> {
            if (context.roster().activeCount() < options.getMinimumPlayers()) {
                return; // wait for players
            }

            if (maxPlayers > 0 && context.roster().activeCount() >= maxPlayers) {
                int reduced = remainingSeconds.updateAndGet(current -> Math.min(current, 10));
                if (reduced == 10 && accelerated.compareAndSet(false, true)) {
                    context.broadcast(ChatColor.YELLOW + "All slots filled! Match starting in 10 seconds.");
                }
            }

            int seconds = remainingSeconds.getAndDecrement();
            if (seconds <= 0) {
                context.requestTransition(nextStateId);
                cancelTask();
                return;
            }

            if (seconds <= 10) {
                context.broadcast("Match starting in " + seconds + " seconds");
            }
        }, 0L, 20L);
    }

    @Override
    public void onExit(StateContext context) {
        cancelTask();
        context.getAttributeOptional(LOBBY_ATTRIBUTE, PregameLobbyInstance.class).ifPresent(instance -> {
            instance.remove();
            context.removeAttribute(LOBBY_ATTRIBUTE);
        });

        Optional<String> slotId = context.getAttributeOptional(MinigameAttributes.SLOT_ID, String.class);
        if (slotId.isPresent()) {
            clearSlotSpawnMetadata(slotId.get());
        }
        context.removeAttribute(SPAWN_ATTRIBUTE);
        context.removeAttribute(MinigameAttributes.SLOT_METADATA);
    }

    @SuppressWarnings("unchecked")
    private void spawnPreLobby(StateContext context) {
        if (context.getAttributeOptional(LOBBY_ATTRIBUTE, PregameLobbyInstance.class).isPresent()) {
            return;
        }

        Optional<MinigameRegistration> registrationOpt = context.getRegistration();
        Optional<String> schematicIdOpt = registrationOpt.flatMap(MinigameRegistration::getPreLobbySchematicId);

        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            return;
        }

        MinigameEnvironmentService environmentService = locator.findService(MinigameEnvironmentService.class)
            .orElse(null);
        if (environmentService == null) {
            context.getPlugin().getLogger().warning("Pre-lobby spawn skipped; environment service unavailable.");
            return;
        }

        String slotId = context.getAttributeOptional(MinigameAttributes.SLOT_ID, String.class)
            .orElseGet(() -> resolveSlotId(context, locator).orElse(null));
        if (slotId == null) {
            context.getPlugin().getLogger().warning("Pre-lobby spawn skipped; slot id unavailable.");
            return;
        }

        Map<String, String> metadata = context.getAttributeOptional(MinigameAttributes.SLOT_METADATA, Map.class)
            .map(value -> (Map<String, String>) value)
            .orElse(Map.of());

        MatchEnvironment environment = environmentService.getEnvironment(slotId)
            .orElseGet(() -> environmentService.prepareEnvironment(slotId, metadata));
        if (environment == null) {
            context.getPlugin().getLogger().warning("Pre-lobby spawn skipped; environment not prepared for slot " + slotId);
            return;
        }

        World world = Bukkit.getWorld(environment.worldName());
        if (world == null) {
            context.getPlugin().getLogger().warning("Pre-lobby spawn skipped; world not loaded for environment " + environment.worldName());
            return;
        }

        Location lobbySpawn = environment.lobbySpawn();
        if (lobbySpawn == null) {
            context.getPlugin().getLogger().warning("Pre-lobby spawn skipped; lobby spawn unavailable for slot " + slotId);
            return;
        }

        Location[] spawnHolder = new Location[] { lobbySpawn.clone() };
        context.setAttribute(MinigameAttributes.SLOT_ID, slotId);

        if (schematicIdOpt.isPresent()) {
            int offset = registrationOpt.map(MinigameRegistration::getPreLobbyHeightOffset).orElse(50);
            PregameLobbyManager.spawn(context.getPlugin(), world, lobbySpawn, schematicIdOpt.get(), offset)
                .ifPresent(instance -> {
                    context.setAttribute(LOBBY_ATTRIBUTE, instance);
                    spawnHolder[0] = instance.getSpawnLocation();
                });
        }

        context.setAttribute(SPAWN_ATTRIBUTE, spawnHolder[0]);
        world.setSpawnLocation(spawnHolder[0]);

        updateSlotSpawnMetadata(context, spawnHolder[0]);
        Location teleportTarget = spawnHolder[0].clone();
        context.forEachPlayer(player -> player.teleportAsync(teleportTarget.clone()));
    }

    private void cancelTask() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void updateSlotSpawnMetadata(StateContext context, Location spawn) {
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null || spawn == null) {
            return;
        }

        Optional<String> slotIdOpt = resolveSlotId(context, locator);
        if (slotIdOpt.isEmpty()) {
            context.getPlugin().getLogger().fine("Unable to resolve slot id for pre-lobby metadata update");
            return;
        }

        SimpleSlotOrchestrator orchestrator = locator.findService(SimpleSlotOrchestrator.class).orElse(null);
        if (orchestrator == null) {
            context.getPlugin().getLogger().warning("Cannot update slot metadata; orchestrator unavailable");
            return;
        }

        Map<String, String> payload = new HashMap<>();
        payload.put("targetWorld", spawn.getWorld().getName());
        payload.put("spawnX", String.valueOf(spawn.getX()));
        payload.put("spawnY", String.valueOf(spawn.getY()));
        payload.put("spawnZ", String.valueOf(spawn.getZ()));
        payload.put("spawnYaw", String.valueOf(spawn.getYaw()));
        payload.put("spawnPitch", String.valueOf(spawn.getPitch()));

        orchestrator.updateSlotMetadata(slotIdOpt.get(), payload);
        locator.findService(MinigameEngine.class).ifPresent(engine -> engine.refreshSlotMetadata(slotIdOpt.get(), payload));
        context.setAttribute(MinigameAttributes.SLOT_ID, slotIdOpt.get());
        context.setAttribute(MinigameAttributes.SLOT_METADATA, new HashMap<>(payload));
    }

    private void clearSlotSpawnMetadata(String slotId) {
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            return;
        }
        SimpleSlotOrchestrator orchestrator = locator.findService(SimpleSlotOrchestrator.class).orElse(null);
        if (orchestrator == null) {
            return;
        }
        Map<String, String> payload = new HashMap<>();
        payload.put("spawnX", null);
        payload.put("spawnY", null);
        payload.put("spawnZ", null);
        payload.put("spawnYaw", null);
        payload.put("spawnPitch", null);
        orchestrator.updateSlotMetadata(slotId, payload);
    }

    private Optional<String> resolveSlotId(StateContext context, ServiceLocatorImpl locator) {
        Optional<String> attr = context.getAttributeOptional(MinigameAttributes.SLOT_ID, String.class);
        if (attr.isPresent()) {
            return attr;
        }

        PlayerRouteRegistry registry = locator.findService(PlayerRouteRegistry.class).orElse(null);
        if (registry == null) {
            return Optional.empty();
        }

        for (UUID playerId : context.getActivePlayers()) {
            Optional<PlayerRouteRegistry.RouteAssignment> assignment = registry.get(playerId);
            if (assignment.isPresent() && assignment.get().slotId() != null && !assignment.get().slotId().isBlank()) {
                return Optional.of(assignment.get().slotId());
            }
        }
        return Optional.empty();
    }
}



