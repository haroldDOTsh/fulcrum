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
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankChangeContext;
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
 * Handles rank persistence, retrieval, caching, and audit logging.
 */
public class RankFeature implements PluginFeature, RankService, Listener {

    private static final String PLAYERS_COLLECTION = "players";
    private final Map<UUID, Set<Rank>> rankCache = new ConcurrentHashMap<>();
    private final Map<UUID, Rank> primaryRankCache = new ConcurrentHashMap<>();

    private JavaPlugin plugin;
    private Logger logger;
    private DataAPI dataAPI;
    private Collection playersCollection;
    private DependencyContainer container;
    private RankAuditLogRepository auditLogRepository;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.container = container;

        try {
            this.dataAPI = container.get(DataAPI.class);
            if (dataAPI == null) {
                logger.warning("DataAPI not available, rank persistence will not work!");
                return;
            }

            this.playersCollection = dataAPI.collection(PLAYERS_COLLECTION);

            initializeAuditLogging();

            container.register(RankService.class, this);
            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().registerService(RankService.class, this);
            }

            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            registerCommand();

            logger.info("Rank system initialized");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize rank system", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down rank system...");

        saveAllCachedData();
        rankCache.clear();
        primaryRankCache.clear();

        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(RankService.class);
        }

        auditLogRepository = null;

        logger.info("Rank system shut down");
    }

    @Override
    public int getPriority() {
        return 50; // Normal priority
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        loadPlayerRanks(playerId).thenAccept(ranks -> {
            if (ranks.isEmpty()) {
                // Ensure new players have a default rank recorded but avoid audit noise
                setPrimaryRank(playerId, Rank.DEFAULT, RankChangeContext.system());
            }

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                player.updateCommands();
                logger.fine("Updated command tree for " + player.getName() + " on join");
            }, 10L);
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Failed to load ranks for " + player.getName(), ex);
            return null;
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        CompletableFuture.runAsync(() -> persistRankState(
                playerId,
                primaryRankCache.get(playerId),
                primaryRankCache.get(playerId),
                rankCache.getOrDefault(playerId, Set.of()),
                RankChangeContext.system(),
                false,
                false
        )).whenComplete((ignored, throwable) -> {
            rankCache.remove(playerId);
            primaryRankCache.remove(playerId);
        });
    }

    @Override
    public CompletableFuture<Rank> getPrimaryRank(UUID playerId) {
        Rank cached = primaryRankCache.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return loadPlayerRanks(playerId).thenApply(ranks -> {
            Rank primary = getEffectiveRankFromSet(ranks);
            primaryRankCache.put(playerId, primary);
            return primary;
        });
    }

    @Override
    public Rank getPrimaryRankSync(UUID playerId) {
        return primaryRankCache.getOrDefault(playerId, Rank.DEFAULT);
    }

    @Override
    public CompletableFuture<Set<Rank>> getAllRanks(UUID playerId) {
        Set<Rank> cached = rankCache.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(new HashSet<>(cached));
        }
        return loadPlayerRanks(playerId).thenApply(HashSet::new);
    }

    @Override
    public CompletableFuture<Void> setPrimaryRank(UUID playerId, Rank rank, RankChangeContext context) {
        return CompletableFuture.runAsync(() -> {
            Set<Rank> ranks = ensureRanksLoaded(playerId);
            Set<Rank> before = new HashSet<>(ranks);
            Rank previousPrimary = primaryRankCache.getOrDefault(playerId, getEffectiveRankFromSet(ranks));

            ranks.add(rank);
            Rank newPrimary = getEffectiveRankFromSet(ranks);
            primaryRankCache.put(playerId, newPrimary);

            boolean changed = !before.equals(ranks) || !Objects.equals(previousPrimary, newPrimary);
            persistRankState(playerId, previousPrimary, newPrimary, ranks, context, changed, true);
            updateOnlinePlayerCommands(playerId);
        });
    }

    @Override
    public CompletableFuture<Void> addRank(UUID playerId, Rank rank, RankChangeContext context) {
        return CompletableFuture.runAsync(() -> {
            Set<Rank> ranks = ensureRanksLoaded(playerId);
            Set<Rank> before = new HashSet<>(ranks);
            Rank previousPrimary = primaryRankCache.getOrDefault(playerId, getEffectiveRankFromSet(ranks));

            boolean added = ranks.add(rank);
            if (!added) {
                return;
            }

            Rank newPrimary = getEffectiveRankFromSet(ranks);
            primaryRankCache.put(playerId, newPrimary);

            boolean changed = true;
            persistRankState(playerId, previousPrimary, newPrimary, ranks, context, changed, true);
            updateOnlinePlayerCommands(playerId);
        });
    }

    @Override
    public CompletableFuture<Void> removeRank(UUID playerId, Rank rank, RankChangeContext context) {
        return CompletableFuture.runAsync(() -> {
            Set<Rank> ranks = ensureRanksLoaded(playerId);
            if (!ranks.contains(rank)) {
                return;
            }

            Set<Rank> before = new HashSet<>(ranks);
            Rank previousPrimary = primaryRankCache.getOrDefault(playerId, getEffectiveRankFromSet(ranks));

            ranks.remove(rank);
            Rank newPrimary = getEffectiveRankFromSet(ranks);
            primaryRankCache.put(playerId, newPrimary);

            boolean changed = true;
            persistRankState(playerId, previousPrimary, newPrimary, ranks, context, changed, true);
            updateOnlinePlayerCommands(playerId);
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
    public CompletableFuture<Void> resetRanks(UUID playerId, RankChangeContext context) {
        return CompletableFuture.runAsync(() -> {
            Set<Rank> ranks = ensureRanksLoaded(playerId);
            Set<Rank> before = new HashSet<>(ranks);
            Rank previousPrimary = primaryRankCache.getOrDefault(playerId, getEffectiveRankFromSet(ranks));

            ranks.clear();
            ranks.add(Rank.DEFAULT);

            Rank newPrimary = Rank.DEFAULT;
            primaryRankCache.put(playerId, newPrimary);

            boolean changed = !before.equals(ranks) || !Objects.equals(previousPrimary, newPrimary);
            persistRankState(playerId, previousPrimary, newPrimary, ranks, context, changed, true);
            updateOnlinePlayerCommands(playerId);
        });
    }

    private void updateOnlinePlayerCommands(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.updateCommands();
                logger.fine("Updated command tree for " + player.getName() + " after rank change");
            });
        }
    }

    private Set<Rank> ensureRanksLoaded(UUID playerId) {
        Set<Rank> ranks = rankCache.get(playerId);
        if (ranks != null) {
            return ranks;
        }
        return loadPlayerRanks(playerId).join();
    }

    private CompletableFuture<Set<Rank>> loadPlayerRanks(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            Set<Rank> ranks = readRanksFromPlayerDocument(playerId);

            rankCache.put(playerId, ranks);
            primaryRankCache.put(playerId, getEffectiveRankFromSet(ranks));
            return ranks;
        });
    }

    private Set<Rank> readRanksFromPlayerDocument(UUID playerId) {
        try {
            Document doc = playersCollection.document(playerId.toString());
            Set<Rank> ranks = new HashSet<>();

            Object storedRanks = doc.get("rankInfo.all");
            if (storedRanks instanceof List<?> list) {
                for (Object value : list) {
                    Rank parsed = parseRank(value);
                    if (parsed != null) {
                        ranks.add(parsed);
                    }
                }
            }

            String primaryName = doc.get("rankInfo.primary", null);
            if (primaryName == null) {
                primaryName = doc.get("rank", null); // legacy fallback
            }
            Rank primary = parseRank(primaryName);
            if (primary != null) {
                ranks.add(primary);
            }

            return ranks;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read rank data for " + playerId, e);
            return new HashSet<>();
        }
    }

    private Rank parseRank(Object value) {
        if (value == null) {
            return null;
        }

        String name = value.toString().trim();
        if (name.isEmpty()) {
            return null;
        }

        try {
            return Rank.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ex) {
            logger.fine("Unknown rank entry encountered: " + name);
            return null;
        }
    }

    private void persistRankState(UUID playerId,
                                  Rank previousPrimary,
                                  Rank newPrimary,
                                  Set<Rank> ranks,
                                  RankChangeContext context,
                                  boolean changed,
                                  boolean allowLogging) {
        try {
            Document playerDoc = playersCollection.document(playerId.toString());
            String playerName = playerDoc.get("username", "Unknown");

            List<String> orderedRanks = ranks.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(Rank::getPriority).reversed())
                    .map(Enum::name)
                    .collect(Collectors.toCollection(ArrayList::new));

            String primaryName = newPrimary != null ? newPrimary.name() : Rank.DEFAULT.name();
            playerDoc.set("rank", primaryName);

            Map<String, Object> rankInfo = new HashMap<>();
            rankInfo.put("primary", primaryName);
            rankInfo.put("all", orderedRanks);

            if (changed && context != null) {
                rankInfo.put("updatedAt", System.currentTimeMillis());
                rankInfo.put("updatedBy", buildUpdatedBy(context));
            } else {
                Object updatedAt = playerDoc.get("rankInfo.updatedAt");
                if (updatedAt != null) {
                    rankInfo.put("updatedAt", updatedAt);
                }
                Object updatedBy = playerDoc.get("rankInfo.updatedBy");
                if (updatedBy != null) {
                    rankInfo.put("updatedBy", updatedBy);
                }
            }

            playerDoc.set("rankInfo", rankInfo);

            if (auditLogRepository != null && allowLogging && changed && context != null &&
                    context.executorType() != RankChangeContext.Executor.SYSTEM) {
                auditLogRepository.recordChange(playerId, playerName, previousPrimary, newPrimary, orderedRanks, context);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to persist rank data for " + playerId, e);
        }
    }

    private Map<String, Object> buildUpdatedBy(RankChangeContext context) {
        Map<String, Object> updatedBy = new HashMap<>();
        updatedBy.put("type", context.executorType().name());
        updatedBy.put("name", context.executorName());
        if (context.executorUuid() != null) {
            updatedBy.put("uuid", context.executorUuid().toString());
        }
        return updatedBy;
    }

    private void initializeAuditLogging() {
        PostgresConnectionAdapter postgresAdapter = null;
        if (container != null) {
            postgresAdapter = container.getOptional(PostgresConnectionAdapter.class).orElse(null);
        }
        if (postgresAdapter == null && ServiceLocatorImpl.getInstance() != null) {
            postgresAdapter = ServiceLocatorImpl.getInstance()
                    .findService(PostgresConnectionAdapter.class)
                    .orElse(null);
        }

        if (postgresAdapter == null) {
            logger.warning("PostgreSQL adapter not available; rank audit logging disabled");
            auditLogRepository = null;
            return;
        }

        RankAuditLogRepository repository = new RankAuditLogRepository(postgresAdapter, logger);
        try {
            repository.initialize(plugin.getClass().getClassLoader());
            this.auditLogRepository = repository;
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Failed to initialize rank audit logging; disabling audit trail", exception);
            this.auditLogRepository = null;
        }
    }

    private void saveAllCachedData() {
        rankCache.forEach((playerId, ranks) -> {
            Rank primary = primaryRankCache.getOrDefault(playerId, getEffectiveRankFromSet(ranks));
            persistRankState(playerId, primary, primary, ranks, RankChangeContext.system(), false, false);
        });
    }

    private void registerCommand() {
        try {
            RankCommand rankCommand = new RankCommand(this, logger);
            CommandRegistrar.register(rankCommand.build());
            logger.info("Rank command registered successfully");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to register rank command", e);
        }
    }

    private Rank getEffectiveRankFromSet(Set<Rank> ranks) {
        if (ranks == null || ranks.isEmpty()) {
            return Rank.DEFAULT;
        }

        return ranks.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(Rank::getPriority))
                .orElse(Rank.DEFAULT);
    }
}
