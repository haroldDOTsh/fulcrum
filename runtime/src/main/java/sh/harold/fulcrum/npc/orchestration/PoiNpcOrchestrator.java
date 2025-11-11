package sh.harold.fulcrum.npc.orchestration;

import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.common.cooldown.*;
import sh.harold.fulcrum.npc.NpcDefinition;
import sh.harold.fulcrum.npc.NpcRegistry;
import sh.harold.fulcrum.npc.adapter.NpcAdapter;
import sh.harold.fulcrum.npc.adapter.NpcHandle;
import sh.harold.fulcrum.npc.adapter.NpcSpawnRequest;
import sh.harold.fulcrum.npc.behavior.InteractionContext;
import sh.harold.fulcrum.npc.behavior.NpcBehavior;
import sh.harold.fulcrum.npc.behavior.NpcInteractionHelpers;
import sh.harold.fulcrum.npc.behavior.PassiveContext;
import sh.harold.fulcrum.npc.poi.PoiActivatedEvent;
import sh.harold.fulcrum.npc.poi.PoiActivationBus;
import sh.harold.fulcrum.npc.poi.PoiActivationListener;
import sh.harold.fulcrum.npc.poi.PoiDeactivatedEvent;
import sh.harold.fulcrum.npc.skin.NpcSkinCacheService;
import sh.harold.fulcrum.npc.view.NpcViewerService;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens for POI activations and spawns/despawns NPC definitions accordingly.
 */
