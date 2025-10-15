package sh.harold.fulcrum.fundamentals.playerdata;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class PlayerDataFeature implements PluginFeature, Listener {
    private static final String PLAYERS_COLLECTION = "players";
    private static final Set<String> CORE_FIELDS = Set.of(
            "username", "firstJoin", "lastSeen"
    );

    private JavaPlugin plugin;
    private Logger logger;
    private DataAPI dataAPI;
    private final Map<UUID, PlayerSessionService.PlayerSessionHandle> activeHandles = new ConcurrentHashMap<>();
    private PlayerSessionService sessionService;
    private sh.harold.fulcrum.fundamentals.session.PlayerSessionLogRepository sessionLogRepository;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        // Get DataAPI from DependencyContainer
        this.dataAPI = container.getOptional(DataAPI.class).orElse(null);
        this.sessionService = container.getOptional(PlayerSessionService.class)
                .orElseThrow(() -> new IllegalStateException("PlayerSessionService is required before PlayerDataFeature"));
        this.sessionLogRepository = container.getOptional(sh.harold.fulcrum.fundamentals.session.PlayerSessionLogRepository.class).orElse(null);

        if (dataAPI == null) {
            logger.severe("DataAPI not available! PlayerDataFeature requires DataAPI to be initialized first.");
            throw new RuntimeException("DataAPI not available");
        }

        // Register event listeners
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        logger.info("PlayerDataFeature initialized - tracking backend player data");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> updatePlayerData(player));
    }

    private void updatePlayerData(Player player) {
        UUID playerId = player.getUniqueId();
        PersistedState persisted = loadPersistedState(playerId);

        PlayerSessionService.PlayerSessionHandle handle = sessionService.attachOrCreateSession(playerId, persisted.data());
        activeHandles.put(playerId, handle);

        long now = System.currentTimeMillis();
        sessionService.withActiveSession(playerId, record -> {
            Map<String, Object> core = record.getCore();
            core.put("username", player.getName());

            if (handle.createdNew()) {
                core.put("firstJoin", now);
            } else {
                core.putIfAbsent("firstJoin", now);
            }

            core.put("lastSeen", now);
            record.touch();
        });

        if (handle.createdNew() && !persisted.exists()) {
            Map<String, Object> seed = new HashMap<>();
            seed.put("username", player.getName());
            seed.put("firstJoin", now);
            seed.put("lastSeen", now);
            dataAPI.collection(PLAYERS_COLLECTION).create(playerId.toString(), seed);
        }

        sessionService.startServerSegment(playerId);

        logger.fine(() -> "Session state refreshed for " + player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> handleQuit(player));
    }

    private void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        sessionService.withActiveSession(playerId, record -> {
            long now = System.currentTimeMillis();
            record.getCore().put("lastSeen", now);
        });

        sessionService.endActiveSegment(playerId);

        PlayerSessionService.PlayerSessionHandle handle = activeHandles.remove(playerId);
        if (handle == null) {
            return;
        }

        sessionService.endSession(playerId, handle.sessionId())
                .ifPresent(record -> {
                    persistSession(record);
                    if (sessionLogRepository != null) {
                        sessionLogRepository.recordSession(record, System.currentTimeMillis());
                    }
                });
    }

    public CompletableFuture<Document> getPlayerData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            Document doc = dataAPI.collection(PLAYERS_COLLECTION).document(uuid.toString());
            return doc.exists() ? doc : null;
        });
    }

    public CompletableFuture<Boolean> playerDataExists(UUID uuid) {
        return CompletableFuture.supplyAsync(() ->
                dataAPI.collection(PLAYERS_COLLECTION).document(uuid.toString()).exists());
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down PlayerDataFeature");
    }

    @Override
    public int getPriority() {
        return 50; // After DataAPI (priority 10)
    }

    private PersistedState loadPersistedState(UUID playerId) {
        Document doc = dataAPI.collection(PLAYERS_COLLECTION).document(playerId.toString());
        if (doc.exists()) {
            Map<String, Object> filtered = new HashMap<>();
            Map<String, Object> source = doc.toMap();
            for (String key : CORE_FIELDS) {
                Object value = source.get(key);
                if (value != null) {
                    filtered.put(key, value);
                }
            }
            Object rankInfo = source.get("rankInfo");
            if (rankInfo instanceof Map<?, ?> info) {
                filtered.put("rankInfo", info);
            }
            Object rank = source.get("rank");
            if (rank != null) {
                filtered.put("rank", rank);
            }
            Object environment = source.get("environment");
            if (environment != null) {
                filtered.put("environment", environment);
            }
            Object minigames = source.get("minigames");
            if (minigames instanceof Map<?, ?> games && !games.isEmpty()) {
                filtered.put("minigames", games);
            }
            return new PersistedState(filtered, true);
        }
        return new PersistedState(new HashMap<>(), false);
    }

    private void persistSession(PlayerSessionRecord record) {
        Map<String, Object> payload = buildPersistencePayload(record);
        Document document = dataAPI.collection(PLAYERS_COLLECTION).document(record.getPlayerId().toString());

        if (document.exists()) {
            payload.forEach((key, value) -> document.set(key, value));
        } else {
            dataAPI.collection(PLAYERS_COLLECTION).create(record.getPlayerId().toString(), payload);
        }
        logger.fine(() -> "Persisted session for " + record.getPlayerId());
    }

    private Map<String, Object> buildPersistencePayload(PlayerSessionRecord record) {
        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> core = record.getCore();
        for (String key : CORE_FIELDS) {
            if ("uuid".equals(key)) {
                continue;
            }
            Object value = core.get(key);
            if (value != null) {
                payload.put(key, value);
            }
        }

        Object environment = core.get("environment");
        if (environment != null) {
            payload.put("environment", environment);
        }

        if (record.shouldPersistRank()) {
            Map<String, Object> rankInfo = new HashMap<>(record.getRank());
            payload.put("rankInfo", rankInfo);
            Object primary = rankInfo.get("primary");
            if (primary instanceof String primaryName && !Rank.DEFAULT.name().equalsIgnoreCase(primaryName)) {
                payload.put("rank", primaryName);
            }
        }

        if (!record.getMinigames().isEmpty()) {
            payload.put("minigames", new HashMap<>(record.getMinigames()));
        }

        return payload;
    }

    private record PersistedState(Map<String, Object> data, boolean exists) {
    }
}
