package sh.harold.fulcrum.fundamentals.rank;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.authority.client.AuthorityCommands;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.fundamentals.rank.commands.RankCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.runtime.threading.PaperRuntime;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Implementation of the rank system as a plugin feature.
 * Handles rank persistence, retrieval, and caching.
 */
public class RankFeature implements PluginFeature, RankService, Listener {

    private final Map<UUID, Set<Rank>> rankCache = new ConcurrentHashMap<>();
    private final Map<UUID, Rank> primaryRankCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rankRevisionCache = new ConcurrentHashMap<>();
    
    private JavaPlugin plugin;
    private Logger logger;
    private DataAuthority.CommandPort commandPort;
    private DataAuthority.PlayerRankReader rankReader;
    private DependencyContainer container;
    private PaperRuntime runtime;
    
    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.container = container;
        
        try {
            this.commandPort = container.get(DataAuthority.CommandPort.class);
            this.rankReader = container.get(DataAuthority.PlayerRankReader.class);
            this.runtime = container.get(PaperRuntime.class);
            
            // Register service with container
            container.register(RankService.class, this);
            
            // Also register via ServiceLocator if available
            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().registerService(RankService.class, this);
            }
            
            // Register events
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            
            // Register rank command
            registerCommand();
            
            logger.info("Rank system initialized");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize rank system", e);
        }
    }
    
    @Override
    public void shutdown() {
        logger.info("Shutting down rank system...");
        
        // Clear caches
        rankCache.clear();
        primaryRankCache.clear();
        rankRevisionCache.clear();
        
        // Unregister service
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(RankService.class);
        }
        
        logger.info("Rank system shut down");
    }
    
    @Override
    public int getPriority() {
        return 50; // Normal priority
    }
    
    @Override
    public Class<?>[] getDependencies() {
        return new Class<?>[] {
            DataAuthority.CommandPort.class,
            DataAuthority.PlayerRankReader.class,
            PaperRuntime.class
        };
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        
        // Load player ranks asynchronously
        loadPlayerRanks(playerId).thenCompose(ranks -> {
            if (ranks.isEmpty()) {
                // New player, set default rank
                return setPrimaryRank(playerId, Rank.DEFAULT);
            }
            return updateCommandsLater(playerId, "join", 10L);
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Failed to load ranks for " + playerName, ex);
            return null;
        });
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        rankCache.remove(playerId);
        primaryRankCache.remove(playerId);
        rankRevisionCache.remove(playerId);
    }
    
    @Override
    public CompletableFuture<Rank> getPrimaryRank(UUID playerId) {
        // Check cache first
        Rank cached = primaryRankCache.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        // Load from storage
        return loadPlayerRanks(playerId).thenApply(ranks -> {
            Rank primary = ranks.stream()
                .filter(r -> r != null)
                .findFirst()
                .orElse(Rank.DEFAULT);
            primaryRankCache.put(playerId, primary);
            return primary;
        });
    }
    
    @Override
    public Rank getPrimaryRankSync(UUID playerId) {
        Rank cached = primaryRankCache.get(playerId);
        if (cached != null) {
            return cached;
        }
        
        // If not cached, return default (avoid blocking)
        return Rank.DEFAULT;
    }
    
    @Override
    public CompletableFuture<Set<Rank>> getAllRanks(UUID playerId) {
        // Check cache first
        Set<Rank> cached = rankCache.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(new HashSet<>(cached));
        }
        
        // Load from storage
        return loadPlayerRanks(playerId);
    }
    
    @Override
    public CompletableFuture<Void> setPrimaryRank(UUID playerId, Rank rank) {
        return currentRankState(playerId).thenCompose(state -> {
            Set<Rank> newRanks = immutableWith(state.ranks(), rank);
            return submitRankCommand("GRANT_RANK", playerId, rank, newRanks, state.revision())
                .thenAccept(revision -> cacheRanks(playerId, rank, newRanks, revision))
                .thenCompose(ignored -> updateCommands(playerId, "primary rank change"));
        });
    }
    
    @Override
    public CompletableFuture<Void> addRank(UUID playerId, Rank rank) {
        return currentRankState(playerId).thenCompose(state -> {
            Set<Rank> newRanks = immutableWith(state.ranks(), rank);
            Rank currentPrimary = getEffectiveRankFromSet(state.ranks());
            Rank newPrimary = currentPrimary == null || rank.getPriority() > currentPrimary.getPriority()
                ? rank
                : currentPrimary;

            return submitRankCommand("GRANT_RANK", playerId, newPrimary, newRanks, state.revision())
                .thenAccept(revision -> cacheRanks(playerId, newPrimary, newRanks, revision))
                .thenCompose(ignored -> updateCommands(playerId, "rank addition"));
        });
    }
    
    @Override
    public CompletableFuture<Void> removeRank(UUID playerId, Rank rank) {
        return currentRankState(playerId).thenCompose(state -> {
            Set<Rank> newRanks = immutableWithout(state.ranks(), rank);
            if (newRanks == null) {
                return CompletableFuture.completedFuture(null);
            }
            Rank newPrimary = getEffectiveRankFromSet(newRanks);
            return submitRankCommand("REVOKE_RANK", playerId, newPrimary, newRanks, state.revision())
                .thenAccept(revision -> cacheRanks(playerId, newPrimary, newRanks, revision))
                .thenCompose(ignored -> updateCommands(playerId, "rank removal"));
        });
    }
    
    @Override
    public CompletableFuture<Rank> getEffectiveRank(UUID playerId) {
        return getAllRanks(playerId).thenApply(this::getEffectiveRankFromSet);
    }
    
    @Override
    public Rank getEffectiveRankSync(UUID playerId) {
        Set<Rank> ranks = rankCache.get(playerId);
        if (ranks == null || ranks.isEmpty()) {
            return Rank.DEFAULT;
        }
        return getEffectiveRankFromSet(ranks);
    }
    
    @Override
    public CompletableFuture<Boolean> hasRank(UUID playerId, Rank rank) {
        return getAllRanks(playerId).thenApply(ranks -> ranks.contains(rank));
    }
    
    @Override
    public CompletableFuture<Boolean> isStaff(UUID playerId) {
        return getAllRanks(playerId).thenApply(ranks -> 
            ranks.stream().anyMatch(Rank::isStaff)
        );
    }
    
    @Override
    public CompletableFuture<Void> resetRanks(UUID playerId) {
        return currentRankState(playerId).thenCompose(state -> {
            Set<Rank> newRanks = immutableWith(null, Rank.DEFAULT);
            return submitRankCommand("GRANT_RANK", playerId, Rank.DEFAULT, newRanks, state.revision())
                .thenAccept(revision -> cacheRanks(playerId, Rank.DEFAULT, newRanks, revision))
                .thenCompose(ignored -> updateCommands(playerId, "rank reset"));
        });
    }
    
    /**
     * Loads player ranks from storage.
     */
    private CompletableFuture<Set<Rank>> loadPlayerRanks(UUID playerId) {
        return loadRankState(playerId).thenApply(RankState::ranks);
    }

    private CompletableFuture<RankState> currentRankState(UUID playerId) {
        Set<Rank> cachedRanks = rankCache.get(playerId);
        Long cachedRevision = rankRevisionCache.get(playerId);
        if (cachedRanks != null && cachedRevision != null) {
            return CompletableFuture.completedFuture(new RankState(immutableCopy(cachedRanks), cachedRevision));
        }
        return loadRankState(playerId);
    }

    private CompletableFuture<RankState> loadRankState(UUID playerId) {
        return rankReader.quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .thenApply(read -> {
                if (!read.satisfied()) {
                    if (read.quote().status() == DataAuthority.ReadQuoteStatus.NOT_FOUND) {
                        return new RankState(new HashSet<>(), 0L);
                    }
                    throw new IllegalStateException("Rank projection for " + playerId + " is not safe to use: "
                        + read.quote().status() + " " + read.quote().message());
                }
                DataAuthority.PlayerRankSnapshot snapshot = read.snapshot().orElseThrow();
                Set<Rank> ranks = ranksFromSnapshot(snapshot);
                long revision = snapshot.revision();
                if (!ranks.isEmpty()) {
                    Rank primary = getEffectiveRankFromSet(ranks);
                    cacheRanks(playerId, primary, ranks, revision);
                }
                return new RankState(ranks, revision);
            })
            .exceptionally(e -> {
                logger.log(Level.WARNING, "Failed to load ranks for " + playerId, e);
                throw new CompletionException(e);
            })
            .toCompletableFuture();
    }
    
    private CompletableFuture<Long> submitRankCommand(
        String declarationId,
        UUID playerId,
        Rank primary,
        Set<Rank> ranks,
        long expectedRevision
    ) {
        Set<Rank> safeRanks = ranks == null || ranks.isEmpty()
            ? immutableWith(null, primary == null ? Rank.DEFAULT : primary)
            : ranks;
        Rank safePrimary = primary == null ? getEffectiveRankFromSet(safeRanks) : primary;

        long now = System.currentTimeMillis();
        List<String> rankNames = safeRanks.stream().map(Enum::name).collect(Collectors.toList());
        AuthorityCommands.RankCommands rankCommands = AuthorityCommands.transport().rank(playerId);
        DataAuthority.PlayerRankCommand command = switch (declarationId) {
            case "GRANT_RANK" -> rankCommands.grantRank(safePrimary.name(), rankNames, expectedRevision, now);
            case "REVOKE_RANK" -> rankCommands.revokeRank(safePrimary.name(), rankNames, expectedRevision, now);
            default -> throw new IllegalArgumentException("Unsupported rank command declaration " + declarationId);
        };

        return submitRankCommand(command, playerId);
    }

    private CompletableFuture<Long> submitRankCommand(
        DataAuthority.PlayerRankCommand command,
        UUID playerId
    ) {
        return commandPort.submit(command).thenApply(result -> {
            if (!result.accepted()) {
                throw new IllegalStateException("Rank command rejected for " + playerId + ": "
                    + result.rejectionReason() + " " + result.message());
            }
            return result.revision();
        }).toCompletableFuture();
    }

    private Set<Rank> ranksFromSnapshot(DataAuthority.PlayerRankSnapshot snapshot) {
        Set<Rank> ranks = new HashSet<>();
        for (String rankName : snapshot.ranks()) {
            rankFromName(rankName).ifPresent(ranks::add);
        }
        if (ranks.isEmpty()) {
            rankFromName(snapshot.primaryRank()).ifPresentOrElse(ranks::add, () -> ranks.add(Rank.DEFAULT));
        }
        return ranks;
    }

    private Optional<Rank> rankFromName(String rankName) {
        if (rankName == null || rankName.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Rank.valueOf(rankName.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown rank: " + rankName);
            return Optional.empty();
        }
    }

    private void cacheRanks(UUID playerId, Rank primary, Set<Rank> ranks, long revision) {
        rankCache.put(playerId, immutableCopy(ranks));
        primaryRankCache.put(playerId, primary == null ? getEffectiveRankFromSet(ranks) : primary);
        rankRevisionCache.put(playerId, revision);
    }

    private CompletableFuture<Void> updateCommands(UUID playerId, String reason) {
        return runtime.runSync("update command tree after " + reason, () -> updateCommandsNow(playerId, reason));
    }

    private CompletableFuture<Void> updateCommandsLater(UUID playerId, String reason, long delayTicks) {
        return runtime.runSync("schedule command tree update after " + reason, () ->
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> updateCommandsNow(playerId, reason), delayTicks)
        );
    }

    private void updateCommandsNow(UUID playerId, String reason) {
        runtime.requirePrimary("update command tree after " + reason);
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.updateCommands();
            logger.fine("Updated command tree for " + player.getName() + " after " + reason);
        }
    }

    private Set<Rank> immutableWith(Set<Rank> ranks, Rank rank) {
        EnumSet<Rank> copy = copyOf(ranks);
        copy.add(rank);
        return Collections.unmodifiableSet(copy);
    }

    private Set<Rank> immutableWithout(Set<Rank> ranks, Rank rank) {
        if (ranks == null || ranks.isEmpty()) {
            return null;
        }
        EnumSet<Rank> copy = copyOf(ranks);
        copy.remove(rank);
        return copy.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(copy);
    }

    private Set<Rank> immutableCopy(Set<Rank> ranks) {
        return ranks == null || ranks.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(copyOf(ranks));
    }

    private EnumSet<Rank> copyOf(Set<Rank> ranks) {
        return ranks == null || ranks.isEmpty()
            ? EnumSet.noneOf(Rank.class)
            : EnumSet.copyOf(ranks);
    }
    
    /**
     * Registers the rank command with the server.
     */
    private void registerCommand() {
        try {
            RankCommand rankCommand = new RankCommand(this, logger);
            CommandRegistrar.register(rankCommand.build());
            logger.info("Rank command registered successfully");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to register rank command", e);
        }
    }
    
    /**
     * Gets the effective rank from a set of ranks based on priority.
     */
    private Rank getEffectiveRankFromSet(Set<Rank> ranks) {
        if (ranks == null || ranks.isEmpty()) {
            return Rank.DEFAULT;
        }
        
        return ranks.stream()
            .max(Comparator.comparingInt(Rank::getPriority))
            .orElse(Rank.DEFAULT);
    }

    private record RankState(Set<Rank> ranks, long revision) {}
}
