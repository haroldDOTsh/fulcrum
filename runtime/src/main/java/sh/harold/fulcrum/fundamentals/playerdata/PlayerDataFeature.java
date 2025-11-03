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
import sh.harold.fulcrum.common.cache.PlayerCache;
import sh.harold.fulcrum.common.settings.PlayerSettingsService;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class PlayerDataFeature implements PluginFeature, Listener {
    private static final String PLAYERS_COLLECTION = "players";
    private static final Set<String> CORE_FIELDS = Set.of(
            "username", "firstJoin", "lastSeen"
    );
    private static final int BRAND_PROBE_MAX_ATTEMPTS = 10;
    private static final long BRAND_PROBE_INTERVAL_TICKS = 20L;

    private JavaPlugin plugin;
    private Logger logger;
    private DataAPI dataAPI;
    private DependencyContainer container;
    private final Map<UUID, PlayerSessionService.PlayerSessionHandle> activeHandles = new ConcurrentHashMap<>();
    private PlayerSessionService sessionService;
    private sh.harold.fulcrum.fundamentals.session.PlayerSessionLogRepository sessionLogRepository;
    private PlayerSettingsService playerSettingsService;
    private PlayerCache playerCache;

    private static Map<String, Object> buildDefaultSettings() {
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("enabled", false);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("debug", debug);
        return settings;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.container = container;

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

        this.playerCache = new sh.harold.fulcrum.fundamentals.playerdata.cache.RuntimePlayerCache(dataAPI, sessionService);
        this.playerSettingsService = new RuntimePlayerSettingsService(playerCache, sessionService);
        container.register(PlayerCache.class, playerCache);
        container.register(PlayerSettingsService.class, playerSettingsService);
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            locator.registerService(PlayerCache.class, playerCache);
            locator.registerService(PlayerSettingsService.class, playerSettingsService);
        }

        logger.info("PlayerDataFeature initialized - tracking backend player data");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        int protocolVersion = player.getProtocolVersion();
        String clientBrand = sanitizeBrand(player.getClientBrandName());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> updatePlayerData(player, protocolVersion, clientBrand));
        if (clientBrand == null) {
            scheduleBrandProbe(player.getUniqueId(), 0);
        }
    }

    private void updatePlayerData(Player player, int protocolVersion, String initialBrand) {
        UUID playerId = player.getUniqueId();
        initialBrand = sanitizeBrand(initialBrand);
        PersistedState persisted = loadPersistedState(playerId);

        PlayerSessionService.PlayerSessionHandle handle = sessionService.attachOrCreateSession(playerId, persisted.data());
        activeHandles.put(playerId, handle);

        long now = System.currentTimeMillis();
        final String brandToApply = initialBrand;
        sessionService.withActiveSession(playerId, record -> {
            Map<String, Object> core = record.getCore();
            core.put("username", player.getName());

            if (handle.createdNew()) {
                core.put("firstJoin", now);
            } else {
                core.putIfAbsent("firstJoin", now);
            }

            core.put("lastSeen", now);
            record.setClientProtocolVersion(protocolVersion);
            if (brandToApply != null) {
                record.setClientBrand(brandToApply);
            }
            record.touch();
        });

        if (handle.createdNew() && !persisted.exists()) {
            Map<String, Object> seed = new HashMap<>();
            seed.put("username", player.getName());
            seed.put("firstJoin", now);
            seed.put("lastSeen", now);
            seed.put("settings", buildDefaultSettings());
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

        sessionService.reload(playerId);

        PlayerSessionService.PlayerSessionHandle handle = activeHandles.remove(playerId);
        if (handle == null) {
            return;
        }
        if (sessionService.getHandoff(playerId).isPresent()) {
            logger.fine(() -> "Skipping session termination for handoff " + player.getName());
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
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            locator.unregisterService(PlayerCache.class);
            locator.unregisterService(PlayerSettingsService.class);
        }
        if (container != null) {
            container.unregister(PlayerCache.class);
            container.unregister(PlayerSettingsService.class);
        }
        playerSettingsService = null;
        playerCache = null;
        logger.info("Shutting down PlayerDataFeature");
    }

    @Override
    public int getPriority() {
        return 50; // After DataAPI (priority 10)
    }

    private void scheduleBrandProbe(UUID playerId, int attempt) {
        if (attempt >= BRAND_PROBE_MAX_ATTEMPTS) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline()) {
                return;
            }
            String brand = sanitizeBrand(online.getClientBrandName());
            if (brand != null) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                        sessionService.withActiveSession(playerId, record -> {
                            record.setClientBrand(brand);
                            record.touch();
                        }));
            } else {
                scheduleBrandProbe(playerId, attempt + 1);
            }
        }, BRAND_PROBE_INTERVAL_TICKS);
    }

    private String sanitizeBrand(String brand) {
        if (brand == null) {
            return null;
        }
        String trimmed = brand.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
            Object settings = source.get("settings");
            if (settings instanceof Map<?, ?> map && !map.isEmpty()) {
                filtered.put("settings", copyNestedMap(map));
            }
            Object playtime = source.get("playtime");
            if (playtime instanceof Map<?, ?> map && !map.isEmpty()) {
                filtered.put("playtime", copyNestedMap(map));
            }
            Object extras = source.get("extras");
            if (extras instanceof Map<?, ?> map && !map.isEmpty()) {
                filtered.put("extras", copyNestedMap(map));
            }
            filtered.putIfAbsent("settings", buildDefaultSettings());
            return new PersistedState(filtered, true);
        }
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("settings", buildDefaultSettings());
        return new PersistedState(defaults, false);
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

        persistScopedDocuments(record);
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

        Object settings = core.get("settings");
        if (settings instanceof Map<?, ?> map && !map.isEmpty()) {
            payload.put("settings", copyNestedMap(map));
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

        if (!record.getPlaytime().isEmpty()) {
            payload.put("playtime", copyNestedMap(record.getPlaytime()));
        }

        Integer protocolVersion = record.getClientProtocolVersion();
        if (protocolVersion != null) {
            payload.put("clientProtocolVersion", protocolVersion);
        }
        String brand = record.getClientBrand();
        if (brand != null) {
            payload.put("clientBrand", brand);
        }

        if (!record.getExtras().isEmpty()) {
            payload.put("extras", copyNestedMap(record.getExtras()));
        }

        return payload;
    }

    private void persistScopedDocuments(PlayerSessionRecord record) {
        Map<String, Map<String, Object>> scoped = record.getScopedData();
        if (scoped.isEmpty()) {
            return;
        }
        String playerId = record.getPlayerId().toString();
        scoped.forEach((family, state) -> {
            if (state == null || state.isEmpty()) {
                return;
            }
            Object settingsObj = state.get("settings");
            Map<String, Object> settings = settingsObj instanceof Map<?, ?> map ? copyNestedMap(map) : new LinkedHashMap<>();
            boolean loaded = Boolean.TRUE.equals(state.get("__loaded"));
            if (!loaded && settings.isEmpty()) {
                return;
            }
            String collection = "player_data_" + family;
            Document document = dataAPI.collection(collection).document(playerId);
            if (settings.isEmpty()) {
                if (document.exists()) {
                    document.set("settings", new LinkedHashMap<>());
                }
            } else if (document.exists()) {
                document.set("settings", settings);
            } else {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("settings", settings);
                dataAPI.collection(collection).create(playerId, payload);
            }
        });
    }

    private Map<String, Object> copyNestedMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> {
            String stringKey = String.valueOf(key);
            if (value instanceof Map<?, ?> nested) {
                copy.put(stringKey, copyNestedMap(nested));
            } else if (value instanceof List<?> list) {
                copy.put(stringKey, new ArrayList<>(list));
            } else {
                copy.put(stringKey, value);
            }
        });
        return copy;
    }

    private record PersistedState(Map<String, Object> data, boolean exists) {
    }
}
