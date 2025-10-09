package sh.harold.fulcrum.minigame.state.context;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import sh.harold.fulcrum.minigame.MinigameAttributes;
import sh.harold.fulcrum.minigame.match.RosterManager;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.state.machine.StateMachine;

/**
 * Runtime context passed to states. Provides access to players, scheduler, and shared attributes.
 */
public final class StateContext {
    private final JavaPlugin plugin;
    private final UUID matchId;
    private final Set<UUID> activePlayers;
    private StateMachine stateMachine;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final RosterManager roster;
    private final MinigameRegistration registration;

    public StateContext(JavaPlugin plugin,
                        UUID matchId,
                        Set<UUID> activePlayers,
                        StateMachine stateMachine,
                        RosterManager roster,
                        MinigameRegistration registration) {
        this.plugin = plugin;
        this.matchId = matchId;
        this.activePlayers = activePlayers;
        this.stateMachine = stateMachine;
        this.roster = roster;
        this.registration = registration;
    }

    public void bind(StateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    public UUID getMatchId() {
        return matchId;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public Set<UUID> getActivePlayers() {
        return activePlayers;
    }

    public void addPlayer(Player player) {
        activePlayers.add(player.getUniqueId());
    }

    public void removePlayer(UUID playerId) {
        activePlayers.remove(playerId);
    }

    public RosterManager roster() {
        return roster;
    }
    public Optional<MinigameRegistration> getRegistration() {
        return Optional.ofNullable(registration);
    }

    public Optional<Player> findPlayer(UUID playerId) {
        return Optional.ofNullable(Bukkit.getPlayer(playerId));
    }

    public void broadcast(String message) {
        for (UUID playerId : activePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    public void requestTransition(String stateId) {
        if (stateMachine != null) {
            stateMachine.requestTransition(stateId);
        }
    }

    public void scheduleTask(Runnable task, long delayTicks) {
        BukkitScheduler scheduler = Bukkit.getScheduler();
        scheduler.runTaskLater(plugin, task, delayTicks);
    }

    public org.bukkit.scheduler.BukkitTask scheduleRepeatingTask(Runnable task, long delayTicks, long intervalTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, intervalTicks);
    }

    public <T> void setAttribute(String key, T value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Supplier<T> defaultSupplier) {
        return (T) attributes.computeIfAbsent(key, k -> defaultSupplier.get());
    }

    public <T> Optional<T> getAttributeOptional(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }

    public void removeAttribute(String key) {
        attributes.remove(key);
    }

    public void forEachPlayer(Consumer<Player> consumer) {
        for (UUID playerId : activePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                consumer.accept(player);
            }
        }
    }

    public void markMatchComplete() {
        setAttribute(MinigameAttributes.MATCH_COMPLETE, Boolean.TRUE);
    }

    public void configureSpectator(Player player) {
        if (player == null) {
            return;
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);

        ItemStack bed = new ItemStack(Material.RED_BED);
        ItemMeta meta = bed.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "Return to Lobby");
            meta.addItemFlags(ItemFlag.values());
            bed.setItemMeta(meta);
        }
        player.getInventory().setItem(8, bed);
        player.getInventory().setHeldItemSlot(8);
    }

    public void transitionPlayerToSpectator(UUID playerId) {
        RosterManager.Entry entry = roster.get(playerId);
        if (entry != null) {
            entry.setState(RosterManager.PlayerState.SPECTATOR);
        }
        findPlayer(playerId).ifPresent(this::configureSpectator);
    }

    public void markEliminated(UUID playerId, boolean spectatorMode) {
        RosterManager.Entry entry = roster.get(playerId);
        if (entry != null) {
            entry.setState(RosterManager.PlayerState.ELIMINATED);
        }
        if (spectatorMode) {
            transitionPlayerToSpectator(playerId);
        }
    }

    public void setRespawnAllowed(UUID playerId, boolean allowed) {
        RosterManager.Entry entry = roster.get(playerId);
        if (entry != null) {
            entry.setRespawnAllowed(allowed);
        }
    }

    public boolean isRespawnAllowed(UUID playerId) {
        RosterManager.Entry entry = roster.get(playerId);
        return entry != null && entry.isRespawnAllowed();
    }
}

