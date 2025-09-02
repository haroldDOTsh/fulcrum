package sh.harold.fulcrum.fundamentals.rank;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.fundamentals.rank.commands.RankCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Implementation of the rank system as a plugin feature.
 * Handles rank persistence, retrieval, and caching.
 */
public class RankFeature implements PluginFeature, RankService, Listener {
    
    private static final String COLLECTION_NAME = "player_ranks";
    private static final String FIELD_UUID = "uuid";
    private static final String FIELD_PRIMARY_RANK = "primary_rank";
    private static final String FIELD_RANKS = "ranks";
    
    private final Map<UUID, Set<Rank>> rankCache = new ConcurrentHashMap<>();
    private final Map<UUID, Rank> primaryRankCache = new ConcurrentHashMap<>();
    
    private JavaPlugin plugin;
    private Logger logger;
    private DataAPI dataAPI;
    private Collection ranksCollection;
    private DependencyContainer container;
    
    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.container = container;
        
        try {
            // Get DataAPI from container
            this.dataAPI = container.get(DataAPI.class);
            if (dataAPI == null) {
                logger.warning("DataAPI not available, rank persistence will not work!");
                return;
            }
            
            // Get or create ranks collection
            this.ranksCollection = dataAPI.from(COLLECTION_NAME);
            
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
        
        // Save all cached data
        saveAllCachedData();
        
        // Clear caches
        rankCache.clear();
        primaryRankCache.clear();
        
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
        return new Class<?>[] { DataAPI.class };
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Load player ranks asynchronously
        loadPlayerRanks(playerId).thenAccept(ranks -> {
            if (ranks.isEmpty()) {
                // New player, set default rank
                setPrimaryRank(playerId, Rank.DEFAULT);
            }
            
            // Update command permissions for tab completion after rank load
            // This ensures tab completion reflects their current rank
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                player.updateCommands();
                logger.fine("Updated command tree for " + player.getName() + " on join");
            }, 10L); // Small delay to ensure everything is loaded
            
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Failed to load ranks for " + player.getName(), ex);
            return null;
        });
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        
        // Save player data if modified
        savePlayerRanks(playerId).thenRun(() -> {
            // Remove from cache after saving
            rankCache.remove(playerId);
            primaryRankCache.remove(playerId);
        });
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
        return CompletableFuture.runAsync(() -> {
            // Update primary rank cache
            primaryRankCache.put(playerId, rank);
            
            // Update ranks cache - ensure primary rank is in the set
            Set<Rank> ranks = rankCache.computeIfAbsent(playerId, k -> new HashSet<>());
            ranks.add(rank);
            
            // Save to storage
            savePlayerRanks(playerId).join();
            
            // Update command permissions if player is online
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.updateCommands();
                    logger.fine("Updated command tree for " + player.getName() + " after primary rank change");
                });
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> addRank(UUID playerId, Rank rank) {
        return CompletableFuture.runAsync(() -> {
            // Update ranks cache
            Set<Rank> ranks = rankCache.computeIfAbsent(playerId, k -> new HashSet<>());
            ranks.add(rank);
            
            // If this is the first rank or higher priority, update primary
            Rank currentPrimary = primaryRankCache.get(playerId);
            if (currentPrimary == null || rank.getPriority() > currentPrimary.getPriority()) {
                primaryRankCache.put(playerId, rank);
            }
            
            // Save to storage
            savePlayerRanks(playerId).join();
            
            // Update command permissions if player is online
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.updateCommands();
                    logger.fine("Updated command tree for " + player.getName() + " after rank addition");
                });
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> removeRank(UUID playerId, Rank rank) {
        return CompletableFuture.runAsync(() -> {
            Set<Rank> ranks = rankCache.get(playerId);
            if (ranks != null) {
                ranks.remove(rank);
                
                // If removed rank was primary, find new primary
                Rank currentPrimary = primaryRankCache.get(playerId);
                if (currentPrimary == rank) {
                    Rank newPrimary = getEffectiveRankFromSet(ranks);
                    primaryRankCache.put(playerId, newPrimary);
                }
                
                // Save to storage
                savePlayerRanks(playerId).join();
                
                // Update command permissions if player is online
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        player.updateCommands();
                        logger.fine("Updated command tree for " + player.getName() + " after rank removal");
                    });
                }
            }
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
        return CompletableFuture.runAsync(() -> {
            // Clear caches
            rankCache.remove(playerId);
            primaryRankCache.remove(playerId);
            
            // Set default rank
            setPrimaryRank(playerId, Rank.DEFAULT).join();
            
            // Update command permissions if player is online
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.updateCommands();
                    logger.fine("Updated command tree for " + player.getName() + " after rank reset");
                });
            }
        });
    }
    
    /**
     * Loads player ranks from storage.
     */
    private CompletableFuture<Set<Rank>> loadPlayerRanks(UUID playerId) {
        if (ranksCollection == null) {
            return CompletableFuture.completedFuture(new HashSet<>());
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                Document doc = ranksCollection.document(playerId.toString());
                if (!doc.exists()) {
                    return new HashSet<>();
                }
                
                Set<Rank> ranks = new HashSet<>();
                
                // Load ranks list
                @SuppressWarnings("unchecked")
                List<String> rankNames = (List<String>) doc.get(FIELD_RANKS);
                if (rankNames != null) {
                    for (String rankName : rankNames) {
                        try {
                            ranks.add(Rank.valueOf(rankName));
                        } catch (IllegalArgumentException e) {
                            logger.warning("Unknown rank: " + rankName);
                        }
                    }
                }
                
                // Load primary rank if no ranks loaded
                if (ranks.isEmpty()) {
                    Object primaryRankObj = doc.get(FIELD_PRIMARY_RANK);
                    if (primaryRankObj != null) {
                        String primaryRankName = primaryRankObj.toString();
                        try {
                            ranks.add(Rank.valueOf(primaryRankName));
                        } catch (IllegalArgumentException e) {
                            ranks.add(Rank.DEFAULT);
                        }
                    }
                }
                
                // Cache the loaded ranks
                if (!ranks.isEmpty()) {
                    rankCache.put(playerId, ranks);
                    Rank primary = getEffectiveRankFromSet(ranks);
                    primaryRankCache.put(playerId, primary);
                }
                
                return ranks;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load ranks for " + playerId, e);
                return new HashSet<>();
            }
        });
    }
    
    /**
     * Saves player ranks to storage.
     */
    private CompletableFuture<Void> savePlayerRanks(UUID playerId) {
        if (ranksCollection == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                Set<Rank> ranks = rankCache.get(playerId);
                Rank primary = primaryRankCache.get(playerId);
                
                if (ranks == null && primary == null) {
                    return;
                }
                
                Map<String, Object> data = new HashMap<>();
                data.put(FIELD_UUID, playerId.toString());
                
                if (primary != null) {
                    data.put(FIELD_PRIMARY_RANK, primary.name());
                }
                
                if (ranks != null && !ranks.isEmpty()) {
                    List<String> rankNames = ranks.stream()
                        .map(Enum::name)
                        .collect(Collectors.toList());
                    data.put(FIELD_RANKS, rankNames);
                }
                
                // Create or update document
                Document doc = ranksCollection.document(playerId.toString());
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    doc.set(entry.getKey(), entry.getValue());
                }
                
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to save ranks for " + playerId, e);
            }
        });
    }
    
    /**
     * Saves all cached data to storage.
     */
    private void saveAllCachedData() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (UUID playerId : rankCache.keySet()) {
            futures.add(savePlayerRanks(playerId));
        }
        
        // Wait for all saves to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
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
}