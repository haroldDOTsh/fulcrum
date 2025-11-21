package sh.harold.fulcrum.fundamentals.slot.presence;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.fundamentals.actionflag.VanishService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Central authority for slot isolation (membership, world bindings, visibility, audiences).
 */
public final class SlotPresenceService {
    private final JavaPlugin plugin;
    private final VanishService vanishService;
    private final Map<UUID, String> playerSlots = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> slotPlayers = new ConcurrentHashMap<>();
    private final Map<String, String> worldSlots = new ConcurrentHashMap<>();
    private final Set<VisibilityKey> hiddenPairs = ConcurrentHashMap.newKeySet();

    public SlotPresenceService(JavaPlugin plugin, VanishService vanishService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.vanishService = vanishService;
    }

    public Optional<String> resolveSlotId(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(playerSlots.get(playerId));
    }

    public Optional<String> resolveSlotId(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(worldSlots.get(normalize(worldName)));
    }

    public Set<UUID> getPlayerIdsInSlot(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(slotPlayers.getOrDefault(normalize(slotId), Set.of()));
    }

    public Collection<Player> getOnlinePlayersInSlot(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return Set.of();
        }
        Collection<Player> result = new ArrayList<>();
        for (UUID id : getPlayerIdsInSlot(slotId)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline()) {
                result.add(player);
            }
        }
        return result;
    }

    public Set<String> getPlayerNamesInSlot(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return Set.of();
        }
        Set<UUID> ids = slotPlayers.getOrDefault(normalize(slotId), Set.of());
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new CopyOnWriteArraySet<>();
        for (UUID id : ids) {
            String name = playerNames.get(id);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return Set.copyOf(names);
    }

    public void bindPlayer(UUID playerId, String playerName, String slotId) {
        if (playerId == null) {
            return;
        }
        if (slotId == null || slotId.isBlank()) {
            unbindPlayer(playerId);
            return;
        }
        String normalizedSlot = normalize(slotId);
        if (playerName != null && !playerName.isBlank()) {
            playerNames.put(playerId, playerName);
        }
        String previousSlot = playerSlots.put(playerId, normalizedSlot);
        if (previousSlot != null && !previousSlot.equals(normalizedSlot)) {
            slotPlayers.computeIfPresent(previousSlot, (ignored, members) -> {
                members.remove(playerId);
                return members.isEmpty() ? null : members;
            });
        }
        slotPlayers.computeIfAbsent(normalizedSlot, key -> ConcurrentHashMap.newKeySet()).add(playerId);
        refreshVisibility();
    }

    public void unbindPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        String slotId = playerSlots.remove(playerId);
        playerNames.remove(playerId);
        if (slotId != null) {
            slotPlayers.computeIfPresent(slotId, (ignored, members) -> {
                members.remove(playerId);
                return members.isEmpty() ? null : members;
            });
        }
        refreshVisibility();
    }

    public void clearSlot(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return;
        }
        String normalized = normalize(slotId);
        Set<UUID> members = slotPlayers.remove(normalized);
        if (members != null) {
            members.forEach(playerSlots::remove);
        }
        refreshVisibility();
    }

    public void bindWorld(String worldName, String slotId) {
        if (worldName == null || worldName.isBlank() || slotId == null || slotId.isBlank()) {
            return;
        }
        worldSlots.put(normalize(worldName), normalize(slotId));
    }

    public void unbindWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return;
        }
        worldSlots.remove(normalize(worldName));
    }

    public void unbindWorldsForSlot(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return;
        }
        String normalized = normalize(slotId);
        worldSlots.entrySet().removeIf(entry -> normalized.equals(entry.getValue()));
    }

    public void recordPlayerName(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) {
            return;
        }
        playerNames.put(playerId, playerName);
    }

    public boolean hasAnyMemberships() {
        return !playerSlots.isEmpty();
    }

    public void refreshVisibility() {
        if (Bukkit.isPrimaryThread()) {
            refreshVisibilitySync();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, this::refreshVisibilitySync);
        }
    }

    public void shutdown() {
        hiddenPairs.clear();
        playerSlots.clear();
        playerNames.clear();
        slotPlayers.clear();
        worldSlots.clear();
    }

    private void refreshVisibilitySync() {
        Collection<? extends Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        for (Player viewer : online) {
            UUID viewerId = viewer.getUniqueId();
            String viewerSlot = playerSlots.get(viewerId);
            boolean viewerVanished = isVanished(viewerId);
            for (Player target : online) {
                if (viewer == target) {
                    continue;
                }
                UUID targetId = target.getUniqueId();
                String targetSlot = playerSlots.get(targetId);
                VisibilityKey key = new VisibilityKey(viewerId, targetId);
                if (viewerSlot != null && targetSlot != null && !viewerSlot.equalsIgnoreCase(targetSlot)) {
                    if (hiddenPairs.add(key)) {
                        viewer.hidePlayer(plugin, target);
                    }
                } else {
                    if (hiddenPairs.remove(key) && !viewerVanished && !isVanished(targetId)) {
                        viewer.showPlayer(plugin, target);
                    }
                }
            }
        }
        hiddenPairs.removeIf(key -> plugin.getServer().getPlayer(key.viewer()) == null
                || plugin.getServer().getPlayer(key.target()) == null);
    }

    private boolean isVanished(UUID playerId) {
        return vanishService != null && vanishService.isVanished(playerId);
    }

    private String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

    private record VisibilityKey(UUID viewer, UUID target) {
    }
}
