package sh.harold.fulcrum.minigame.state.context;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.fundamentals.actionflag.*;
import sh.harold.fulcrum.minigame.MinigameAttributes;
import sh.harold.fulcrum.minigame.MinigameBlueprint;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService.MatchEnvironment;
import sh.harold.fulcrum.minigame.match.RosterManager;
import sh.harold.fulcrum.minigame.state.machine.StateDefinition;
import sh.harold.fulcrum.minigame.state.machine.StateMachine;
import sh.harold.fulcrum.minigame.team.MatchTeam;
import sh.harold.fulcrum.minigame.team.TeamData;
import sh.harold.fulcrum.minigame.team.TeamService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runtime context passed to states. Provides access to players, scheduler, and shared attributes.
 */
public final class StateContext {
    private final JavaPlugin plugin;
    private final UUID matchId;
    private final Set<UUID> activePlayers;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final RosterManager roster;
    private final MinigameRegistration registration;
    private final ActionFlagService actionFlags;
    private final TeamService teamService;
    private final Map<UUID, OverrideScopeHandle> spectatorOverrides = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> respawnTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> respawnCountdowns = new ConcurrentHashMap<>();
    private final Set<UUID> registeredPlayers = ConcurrentHashMap.newKeySet();
    private final Queue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();
    private volatile String currentFlagContext;
    private StateMachine stateMachine;
    private volatile boolean frozen;
    private String queuedTransition;

    public StateContext(JavaPlugin plugin,
                        UUID matchId,
                        Set<UUID> activePlayers,
                        StateMachine stateMachine,
                        RosterManager roster,
                        MinigameRegistration registration,
                        ActionFlagService actionFlags,
                        TeamService teamService) {
        this.plugin = plugin;
        this.matchId = matchId;
        this.activePlayers = activePlayers;
        this.stateMachine = stateMachine;
        this.roster = roster;
        this.registration = registration;
        this.actionFlags = actionFlags;
        this.teamService = teamService;
        this.currentFlagContext = null;
        this.registeredPlayers.addAll(activePlayers);
    }

