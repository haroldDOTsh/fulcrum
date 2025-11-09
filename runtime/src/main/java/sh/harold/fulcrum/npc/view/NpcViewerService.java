package sh.harold.fulcrum.npc.view;

import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.npc.NpcDefinition;
import sh.harold.fulcrum.npc.adapter.NpcHandle;
import sh.harold.fulcrum.npc.visibility.NpcVisibility;
import sh.harold.fulcrum.npc.visibility.NpcVisibilityContext;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-player visibility, hiding NPC entities until the predicate allows them.
 */
public final class NpcViewerService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final RankService rankService;
    private final PlayerSessionService sessionService;
    private final Map<UUID, TrackedNpc> trackedNpcs = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> playerVisibleNpcs = new ConcurrentHashMap<>();
    private final BukkitTask refreshTask;

    public NpcViewerService(JavaPlugin plugin,
                            RankService rankService,
                            PlayerSessionService sessionService) {
        this.plugin = plugin;
        this.rankService = rankService;
        this.sessionService = sessionService;
        this.refreshTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::refreshAllPlayers,
                40L,
                40L
        );
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void register(NpcHandle handle, NpcDefinition definition) {
        trackedNpcs.put(handle.instanceId(), new TrackedNpc(handle, definition));
        plugin.getServer().getScheduler().runTask(plugin, this::refreshAllPlayers);
    }

    public void unregister(UUID instanceId) {
        TrackedNpc removed = trackedNpcs.remove(instanceId);
        if (removed == null) {
            return;
        }
        Entity entity = removed.handle().bukkitEntity();
        if (entity == null) {
            return;
        }
        playerVisibleNpcs.forEach((playerId, visibleSet) -> {
            if (visibleSet.remove(instanceId)) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    player.hideEntity(plugin, entity);
                }
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refreshPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerVisibleNpcs.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refreshPlayer(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        refreshPlayer(event.getPlayer());
    }

    @EventHandler
    public void onChunkLoad(PlayerChunkLoadEvent event) {
        refreshPlayer(event.getPlayer());
    }

    @EventHandler
    public void onChunkUnload(PlayerChunkUnloadEvent event) {
        refreshPlayer(event.getPlayer());
    }

    private void refreshAllPlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refreshPlayer(player);
        }
    }

    public Collection<Player> viewersFor(UUID instanceId) {
        List<Player> viewers = new java.util.ArrayList<>();
        playerVisibleNpcs.forEach((playerId, visibleSet) -> {
            if (visibleSet.contains(instanceId)) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    viewers.add(player);
                }
            }
        });
        return List.copyOf(viewers);
    }

    private void refreshPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Set<UUID> visibleForPlayer = playerVisibleNpcs.computeIfAbsent(
                playerId,
                ignored -> ConcurrentHashMap.newKeySet()
        );
        trackedNpcs.values().forEach(tracked -> applyVisibility(tracked, player, visibleForPlayer));
    }

    private void applyVisibility(TrackedNpc tracked, Player player, Set<UUID> visibleForPlayer) {
        Entity entity = tracked.handle().bukkitEntity();
        if (entity == null) {
            return;
        }
        boolean shouldSee = evaluateVisibility(tracked, player);
        UUID instanceId = tracked.handle().instanceId();
        if (shouldSee) {
            if (visibleForPlayer.add(instanceId)) {
                player.showEntity(plugin, entity);
            }
        } else if (visibleForPlayer.remove(instanceId)) {
            player.hideEntity(plugin, entity);
        }
    }

    private boolean evaluateVisibility(TrackedNpc tracked, Player player) {
        if (tracked.handle().lastKnownLocation() == null
                || !player.getWorld().equals(tracked.handle().lastKnownLocation().getWorld())) {
            return false;
        }
        NpcVisibility visibility = tracked.definition().visibility();
        if (visibility == null) {
            return true;
        }
        return visibility.test(new DefaultVisibilityContext(player, tracked.definition()));
    }

    @Override
    public void close() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
    }

    private record TrackedNpc(NpcHandle handle, NpcDefinition definition) {
    }

    private final class DefaultVisibilityContext implements NpcVisibilityContext {
        private final Player player;
        private final NpcDefinition definition;
        private final PlayerSessionRecord sessionRecord;
        private final Set<Rank> ranks;
        private final Rank primaryRank;

        private DefaultVisibilityContext(Player player, NpcDefinition definition) {
            this.player = player;
            this.definition = definition;
            this.sessionRecord = sessionService != null
                    ? sessionService.getActiveSession(player.getUniqueId()).orElse(null)
                    : null;
            Rank resolved = rankService != null
                    ? rankService.getPrimaryRankSync(player.getUniqueId())
                    : Rank.DEFAULT;
            this.primaryRank = resolved != null ? resolved : Rank.DEFAULT;
            this.ranks = Set.of(this.primaryRank);
        }

        @Override
        public UUID playerId() {
            return player.getUniqueId();
        }

        @Override
        public Player player() {
            return player;
        }

        @Override
        public Rank primaryRank() {
            return primaryRank;
        }

        @Override
        public Set<Rank> ranks() {
            return ranks;
        }

        @Override
        public PlayerSessionRecord playerState() {
            return sessionRecord;
        }

        @Override
        public NpcDefinition definition() {
            return definition;
        }
    }
}