public final class PoiNpcOrchestrator implements PoiActivationListener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Logger logger;
    private final NpcRegistry npcRegistry;
    private final PoiActivationBus activationBus;
    private final NpcAdapter adapter;
    private final NpcSkinCacheService skinCache;
    private final NpcViewerService viewerService;
    private final NpcInteractionHelpers helpers;
    private final CooldownRegistry cooldownRegistry;
    private final Map<PoiInstanceKey, CopyOnWriteArrayList<NpcHandle>> activeHandles = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveNpc> activeByInstance = new ConcurrentHashMap<>();
    private final PoiActivationBus.Subscription subscription;
    private final NpcInstanceRegistry instanceRegistry = new NpcInstanceRegistry();

    public PoiNpcOrchestrator(JavaPlugin plugin,
                              Logger logger,
                              NpcRegistry npcRegistry,
                              PoiActivationBus activationBus,
                              NpcAdapter adapter,
                              NpcSkinCacheService skinCache,
                              NpcViewerService viewerService,
                              CooldownRegistry cooldownRegistry,
                              NpcInteractionHelpers helpers) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.npcRegistry = Objects.requireNonNull(npcRegistry, "npcRegistry");
        this.activationBus = Objects.requireNonNull(activationBus, "activationBus");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.skinCache = Objects.requireNonNull(skinCache, "skinCache");
        this.viewerService = Objects.requireNonNull(viewerService, "viewerService");
        this.cooldownRegistry = Objects.requireNonNull(cooldownRegistry, "cooldownRegistry");
        this.helpers = Objects.requireNonNull(helpers, "helpers");
        this.subscription = activationBus.subscribe(this);
    }

    @Override
    public void onActivated(PoiActivatedEvent event) {
        event.anchorId().ifPresent(anchor -> npcRegistry.all().stream()
                .filter(definition -> anchor.equalsIgnoreCase(definition.poiAnchor()))
                .forEach(definition -> schedulePoiSpawn(event, definition)));
    }

    @Override
    public void onDeactivated(PoiDeactivatedEvent event) {
        event.anchorId().ifPresent(anchor -> {
            PoiInstanceKey key = PoiInstanceKey.from(event.worldName(), anchor, event.location());
            List<NpcHandle> handles = activeHandles.remove(key);
            if (handles == null) {
                return;
            }
            for (NpcHandle handle : handles) {
                despawnHandle(handle);
            }
        });
    }

    private void schedulePoiSpawn(PoiActivatedEvent event, NpcDefinition definition) {
        Location location = resolveLocation(event, definition);
        spawnFromDefinition(definition, location, event.location(), event.worldName(), definition.poiAnchor(), event, 0L);
    }

    private void spawnFromDefinition(NpcDefinition definition,
                                     Location location,
                                     Location keyLocation,
                                     String worldName,
                                     String anchor,
                                     PoiActivatedEvent event,
                                     long lifetimeTicks) {
        UUID instanceId = UUID.randomUUID();
        Location spawnLocation = location.clone();
        skinCache.resolve(definition.profile())
                .thenCompose(payload -> adapter.spawn(
                        new NpcSpawnRequest(instanceId, definition, spawnLocation, event, payload)))
                .thenAccept(handle -> {
                    storeHandle(handle, worldName, anchor, keyLocation);
                    if (lifetimeTicks > 0) {
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> despawnHandle(handle), lifetimeTicks);
                    }
                })
                .exceptionally(exception -> {
                    logger.log(Level.WARNING,
                            "Failed to spawn NPC " + definition.id() + " (anchor=" + anchor + ")", exception);
                    return null;
                });
    }

    private void storeHandle(NpcHandle handle, String worldName, String anchor, Location keyLocation) {
        PoiInstanceKey key = PoiInstanceKey.from(worldName, anchor, keyLocation);
        activeHandles.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(handle);
        viewerService.register(handle, handle.definition());
        instanceRegistry.register(handle);
        schedulePassive(handle);
    }

    private void schedulePassive(NpcHandle handle) {
        NpcBehavior behavior = handle.definition().behavior();
        if (behavior == null) {
            return;
        }
        BukkitTask task = null;
        if (behavior.passiveHandler() != null) {
            int interval = Math.max(1, behavior.passiveIntervalTicks());
            task = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    () -> behavior.passiveHandler().execute(new DefaultPassiveContext(handle)),
                    interval,
                    interval
            );
        }
        activeByInstance.put(handle.instanceId(), new ActiveNpc(handle, behavior, task));
    }

    private void despawnHandle(NpcHandle handle) {
        try {
            adapter.despawn(handle);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Failed to despawn NPC " + handle.definition().id(), exception);
        }
        viewerService.unregister(handle.instanceId());
        instanceRegistry.unregister(handle);
        activeHandles.values().forEach(list -> list.remove(handle));
        ActiveNpc active = activeByInstance.remove(handle.instanceId());
        if (active != null && active.task() != null) {
            active.task().cancel();
        }
    }

    public void handleInteraction(UUID adapterId, Player player) {
        if (adapterId == null || player == null || !player.isOnline()) {
            return;
        }
        NpcHandle handle = instanceRegistry.findByAdapterId(adapterId);
        if (handle == null) {
            return;
        }
        ActiveNpc active = activeByInstance.get(handle.instanceId());
        if (active == null) {
            return;
        }
        CooldownKey cooldownKey = CooldownKeys.npcInteraction(player.getUniqueId(), handle.instanceId());
        int cooldownTicks = Math.max(0, handle.definition().behavior().interactionCooldownTicks());
        if (cooldownTicks <= 0) {
            executeInteraction(active, handle, player, cooldownKey);
            return;
        }
        long cooldownMillis = (long) Math.max(1, cooldownTicks) * 50L;
        Duration window = Duration.ofMillis(cooldownMillis);
        cooldownRegistry.acquire(cooldownKey, CooldownSpec.rejecting(window))
                .thenAccept(acquisition -> handleAcquisition(acquisition, active, handle, player, cooldownKey))
                .exceptionally(throwable -> {
                    logger.log(Level.WARNING, "Failed to enforce NPC interaction cooldown", throwable);
                    return null;
                });
    }

    private void handleAcquisition(CooldownAcquisition acquisition,
                                   ActiveNpc active,
                                   NpcHandle handle,
                                   Player player,
                                   CooldownKey cooldownKey) {
        if (!(acquisition instanceof CooldownAcquisition.Accepted)) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        ActiveNpc current = activeByInstance.get(handle.instanceId());
        if (current != active) {
            return;
        }
        executeInteraction(active, handle, player, cooldownKey);
    }

    private void executeInteraction(ActiveNpc active,
                                    NpcHandle handle,
                                    Player player,
                                    CooldownKey cooldownKey) {
        plugin.getServer().getScheduler().runTask(
                plugin,
                () -> active.behavior().interactionHandler().execute(
                        new DefaultInteractionContext(handle, player, cooldownKey)
                )
        );
    }

    private Location resolveLocation(PoiActivatedEvent event, NpcDefinition definition) {
        Location base = event.location();
        Location spawn = base.clone().add(definition.relativeOffset());
        JsonObject configuration = event.configuration();
        float baseYaw = configuration.has("yaw") ? configuration.get("yaw").getAsFloat() : base.getYaw();
        spawn.setYaw(baseYaw + definition.yawOffset());
        spawn.setPitch(definition.pitchOffset());
        return spawn;
    }

    public void spawnTemporaryNpc(NpcDefinition definition, Location location, long lifetimeTicks) {
        String anchor = definition.poiAnchor() != null ? definition.poiAnchor() : definition.id();
        String worldName = location.getWorld() != null ? location.getWorld().getName() : "unknown";
        spawnFromDefinition(definition, location, location, worldName, anchor, null, lifetimeTicks);
    }

    @Override
    public void close() {
        subscription.unsubscribe();
        activeHandles.values().forEach(handles -> handles.forEach(this::despawnHandle));
        activeHandles.clear();
        activeByInstance.values().forEach(active -> {
            if (active.task() != null) {
                active.task().cancel();
            }
        });
        activeByInstance.clear();
    }

    private record PoiInstanceKey(String worldName, String anchorId, int x, int y, int z) {
        static PoiInstanceKey from(String world, String anchor, Location location) {
            if (anchor == null) {
                return null;
            }
            return new PoiInstanceKey(
                    world,
                    anchor.toLowerCase(Locale.ROOT),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }
    }

    private record ActiveNpc(NpcHandle handle, NpcBehavior behavior, BukkitTask task) {
    }

    private class DefaultPassiveContext implements PassiveContext {
        private final NpcHandle handle;

        private DefaultPassiveContext(NpcHandle handle) {
            this.handle = handle;
        }

        @Override
        public UUID npcInstanceId() {
            return handle.instanceId();
        }

        @Override
        public NpcDefinition definition() {
            return handle.definition();
        }

        @Override
        public Location location() {
            return handle.lastKnownLocation();
        }

        @Override
        public Collection<Player> viewers() {
            return viewerService.viewersFor(handle.instanceId());
        }

        @Override
        public NpcInteractionHelpers helpers() {
            return helpers;
        }
    }

    private final class DefaultInteractionContext extends DefaultPassiveContext implements InteractionContext {
        private final Player player;
        private final CooldownKey cooldownKey;

        private DefaultInteractionContext(NpcHandle handle, Player player, CooldownKey cooldownKey) {
            super(handle);
            this.player = player;
            this.cooldownKey = cooldownKey;
        }

        @Override
        public Player player() {
            return player;
        }

        @Override
        public UUID playerId() {
            return player.getUniqueId();
        }

        @Override
        public CooldownKey cooldownKey() {
            return cooldownKey;
        }
    }
}