    public void bind(StateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    public StateMachine getStateMachine() {
        return stateMachine;
    }

    public String currentStateId() {
        return stateMachine != null ? stateMachine.getCurrentStateId() : "";
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
        registeredPlayers.add(player.getUniqueId());
    }

    public void removePlayer(UUID playerId) {
        activePlayers.remove(playerId);
        registeredPlayers.remove(playerId);
    }

    public RosterManager roster() {
        return roster;
    }

    public ActionFlagService actionFlags() {
        return actionFlags;
    }

    public TeamService teams() {
        return teamService;
    }

    public Optional<MatchTeam> team(UUID playerId) {
        if (teamService == null) {
            return Optional.empty();
        }
        return teamService.team(playerId);
    }

    public Collection<MatchTeam> teamsView() {
        if (teamService == null) {
            return List.of();
        }
        return teamService.teams();
    }

    public TeamData teamData(String teamId) {
        if (teamService == null) {
            return new TeamData();
        }
        return teamService.data(teamId);
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
        if (stateMachine == null) {
            return;
        }
        if (frozen) {
            queuedTransition = stateId;
            broadcast("[SM] Transition '" + stateId + "' queued (frozen).");
            return;
        }
        stateMachine.requestTransition(stateId);
    }

    public void scheduleTask(Runnable task, long delayTicks) {
        BukkitScheduler scheduler = Bukkit.getScheduler();
        scheduler.runTaskLater(plugin, () -> executeScheduled(task), delayTicks);
    }

    public org.bukkit.scheduler.BukkitTask scheduleRepeatingTask(Runnable task, long delayTicks, long intervalTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!frozen) {
                task.run();
            }
        }, delayTicks, intervalTicks);
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

    public void applyFlagContext(String contextId) {
        if (actionFlags != null) {
            actionFlags.applyContext(activePlayers, contextId);
            actionFlags.getBundle(contextId).flatMap(FlagBundle::gamemode).ifPresent(this::applyGamemodeForActive);
        }
        this.currentFlagContext = contextId;
    }

    public void applyFlagContext(UUID playerId, String contextId) {
        if (actionFlags != null) {
            actionFlags.applyContext(playerId, contextId);
            actionFlags.getBundle(contextId).flatMap(FlagBundle::gamemode).ifPresent(mode -> applyGamemode(playerId, mode));
        }
    }

    public OverrideScopeHandle pushOverride(UUID playerId, OverrideRequest request) {
        if (actionFlags == null) {
            return null;
        }
        return actionFlags.pushOverride(playerId, request);
    }

    public void popOverride(OverrideScopeHandle handle) {
        if (actionFlags == null || handle == null) {
            return;
        }
        actionFlags.popOverride(handle);
    }

    public void eliminatePlayer(UUID playerId, boolean allowRespawn, long respawnDelayTicks) {
        RosterManager.Entry entry = roster.get(playerId);
        if (entry == null) {
            return;
        }

        entry.setRespawnAllowed(allowRespawn);
        cancelRespawnTask(playerId);

        transitionPlayerToSpectator(playerId);
        teleportToDefaultSpawn(playerId);

        if (allowRespawn) {
            scheduleRespawn(playerId, Math.max(0L, respawnDelayTicks));
        }
    }

    public void clearFlags(UUID playerId) {
        if (actionFlags != null) {
            actionFlags.clear(playerId);
        }
        clearSpectatorOverride(playerId);
        cancelRespawnTask(playerId);
    }

    private static boolean shouldAnnounceRespawnCountdown(long seconds) {
        return seconds == 60 || seconds == 30 || seconds == 20 || seconds <= 10;
    }

    public String getCurrentFlagContext() {
        return currentFlagContext;
    }

    public void clearSpectatorOverride(UUID playerId) {
        OverrideScopeHandle handle = spectatorOverrides.remove(playerId);
        if (handle != null && actionFlags != null) {
            actionFlags.popOverride(handle);
        }
    }

    /**
     * Clears lingering spectator overrides/inventory when a player joins a fresh match.
     */
    public void resetPlayerStateForMatch(UUID playerId) {
        if (actionFlags != null) {
            actionFlags.clear(playerId);
        }
        findPlayer(playerId).ifPresent(player -> {
            player.setAllowFlight(false);
            player.setFlying(false);
            player.getInventory().remove(Material.RED_BED);
            if (player.getGameMode() == GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SURVIVAL);
            }
        });
    }

    private void applySpectatorOverride(UUID playerId) {
        if (actionFlags == null) {
            return;
        }
        spectatorOverrides.computeIfAbsent(playerId, id ->
                actionFlags.pushOverride(id, ActionFlagPresets.spectatorOverride()));
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
        applySpectatorOverride(playerId);
    }

    public void markEliminated(UUID playerId, boolean spectatorMode) {
        RosterManager.Entry entry = roster.get(playerId);
        if (entry != null) {
            entry.setState(RosterManager.PlayerState.ELIMINATED);
        }
        if (spectatorMode) {
            transitionPlayerToSpectator(playerId);
        } else {
            clearSpectatorOverride(playerId);
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

    private static ChatColor countdownColor(long seconds) {
        if (seconds > 19) {
            return ChatColor.YELLOW;
        }
        if (seconds > 9) {
            return ChatColor.GOLD;
        }
        return ChatColor.RED;
    }

    private void performRespawn(UUID playerId) {
        RosterManager.Entry entry = roster.get(playerId);
        if (entry == null || !entry.isRespawnAllowed()) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        entry.setState(RosterManager.PlayerState.ACTIVE);
        clearSpectatorOverride(playerId);

        player.setAllowFlight(false);
        player.setFlying(false);
        player.getInventory().remove(Material.RED_BED);

        Location respawnLocation = resolveRespawnLocation(playerId);
        if (respawnLocation != null) {
            player.teleportAsync(respawnLocation);
            player.setRespawnLocation(respawnLocation, true);
            if (actionFlags != null && currentFlagContext != null) {
                actionFlags.getBundle(currentFlagContext).flatMap(FlagBundle::gamemode).ifPresent(mode -> applyGamemode(playerId, mode));
            }
        }

        if (currentFlagContext != null && !currentFlagContext.isBlank()) {
            applyFlagContext(playerId, currentFlagContext);
        }
        player.sendMessage(ChatColor.YELLOW + "You have respawned!" + ChatColor.RESET);
    }

    public void teleportPlayerToDefaultSpawn(UUID playerId) {
        teleportToDefaultSpawn(playerId);
    }

    private void teleportToDefaultSpawn(UUID playerId) {
        Location target = resolveDefaultSpawn();
        if (target == null) {
            return;
        }
        findPlayer(playerId).ifPresent(player -> {
            player.teleportAsync(target);
            player.setRespawnLocation(target, true);
            if (actionFlags != null && currentFlagContext != null) {
                actionFlags.getBundle(currentFlagContext).flatMap(FlagBundle::gamemode).ifPresent(mode -> applyGamemode(playerId, mode));
            }
        });
    }

    private Location resolveMatchOrigin() {
        return getAttributeOptional(MinigameAttributes.MATCH_ENVIRONMENT, MatchEnvironment.class)
                .map(MatchEnvironment::matchSpawn)
                .map(Location::clone)
                .orElseGet(this::resolveWorldSpawnFallback);
    }

    private Location resolveRespawnLocation(UUID playerId) {
        // TODO: hook team-specific spawns once implemented
        return resolveDefaultSpawn();
    }

    private Location resolveDefaultSpawn() {
        Optional<MatchEnvironment> environment = getAttributeOptional(MinigameAttributes.MATCH_ENVIRONMENT, MatchEnvironment.class);
        if (environment.isEmpty()) {
            return resolveWorldSpawnFallback();
        }
        MatchEnvironment env = environment.get();
        boolean isPreLobby = MinigameBlueprint.STATE_PRE_LOBBY.equals(currentStateId())
                || ActionFlagContexts.MATCH_PREGAME_DEFAULT.equals(getCurrentFlagContext());
        Location spawn = isPreLobby ? env.lobbySpawn() : env.matchSpawn();
        if (spawn == null) {
            spawn = env.matchSpawn();
        }
        return spawn != null ? spawn.clone() : resolveWorldSpawnFallback();
    }

    private Location resolveWorldSpawnFallback() {
        for (UUID id : activePlayers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                World world = player.getWorld();
                if (world != null) {
                    return world.getSpawnLocation().clone();
                }
            }
        }
        World defaultWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        return defaultWorld != null ? defaultWorld.getSpawnLocation().clone() : null;
    }

    public void clearFlagsForRoster() {
        if (actionFlags != null) {
            for (UUID playerId : activePlayers) {
                actionFlags.clear(playerId);
            }
        }
        for (UUID playerId : activePlayers) {
            clearSpectatorOverride(playerId);
            cancelRespawnTask(playerId);
        }
        spectatorOverrides.clear();
        respawnTasks.clear();
        respawnCountdowns.clear();
        registeredPlayers.clear();
        pendingTasks.clear();
        this.currentFlagContext = null;
    }

    public void registerPlayer(UUID playerId) {
        registeredPlayers.add(playerId);
        if (currentFlagContext != null && actionFlags != null) {
            actionFlags.getBundle(currentFlagContext).flatMap(FlagBundle::gamemode).ifPresent(mode -> applyGamemode(playerId, mode));
        }
    }

    public void unregisterPlayer(UUID playerId) {
        registeredPlayers.remove(playerId);
    }

    public boolean isPlayerRegistered(UUID playerId) {
        return registeredPlayers.contains(playerId);
    }

    public boolean isStateMachineFrozen() {
        return frozen;
    }

    public Optional<String> getQueuedTransition() {
        return Optional.ofNullable(queuedTransition);
    }

    public void freezeStateMachine(CommandSender sender) {
        if (frozen) {
            sender.sendMessage(Component.text("State machine already frozen.", NamedTextColor.YELLOW));
            return;
        }
        frozen = true;
        sender.sendMessage(Component.text("State machine frozen.", NamedTextColor.GREEN));
    }

    public void resumeStateMachine(CommandSender sender) {
        if (!frozen) {
            sender.sendMessage(Component.text("State machine is not frozen.", NamedTextColor.YELLOW));
            return;
        }
        frozen = false;
        sender.sendMessage(Component.text("State machine resumed.", NamedTextColor.GREEN));
        runPendingTasks();
        if (queuedTransition != null) {
            String state = queuedTransition;
            queuedTransition = null;
            broadcast("[SM] Applying queued transition to '" + state + "'.");
            requestTransition(state);
        }
    }

    public void checkFrozenTransitions() {
        if (!frozen || stateMachine == null) {
            return;
        }
        StateDefinition definition = stateMachine.getDefinition(stateMachine.getCurrentStateId());
        if (definition == null) {
            return;
        }
        definition.getTransitions().stream()
                .filter(transition -> transition.shouldTransition(this))
                .findFirst()
                .ifPresent(transition -> {
                    String target = transition.getTargetStateId();
                    if (!target.equals(queuedTransition)) {
                        queuedTransition = target;
                        broadcast("[SM] Transition '" + target + "' satisfied while frozen; queued for resume.");
                    }
                });
    }

    private void executeScheduled(Runnable task) {
        if (task == null) {
            return;
        }
        if (frozen) {
            pendingTasks.add(task);
        } else {
            task.run();
        }
    }

    private void scheduleRespawn(UUID playerId, long delayTicks) {
        if (delayTicks <= 0L) {
            executeScheduled(() -> performRespawn(playerId));
            return;
        }
        long seconds = Math.max(1L, (delayTicks + 19L) / 20L);
        cancelRespawnCountdown(playerId);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            respawnTasks.remove(playerId);
            cancelRespawnCountdown(playerId);
            executeScheduled(() -> performRespawn(playerId));
        }, delayTicks);
        respawnTasks.put(playerId, task);
        startRespawnCountdown(playerId, seconds);
    }

    private void cancelRespawnTask(UUID playerId) {
        BukkitTask task = respawnTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
        cancelRespawnCountdown(playerId);
    }

    private void startRespawnCountdown(UUID playerId, long totalSeconds) {
        if (totalSeconds <= 0L) {
            return;
        }
        BukkitRunnable countdown = new BukkitRunnable() {
            private long remainingSeconds = totalSeconds;

            @Override
            public void run() {
                if (!respawnTasks.containsKey(playerId) || remainingSeconds <= 0L) {
                    respawnCountdowns.remove(playerId);
                    cancel();
                    return;
                }
                if (shouldAnnounceRespawnCountdown(remainingSeconds)) {
                    sendRespawnCountdown(playerId, remainingSeconds);
                }
                remainingSeconds--;
            }
        };
        BukkitTask countdownTask = countdown.runTaskTimer(plugin, 0L, 20L);
        respawnCountdowns.put(playerId, countdownTask);
    }

    private void cancelRespawnCountdown(UUID playerId) {
        BukkitTask countdown = respawnCountdowns.remove(playerId);
        if (countdown != null) {
            countdown.cancel();
        }
    }

    private void sendRespawnCountdown(UUID playerId, long seconds) {
        findPlayer(playerId).ifPresent(player -> {
            ChatColor color = countdownColor(seconds);
            String unit = seconds == 1L ? " second" : " seconds";
            player.sendMessage(ChatColor.YELLOW + "You will respawn in " + color + seconds + ChatColor.YELLOW + unit + ChatColor.RESET);
        });
    }

    private void runPendingTasks() {
        Runnable runnable;
        while ((runnable = pendingTasks.poll()) != null) {
            try {
                runnable.run();
            } catch (Exception ex) {
                plugin.getLogger().warning("Error executing resumed task: " + ex.getMessage());
            }
        }
    }

    private void applyGamemodeForActive(org.bukkit.GameMode gamemode) {
        for (UUID playerId : activePlayers) {
            applyGamemode(playerId, gamemode);
        }
    }

    private void applyGamemode(UUID playerId, org.bukkit.GameMode gamemode) {
        if (gamemode == null) {
            return;
        }
        RosterManager.Entry entry = roster.get(playerId);
        if (entry != null && entry.getState() == RosterManager.PlayerState.SPECTATOR) {
            return;
        }
        findPlayer(playerId).ifPresent(player -> player.setGameMode(gamemode));
    }

}
